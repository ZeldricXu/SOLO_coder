package cli

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
	"github.com/spf13/cobra/doc"
)

func NewManPageCmd() *cobra.Command {
	var manDir string

	cmd := &cobra.Command{
		Use:   "manpage",
		Short: "Generate man page documentation",
		Long:  "Generate man page documentation for htest and all its subcommands.",
		RunE: func(cmd *cobra.Command, args []string) error {
			root := cmd.Root()

			if manDir != "" {
				if err := os.MkdirAll(manDir, 0755); err != nil {
					return fmt.Errorf("creating man directory: %w", err)
				}
				header := &doc.GenManHeader{
					Title:   "HTEST",
					Section: "1",
					Manual:  "Htest Manual",
					Source:  "htest v1.0.0",
				}
				if err := doc.GenManTree(root, header, manDir); err != nil {
					return fmt.Errorf("generating man pages: %w", err)
				}
				fmt.Fprintf(os.Stdout, "Man pages generated in %s\n", manDir)
				return nil
			}

			header := &doc.GenManHeader{
				Title:   "HTEST",
				Section: "1",
				Manual:  "Htest Manual",
				Source:  "htest v1.0.0",
			}
			return doc.GenMan(root, header, os.Stdout)
		},
	}

	cmd.Flags().StringVar(&manDir, "dir", "", "output directory for man page files")

	return cmd
}
