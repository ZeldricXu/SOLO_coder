package docindex

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
	g := r.Group("/docs")
	{
		g.POST("", h.CreateDocument)
		g.GET("/search", h.Search)
		g.GET("", h.ListDocuments)
		g.GET("/:id", h.GetDocument)
		g.PUT("/:id", h.UpdateDocument)
		g.DELETE("/:id", h.DeleteDocument)
	}
	src := r.Group("/sources")
	{
		src.GET("", h.ListSources)
		src.POST("", h.CreateSource)
		src.POST("/:id/sync", h.SyncSource)
	}
}

func (h *Handler) CreateDocument(c *gin.Context) {
	var doc Document
	if err := c.ShouldBindJSON(&doc); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	created, err := h.service.CreateDocument(c.Request.Context(), &doc)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}

func (h *Handler) GetDocument(c *gin.Context) {
	id := c.Param("id")
	doc, err := h.service.GetDocument(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusNotFound, models.ErrorResponse(404, "Document not found"))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(doc))
}

func (h *Handler) UpdateDocument(c *gin.Context) {
	id := c.Param("id")
	var doc Document
	if err := c.ShouldBindJSON(&doc); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}
	doc.ID = id

	updated, err := h.service.UpdateDocument(c.Request.Context(), &doc)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(updated))
}

func (h *Handler) DeleteDocument(c *gin.Context) {
	id := c.Param("id")
	if err := h.service.DeleteDocument(c.Request.Context(), id); err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(map[string]string{"id": id}))
}

func (h *Handler) Search(c *gin.Context) {
	var q SearchQuery
	if err := c.ShouldBindQuery(&q); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	results, total, err := h.service.Search(c.Request.Context(), &q)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(map[string]interface{}{
		"results": results,
		"total":   total,
		"page":    q.Page,
		"size":    q.Size,
	}))
}

func (h *Handler) ListDocuments(c *gin.Context) {
	page := 0
	size := 20
	if v := c.DefaultQuery("page", "0"); v != "" {
		if p, err := parseInt(v); err == nil {
			page = p
		}
	}
	if v := c.DefaultQuery("size", "20"); v != "" {
		if s, err := parseInt(v); err == nil {
			size = s
		}
	}

	docs, total, err := h.service.ListDocuments(c.Request.Context(), page, size)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusOK, models.SuccessResponse(map[string]interface{}{
		"items": docs,
		"total": total,
		"page":  page,
		"size":  size,
	}))
}

func (h *Handler) ListSources(c *gin.Context) {
	sources, err := h.service.ListSources(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(sources))
}

func (h *Handler) CreateSource(c *gin.Context) {
	var src DocumentSource
	if err := c.ShouldBindJSON(&src); err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}

	created, err := h.service.CreateSource(c.Request.Context(), &src)
	if err != nil {
		c.JSON(http.StatusInternalServerError, models.ErrorResponse(500, err.Error()))
		return
	}

	c.JSON(http.StatusCreated, models.CreatedResponse(created))
}

func (h *Handler) SyncSource(c *gin.Context) {
	id := c.Param("id")
	job, err := h.service.SyncSource(c.Request.Context(), id)
	if err != nil {
		c.JSON(http.StatusBadRequest, models.ErrorResponse(400, err.Error()))
		return
	}
	c.JSON(http.StatusOK, models.SuccessResponse(job))
}

func parseInt(s string) (int, error) {
	var n int
	_, err := s[0], s[len(s)-1]
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return 0, err
		}
		n = n*10 + int(s[i]-'0')
	}
	return n, nil
}
