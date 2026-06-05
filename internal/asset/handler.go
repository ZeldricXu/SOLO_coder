package asset

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
)

type Handler struct {
	service *AssetService
}

func NewHandler(service *AssetService) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	projects := r.Group("/projects")
	{
		projects.POST("", h.CreateProject)
		projects.GET("", h.ListProjects)
		projects.GET("/:id", h.GetProject)
		projects.DELETE("/:id", h.DeleteProject)

		projects.POST("/:id/permissions", h.GrantPermission)
		projects.DELETE("/:id/permissions", h.RevokePermission)

		datasets := projects.Group("/:projectId/datasets")
		{
			datasets.POST("", h.CreateDataset)
			datasets.GET("", h.ListDatasets)
			datasets.GET("/:id", h.GetDataset)
			datasets.DELETE("/:id", h.DeleteDataset)
			datasets.GET("/:id/stats", h.GetDatasetStats)

			versions := datasets.Group("/:datasetId/versions")
			{
				versions.POST("", h.CreateVersion)
				versions.GET("", h.ListVersions)
				versions.GET("/:id", h.GetVersion)
			}
		}
	}

	users := r.Group("/users")
	{
		users.POST("", h.CreateUser)
		users.GET("/:id", h.GetUser)
		users.POST("/auth", h.Authenticate)
	}
}

func (h *Handler) CreateProject(c *gin.Context) {
	var req struct {
		Name        string `json:"name" binding:"required"`
		Description string `json:"description"`
		OwnerID     string `json:"owner_id" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	project, err := h.service.CreateProject(req.Name, req.Description, req.OwnerID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, project)
}

func (h *Handler) ListProjects(c *gin.Context) {
	userID := c.Query("user_id")
	if userID == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "user_id is required"})
		return
	}

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	projects, total, err := h.service.ListProjects(userID, limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"projects": projects,
		"total":    total,
		"limit":    limit,
		"offset":   offset,
	})
}

func (h *Handler) GetProject(c *gin.Context) {
	id := c.Param("id")

	project, err := h.service.GetProject(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, project)
}

func (h *Handler) DeleteProject(c *gin.Context) {
	id := c.Param("id")

	if err := h.service.DeleteProject(id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "project deleted"})
}

func (h *Handler) GrantPermission(c *gin.Context) {
	projectID := c.Param("id")

	var req struct {
		UserID     string `json:"user_id" binding:"required"`
		Permission string `json:"permission" binding:"required,oneof=read write owner"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.service.GrantPermission(projectID, req.UserID, req.Permission); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "permission granted"})
}

func (h *Handler) RevokePermission(c *gin.Context) {
	projectID := c.Param("id")

	var req struct {
		UserID     string `json:"user_id" binding:"required"`
		Permission string `json:"permission" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := h.service.RevokePermission(projectID, req.UserID, req.Permission); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "permission revoked"})
}

func (h *Handler) CreateDataset(c *gin.Context) {
	projectID := c.Param("projectId")

	var req struct {
		Name        string `json:"name" binding:"required"`
		Description string `json:"description"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	dataset, err := h.service.CreateDataset(projectID, req.Name, req.Description)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, dataset)
}

func (h *Handler) ListDatasets(c *gin.Context) {
	projectID := c.Param("projectId")

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	datasets, total, err := h.service.ListDatasets(projectID, limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"datasets": datasets,
		"total":    total,
		"limit":    limit,
		"offset":   offset,
	})
}

func (h *Handler) GetDataset(c *gin.Context) {
	id := c.Param("id")

	dataset, err := h.service.GetDataset(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, dataset)
}

func (h *Handler) DeleteDataset(c *gin.Context) {
	id := c.Param("id")

	if err := h.service.DeleteDataset(id); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "dataset deleted"})
}

func (h *Handler) GetDatasetStats(c *gin.Context) {
	id := c.Param("id")

	stats, err := h.service.GetDatasetStats(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, stats)
}

func (h *Handler) CreateVersion(c *gin.Context) {
	datasetID := c.Param("datasetId")

	file, header, err := c.Request.FormFile("file")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "file is required"})
		return
	}
	defer file.Close()

	version, err := h.service.CreateVersion(datasetID, file, header.Filename, header.Size)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, version)
}

func (h *Handler) ListVersions(c *gin.Context) {
	datasetID := c.Param("datasetId")

	versions, err := h.service.GetVersions(datasetID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"versions": versions,
		"count":    len(versions),
	})
}

func (h *Handler) GetVersion(c *gin.Context) {
	id := c.Param("id")

	version, err := h.service.GetVersion(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, version)
}

func (h *Handler) CreateUser(c *gin.Context) {
	var req struct {
		Username string `json:"username" binding:"required"`
		Email    string `json:"email" binding:"required,email"`
		Password string `json:"password" binding:"required,min=6"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	user, err := h.service.CreateUser(req.Username, req.Email, req.Password)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, user)
}

func (h *Handler) GetUser(c *gin.Context) {
	id := c.Param("id")

	user, err := h.service.GetUser(id)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, user)
}

func (h *Handler) Authenticate(c *gin.Context) {
	var req struct {
		Username string `json:"username" binding:"required"`
		Password string `json:"password" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	user, err := h.service.Authenticate(req.Username, req.Password)
	if err != nil {
		c.JSON(http.StatusUnauthorized, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"user":  user,
		"token": "jwt_token_placeholder",
	})
}
