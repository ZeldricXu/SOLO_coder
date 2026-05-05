package cli

import (
	"configsync/internal/config"
	"configsync/internal/logger"
	"configsync/internal/pool"
	"configsync/internal/server"

	"github.com/spf13/cobra"
)

type AppContext struct {
	DataDir        string
	ServerManager  *server.Manager
	ConfigManager  *config.ConfigManager
	Logger         *logger.Logger
	ConnectionPool *pool.ConnectionPool
}

type CommandHandler interface {
	Name() string
	Description() string
	CreateCommand() *cobra.Command
	SetAppContext(ctx *AppContext)
}
