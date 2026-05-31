package edge_rules

import (
	"context"

	"github.com/gin-gonic/gin"

	"edgescheduler/internal/common/config"
	"edgescheduler/internal/common/logger"
)

type Module struct {
	engine  EdgeRulesEngine
	handler *EdgeRulesHandler
}

func NewModule() *Module {
	return &Module{}
}

func (m *Module) Init(ctx context.Context, cfg *config.Config) error {
	logger.Info("Initializing edge rules module")

	m.engine = NewEdgeRulesEngine()
	m.handler = NewEdgeRulesHandler(m.engine)

	m.engine.StartRuleEngine(ctx, 5)

	logger.Info("Edge rules module initialized")
	return nil
}

func (m *Module) RegisterRoutes(router *gin.RouterGroup) {
	m.handler.RegisterRoutes(router)
}
