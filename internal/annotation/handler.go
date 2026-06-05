package annotation

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
)

type Handler struct {
	service *AnnotationService
}

func NewHandler(service *AnnotationService) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	ann := r.Group("/annotations")
	{
		ann.POST("", h.CreateAnnotation)
		ann.GET("/:id", h.GetAnnotation)
		ann.PUT("/:id", h.UpdateAnnotation)
		ann.DELETE("/:id", h.DeleteAnnotation)
		ann.GET("", h.ListAnnotations)
	}

	meas := r.Group("/measurements")
	{
		meas.POST("", h.CreateMeasurement)
		meas.GET("/:id", h.GetMeasurement)
		meas.DELETE("/:id", h.DeleteMeasurement)
		meas.GET("", h.ListMeasurements)
	}
}

func (h *Handler) CreateAnnotation(c *gin.Context) {
	var ann Annotation
	if err := c.ShouldBindJSON(&ann); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if ann.DatasetID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "dataset_id is required"})
		return
	}
	if ann.CreatorID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "creator_id is required"})
		return
	}

	created, err := h.service.CreateAnnotation(&ann)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, created)
}

func (h *Handler) GetAnnotation(c *gin.Context) {
	id := c.Param("id")

	ann, err := h.service.GetAnnotation(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "annotation not found"})
		return
	}

	c.JSON(http.StatusOK, ann)
}

func (h *Handler) UpdateAnnotation(c *gin.Context) {
	id := c.Param("id")

	var ann Annotation
	if err := c.ShouldBindJSON(&ann); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	updated, err := h.service.UpdateAnnotation(id, &ann)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, updated)
}

func (h *Handler) DeleteAnnotation(c *gin.Context) {
	id := c.Param("id")

	if err := h.service.DeleteAnnotation(id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "deleted"})
}

func (h *Handler) ListAnnotations(c *gin.Context) {
	datasetID := c.Query("dataset_id")
	if datasetID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "dataset_id is required"})
		return
	}

	annotationType := AnnotationType(c.Query("type"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "100"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	annotations, total, err := h.service.ListAnnotations(datasetID, annotationType, limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"data":  annotations,
		"total": total,
		"limit": limit,
		"offset": offset,
	})
}

func (h *Handler) CreateMeasurement(c *gin.Context) {
	var m Measurement
	if err := c.ShouldBindJSON(&m); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if m.DatasetID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "dataset_id is required"})
		return
	}
	if m.CreatorID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "creator_id is required"})
		return
	}
	if len(m.Points) < 2 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "at least 2 points are required"})
		return
	}

	created, err := h.service.CreateMeasurement(&m)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, created)
}

func (h *Handler) GetMeasurement(c *gin.Context) {
	id := c.Param("id")

	m, err := h.service.GetMeasurement(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "measurement not found"})
		return
	}

	c.JSON(http.StatusOK, m)
}

func (h *Handler) DeleteMeasurement(c *gin.Context) {
	id := c.Param("id")

	if err := h.service.DeleteMeasurement(id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "deleted"})
}

func (h *Handler) ListMeasurements(c *gin.Context) {
	datasetID := c.Query("dataset_id")
	if datasetID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "dataset_id is required"})
		return
	}

	measurementType := MeasurementType(c.Query("type"))
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "100"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	measurements, total, err := h.service.ListMeasurements(datasetID, measurementType, limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"data":  measurements,
		"total": total,
		"limit": limit,
		"offset": offset,
	})
}
