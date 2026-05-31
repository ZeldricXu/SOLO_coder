package featureflags

import (
	"depguard/models"
	"github.com/gin-gonic/gin"
	"net/http"
)

type Handler struct {
	service *Service
}

func NewHandler() *Handler {
	return &Handler{service: NewService()}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	flags := r.Group("/feature-flags")
	{
		flags.GET("", h.ListFlags)
		flags.POST("", h.CreateFlag)
		flags.GET("/:key", h.GetFlag)
		flags.PUT("/:key", h.UpdateFlag)
		flags.DELETE("/:key", h.DeleteFlag)
		flags.POST("/evaluate", h.Evaluate)
		flags.POST("/batch-evaluate", h.BatchEvaluate)
		flags.GET("/:key/stats", h.GetStats)
	}

	segments := r.Group("/segments")
	{
		segments.GET("", h.ListSegments)
		segments.POST("", h.CreateSegment)
	}
}

func (h *Handler) ListFlags(c *gin.Context) {
	flags, err := h.service.ListFlags(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(flags))
}

func (h *Handler) CreateFlag(c *gin.Context) {
	var flag FeatureFlag
	if err := c.ShouldBindJSON(&flag); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	created, err := h.service.CreateFlag(c.Request.Context(), &flag)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}

func (h *Handler) GetFlag(c *gin.Context) {
	key := c.Param("key")
	flag, err := h.service.GetFlag(c.Request.Context(), key)
	if err != nil {
		c.JSON(http.StatusNotFound, models.ErrorResponse(404, "Flag not found"))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(flag))
}

func (h *Handler) UpdateFlag(c *gin.Context) {
	key := c.Param("key")
	var flag FeatureFlag
	if err := c.ShouldBindJSON(&flag); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	updated, err := h.service.UpdateFlag(c.Request.Context(), key, &flag)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(updated))
}

func (h *Handler) DeleteFlag(c *gin.Context) {
	key := c.Param("key")
	if err := h.service.DeleteFlag(c.Request.Context(), key); err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(map[string]string{"key": key}))
}

func (h *Handler) Evaluate(c *gin.Context) {
	var req struct {
		FlagKey string            `json:"flag_key" binding:"required"`
		Context EvaluationContext `json:"context"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	result, err := h.service.Evaluate(c.Request.Context(), req.FlagKey, &req.Context)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(result))
}

func (h *Handler) BatchEvaluate(c *gin.Context) {
	var req struct {
		FlagKeys []string          `json:"flag_keys" binding:"required"`
		Context  EvaluationContext `json:"context"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	results, err := h.service.BatchEvaluate(c.Request.Context(), req.FlagKeys, &req.Context)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(results))
}

func (h *Handler) GetStats(c *gin.Context) {
	key := c.Param("key")
	stats, err := h.service.GetExperimentStats(c.Request.Context(), key)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(stats))
}

func (h *Handler) ListSegments(c *gin.Context) {
	segments, err := h.service.ListSegments(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(segments))
}

func (h *Handler) CreateSegment(c *gin.Context) {
	var segment UserSegment
	if err := c.ShouldBindJSON(&segment); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	created, err := h.service.CreateSegment(c.Request.Context(), &segment)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}
