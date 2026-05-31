package data_aggregation

import (
	"context"

	"github.com/gin-gonic/gin"

	"edgescheduler/internal/common/config"
	"edgescheduler/internal/common/logger"
)

type Module struct {
	service DataAggregationService
	handler *DataAggregationHandler
}

func NewModule() *Module {
	return &Module{}
}

func (m *Module) Init(ctx context.Context, cfg *config.Config) error {
	logger.Info("Initializing data aggregation module")

	m.service = NewDataAggregationService()
	m.handler = NewDataAggregationHandler(m.service)

	m.service.StartAggregator(ctx, 5, 10)

	logger.Info("Data aggregation module initialized")
	return nil
}

func (m *Module) RegisterRoutes(router *gin.RouterGroup) {
	m.handler.RegisterRoutes(router)
}
