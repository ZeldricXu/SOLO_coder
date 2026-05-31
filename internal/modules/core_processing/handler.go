package core_processing

import (
	"net/http"
	"strconv"

	"loglevelplatform/internal/common/logger"
	"loglevelplatform/internal/common/models"
	"loglevelplatform/pkg/utils"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"
)

type Handler struct {
	service *Service
}

func NewHandler(service *Service) *Handler {
	return &Handler{service: service}
}

func (h *Handler) Process(c *gin.Context) {
	ctx := c.Request.Context()
	traceID := utils.NewTraceID()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", traceID)))

	var req ProcessRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  422,
			"error": err.Error(),
		})
		return
	}
	req.TraceID = traceID

	result, err := h.service.ExecuteHandler(ctx, &req)
	if err != nil {
		if appErr, ok := err.(interface{ Code string }); ok {
			_ = appErr
			c.JSON(http.StatusUnprocessableEntity, gin.H{
				"code":  422,
				"error": err.Error(),
			})
		} else {
			c.JSON(http.StatusInternalServerError, gin.H{
				"code":  500,
				"error": "internal processing error",
			})
		}
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": result,
	})
}

func (h *Handler) CreateEntity(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	var entity models.CoreEntity
	if err := c.ShouldBindJSON(&entity); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	result, err := h.service.CreateEntity(ctx, &entity)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"code": 201,
		"data": result,
	})
}

func (h *Handler) GetEntity(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	id := c.Param("id")
	entity, err := h.service.GetEntity(ctx, id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{
			"code":  404,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": entity,
	})
}

func (h *Handler) UpdateEntity(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	id := c.Param("id")
	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	result, err := h.service.UpdateEntity(ctx, id, updates)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": result,
	})
}

func (h *Handler) ListEntities(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	entityType := c.Query("type")
	status := c.Query("status")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	entities, total, err := h.service.ListEntities(ctx, entityType, status, limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"items": entities,
			"total": total,
			"limit": limit,
			"offset": offset,
		},
	})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	processing := r.Group("/core")
	{
		processing.POST("/process", h.Process)
		processing.POST("/entities", h.CreateEntity)
		processing.GET("/entities", h.ListEntities)
		processing.GET("/entities/:id", h.GetEntity)
		processing.PUT("/entities/:id", h.UpdateEntity)
	}
}
