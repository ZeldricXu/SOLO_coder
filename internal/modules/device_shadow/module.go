package device_shadow

import (
	"context"
	"time"

	"github.com/gin-gonic/gin"

	"edgescheduler/internal/common/config"
	"edgescheduler/internal/common/logger"
)

type Module struct {
	service DeviceShadowService
	handler *DeviceShadowHandler
}

func NewModule() *Module {
	return &Module{}
}

func (m *Module) Init(ctx context.Context, cfg *config.Config) error {
	logger.Info("Initializing device shadow module")

	m.service = NewDeviceShadowService()
	m.handler = NewDeviceShadowHandler(m.service)

	m.service.StartShadowSync(ctx, 30*time.Second)

	logger.Info("Device shadow module initialized")
	return nil
}

func (m *Module) RegisterRoutes(router *gin.RouterGroup) {
	m.handler.RegisterRoutes(router)
}
