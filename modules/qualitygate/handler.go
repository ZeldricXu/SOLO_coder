package qualitygate

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
	rules := r.Group("/quality-rules")
	{
		rules.GET("", h.ListRules)
		rules.POST("", h.CreateRule)
		rules.PUT("/:id", h.UpdateRule)
		rules.DELETE("/:id", h.DeleteRule)
	}

	gates := r.Group("/quality-gates")
	{
		gates.GET("", h.ListGates)
		gates.POST("", h.CreateGate)
	}

	analysis := r.Group("/analysis")
	{
		analysis.POST("", h.Analyze)
		analysis.GET("/reports", h.ListReports)
		analysis.GET("/reports/:id", h.GetReport)
	}
}

func (h *Handler) ListRules(c *gin.Context) {
	language := c.Query("language")
	rules, err := h.service.ListRules(c.Request.Context(), language)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(rules))
}

func (h *Handler) CreateRule(c *gin.Context) {
	var rule AnalysisRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	created, err := h.service.CreateRule(c.Request.Context(), &rule)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}

func (h *Handler) UpdateRule(c *gin.Context) {
	id := c.Param("id")
	var rule AnalysisRule
	if err := c.ShouldBindJSON(&rule); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}
	rule.ID = id

	updated, err := h.service.UpdateRule(c.Request.Context(), &rule)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(updated))
}

func (h *Handler) DeleteRule(c *gin.Context) {
	id := c.Param("id")
	if err := h.service.DeleteRule(c.Request.Context(), id); err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(map[string]string{"id": id}))
}

func (h *Handler) ListGates(c *gin.Context) {
	gates, err := h.service.ListGates(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(gates))
}

func (h *Handler) CreateGate(c *gin.Context) {
	var gate QualityGate
	if err := c.ShouldBindJSON(&gate); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	created, err := h.service.CreateGate(c.Request.Context(), &gate)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}

func (h *Handler) Analyze(c *gin.Context) {
	var req AnalyzeRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	report, err := h.service.Analyze(c.Request.Context(), &req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusAccepted, models.BaseResponse{Code: 202, Data: report})
}

func (h *Handler) ListReports(c *gin.Context) {
	projectID := c.Query("project_id")
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

	reports, total, err := h.service.ListReports(c.Request.Context(), projectID, page, size)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(map[string]interface{}{
		"items": reports,
		"total": total,
		"page":  page,
		"size":  size,
	}))
}

func (h *Handler) GetReport(c *gin.Context) {
	id := c.Param("id")
	report, err := h.service.GetReport(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, models.ErrorResponse(404, "Report not found"))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(report))
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
