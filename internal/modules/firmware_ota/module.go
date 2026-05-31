package firmware_ota

import (
	"context"

	"github.com/gin-gonic/gin"

	"edgescheduler/internal/common/config"
	"edgescheduler/internal/common/logger"
)

type Module struct {
	service FirmwareOTAService
	handler *FirmwareOTAHandler
}

func NewModule() *Module {
	return &Module{}
}

func (m *Module) Init(ctx context.Context, cfg *config.Config) error {
	logger.Info("Initializing firmware OTA module")

	m.service = NewFirmwareOTAService()
	m.handler = NewFirmwareOTAHandler(m.service)

	m.service.StartOTAManager(ctx)

	logger.Info("Firmware OTA module initialized")
	return nil
}

func (m *Module) RegisterRoutes(router *gin.RouterGroup) {
	m.handler.RegisterRoutes(router)
}
