package documentation

import (
	"github.com/gin-gonic/gin"
	"github.com/solocoder/session138/internal/logger"
	"github.com/solocoder/session138/pkg/utils"
	"go.uber.org/zap"
	"net/http"
	"strings"
	"sync"
	"time"
)

type Document struct {
	ID          string    `json:"id"`
	Title       string    `json:"title"`
	Content     string    `json:"content"`
	Source      string    `json:"source"`
	URL         string    `json:"url"`
	Tags        []string  `json:"tags"`
	Author      string    `json:"author"`
	Permissions []string  `json:"permissions"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

type SearchRequest struct {
	Query      string   `json:"query"`
	Source     string   `json:"source"`
	Tags       []string `json:"tags"`
	UserGroups []string `json:"user_groups"`
	Page       int      `json:"page"`
	PageSize   int      `json:"page_size"`
}

type SearchResult struct {
	Document Document `json:"document"`
	Score    float64  `json:"score"`
	Matches  []string `json:"matches"`
}

var (
	documents = make(map[string]Document)
	docMutex  sync.RWMutex
)

func initSampleDocs() {
	docs := []Document{
		{
			ID:          utils.GenerateID("doc"),
			Title:       "Go服务开发指南",
			Content:     "本文档介绍如何使用Go语言开发高性能微服务。包含项目结构、最佳实践、性能优化等内容。",
			Source:      "confluence",
			URL:         "https://docs.example.com/go-guide",
			Tags:        []string{"go", "microservice", "development"},
			Author:      "admin",
			Permissions: []string{"engineering", "devops"},
			CreatedAt:   utils.Now(),
			UpdatedAt:   utils.Now(),
		},
		{
			ID:          utils.GenerateID("doc"),
			Title:       "Kubernetes部署手册",
			Content:     "详细介绍如何在Kubernetes集群上部署和管理容器化应用。包含Deployment、Service、Ingress等资源配置。",
			Source:      "gitbook",
			URL:         "https://docs.example.com/k8s-deploy",
			Tags:        []string{"kubernetes", "devops", "deployment"},
			Author:      "devops-team",
			Permissions: []string{"engineering", "devops", "qa"},
			CreatedAt:   utils.Now(),
			UpdatedAt:   utils.Now(),
		},
		{
			ID:          utils.GenerateID("doc"),
			Title:       "API设计规范",
			Content:     "公司内部RESTful API设计规范。包含命名约定、错误处理、分页、过滤等标准。",
			Source:      "internal",
			URL:         "https://docs.example.com/api-spec",
			Tags:        []string{"api", "rest", "specification"},
			Author:      "architecture-team",
			Permissions: []string{"engineering", "product"},
			CreatedAt:   utils.Now(),
			UpdatedAt:   utils.Now(),
		},
	}

	for _, doc := range docs {
		documents[doc.ID] = doc
	}
}

func init() {
	initSampleDocs()
}

func AddDocument(c *gin.Context) {
	var doc Document
	if err := c.ShouldBindJSON(&doc); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	doc.ID = utils.GenerateID("doc")
	doc.CreatedAt = utils.Now()
	doc.UpdatedAt = utils.Now()

	if doc.Permissions == nil {
		doc.Permissions = []string{"engineering"}
	}

	docMutex.Lock()
	documents[doc.ID] = doc
	docMutex.Unlock()

	logger.Info("documentation", "文档已添加",
		zap.String("doc_id", doc.ID),
		zap.String("title", doc.Title),
	)

	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": doc})
}

func GetDocument(c *gin.Context) {
	id := c.Param("id")
	userGroups := strings.Split(c.GetHeader("X-User-Groups"), ",")

	docMutex.RLock()
	doc, exists := documents[id]
	docMutex.RUnlock()

	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "文档不存在"})
		return
	}

	if !hasPermission(doc.Permissions, userGroups) {
		c.JSON(http.StatusForbidden, gin.H{"code": 403, "message": "无权限访问该文档"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": doc})
}

func SearchDocuments(c *gin.Context) {
	var req SearchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		req.Query = c.Query("q")
		req.Source = c.Query("source")
		req.UserGroups = strings.Split(c.GetHeader("X-User-Groups"), ",")
		req.Page = 1
		req.PageSize = 20
	}

	if req.UserGroups == nil || len(req.UserGroups) == 0 {
		req.UserGroups = []string{"engineering"}
	}

	docMutex.RLock()
	defer docMutex.RUnlock()

	results := make([]SearchResult, 0)

	for _, doc := range documents {
		if !hasPermission(doc.Permissions, req.UserGroups) {
			continue
		}

		if req.Source != "" && doc.Source != req.Source {
			continue
		}

		if len(req.Tags) > 0 {
			hasTag := false
			for _, t := range req.Tags {
				if utils.Contains(doc.Tags, t) {
					hasTag = true
					break
				}
			}
			if !hasTag {
				continue
			}
		}

		score := 0.0
		matches := make([]string, 0)

		if req.Query != "" {
			queryLower := strings.ToLower(req.Query)
			titleLower := strings.ToLower(doc.Title)
			contentLower := strings.ToLower(doc.Content)

			if strings.Contains(titleLower, queryLower) {
				score += 5.0
				matches = append(matches, "title")
			}

			if strings.Contains(contentLower, queryLower) {
				score += 2.0
				matches = append(matches, "content")
			}

			for _, tag := range doc.Tags {
				if strings.Contains(strings.ToLower(tag), queryLower) {
					score += 3.0
					matches = append(matches, "tag:"+tag)
				}
			}

			if score == 0 {
				continue
			}
		} else {
			score = 1.0
		}

		results = append(results, SearchResult{
			Document: doc,
			Score:    score,
			Matches:  matches,
		})
	}

	for i := 0; i < len(results); i++ {
		for j := i + 1; j < len(results); j++ {
			if results[j].Score > results[i].Score {
				results[i], results[j] = results[j], results[i]
			}
		}
	}

	start := (req.Page - 1) * req.PageSize
	end := start + req.PageSize
	if start > len(results) {
		start = len(results)
	}
	if end > len(results) {
		end = len(results)
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"items": results[start:end],
			"total": len(results),
			"page":  req.Page,
			"size":  req.PageSize,
		},
	})
}

func ListSources(c *gin.Context) {
	sources := make(map[string]bool)
	docMutex.RLock()
	for _, doc := range documents {
		sources[doc.Source] = true
	}
	docMutex.RUnlock()

	sourceList := make([]string, 0, len(sources))
	for s := range sources {
		sourceList = append(sourceList, s)
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": sourceList})
}

func DeleteDocument(c *gin.Context) {
	id := c.Param("id")

	docMutex.Lock()
	if _, exists := documents[id]; !exists {
		docMutex.Unlock()
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "文档不存在"})
		return
	}
	delete(documents, id)
	docMutex.Unlock()

	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "文档已删除"})
}

func hasPermission(docPermissions, userGroups []string) bool {
	if len(docPermissions) == 0 {
		return true
	}

	for _, group := range userGroups {
		if utils.Contains(docPermissions, group) {
			return true
		}
	}

	return false
}

func RegisterRoutes(r *gin.RouterGroup) {
	docs := r.Group("/documentation")
	{
		docs.POST("", AddDocument)
		docs.POST("/search", SearchDocuments)
		docs.GET("/sources", ListSources)
		docs.GET("/:id", GetDocument)
		docs.DELETE("/:id", DeleteDocument)
	}
}
