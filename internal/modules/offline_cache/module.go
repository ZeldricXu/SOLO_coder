package offline_cache

import (
	"context"

	"github.com/gin-gonic/gin"

	"edgescheduler/internal/common/config"
	"edgescheduler/internal/common/logger"
)

type Module struct {
	service OfflineCacheService
	handler *OfflineCacheHandler
}

func NewModule() *Module {
	return &Module{}
}

func (m *Module) Init(ctx context.Context, cfg *config.Config) error {
	logger.Info("Initializing offline cache module")

	m.service = NewOfflineCacheService()
	m.handler = NewOfflineCacheHandler(m.service)

	m.service.StartSyncManager(ctx, 5, 10)

	logger.Info("Offline cache module initialized")
	return nil
}

func (m *Module) RegisterRoutes(router *gin.RouterGroup) {
	m.handler.RegisterRoutes(router)
}
