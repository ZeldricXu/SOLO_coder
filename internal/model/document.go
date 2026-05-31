package model

import (
	"time"
)

type DocumentIndex struct {
	ID            string                 `gorm:"primaryKey;column:id" json:"id"`
	Title         string                 `gorm:"column:title;index" json:"title"`
	Source        string                 `gorm:"column:source;index" json:"source"`
	SourceURL     string                 `gorm:"column:source_url" json:"source_url"`
	Category      string                 `gorm:"column:category;index" json:"category"`
	Tags          []string               `gorm:"column:tags;type:jsonb;serializer:json" json:"tags"`
	Content       string                 `gorm:"column:content;type:text" json:"content"`
	IndexedContent string                `gorm:"column:indexed_content;type:text" json:"-"`
	Permissions   []string               `gorm:"column:permissions;type:jsonb;serializer:json" json:"permissions"`
	Owner         string                 `gorm:"column:owner;index" json:"owner"`
	LastModified  *time.Time             `gorm:"column:last_modified" json:"last_modified"`
	LastIndexed   time.Time              `gorm:"column:last_indexed" json:"last_indexed"`
	Version       int                    `gorm:"column:version" json:"version"`
	Metadata      map[string]interface{} `gorm:"column:metadata;type:jsonb" json:"metadata"`
	CreatedAt     time.Time              `gorm:"column:created_at" json:"created_at"`
	UpdatedAt     time.Time              `gorm:"column:updated_at" json:"updated_at"`
}

func (DocumentIndex) TableName() string {
	return "document_index"
}

type IndexDocumentRequest struct {
	Title        string                 `json:"title" binding:"required"`
	Source       string                 `json:"source" binding:"required"`
	SourceURL    string                 `json:"source_url"`
	Category     string                 `json:"category"`
	Tags         []string               `json:"tags"`
	Content      string                 `json:"content" binding:"required"`
	Permissions  []string               `json:"permissions"`
	Owner        string                 `json:"owner" binding:"required"`
	LastModified *time.Time             `json:"last_modified"`
	Metadata     map[string]interface{} `json:"metadata"`
}

type SearchDocumentRequest struct {
	Query       string   `form:"q" binding:"required"`
	Source      string   `form:"source"`
	Category    string   `form:"category"`
	Tags        []string `form:"tags"`
	UserID      string   `form:"user_id"`
	Page        int      `form:"page,default=1"`
	PageSize    int      `form:"page_size,default=20"`
}

type SearchDocumentResult struct {
	ID           string   `json:"id"`
	Title        string   `json:"title"`
	Source       string   `json:"source"`
	SourceURL    string   `json:"source_url"`
	Category     string   `json:"category"`
	Tags         []string `json:"tags"`
	Snippet      string   `json:"snippet"`
	Score        float64  `json:"score"`
	LastModified *time.Time `json:"last_modified"`
}

type SearchDocumentResponse struct {
	Total    int64                  `json:"total"`
	Docs     []SearchDocumentResult `json:"docs"`
	Facets   map[string]interface{} `json:"facets"`
}

type SyncDocumentsRequest struct {
	Sources []string `json:"sources"`
	Force   bool     `json:"force"`
}

type SyncResult struct {
	Source    string `json:"source"`
	Indexed   int    `json:"indexed"`
	Updated   int    `json:"updated"`
	Skipped   int    `json:"skipped"`
	Failed    int    `json:"failed"`
}
