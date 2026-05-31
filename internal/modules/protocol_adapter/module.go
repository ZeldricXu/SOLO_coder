package protocol_adapter

import (
	"context"

	"github.com/gin-gonic/gin"

	"edgescheduler/internal/common/config"
	"edgescheduler/internal/common/logger"
)

type Module struct {
	service ProtocolAdapterService
	handler *ProtocolAdapterHandler
}

func NewModule() *Module {
	return &Module{}
}

func (m *Module) Init(ctx context.Context, cfg *config.Config) error {
	logger.Info("Initializing protocol adapter module")

	m.service = NewProtocolAdapterService()
	m.handler = NewProtocolAdapterHandler(m.service)

	m.service.StartAdapter(ctx, 3)

	logger.Info("Protocol adapter module initialized")
	return nil
}

func (m *Module) RegisterRoutes(router *gin.RouterGroup) {
	m.handler.RegisterRoutes(router)
}
