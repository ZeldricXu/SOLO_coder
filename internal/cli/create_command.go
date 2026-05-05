package cli

import (
	"fmt"
	"path/filepath"

	"dbmigrator/internal/config"
	"dbmigrator/internal/migration"
)

type CreateCommand struct {
	nameFlag   string
	configFlag string
}

func NewCreateCommand() *CreateCommand {
	return &CreateCommand{}
}

func (c *CreateCommand) Name() string {
	return "create"
}

func (c *CreateCommand) Description() string {
	return "Create a new migration file"
}

func (c *CreateCommand) Usage() string {
	return "create --name <migration_name> [--config <file>]"
}

func (c *CreateCommand) Flags(ctx *CommandContext) {
	fs := ctx.FlagSet
	fs.StringVar(&c.nameFlag, "name", "", "Migration name (required)")
	fs.StringVar(&c.configFlag, "config", "", "Path to config file")
}

func (c *CreateCommand) Execute(ctx *CommandContext) error {
	if c.nameFlag == "" {
		return fmt.Errorf("--name is required\nUsage: dbmigrator create --name <migration_name>")
	}

	safeName := sanitizeMigrationName(c.nameFlag)
	if safeName == "" {
		return fmt.Errorf("invalid migration name")
	}

	cfg, err := config.LoadConfig(c.configFlag)
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	migrationsDir := cfg.Migrations.Directory
	if !filepath.IsAbs(migrationsDir) {
		migrationsDir = filepath.Join(filepath.Dir(findConfigPath(c.configFlag)), migrationsDir)
	}

	migManager, err := migration.NewManager(migrationsDir)
	if err != nil {
		return fmt.Errorf("failed to create migration manager: %w", err)
	}

	upFile, downFile, err := migManager.CreateMigration(safeName)
	if err != nil {
		return fmt.Errorf("failed to create migration: %w", err)
	}

	fmt.Println("Migration files created:")
	fmt.Printf("  UP:   %s\n", upFile)
	fmt.Printf("  DOWN: %s\n", downFile)
	fmt.Println("\nEdit these files to add your migration SQL.")
	fmt.Println("\nTo declare dependencies on other migrations, add a comment like:")
	fmt.Println("  -- @depends mig_20260504_134000_create_table")

	return nil
}
