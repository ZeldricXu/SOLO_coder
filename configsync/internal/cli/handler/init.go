package handler

import (
	"fmt"
	"os"
	"path/filepath"

	"configsync/internal/cli"
	"configsync/internal/config"
	"configsync/internal/logger"
	"configsync/internal/server"

	"github.com/spf13/cobra"
)

type InitHandler struct {
	ctx *cli.AppContext
}

func NewInitHandler() *InitHandler {
	return &InitHandler{}
}

func (h *InitHandler) Name() string {
	return "init"
}

func (h *InitHandler) Description() string {
	return "初始化ConfigSync环境"
}

func (h *InitHandler) SetAppContext(ctx *cli.AppContext) {
	h.ctx = ctx
}

func (h *InitHandler) CreateCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   h.Name(),
		Short: h.Description(),
		Run:   h.Execute,
	}

	return cmd
}

func (h *InitHandler) Execute(cmd *cobra.Command, args []string) {
	serverDir := filepath.Join(h.ctx.DataDir, "servers")
	configDir := filepath.Join(h.ctx.DataDir, "configs")
	logDir := filepath.Join(h.ctx.DataDir, "logs")

	for _, dir := range []string{serverDir, configDir, logDir} {
		if err := os.MkdirAll(dir, 0755); err != nil {
			fmt.Fprintf(os.Stderr, "Error: 创建目录失败 %s: %v\n", dir, err)
			os.Exit(1)
		}
	}

	_, err := server.NewManager(serverDir)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: 初始化服务器管理器失败: %v\n", err)
		os.Exit(1)
	}

	_, err = config.NewConfigManager(configDir)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: 初始化配置管理器失败: %v\n", err)
		os.Exit(1)
	}

	_, err = logger.NewLogger(logDir)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: 初始化日志管理器失败: %v\n", err)
		os.Exit(1)
	}

	fmt.Println("ConfigSync 环境初始化完成!")
	fmt.Printf("数据目录: %s\n", h.ctx.DataDir)
	fmt.Printf("  服务器配置: %s\n", serverDir)
	fmt.Printf("  配置仓库: %s\n", configDir)
	fmt.Printf("  操作日志: %s\n", logDir)
}
