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
		ann.GET("/by-tag", h.QueryByTag)
		ann.GET("/by-label-group", h.QueryByLabelGroup)
		ann.GET("/tag-keys", h.GetTagKeys)
		ann.GET("/label-group-names", h.GetLabelGroupNames)
		ann.GET("/:id", h.GetAnnotation)
		ann.PUT("/:id", h.UpdateAnnotation)
		ann.DELETE("/:id", h.DeleteAnnotation)
		ann.GET("/:id/tags", h.GetAnnotationTags)
		ann.PATCH("/:id/tags", h.UpdateAnnotationTags)
		ann.POST("/:id/label-groups", h.AddLabelGroup)
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

func (h *Handler) GetAnnotationTags(c *gin.Context) {
	id := c.Param("id")

	ann, err := h.service.GetAnnotation(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "annotation not found"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"tags": ann.Tags})
}

func (h *Handler) UpdateAnnotationTags(c *gin.Context) {
	id := c.Param("id")

	var tags map[string]string
	if err := c.ShouldBindJSON(&tags); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	ann, err := h.service.UpdateAnnotationTags(id, tags)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, ann)
}

func (h *Handler) QueryByTag(c *gin.Context) {
	datasetID := c.Query("dataset_id")
	if datasetID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "dataset_id is required"})
		return
	}

	var req struct {
		Conditions []TagQueryCondition `json:"conditions"`
		Operator   string              `json:"operator"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "100"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	annotations, total, err := h.service.QueryAnnotationsByTags(datasetID, req.Conditions, req.Operator, limit, offset)
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

func (h *Handler) QueryByLabelGroup(c *gin.Context) {
	datasetID := c.Query("dataset_id")
	if datasetID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "dataset_id is required"})
		return
	}

	groupName := c.Query("group_name")
	if groupName == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "group_name is required"})
		return
	}

	var labels map[string]string
	if err := c.ShouldBindJSON(&labels); err != nil {
		labels = map[string]string{}
	}

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "100"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	annotations, total, err := h.service.ListAnnotationsByLabelGroup(datasetID, groupName, labels, limit, offset)
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

func (h *Handler) GetTagKeys(c *gin.Context) {
	datasetID := c.Query("dataset_id")
	if datasetID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "dataset_id is required"})
		return
	}

	keys, err := h.service.GetAnnotationTagKeys(datasetID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"keys": keys})
}

func (h *Handler) AddLabelGroup(c *gin.Context) {
	id := c.Param("id")

	var group LabelGroup
	if err := c.ShouldBindJSON(&group); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	ann, err := h.service.AddLabelGroup(id, &group)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, ann)
}

func (h *Handler) GetLabelGroupNames(c *gin.Context) {
	datasetID := c.Query("dataset_id")
	if datasetID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "dataset_id is required"})
		return
	}

	names, err := h.service.GetAllLabelGroupNames(datasetID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"names": names})
}
