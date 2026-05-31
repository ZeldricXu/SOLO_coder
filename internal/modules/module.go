package modules

import (
	"context"

	"github.com/gin-gonic/gin"

	"edgescheduler/internal/common/config"
)

type Module interface {
	Init(ctx context.Context, cfg *config.Config) error
	RegisterRoutes(router *gin.RouterGroup)
}
