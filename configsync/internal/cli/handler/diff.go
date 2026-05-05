package handler

import (
	"fmt"
	"os"
	"strings"

	"configsync/internal/cli"
	"configsync/internal/sync"

	"github.com/spf13/cobra"
)

type DiffHandler struct {
	ctx   *cli.AppContext
	file  string
	group string
	local bool
}

func NewDiffHandler() *DiffHandler {
	return &DiffHandler{}
}

func (h *DiffHandler) Name() string {
	return "diff"
}

func (h *DiffHandler) Description() string {
	return "比对配置文件变更"
}

func (h *DiffHandler) SetAppContext(ctx *cli.AppContext) {
	h.ctx = ctx
}

func (h *DiffHandler) CreateCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   h.Name(),
		Short: h.Description(),
		Run:   h.Execute,
	}

	cmd.Flags().StringVar(&h.file, "file", "", "配置文件名称 (必填)")
	cmd.Flags().StringVar(&h.group, "group", "", "目标服务器组名称 (可选，用于比对远程)")
	cmd.Flags().BoolVar(&h.local, "local", false, "比对本地工作区与最新版本")

	cmd.MarkFlagRequired("file")

	return cmd
}

func (h *DiffHandler) Execute(cmd *cobra.Command, args []string) {
	if h.local {
		diff, err := h.ctx.ConfigManager.GetDiff(h.file)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: 获取差异失败: %v\n", err)
			os.Exit(1)
		}

		if diff == "" {
			fmt.Println("配置文件无变更")
		} else {
			fmt.Println("变更差异:")
			fmt.Println(diff)
		}
		return
	}

	if h.group != "" {
		group, err := h.ctx.ServerManager.GetGroup(h.group)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: 服务器组不存在: %v\n", err)
			os.Exit(1)
		}

		localContent, err := h.ctx.ConfigManager.ReadConfig(h.file)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: 读取本地配置失败: %v\n", err)
			os.Exit(1)
		}

		fmt.Printf("比对配置文件: %s\n", h.file)
		fmt.Printf("目标服务器组: %s\n", h.group)
		fmt.Println("----------------------------------------")

		syncService := sync.NewSyncService(h.ctx.ConnectionPool)
		defer syncService.Close()

		for _, srv := range group.Servers {
			fmt.Printf("\n服务器: %s (%s)\n", srv.ServerID, srv.Host)
			diff, err := syncService.DiffLocalRemote(&srv, localContent)
			if err != nil {
				fmt.Printf("  错误: %v\n", err)
				continue
			}
			if diff == "" {
				fmt.Println("  配置一致")
			} else {
				fmt.Println("  差异:")
				lines := strings.Split(diff, "\n")
				for _, line := range lines {
					if line != "" {
						fmt.Printf("  %s\n", line)
					}
				}
			}
		}
		return
	}

	fmt.Println("请指定 --local 或 --group 参数")
	os.Exit(1)
}
