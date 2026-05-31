package scaffold

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
	templates := r.Group("/templates")
	{
		templates.GET("", h.ListTemplates)
		templates.POST("", h.CreateTemplate)
		templates.GET("/:id", h.GetTemplate)
		templates.POST("/:id/questions", h.GetNextQuestions)
		templates.POST("/:id/generate", h.Generate)
	}

	generations := r.Group("/generations")
	{
		generations.GET("", h.ListGenerations)
		generations.GET("/:id", h.GetGeneration)
		generations.GET("/:id/download", h.Download)
	}
}

func (h *Handler) ListTemplates(c *gin.Context) {
	language := c.Query("language")
	templates, err := h.service.ListTemplates(c.Request.Context(), language)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(templates))
}

func (h *Handler) GetTemplate(c *gin.Context) {
	id := c.Param("id")
	tpl, err := h.service.GetTemplate(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, models.ErrorResponse(404, "Template not found"))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(tpl))
}

func (h *Handler) CreateTemplate(c *gin.Context) {
	var tpl Template
	if err := c.ShouldBindJSON(&tpl); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	created, err := h.service.CreateTemplate(c.Request.Context(), &tpl)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}

func (h *Handler) GetNextQuestions(c *gin.Context) {
	templateID := c.Param("id")

	var req struct {
		Answers map[string]interface{} `json:"answers"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		req.Answers = map[string]interface{}{}
	}

	flow, err := h.service.GetNextQuestions(c.Request.Context(), templateID, req.Answers)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(flow))
}

func (h *Handler) Generate(c *gin.Context) {
	templateID := c.Param("id")

	var req struct {
		Parameters map[string]interface{} `json:"parameters"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	gen, err := h.service.Generate(c.Request.Context(), templateID, req.Parameters)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusAccepted, models.BaseResponse{Code: 202, Data: gen})
}

func (h *Handler) ListGenerations(c *gin.Context) {
	templateID := c.Query("template_id")
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

	reqs, total, err := h.service.ListGenerations(c.Request.Context(), templateID, page, size)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(map[string]interface{}{
		"items": reqs,
		"total": total,
		"page":  page,
		"size":  size,
	}))
}

func (h *Handler) GetGeneration(c *gin.Context) {
	id := c.Param("id")
	req, err := h.service.GetGeneration(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, models.ErrorResponse(404, "Generation not found"))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(req))
}

func (h *Handler) Download(c *gin.Context) {
	id := c.Param("id")
	data, err := h.service.DownloadZip(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.Header("Content-Type", "application/zip")
	c.Header("Content-Disposition", "attachment; filename=project.zip")
	c.Data(http.StatusOK, "application/zip", data)
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
