package cli

import (
	"fmt"
	"os"
	"path/filepath"
	"text/tabwriter"

	"dbmigrator/internal/config"
	"dbmigrator/internal/database"
	"dbmigrator/internal/migration"
	"dbmigrator/internal/status"
	"dbmigrator/pkg/models"
)

type StatusCommand struct {
	envFlag    string
	configFlag string
	allFlag    bool
}

func NewStatusCommand() *StatusCommand {
	return &StatusCommand{}
}

func (c *StatusCommand) Name() string {
	return "status"
}

func (c *StatusCommand) Description() string {
	return "Show migration status"
}

func (c *StatusCommand) Usage() string {
	return "status [--env <environment>] [--config <file>] [--all]"
}

func (c *StatusCommand) Flags(ctx *CommandContext) {
	fs := ctx.FlagSet
	fs.StringVar(&c.envFlag, "env", "development", "Target environment")
	fs.StringVar(&c.configFlag, "config", "", "Path to config file")
	fs.BoolVar(&c.allFlag, "all", false, "Show all migrations including history")
}

func (c *StatusCommand) Execute(ctx *CommandContext) error {
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

	executedIDs, executedStates, err := statusTracker.GetExecutedMigrations()
	if err != nil {
		return fmt.Errorf("failed to get executed migrations: %w", err)
	}

	allMigrations := migManager.GetMigrations()
	executedMap := make(map[string]*models.MigrationState)
	for _, state := range executedStates {
		executedMap[state.MigrationID] = state
	}

	fmt.Printf("\nMigration Status (Environment: %s)\n", c.envFlag)
	fmt.Println("========================================")

	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "VERSION\tNAME\tSTATUS\tEXECUTED AT\tDEPENDENCIES")
	fmt.Fprintln(w, "-------\t----\t------\t-----------\t------------")

	for _, mig := range allMigrations {
		state, isExecuted := executedMap[mig.MigrationID]
		status := "PENDING"
		executedAt := "-"
		deps := "-"

		if isExecuted {
			status = "EXECUTED"
			if !state.ExecutedAt.IsZero() {
				executedAt = state.ExecutedAt.Format("2006-01-02 15:04:05")
			}
		}

		if len(mig.Dependencies) > 0 {
			deps = fmt.Sprintf("%v", mig.Dependencies)
		}

		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\n", mig.Version, mig.Name, status, executedAt, deps)
	}

	w.Flush()

	pendingCount := len(allMigrations) - len(executedStates)
	fmt.Printf("\nSummary: %d total, %d executed, %d pending\n",
		len(allMigrations), len(executedStates), pendingCount)

	return nil
}
