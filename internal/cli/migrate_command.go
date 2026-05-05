package cli

import (
	"fmt"
	"os"
	"path/filepath"

	"dbmigrator/internal/config"
	"dbmigrator/internal/database"
	"dbmigrator/internal/executor"
	"dbmigrator/internal/logger"
	"dbmigrator/internal/migration"
	"dbmigrator/internal/status"
	"dbmigrator/pkg/models"
)

type MigrateCommand struct {
	envFlag     string
	toFlag      string
	configFlag  string
	dryRunFlag  bool
}

func NewMigrateCommand() *MigrateCommand {
	return &MigrateCommand{}
}

func (c *MigrateCommand) Name() string {
	return "migrate"
}

func (c *MigrateCommand) Description() string {
	return "Execute pending database migrations"
}

func (c *MigrateCommand) Usage() string {
	return "migrate [--env <environment>] [--to <version>] [--config <file>] [--dry-run]"
}

func (c *MigrateCommand) Flags(ctx *CommandContext) {
	fs := ctx.FlagSet
	fs.StringVar(&c.envFlag, "env", "development", "Target environment")
	fs.StringVar(&c.toFlag, "to", "latest", "Target version (default: latest)")
	fs.StringVar(&c.configFlag, "config", "", "Path to config file")
	fs.BoolVar(&c.dryRunFlag, "dry-run", false, "Preview migrations without executing")
}

func (c *MigrateCommand) Execute(ctx *CommandContext) error {
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

	exec := executor.NewExecutor(dbConn)

	executedIDs, _, err := statusTracker.GetExecutedMigrations()
	if err != nil {
		return fmt.Errorf("failed to get executed migrations: %w", err)
	}

	var pendingMigrations []*models.Migration
	if c.toFlag == "latest" {
		pendingMigrations, err = migManager.GetMigrationsTopological(executedIDs)
		if err != nil {
			return fmt.Errorf("failed to resolve migration dependencies: %w", err)
		}
	} else {
		migrationsUpTo, err := migManager.GetMigrationsUpToTopological(c.toFlag, executedIDs)
		if err != nil {
			return fmt.Errorf("failed to resolve migration dependencies: %w", err)
		}
		pendingMigrations = migrationsUpTo
	}

	if len(pendingMigrations) == 0 {
		fmt.Println("No pending migrations to execute.")
		return nil
	}

	fmt.Printf("Found %d pending migration(s):\n", len(pendingMigrations))
	for i, mig := range pendingMigrations {
		if len(mig.Dependencies) > 0 {
			fmt.Printf("  %d. %s (%s) [depends: %v]\n", i+1, mig.Version, mig.Name, mig.Dependencies)
		} else {
			fmt.Printf("  %d. %s (%s)\n", i+1, mig.Version, mig.Name)
		}
	}

	if c.dryRunFlag {
		fmt.Println("\n=== Dry Run - Preview Mode ===")
		for _, mig := range pendingMigrations {
			fmt.Printf("\n--- Migration: %s ---\n", mig.MigrationID)
			statements, err := exec.PreviewMigration(mig)
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

	fmt.Println("\nExecuting migrations...")
	successCount := 0
	failedCount := 0

	for _, mig := range pendingMigrations {
		fmt.Printf("  Executing: %s (%s) ... ", mig.Version, mig.Name)

		result, err := exec.ExecuteMigration(mig)
		if err != nil || !result.Success {
			fmt.Println("FAILED")
			if result.Error != nil {
				fmt.Printf("    Error: %v\n", result.Error)
			}
			failedCount++

			if logErr := migrationLogger.LogExecution(mig, "migrate_up", []string{}, result); logErr != nil {
				fmt.Printf("    Warning: Failed to log error: %v\n", logErr)
			}
			break
		}

		if err := statusTracker.RecordMigration(mig, result); err != nil {
			fmt.Println("ERROR (status tracking)")
			fmt.Printf("    Error: %v\n", err)
			failedCount++
			break
		}

		statements, _ := exec.PreviewMigration(mig)
		if logErr := migrationLogger.LogExecution(mig, "migrate_up", statements, result); logErr != nil {
			fmt.Printf("    Warning: Failed to log: %v\n", logErr)
		}

		fmt.Printf("OK (%v)\n", result.ExecutionTime)
		successCount++
	}

	fmt.Printf("\nMigration Summary: %d succeeded, %d failed\n", successCount, failedCount)
	if failedCount > 0 {
		os.Exit(1)
	}

	return nil
}
