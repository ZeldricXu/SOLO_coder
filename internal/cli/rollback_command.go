package cli

import (
	"fmt"
	"os"
	"path/filepath"

	"dbmigrator/internal/config"
	"dbmigrator/internal/database"
	"dbmigrator/internal/logger"
	"dbmigrator/internal/migration"
	"dbmigrator/internal/rollback"
	"dbmigrator/internal/status"
	"dbmigrator/pkg/models"
)

type RollbackCommand struct {
	envFlag    string
	stepsFlag  int
	configFlag string
	dryRunFlag bool
}

func NewRollbackCommand() *RollbackCommand {
	return &RollbackCommand{}
}

func (c *RollbackCommand) Name() string {
	return "rollback"
}

func (c *RollbackCommand) Description() string {
	return "Rollback executed database migrations"
}

func (c *RollbackCommand) Usage() string {
	return "rollback [--env <environment>] [--steps <count>] [--config <file>] [--dry-run]"
}

func (c *RollbackCommand) Flags(ctx *CommandContext) {
	fs := ctx.FlagSet
	fs.StringVar(&c.envFlag, "env", "development", "Target environment")
	fs.IntVar(&c.stepsFlag, "steps", 1, "Number of migrations to rollback")
	fs.StringVar(&c.configFlag, "config", "", "Path to config file")
	fs.BoolVar(&c.dryRunFlag, "dry-run", false, "Preview rollback without executing")
}

func (c *RollbackCommand) Execute(ctx *CommandContext) error {
	cfg, err := config.LoadConfig(c.configFlag)
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	envConfig, err := config.GetEnvironmentConfig(cfg, c.envFlag)
	if err != nil {
		return err
	}

	migrationsDir := cfg.Migrations.Directory
	if !filepath.IsAbs(migrationsDir) {
		migrationsDir = filepath.Join(filepath.Dir(findConfigPath(c.configFlag)), migrationsDir)
	}

	migManager, err := migration.NewManager(migrationsDir)
	if err != nil {
		return fmt.Errorf("failed to create migration manager: %w", err)
	}

	dbConn, err := database.NewDBConnection(&envConfig.Database)
	if err != nil {
		return fmt.Errorf("failed to connect to database: %w", err)
	}
	defer dbConn.Close()

	statusTracker, err := status.NewTracker(dbConn, cfg.Migrations.Table)
	if err != nil {
		return fmt.Errorf("failed to create status tracker: %w", err)
	}

	migrationLogger, err := logger.NewLogger(dbConn, cfg.Migrations.LogTable)
	if err != nil {
		return fmt.Errorf("failed to create logger: %w", err)
	}

	rb := rollback.NewRollbacker(dbConn)

	latestStates, err := statusTracker.GetLatestExecutedMigrations(c.stepsFlag)
	if err != nil {
		return fmt.Errorf("failed to get latest migrations: %w", err)
	}

	if len(latestStates) == 0 {
		fmt.Println("No migrations to rollback.")
		return nil
	}

	migrationsToRollback := make([]*models.Migration, 0)
	for _, state := range latestStates {
		mig, exists := migManager.GetMigrationByID(state.MigrationID)
		if !exists {
			fmt.Printf("Warning: Migration %s not found in files, skipping\n", state.MigrationID)
			continue
		}
		if mig.DownScript == "" {
			fmt.Printf("Warning: Migration %s has no down script, skipping\n", state.MigrationID)
			continue
		}
		migrationsToRollback = append(migrationsToRollback, mig)
	}

	if len(migrationsToRollback) == 0 {
		fmt.Println("No valid migrations to rollback.")
		return nil
	}

	fmt.Printf("Will rollback %d migration(s):\n", len(migrationsToRollback))
	for i, mig := range migrationsToRollback {
		fmt.Printf("  %d. %s (%s)\n", len(migrationsToRollback)-i, mig.Version, mig.Name)
	}

	if c.dryRunFlag {
		fmt.Println("\n=== Dry Run - Preview Mode ===")
		for _, mig := range migrationsToRollback {
			fmt.Printf("\n--- Rollback: %s ---\n", mig.MigrationID)
			statements, err := rb.PreviewRollback(mig)
			if err != nil {
				fmt.Printf("  Error: %v\n", err)
				continue
			}
			for i, stmt := range statements {
				fmt.Printf("  Statement %d: %s\n", i+1, truncateString(stmt, 80))
			}
		}
		return nil
	}

	fmt.Println("\nExecuting rollback...")
	successCount := 0
	failedCount := 0

	for _, mig := range migrationsToRollback {
		fmt.Printf("  Rolling back: %s (%s) ... ", mig.Version, mig.Name)

		result, err := rb.RollbackMigration(mig)
		if err != nil || !result.Success {
			fmt.Println("FAILED")
			if result.Error != nil {
				fmt.Printf("    Error: %v\n", result.Error)
			}
			failedCount++
			break
		}

		if err := statusTracker.UpdateRollbackStatus(mig.MigrationID); err != nil {
			fmt.Println("ERROR (status tracking)")
			fmt.Printf("    Error: %v\n", err)
			failedCount++
			break
		}

		statements, _ := rb.PreviewRollback(mig)
		if logErr := migrationLogger.LogRollback(mig, statements, result); logErr != nil {
			fmt.Printf("    Warning: Failed to log: %v\n", logErr)
		}

		fmt.Printf("OK (%v)\n", result.ExecutionTime)
		successCount++
	}

	fmt.Printf("\nRollback Summary: %d succeeded, %d failed\n", successCount, failedCount)
	if failedCount > 0 {
		os.Exit(1)
	}

	return nil
}
