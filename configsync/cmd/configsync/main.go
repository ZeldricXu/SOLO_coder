package main

import (
	"fmt"
	"os"
	"os/user"
	"path/filepath"

	"github.com/spf13/cobra"

	"configsync/internal/cli"
	"configsync/internal/cli/handler"
	"configsync/internal/cli/registry"
	"configsync/internal/config"
	"configsync/internal/logger"
	"configsync/internal/pool"
	"configsync/internal/server"
)

var (
	rootCmd = &cobra.Command{
		Use:   "configsync",
		Short: "ConfigSync - 分布式配置文件同步管理工具",
		Long:  `ConfigSync是一个命令行工具，用于在多台服务器之间同步配置文件，支持版本管理、变更比对、批量推送和配置回滚。`,
	}

	dataDir string
)

var handlerMap = make(map[string]cli.CommandHandler)

func init() {
	handler.RegisterAllHandlers()

	usr, _ := user.Current()
	defaultDataDir := filepath.Join(usr.HomeDir, ".configsync")

	rootCmd.PersistentFlags().StringVar(&dataDir, "data-dir", defaultDataDir, "数据存储目录")

	buildCommandsFromRegistry()
}

func buildCommandsFromRegistry() {
	reg := registry.GetRegistry()
	handlers := reg.List()

	for _, h := range handlers {
		handlerMap[h.Name()] = h
		cmd := h.CreateCommand()
		rootCmd.AddCommand(cmd)
	}
}

func buildAppContext() (*cli.AppContext, error) {
	serverDir := filepath.Join(dataDir, "servers")
	configDir := filepath.Join(dataDir, "configs")
	logDir := filepath.Join(dataDir, "logs")

	serverManager, err := server.NewManager(serverDir)
	if err != nil {
		return nil, fmt.Errorf("初始化服务器管理器失败: %w", err)
	}

	configManager, err := config.NewConfigManager(configDir)
	if err != nil {
		return nil, fmt.Errorf("初始化配置管理器失败: %w", err)
	}

	logManager, err := logger.NewLogger(logDir)
	if err != nil {
		return nil, fmt.Errorf("初始化日志管理器失败: %w", err)
	}

	connPool := pool.NewConnectionPool()

	return &cli.AppContext{
		DataDir:        dataDir,
		ServerManager:  serverManager,
		ConfigManager:  configManager,
		Logger:         logManager,
		ConnectionPool: connPool,
	}, nil
}

func main() {
	ctx, err := buildAppContext()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}
	defer ctx.ConnectionPool.Close()

	for _, h := range handlerMap {
		h.SetAppContext(ctx)
	}

	if err := rootCmd.Execute(); err != nil {
		fmt.Println(err)
		os.Exit(1)
	}
}
