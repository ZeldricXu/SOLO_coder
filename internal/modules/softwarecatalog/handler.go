package softwarecatalog

import (
	"depguard/internal/common/model"
	"depguard/internal/common/utils"
	"depguard/internal/database"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
)

type Service struct {
	model.BaseModel
	ServiceID    string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"service_id"`
	Name         string                 `gorm:"type:varchar(128);not null" json:"name"`
	Description  string                 `gorm:"type:text" json:"description"`
	ServiceType  string                 `gorm:"type:varchar(32);index" json:"service_type"`
	Language     string                 `gorm:"type:varchar(32)" json:"language"`
	Framework    string                 `gorm:"type:varchar(64)" json:"framework"`
	Version      string                 `gorm:"type:varchar(32)" json:"version"`
	Status       string                 `gorm:"type:varchar(32);index" json:"status"`
	Repository   string                 `gorm:"type:varchar(512)" json:"repository"`
	Owner        string                 `gorm:"type:varchar(64);index" json:"owner"`
	Team         string                 `gorm:"type:varchar(64)" json:"team"`
	Tags         []string               `gorm:"type:varchar(64)[]" json:"tags"`
	Metadata     map[string]interface{} `gorm:"type:jsonb" json:"metadata"`
	Endpoints    []string               `gorm:"type:varchar(512)[]" json:"endpoints"`
	Dependencies []string               `gorm:"type:varchar(64)[]" json:"dependencies"`
}

type Library struct {
	model.BaseModel
	LibraryID    string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"library_id"`
	Name         string                 `gorm:"type:varchar(128);not null" json:"name"`
	Description  string                 `gorm:"type:text" json:"description"`
	Language     string                 `gorm:"type:varchar(32);index" json:"language"`
	Version      string                 `gorm:"type:varchar(32)" json:"version"`
	License      string                 `gorm:"type:varchar(64)" json:"license"`
	Repository   string                 `gorm:"type:varchar(512)" json:"repository"`
	RegistryURL  string                 `gorm:"type:varchar(512)" json:"registry_url"`
	Owner        string                 `gorm:"type:varchar(64)" json:"owner"`
	Tags         []string               `gorm:"type:varchar(64)[]" json:"tags"`
	Metadata     map[string]interface{} `gorm:"type:jsonb" json:"metadata"`
	Dependencies []string               `gorm:"type:varchar(64)[]" json:"dependencies"`
	IsInternal   bool                   `gorm:"default:false" json:"is_internal"`
}

type Dependency struct {
	model.BaseModel
	SourceType   string `gorm:"type:varchar(16);index" json:"source_type"`
	SourceID     string `gorm:"type:varchar(64);index" json:"source_id"`
	TargetType   string `gorm:"type:varchar(16);index" json:"target_type"`
	TargetID     string `gorm:"type:varchar(64);index" json:"target_id"`
	VersionRange string `gorm:"type:varchar(64)" json:"version_range"`
	Scope        string `gorm:"type:varchar(32)" json:"scope"`
}

type Handler struct{}

func NewHandler() *Handler {
	return &Handler{}
}

type CreateServiceRequest struct {
	Name         string                 `json:"name" binding:"required,max=128"`
	Description  string                 `json:"description"`
	ServiceType  string                 `json:"service_type" binding:"required"`
	Language     string                 `json:"language"`
	Framework    string                 `json:"framework"`
	Version      string                 `json:"version"`
	Repository   string                 `json:"repository"`
	Owner        string                 `json:"owner"`
	Team         string                 `json:"team"`
	Tags         []string               `json:"tags"`
	Metadata     map[string]interface{} `json:"metadata"`
	Endpoints    []string               `json:"endpoints"`
	Dependencies []string               `json:"dependencies"`
}

type CreateLibraryRequest struct {
	Name         string                 `json:"name" binding:"required,max=128"`
	Description  string                 `json:"description"`
	Language     string                 `json:"language" binding:"required"`
	Version      string                 `json:"version"`
	License      string                 `json:"license"`
	Repository   string                 `json:"repository"`
	RegistryURL  string                 `json:"registry_url"`
	Owner        string                 `json:"owner"`
	Tags         []string               `json:"tags"`
	Metadata     map[string]interface{} `json:"metadata"`
	Dependencies []string               `json:"dependencies"`
	IsInternal   bool                   `json:"is_internal"`
}

func (h *Handler) CreateService(c *gin.Context) {
	var req CreateServiceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	service := &Service{
		ServiceID:    utils.GenerateID("svc"),
		Name:         req.Name,
		Description:  req.Description,
		ServiceType:  req.ServiceType,
		Language:     req.Language,
		Framework:    req.Framework,
		Version:      req.Version,
		Status:       "active",
		Repository:   req.Repository,
		Owner:        req.Owner,
		Team:         req.Team,
		Tags:         req.Tags,
		Metadata:     req.Metadata,
		Endpoints:    req.Endpoints,
		Dependencies: req.Dependencies,
	}

	if err := database.DB.Create(service).Error; err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "message": "created", "data": service})
}

func (h *Handler) GetService(c *gin.Context) {
	id := c.Param("id")
	var service Service
	if err := database.DB.Where("service_id = ? OR id = ?", id, id).First(&service).Error; err != nil {
		c.JSON(404, gin.H{"code": 404, "message": "Not found"})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": service})
}

func (h *Handler) ListServices(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	serviceType := c.Query("type")
	language := c.Query("language")
	owner := c.Query("owner")
	keyword := c.Query("keyword")

	var services []Service
	var total int64
	query := database.DB.Model(&Service{})

	if serviceType != "" {
		query = query.Where("service_type = ?", serviceType)
	}
	if language != "" {
		query = query.Where("language = ?", language)
	}
	if owner != "" {
		query = query.Where("owner = ?", owner)
	}
	if keyword != "" {
		query = query.Where("name LIKE ? OR description LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&services)

	c.JSON(200, gin.H{"code": 200, "message": "success", "data": gin.H{
		"items": services, "total": total, "page": page, "size": pageSize,
	}})
}

func (h *Handler) UpdateService(c *gin.Context) {
	id := c.Param("id")
	var req CreateServiceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	updates := map[string]interface{}{
		"name":         req.Name,
		"description":  req.Description,
		"service_type": req.ServiceType,
		"language":     req.Language,
		"framework":    req.Framework,
		"version":      req.Version,
		"repository":   req.Repository,
		"owner":        req.Owner,
		"team":         req.Team,
		"tags":         req.Tags,
		"metadata":     req.Metadata,
		"endpoints":    req.Endpoints,
		"dependencies": req.Dependencies,
		"updated_at":   time.Now(),
	}

	if err := database.DB.Model(&Service{}).Where("id = ? OR service_id = ?", id, id).Updates(updates).Error; err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "success"})
}

func (h *Handler) DeleteService(c *gin.Context) {
	id := c.Param("id")
	database.DB.Where("id = ? OR service_id = ?", id, id).Delete(&Service{})
	c.JSON(200, gin.H{"code": 200, "message": "success"})
}

func (h *Handler) CreateLibrary(c *gin.Context) {
	var req CreateLibraryRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	library := &Library{
		LibraryID:    utils.GenerateID("lib"),
		Name:         req.Name,
		Description:  req.Description,
		Language:     req.Language,
		Version:      req.Version,
		License:      req.License,
		Repository:   req.Repository,
		RegistryURL:  req.RegistryURL,
		Owner:        req.Owner,
		Tags:         req.Tags,
		Metadata:     req.Metadata,
		Dependencies: req.Dependencies,
		IsInternal:   req.IsInternal,
	}

	if err := database.DB.Create(library).Error; err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "message": "created", "data": library})
}

func (h *Handler) GetLibrary(c *gin.Context) {
	id := c.Param("id")
	var library Library
	if err := database.DB.Where("library_id = ? OR id = ?", id, id).First(&library).Error; err != nil {
		c.JSON(404, gin.H{"code": 404, "message": "Not found"})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": library})
}

func (h *Handler) ListLibraries(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	language := c.Query("language")
	license := c.Query("license")
	isInternal := c.Query("is_internal")
	keyword := c.Query("keyword")

	var libraries []Library
	var total int64
	query := database.DB.Model(&Library{})

	if language != "" {
		query = query.Where("language = ?", language)
	}
	if license != "" {
		query = query.Where("license = ?", license)
	}
	if isInternal != "" {
		query = query.Where("is_internal = ?", isInternal == "true")
	}
	if keyword != "" {
		query = query.Where("name LIKE ? OR description LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&libraries)

	c.JSON(200, gin.H{"code": 200, "message": "success", "data": gin.H{
		"items": libraries, "total": total, "page": page, "size": pageSize,
	}})
}

func (h *Handler) UpdateLibrary(c *gin.Context) {
	id := c.Param("id")
	var req CreateLibraryRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	updates := map[string]interface{}{
		"name":         req.Name,
		"description":  req.Description,
		"language":     req.Language,
		"version":      req.Version,
		"license":      req.License,
		"repository":   req.Repository,
		"registry_url": req.RegistryURL,
		"owner":        req.Owner,
		"tags":         req.Tags,
		"metadata":     req.Metadata,
		"dependencies": req.Dependencies,
		"is_internal":  req.IsInternal,
		"updated_at":   time.Now(),
	}

	database.DB.Model(&Library{}).Where("id = ? OR library_id = ?", id, id).Updates(updates)
	c.JSON(200, gin.H{"code": 200, "message": "success"})
}

func (h *Handler) DeleteLibrary(c *gin.Context) {
	id := c.Param("id")
	database.DB.Where("id = ? OR library_id = ?", id, id).Delete(&Library{})
	c.JSON(200, gin.H{"code": 200, "message": "success"})
}

func (h *Handler) GetDependencyGraph(c *gin.Context) {
	entityID := c.Query("entity_id")
	entityType := c.Query("entity_type")

	var dependencies []Dependency
	database.DB.Where("(source_id = ? AND source_type = ?) OR (target_id = ? AND target_type = ?)",
		entityID, entityType, entityID, entityType).Find(&dependencies)

	nodes := make([]map[string]interface{}, 0)
	edges := make([]map[string]interface{}, 0)

	nodeMap := make(map[string]bool)
	nodeMap[entityID] = true
	nodes = append(nodes, gin.H{"id": entityID, "type": entityType})

	for _, dep := range dependencies {
		if !nodeMap[dep.TargetID] {
			nodeMap[dep.TargetID] = true
			nodes = append(nodes, gin.H{"id": dep.TargetID, "type": dep.TargetType})
		}
		edges = append(edges, gin.H{
			"source": dep.SourceID,
			"target": dep.TargetID,
			"version": dep.VersionRange,
			"scope":   dep.Scope,
		})
	}

	c.JSON(200, gin.H{"code": 200, "message": "success", "data": gin.H{
		"nodes": nodes, "edges": edges,
	}})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	services := r.Group("/services")
	{
		services.POST("", h.CreateService)
		services.GET("", h.ListServices)
		services.GET("/:id", h.GetService)
		services.PUT("/:id", h.UpdateService)
		services.DELETE("/:id", h.DeleteService)
	}

	libraries := r.Group("/libraries")
	{
		libraries.POST("", h.CreateLibrary)
		libraries.GET("", h.ListLibraries)
		libraries.GET("/:id", h.GetLibrary)
		libraries.PUT("/:id", h.UpdateLibrary)
		libraries.DELETE("/:id", h.DeleteLibrary)
	}

	r.GET("/dependencies/graph", h.GetDependencyGraph)
}
