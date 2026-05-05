package cli

import (
	"flag"
	"fmt"
	"os"
)

type CommandContext struct {
	Args       []string
	FlagSet    *flag.FlagSet
	ConfigPath string
}

type CommandHandler interface {
	Name() string
	Description() string
	Usage() string
	Flags(ctx *CommandContext)
	Execute(ctx *CommandContext) error
}

type CommandRegistry struct {
	commands map[string]CommandHandler
}

func NewCommandRegistry() *CommandRegistry {
	return &CommandRegistry{
		commands: make(map[string]CommandHandler),
	}
}

func (r *CommandRegistry) Register(cmd CommandHandler) {
	r.commands[cmd.Name()] = cmd
}

func (r *CommandRegistry) Get(name string) (CommandHandler, bool) {
	cmd, exists := r.commands[name]
	return cmd, exists
}

func (r *CommandRegistry) List() []CommandHandler {
	cmds := make([]CommandHandler, 0, len(r.commands))
	for _, cmd := range r.commands {
		cmds = append(cmds, cmd)
	}
	return cmds
}

func (r *CommandRegistry) Execute(args []string) error {
	if len(args) < 1 {
		r.PrintUsage()
		return fmt.Errorf("no command specified")
	}

	commandName := args[0]
	commandArgs := args[1:]

	if commandName == "help" || commandName == "--help" || commandName == "-h" {
		if len(commandArgs) > 0 {
			if cmd, exists := r.Get(commandArgs[0]); exists {
				r.PrintCommandHelp(cmd)
				return nil
			}
		}
		r.PrintUsage()
		return nil
	}

	cmd, exists := r.Get(commandName)
	if !exists {
		fmt.Printf("Unknown command: %s\n\n", commandName)
		r.PrintUsage()
		return fmt.Errorf("unknown command: %s", commandName)
	}

	ctx := &CommandContext{
		Args:    commandArgs,
		FlagSet: flag.NewFlagSet(cmd.Name(), flag.ExitOnError),
	}

	cmd.Flags(ctx)

	if err := ctx.FlagSet.Parse(commandArgs); err != nil {
		return err
	}

	return cmd.Execute(ctx)
}

func (r *CommandRegistry) PrintUsage() {
	fmt.Println(`DBMigrator - Database Migration & Version Management Tool

Usage:
  dbmigrator <command> [options]

Commands:`)

	for _, cmd := range r.List() {
		fmt.Printf("  %-12s %s\n", cmd.Name(), cmd.Description())
	}

	fmt.Println(`
Examples:
  dbmigrator migrate --env development
  dbmigrator migrate --env production --to 20260504_134000
  dbmigrator rollback --env development --steps 1
  dbmigrator status --env production
  dbmigrator create --name create_users_table
  dbmigrator compare --env development

Use "dbmigrator help <command>" for more information about a command.`)
}

func (r *CommandRegistry) PrintCommandHelp(cmd CommandHandler) {
	fmt.Printf("Command: %s\n\n", cmd.Name())
	fmt.Printf("Description: %s\n\n", cmd.Description())
	fmt.Println("Usage:")
	fmt.Printf("  dbmigrator %s\n\n", cmd.Usage())

	flagSet := flag.NewFlagSet(cmd.Name(), flag.ContinueOnError)
	ctx := &CommandContext{
		FlagSet: flagSet,
	}
	cmd.Flags(ctx)

	hasFlags := false
	flagSet.VisitAll(func(f *flag.Flag) {
		hasFlags = true
	})

	if hasFlags {
		fmt.Println("Options:")
		flagSet.PrintDefaults()
	}
}

func ExitWithError(err error) {
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}
}
