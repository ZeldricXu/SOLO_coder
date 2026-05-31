package environments

import (
	"depguard/models"
	"github.com/gin-gonic/gin"
	"net/http"
	"time"
)

type Handler struct {
	service *Service
}

func NewHandler() *Handler {
	return &Handler{service: NewService()}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	requests := r.Group("/environment-requests")
	{
		requests.GET("", h.ListRequests)
		requests.POST("", h.CreateRequest)
		requests.POST("/:id/approve", h.ApproveRequest)
	}

	envs := r.Group("/environments")
	{
		envs.GET("", h.ListEnvironments)
		envs.GET("/:id", h.GetEnvironment)
		envs.POST("/:id/stop", h.StopEnvironment)
		envs.DELETE("/:id", h.DeleteEnvironment)
	}

	stats := r.Group("/stats")
	{
		stats.GET("/usage", h.GetUsageStats)
	}
}

func (h *Handler) CreateRequest(c *gin.Context) {
	var req CreateEnvRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	ownerID := c.GetHeader("X-User-ID")
	if ownerID == "" {
		ownerID = "anonymous"
	}

	created, err := h.service.CreateRequest(c.Request.Context(), &req, ownerID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}

func (h *Handler) ListRequests(c *gin.Context) {
	ownerID := c.Query("owner_id")
	status := c.Query("status")
	page := 0
	size := 20
	if v := c.DefaultQuery("page", "0"); v != "" {
		if p, err := parseIntSafe(v); err == nil {
			page = p
		}
	}
	if v := c.DefaultQuery("size", "20"); v != "" {
		if s, err := parseIntSafe(v); err == nil {
			size = s
		}
	}

	requests, total, err := h.service.ListRequests(c.Request.Context(), ownerID, status, page, size)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(map[string]interface{}{
		"items": requests,
		"total": total,
		"page":  page,
		"size":  size,
	}))
}

func (h *Handler) ApproveRequest(c *gin.Context) {
	id := c.Param("id")
	approverID := c.GetHeader("X-User-ID")
	if approverID == "" {
		approverID = "system"
	}

	env, err := h.service.ApproveRequest(c.Request.Context(), id, approverID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(env))
}

func (h *Handler) ListEnvironments(c *gin.Context) {
	ownerID := c.Query("owner_id")
	projectID := c.Query("project_id")
	status := c.Query("status")
	page := 0
	size := 20
	if v := c.DefaultQuery("page", "0"); v != "" {
		if p, err := parseIntSafe(v); err == nil {
			page = p
		}
	}
	if v := c.DefaultQuery("size", "20"); v != "" {
		if s, err := parseIntSafe(v); err == nil {
			size = s
		}
	}

	envs, total, err := h.service.ListEnvironments(c.Request.Context(), ownerID, projectID, status, page, size)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(map[string]interface{}{
		"items": envs,
		"total": total,
		"page":  page,
		"size":  size,
	}))
}

func (h *Handler) GetEnvironment(c *gin.Context) {
	id := c.Param("id")
	env, err := h.service.GetEnvironment(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, models.ErrorResponse(404, "Environment not found"))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(env))
}

func (h *Handler) StopEnvironment(c *gin.Context) {
	id := c.Param("id")
	if err := h.service.StopEnvironment(c.Request.Context(), id); err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(map[string]string{"id": id, "action": "stopped"}))
}

func (h *Handler) DeleteEnvironment(c *gin.Context) {
	id := c.Param("id")
	if err := h.service.DeleteEnvironment(c.Request.Context(), id); err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(map[string]string{"id": id}))
}

func (h *Handler) GetUsageStats(c *gin.Context) {
	end := time.Now()
	start := end.AddDate(0, 0, -7)

	if v := c.Query("start"); v != "" {
		if t, err := time.Parse(time.RFC3339, v); err == nil {
			start = t
		}
	}
	if v := c.Query("end"); v != "" {
		if t, err := time.Parse(time.RFC3339, v); err == nil {
			end = t
		}
	}

	stats, err := h.service.GetUsageStats(c.Request.Context(), start, end)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(stats))
}

func parseIntSafe(s string) (int, error) {
	n := 0
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return 0, nil
		}
		n = n*10 + int(s[i]-'0')
	}
	return n, nil
}
