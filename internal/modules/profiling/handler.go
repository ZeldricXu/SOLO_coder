package profiling

import (
	"net/http"
	"strconv"

	"loglevelplatform/internal/common/logger"

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

func (h *Handler) StartCPUProfile(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	durationSec, _ := strconv.Atoi(c.DefaultQuery("duration", "30"))

	session, err := h.service.StartCPUProfiling(ctx, durationSec)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": session,
	})
}

func (h *Handler) StopCPUProfile(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	_, err := h.service.StopCPUProfiling(ctx)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "CPU profiling stopped",
	})
}

func (h *Handler) TakeHeapProfile(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	session, err := h.service.TakeHeapProfile(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": session,
	})
}

func (h *Handler) TakeGoroutineProfile(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	session, err := h.service.TakeGoroutineProfile(ctx)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": session,
	})
}

func (h *Handler) GetProfile(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	sessionID := c.Param("id")
	session, err := h.service.GetProfile(ctx, sessionID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{
			"code":  404,
			"error": err.Error(),
		})
		return
	}

	download := c.Query("download")
	if download == "true" {
		c.Header("Content-Disposition", "attachment; filename=profile_"+sessionID+".pprof")
		c.Header("Content-Type", "application/octet-stream")
		c.Data(http.StatusOK, "application/octet-stream", session.Data)
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": session,
	})
}

func (h *Handler) ListProfiles(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	profileType := ProfileType(c.Query("type"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "20"))

	profiles := h.service.ListProfiles(ctx, profileType, limit)

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": profiles,
	})
}

func (h *Handler) GetStats(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	stats := h.service.GetCurrentStats(ctx)

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": stats,
	})
}

func (h *Handler) GetFlameGraph(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	sessionID := c.Param("id")
	graph, err := h.service.GenerateFlameGraph(ctx, sessionID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{
			"code":  404,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": graph,
	})
}

func (h *Handler) CompareProfiles(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	sessionA := c.Query("a")
	sessionB := c.Query("b")

	if sessionA == "" || sessionB == "" {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": "both session a and b are required",
		})
		return
	}

	result, err := h.service.CompareProfiles(ctx, sessionA, sessionB)
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

func (h *Handler) DeleteProfile(c *gin.Context) {
	ctx := c.Request.Context()
	ctx = logger.WithContext(ctx, logger.GetLogger().With(zap.String("trace_id", uuid.New().String())))

	sessionID := c.Param("id")
	if err := h.service.DeleteProfile(ctx, sessionID); err != nil {
		c.JSON(http.StatusNotFound, gin.H{
			"code":  404,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "profile deleted",
	})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	profiling := r.Group("/profiling")
	{
		profiling.POST("/cpu/start", h.StartCPUProfile)
		profiling.POST("/cpu/stop", h.StopCPUProfile)
		profiling.POST("/heap", h.TakeHeapProfile)
		profiling.POST("/goroutine", h.TakeGoroutineProfile)
		profiling.GET("/stats", h.GetStats)
		profiling.GET("", h.ListProfiles)
		profiling.GET("/:id", h.GetProfile)
		profiling.GET("/:id/flamegraph", h.GetFlameGraph)
		profiling.GET("/compare", h.CompareProfiles)
		profiling.DELETE("/:id", h.DeleteProfile)
	}
}
