package cli

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
)

func NewCompletionCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "completion [bash|zsh|fish|powershell]",
		Short: "Generate shell completion scripts",
		Long: `Generate shell completion scripts for htest.

To load completions:

Bash:

  $ source <(htest completion bash)

  # To load completions for each session, execute once:
  # Linux:
  $ htest completion bash > /etc/bash_completion.d/htest
  # macOS:
  $ htest completion bash > /usr/local/etc/bash_completion.d/htest

Zsh:

  # If shell completion is not already enabled in your environment,
  # you will need to enable it.  You can execute the following once:
  $ echo "autoload -U compinit; compinit" >> ~/.zshrc

  # To load completions for each session, execute once:
  $ htest completion zsh > "${fpath[1]}/_htest"

  # You will need to start a new shell for this setup to take effect.

fish:

  $ htest completion fish | source

  # To load completions for each session, execute once:
  $ htest completion fish > ~/.config/fish/completions/htest.fish

PowerShell:

  PS> htest completion powershell | Out-String | Invoke-Expression

  # To load completions for every new session, run:
  PS> htest completion powershell > htest.ps1
  # and source this file from your PowerShell profile.
`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			root := cmd.Root()
			shell := args[0]
			var err error
			switch shell {
			case "bash":
				err = root.GenBashCompletion(os.Stdout)
			case "zsh":
				err = root.GenZshCompletion(os.Stdout)
			case "fish":
				err = root.GenFishCompletion(os.Stdout, true)
			case "powershell":
				err = root.GenPowerShellCompletion(os.Stdout)
			default:
				return fmt.Errorf("unsupported shell type %q, must be one of: bash, zsh, fish, powershell", shell)
			}
			return err
		},
	}

	return cmd
}
