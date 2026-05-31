package device_lifecycle

import (
	"context"

	"github.com/gin-gonic/gin"

	"edgescheduler/internal/common/config"
	"edgescheduler/internal/common/logger"
)

type Module struct {
	service DeviceLifecycleService
	handler *DeviceLifecycleHandler
}

func NewModule() *Module {
	return &Module{}
}

func (m *Module) Init(ctx context.Context, cfg *config.Config) error {
	logger.Info("Initializing device lifecycle module")

	m.service = NewDeviceLifecycleService()
	m.handler = NewDeviceLifecycleHandler(m.service)

	m.service.StartHeartbeatMonitor(ctx, 30)

	logger.Info("Device lifecycle module initialized")
	return nil
}

func (m *Module) RegisterRoutes(router *gin.RouterGroup) {
	m.handler.RegisterRoutes(router)
}
