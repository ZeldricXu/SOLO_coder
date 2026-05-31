package docindex

import (
	"depguard/internal/common/model"
	"depguard/internal/common/utils"
	"depguard/internal/database"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
)

type Document struct {
	model.BaseModel
	DocID       string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"doc_id"`
	Title       string                 `gorm:"type:varchar(256);not null" json:"title"`
	Content     string                 `gorm:"type:text" json:"content"`
	Summary     string                 `gorm:"type:text" json:"summary"`
	Source      string                 `gorm:"type:varchar(64);index" json:"source"`
	SourceURL   string                 `gorm:"type:varchar(512)" json:"source_url"`
	DocType     string                 `gorm:"type:varchar(32);index" json:"doc_type"`
	Language    string                 `gorm:"type:varchar(16);index" json:"language"`
	Tags        []string               `gorm:"type:varchar(64)[]" json:"tags"`
	Metadata    map[string]interface{} `gorm:"type:jsonb" json:"metadata"`
	Author      string                 `gorm:"type:varchar(64)" json:"author"`
	ViewCount   int                    `gorm:"default:0" json:"view_count"`
	Permissions []string               `gorm:"type:varchar(64)[]" json:"permissions"`
	IsPublic    bool                   `gorm:"default:true" json:"is_public"`
	IndexedAt   *time.Time             `json:"indexed_at"`
}

type DocumentSource struct {
	model.BaseModel
	SourceID    string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"source_id"`
	Name        string                 `gorm:"type:varchar(128);not null" json:"name"`
	SourceType  string                 `gorm:"type:varchar(32);index" json:"source_type"`
	Config      map[string]interface{} `gorm:"type:jsonb" json:"config"`
	IsEnabled   bool                   `gorm:"default:true" json:"is_enabled"`
	SyncStatus  string                 `gorm:"type:varchar(32)" json:"sync_status"`
	LastSyncAt  *time.Time             `json:"last_sync_at"`
	NextSyncAt  *time.Time             `json:"next_sync_at"`
}

type SearchIndex struct {
	model.BaseModel
	DocID    string   `gorm:"type:varchar(64);index" json:"doc_id"`
	Keywords []string `gorm:"type:varchar(64)[]" json:"keywords"`
	Vectors  string   `gorm:"type:text" json:"vectors"`
}

type Handler struct{}

func NewHandler() *Handler {
	return &Handler{}
}

type CreateDocRequest struct {
	Title       string                 `json:"title" binding:"required,max=256"`
	Content     string                 `json:"content" binding:"required"`
	Summary     string                 `json:"summary"`
	Source      string                 `json:"source" binding:"required"`
	SourceURL   string                 `json:"source_url"`
	DocType     string                 `json:"doc_type"`
	Language    string                 `json:"language"`
	Tags        []string               `json:"tags"`
	Metadata    map[string]interface{} `json:"metadata"`
	Author      string                 `json:"author"`
	Permissions []string               `json:"permissions"`
	IsPublic    bool                   `json:"is_public"`
}

type SearchRequest struct {
	Query    string   `json:"query" binding:"required"`
	DocType  string   `json:"doc_type"`
	Source   string   `json:"source"`
	Tags     []string `json:"tags"`
	Language string   `json:"language"`
}

func (h *Handler) CreateDocument(c *gin.Context) {
	var req CreateDocRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	now := time.Now()
	doc := &Document{
		DocID:       utils.GenerateID("doc"),
		Title:       req.Title,
		Content:     req.Content,
		Summary:     req.Summary,
		Source:      req.Source,
		SourceURL:   req.SourceURL,
		DocType:     req.DocType,
		Language:    req.Language,
		Tags:        req.Tags,
		Metadata:    req.Metadata,
		Author:      req.Author,
		Permissions: req.Permissions,
		IsPublic:    req.IsPublic,
		IndexedAt:   &now,
	}

	if err := database.DB.Create(doc).Error; err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "message": "created", "data": doc})
}

func (h *Handler) GetDocument(c *gin.Context) {
	id := c.Param("id")
	var doc Document
	if err := database.DB.Where("doc_id = ? OR id = ?", id, id).First(&doc).Error; err != nil {
		c.JSON(404, gin.H{"code": 404, "message": "Not found"})
		return
	}

	database.DB.Model(&doc).Update("view_count", doc.ViewCount+1)
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": doc})
}

func (h *Handler) ListDocuments(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	docType := c.Query("type")
	source := c.Query("source")
	language := c.Query("language")
	author := c.Query("author")

	var docs []Document
	var total int64
	query := database.DB.Model(&Document{})

	if docType != "" {
		query = query.Where("doc_type = ?", docType)
	}
	if source != "" {
		query = query.Where("source = ?", source)
	}
	if language != "" {
		query = query.Where("language = ?", language)
	}
	if author != "" {
		query = query.Where("author = ?", author)
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&docs)

	c.JSON(200, gin.H{"code": 200, "message": "success", "data": gin.H{
		"items": docs, "total": total, "page": page, "size": pageSize,
	}})
}

func (h *Handler) UpdateDocument(c *gin.Context) {
	id := c.Param("id")
	var req CreateDocRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	updates := map[string]interface{}{
		"title":       req.Title,
		"content":     req.Content,
		"summary":     req.Summary,
		"source":      req.Source,
		"source_url":  req.SourceURL,
		"doc_type":    req.DocType,
		"language":    req.Language,
		"tags":        req.Tags,
		"metadata":    req.Metadata,
		"author":      req.Author,
		"permissions": req.Permissions,
		"is_public":   req.IsPublic,
		"updated_at":  time.Now(),
	}

	database.DB.Model(&Document{}).Where("id = ? OR doc_id = ?", id, id).Updates(updates)
	c.JSON(200, gin.H{"code": 200, "message": "success"})
}

func (h *Handler) DeleteDocument(c *gin.Context) {
	id := c.Param("id")
	database.DB.Where("id = ? OR doc_id = ?", id, id).Delete(&Document{})
	c.JSON(200, gin.H{"code": 200, "message": "success"})
}

func (h *Handler) Search(c *gin.Context) {
	var req SearchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	var docs []Document
	query := database.DB.Model(&Document{}).Where(
		"title LIKE ? OR content LIKE ? OR summary LIKE ?",
		"%"+req.Query+"%", "%"+req.Query+"%", "%"+req.Query+"%",
	)

	if req.DocType != "" {
		query = query.Where("doc_type = ?", req.DocType)
	}
	if req.Source != "" {
		query = query.Where("source = ?", req.Source)
	}
	if req.Language != "" {
		query = query.Where("language = ?", req.Language)
	}

	query.Limit(50).Order("view_count DESC").Find(&docs)

	results := make([]map[string]interface{}, 0)
	for _, doc := range docs {
		results = append(results, gin.H{
			"doc_id":    doc.DocID,
			"title":     doc.Title,
			"summary":   doc.Summary,
			"source":    doc.Source,
			"doc_type":  doc.DocType,
			"author":    doc.Author,
			"view_count": doc.ViewCount,
			"created_at": doc.CreatedAt,
		})
	}

	c.JSON(200, gin.H{"code": 200, "message": "success", "data": gin.H{
		"total":   len(results),
		"results": results,
		"query":   req.Query,
	}})
}

func (h *Handler) CreateSource(c *gin.Context) {
	var source DocumentSource
	if err := c.ShouldBindJSON(&source); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	source.SourceID = utils.GenerateID("src")
	source.SyncStatus = "idle"

	if err := database.DB.Create(&source).Error; err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "message": "created", "data": source})
}

func (h *Handler) ListSources(c *gin.Context) {
	var sources []DocumentSource
	database.DB.Find(&sources)
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": sources})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	docs := r.Group("/documents")
	{
		docs.POST("", h.CreateDocument)
		docs.GET("", h.ListDocuments)
		docs.GET("/:id", h.GetDocument)
		docs.PUT("/:id", h.UpdateDocument)
		docs.DELETE("/:id", h.DeleteDocument)
	}

	r.POST("/search", h.Search)

	sources := r.Group("/sources")
	{
		sources.POST("", h.CreateSource)
		sources.GET("", h.ListSources)
	}
}
