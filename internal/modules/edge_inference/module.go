package edge_inference

import (
	"context"

	"github.com/gin-gonic/gin"

	"edgescheduler/internal/common/config"
	"edgescheduler/internal/common/logger"
)

type Module struct {
	service EdgeInferenceService
	handler *EdgeInferenceHandler
}

func NewModule() *Module {
	return &Module{}
}

func (m *Module) Init(ctx context.Context, cfg *config.Config) error {
	logger.Info("Initializing edge inference module")

	m.service = NewEdgeInferenceService()
	m.handler = NewEdgeInferenceHandler(m.service)

	m.service.StartScheduler(ctx, 5)

	logger.Info("Edge inference module initialized")
	return nil
}

func (m *Module) RegisterRoutes(router *gin.RouterGroup) {
	m.handler.RegisterRoutes(router)
}
