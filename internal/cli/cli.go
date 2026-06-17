package cli

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/cobra"
	"github.com/spf13/pflag"

	"github.com/multicloud/cli/internal/common"
)

type CLI struct {
	rootCmd     *cobra.Command
	configDir   string
	stateFile   string
	configFile  string
	vaultFile   string
	noColor     bool
	verbose     bool
	autoApprove bool
	jsonOutput  bool
	ctx         context.Context
}

type CLIOption func(*CLI)

func WithConfigDir(dir string) CLIOption {
	return func(c *CLI) { c.configDir = dir }
}

func WithStateFile(file string) CLIOption {
	return func(c *CLI) { c.stateFile = file }
}

func WithConfigFile(file string) CLIOption {
	return func(c *CLI) { c.configFile = file }
}

func WithVaultFile(file string) CLIOption {
	return func(c *CLI) { c.vaultFile = file }
}

func NewCLI(opts ...CLIOption) *CLI {
	homeDir := common.GetHomeDir()
	defaultConfigDir := filepath.Join(homeDir, ".multicloud")

	cli := &CLI{
		configDir:  defaultConfigDir,
		stateFile:  "terraform.tfstate",
		configFile: "main.tf",
		vaultFile:  filepath.Join(defaultConfigDir, "vault.json"),
		ctx:        context.Background(),
	}

	for _, opt := range opts {
		opt(cli)
	}

	cli.rootCmd = cli.buildRootCmd()
	return cli
}

func (c *CLI) buildRootCmd() *cobra.Command {
	rootCmd := &cobra.Command{
		Use:   "multicloud",
		Short: "Multi-Cloud Infrastructure Management CLI",
		Long: `A unified command-line interface for managing infrastructure across 
AWS, Azure, and GCP with declarative configuration, state management,
and compliance scanning.`,
		SilenceUsage:  true,
		SilenceErrors: true,
		PersistentPreRun: func(cmd *cobra.Command, args []string) {
			if c.noColor {
				os.Setenv("NO_COLOR", "1")
			}
		},
	}

	rootCmd.PersistentFlags().BoolVar(&c.noColor, "no-color", false, "Disable colored output")
	rootCmd.PersistentFlags().BoolVarP(&c.verbose, "verbose", "v", false, "Enable verbose output")
	rootCmd.PersistentFlags().StringVar(&c.configDir, "config-dir", c.configDir, "Configuration directory")
	rootCmd.PersistentFlags().StringVar(&c.stateFile, "state", c.stateFile, "State file path")
	rootCmd.PersistentFlags().StringVar(&c.vaultFile, "vault", c.vaultFile, "Vault file path")
	rootCmd.PersistentFlags().BoolVar(&c.jsonOutput, "json", false, "Output in JSON format")

	bindEnvFlag(rootCmd.PersistentFlags().Lookup("no-color"), "MULTICLOUD_NO_COLOR")
	bindEnvFlag(rootCmd.PersistentFlags().Lookup("verbose"), "MULTICLOUD_VERBOSE")
	bindEnvFlag(rootCmd.PersistentFlags().Lookup("config-dir"), "MULTICLOUD_CONFIG_DIR")
	bindEnvFlag(rootCmd.PersistentFlags().Lookup("state"), "MULTICLOUD_STATE_FILE")
	bindEnvFlag(rootCmd.PersistentFlags().Lookup("vault"), "MULTICLOUD_VAULT_FILE")

	rootCmd.AddCommand(c.buildInitCmd())
	rootCmd.AddCommand(c.buildPlanCmd())
	rootCmd.AddCommand(c.buildApplyCmd())
	rootCmd.AddCommand(c.buildDestroyCmd())
	rootCmd.AddCommand(c.buildCredentialsCmd())
	rootCmd.AddCommand(c.buildStateCmd())
	rootCmd.AddCommand(c.buildComplianceCmd())
	rootCmd.AddCommand(c.buildAuditCmd())
	rootCmd.AddCommand(c.buildVersionCmd())
	rootCmd.AddCommand(c.buildCompletionCmd())

	return rootCmd
}

func (c *CLI) buildInitCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "init [config-file]",
		Short: "Initialize a new multi-cloud project",
		Long:  "Initialize the working directory with configuration, state, and vault files.",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(args) > 0 {
				c.configFile = args[0]
			}
			return c.runInit()
		},
	}

	cmd.Flags().Bool("backend", false, "Configure remote backend")
	cmd.Flags().String("backend-type", "local", "Backend type (local, s3)")
	cmd.Flags().String("bucket", "", "S3 bucket name for remote state")
	cmd.Flags().String("key", "", "S3 key for state file")
	cmd.Flags().String("region", "us-east-1", "AWS region for S3 bucket")

	return cmd
}

func (c *CLI) buildPlanCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "plan [config-file]",
		Short: "Generate an execution plan",
		Long:  "Compare the desired configuration against the current state and show planned changes.",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(args) > 0 {
				c.configFile = args[0]
			}
			detailed, _ := cmd.Flags().GetBool("detailed")
			out, _ := cmd.Flags().GetString("out")
			return c.runPlan(detailed, out)
		},
	}

	cmd.Flags().BoolP("detailed", "d", false, "Show detailed diff")
	cmd.Flags().StringP("out", "o", "", "Save plan to file")
	cmd.Flags().Bool("skip-compliance", false, "Skip compliance checks")

	return cmd
}

func (c *CLI) buildApplyCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "apply [plan-file]",
		Short: "Apply the changes",
		Long:  "Apply the execution plan to create, update, or destroy resources.",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			planFile := ""
			if len(args) > 0 {
				planFile = args[0]
			}
			parallel, _ := cmd.Flags().GetInt("parallel")
			rollback, _ := cmd.Flags().GetBool("rollback")
			return c.runApply(planFile, parallel, rollback)
		},
	}

	cmd.Flags().BoolVarP(&c.autoApprove, "auto-approve", "y", false, "Skip interactive approval")
	cmd.Flags().IntP("parallel", "p", 10, "Maximum parallel operations")
	cmd.Flags().Bool("rollback", true, "Enable automatic rollback on failure")
	cmd.Flags().String("config", c.configFile, "Configuration file path")

	return cmd
}

func (c *CLI) buildDestroyCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "destroy",
		Short: "Destroy all managed resources",
		Long:  "Destroy all resources managed by this configuration.",
		RunE: func(cmd *cobra.Command, args []string) error {
			force, _ := cmd.Flags().GetBool("force")
			target, _ := cmd.Flags().GetStringSlice("target")
			return c.runDestroy(force, target)
		},
	}

	cmd.Flags().BoolVarP(&c.autoApprove, "auto-approve", "y", false, "Skip interactive approval")
	cmd.Flags().BoolP("force", "f", false, "Force destroy without confirmation")
	cmd.Flags().StringSliceP("target", "t", nil, "Target specific resources")

	return cmd
}

func (c *CLI) buildCredentialsCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "credentials",
		Short: "Manage cloud credentials",
		Long:  "Add, remove, list, and rotate cloud provider credentials.",
	}

	cmd.AddCommand(c.buildCredAddCmd())
	cmd.AddCommand(c.buildCredListCmd())
	cmd.AddCommand(c.buildCredRemoveCmd())
	cmd.AddCommand(c.buildCredRotateCmd())
	cmd.AddCommand(c.buildCredExportCmd())
	cmd.AddCommand(c.buildCredCheckCmd())

	return cmd
}

func (c *CLI) buildCredAddCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "add [provider]",
		Short: "Add credentials for a cloud provider",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			provider := common.CloudProvider(args[0])
			accessKey, _ := cmd.Flags().GetString("access-key")
			secretKey, _ := cmd.Flags().GetString("secret-key")
			sessionToken, _ := cmd.Flags().GetString("session-token")
			tenantID, _ := cmd.Flags().GetString("tenant-id")
			subscriptionID, _ := cmd.Flags().GetString("subscription-id")
			projectID, _ := cmd.Flags().GetString("project-id")
			region, _ := cmd.Flags().GetString("region")

			return c.runCredAdd(provider, accessKey, secretKey, sessionToken,
				tenantID, subscriptionID, projectID, region)
		},
	}

	cmd.Flags().String("access-key", "", "Access key ID (AWS/GCP)")
	cmd.Flags().String("secret-key", "", "Secret access key (AWS)")
	cmd.Flags().String("session-token", "", "Session token (AWS)")
	cmd.Flags().String("tenant-id", "", "Tenant ID (Azure)")
	cmd.Flags().String("subscription-id", "", "Subscription ID (Azure)")
	cmd.Flags().String("project-id", "", "Project ID (GCP)")
	cmd.Flags().String("region", "", "Default region")
	cmd.Flags().Bool("use-env", false, "Use environment variables")

	return cmd
}

func (c *CLI) buildCredListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List configured credentials",
		RunE: func(cmd *cobra.Command, args []string) error {
			return c.runCredList()
		},
	}
}

func (c *CLI) buildCredRemoveCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "remove [provider]",
		Short: "Remove credentials for a provider",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			provider := common.CloudProvider(args[0])
			return c.runCredRemove(provider)
		},
	}
}

func (c *CLI) buildCredRotateCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "rotate [provider]",
		Short: "Rotate credentials for a provider",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			provider := common.CloudProvider(args[0])
			return c.runCredRotate(provider)
		},
	}
}

func (c *CLI) buildCredExportCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "export [provider]",
		Short: "Export credentials as environment variables",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			provider := common.CloudProvider(args[0])
			return c.runCredExport(provider)
		},
	}
}

func (c *CLI) buildCredCheckCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "check",
		Short: "Check credential validity and rotation status",
		RunE: func(cmd *cobra.Command, args []string) error {
			return c.runCredCheck()
		},
	}
}

func (c *CLI) buildStateCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "state",
		Short: "Manage infrastructure state",
		Long:  "Inspect and modify the state file.",
	}

	cmd.AddCommand(c.buildStateListCmd())
	cmd.AddCommand(c.buildStateShowCmd())
	cmd.AddCommand(c.buildStateRmCmd())
	cmd.AddCommand(c.buildStateMvCmd())
	cmd.AddCommand(c.buildStatePullCmd())
	cmd.AddCommand(c.buildStatePushCmd())
	cmd.AddCommand(c.buildStateUnlockCmd())

	return cmd
}

func (c *CLI) buildStateListCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "list",
		Short: "List resources in state",
		RunE: func(cmd *cobra.Command, args []string) error {
			return c.runStateList()
		},
	}
}

func (c *CLI) buildStateShowCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "show [resource-name]",
		Short: "Show a specific resource in state",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return c.runStateShow(args[0])
		},
	}
}

func (c *CLI) buildStateRmCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "rm [resource-name]",
		Short: "Remove a resource from state",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			force, _ := cmd.Flags().GetBool("force")
			return c.runStateRm(args[0], force)
		},
		Aliases: []string{"remove"},
	}
}

func (c *CLI) buildStateMvCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "mv [source] [destination]",
		Short: "Move a resource in state",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			return c.runStateMv(args[0], args[1])
		},
		Aliases: []string{"move"},
	}
}

func (c *CLI) buildStatePullCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "pull",
		Short: "Pull remote state to local",
		RunE: func(cmd *cobra.Command, args []string) error {
			return c.runStatePull()
		},
	}
}

func (c *CLI) buildStatePushCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "push",
		Short: "Push local state to remote",
		RunE: func(cmd *cobra.Command, args []string) error {
			force, _ := cmd.Flags().GetBool("force")
			return c.runStatePush(force)
		},
	}
}

func (c *CLI) buildStateUnlockCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "unlock [lock-id]",
		Short: "Unlock the state file",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			force, _ := cmd.Flags().GetBool("force")
			return c.runStateUnlock(args[0], force)
		},
	}
}

func (c *CLI) buildComplianceCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "compliance [config-file]",
		Short: "Run compliance scans",
		Long:  "Scan configuration for compliance with security baselines (CIS, etc.).",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if len(args) > 0 {
				c.configFile = args[0]
			}
			framework, _ := cmd.Flags().GetString("framework")
			severity, _ := cmd.Flags().GetString("severity")
			return c.runCompliance(framework, severity)
		},
	}

	cmd.Flags().StringP("framework", "f", "cis", "Compliance framework (cis, etc, custom)")
	cmd.Flags().StringP("severity", "s", "medium", "Minimum severity to report")
	cmd.Flags().Bool("auto-fix", false, "Automatically fix non-compliant items")
	cmd.Flags().String("rules", "", "Path to custom rules file")

	return cmd
}

func (c *CLI) buildAuditCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "audit",
		Short: "View operation audit logs",
		Long:  "View the audit log of all operations performed.",
	}

	cmd.AddCommand(c.buildAuditLogCmd())
	cmd.AddCommand(c.buildAuditExportCmd())

	return cmd
}

func (c *CLI) buildAuditLogCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "log",
		Short: "Show audit log entries",
		RunE: func(cmd *cobra.Command, args []string) error {
			limit, _ := cmd.Flags().GetInt("limit")
			action, _ := cmd.Flags().GetString("action")
			provider, _ := cmd.Flags().GetString("provider")
			return c.runAuditLog(limit, action, provider)
		},
	}
}

func (c *CLI) buildAuditExportCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "export [file]",
		Short: "Export audit logs",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			outputFile := "audit_logs.json"
			if len(args) > 0 {
				outputFile = args[0]
			}
			format, _ := cmd.Flags().GetString("format")
			return c.runAuditExport(outputFile, format)
		},
	}
}

func (c *CLI) buildVersionCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "version",
		Short: "Print version information",
		RunE: func(cmd *cobra.Command, args []string) error {
			return c.runVersion()
		},
	}
}

func (c *CLI) buildCompletionCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "completion [bash|zsh|fish|powershell]",
		Short: "Generate shell completion scripts",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			shell := args[0]
			switch shell {
			case "bash":
				return cmd.Root().GenBashCompletion(os.Stdout)
			case "zsh":
				return cmd.Root().GenZshCompletion(os.Stdout)
			case "fish":
				return cmd.Root().GenFishCompletion(os.Stdout, true)
			case "powershell":
				return cmd.Root().GenPowerShellCompletion(os.Stdout)
			default:
				return fmt.Errorf("unsupported shell: %s", shell)
			}
		},
	}

	return cmd
}

func bindEnvFlag(flag *pflag.Flag, envVar string) {
	if flag == nil {
		return
	}

	if envVal := os.Getenv(envVar); envVal != "" {
		flag.Value.Set(envVal)
	}

	flag.Usage = fmt.Sprintf("%s (env: %s)", flag.Usage, envVar)
}

func (c *CLI) Execute() error {
	return c.rootCmd.Execute()
}

func (c *CLI) ExecuteArgs(args []string) error {
	c.rootCmd.SetArgs(args)
	return c.rootCmd.Execute()
}

func (c *CLI) Context() context.Context {
	return c.ctx
}

func (c *CLI) ConfigDir() string {
	return c.configDir
}

func (c *CLI) StateFile() string {
	return c.stateFile
}

func (c *CLI) ConfigFile() string {
	return c.configFile
}

func (c *CLI) VaultFile() string {
	return c.vaultFile
}

func (c *CLI) NoColor() bool {
	return c.noColor
}

func (c *CLI) Verbose() bool {
	return c.verbose
}

func (c *CLI) AutoApprove() bool {
	return c.autoApprove
}

func (c *CLI) JSONOutput() bool {
	return c.jsonOutput
}

func (c *CLI) print(format string, args ...interface{}) {
	if c.jsonOutput {
		return
	}
	fmt.Printf(format, args...)
}

func (c *CLI) println(format string, args ...interface{}) {
	if c.jsonOutput {
		return
	}
	fmt.Println(fmt.Sprintf(format, args...))
}

func (c *CLI) verbosePrint(format string, args ...interface{}) {
	if c.verbose && !c.jsonOutput {
		fmt.Printf(format, args...)
	}
}

func (c *CLI) verbosePrintln(format string, args ...interface{}) {
	if c.verbose && !c.jsonOutput {
		fmt.Println(fmt.Sprintf(format, args...))
	}
}

func printError(err error) {
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
	}
}

func promptConfirmation(message string) bool {
	fmt.Printf("%s (yes/no): ", message)
	var response string
	fmt.Scanln(&response)
	response = strings.ToLower(strings.TrimSpace(response))
	return response == "yes" || response == "y"
}
