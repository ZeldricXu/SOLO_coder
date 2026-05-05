package cli

import (
	"fmt"
	"os"
	"text/tabwriter"

	"dbmigrator/internal/comparison"
	"dbmigrator/internal/config"
	"dbmigrator/internal/database"
)

type CompareCommand struct {
	envFlag    string
	configFlag string
}

func NewCompareCommand() *CompareCommand {
	return &CompareCommand{}
}

func (c *CompareCommand) Name() string {
	return "compare"
}

func (c *CompareCommand) Description() string {
	return "Compare database schema"
}

func (c *CompareCommand) Usage() string {
	return "compare [--env <environment>] [--config <file>]"
}

func (c *CompareCommand) Flags(ctx *CommandContext) {
	fs := ctx.FlagSet
	fs.StringVar(&c.envFlag, "env", "development", "Target environment")
	fs.StringVar(&c.configFlag, "config", "", "Path to config file")
}

func (c *CompareCommand) Execute(ctx *CommandContext) error {
	cfg, err := config.LoadConfig(c.configFlag)
	if err != nil {
		return fmt.Errorf("failed to load config: %w", err)
	}

	envConfig, err := config.GetEnvironmentConfig(cfg, c.envFlag)
	if err != nil {
		return err
	}

	dbConn, err := database.NewDBConnection(&envConfig.Database)
	if err != nil {
		return fmt.Errorf("failed to connect to database: %w", err)
	}
	defer dbConn.Close()

	comparator := comparison.NewComparator(dbConn)

	currentSchema, err := comparator.GetCurrentSchema()
	if err != nil {
		return fmt.Errorf("failed to get current schema: %w", err)
	}

	fmt.Printf("\nCurrent Database Schema (Environment: %s)\n", c.envFlag)
	fmt.Println("================================================")

	if len(currentSchema) == 0 {
		fmt.Println("No tables found in database.")
		return nil
	}

	for _, table := range currentSchema {
		fmt.Printf("\nTable: %s\n", table.Name)
		fmt.Println("  Columns:")

		w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
		fmt.Fprintln(w, "    Name\tType\tNullable\tPrimary Key")
		fmt.Fprintln(w, "    ----\t----\t--------\t-----------")

		for _, col := range table.Columns {
			nullable := "NO"
			if col.Nullable {
				nullable = "YES"
			}
			pk := "NO"
			if col.IsPrimaryKey {
				pk = "YES"
			}
			fmt.Fprintf(w, "    %s\t%s\t%s\t%s\n", col.Name, col.DataType, nullable, pk)
		}
		w.Flush()
	}

	fmt.Printf("\nTotal tables: %d\n", len(currentSchema))

	return nil
}
