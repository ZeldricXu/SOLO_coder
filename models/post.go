package models

import (
	"time"
)

type PostType string

const (
	PostTypeText  PostType = "text"
	PostTypeImage PostType = "image"
	PostTypeVideo PostType = "video"
)

type PostStatus string

const (
	PostStatusPending   PostStatus = "pending"
	PostStatusPublished PostStatus = "published"
	PostStatusRejected  PostStatus = "rejected"
)

type Post struct {
	PostID       string     `bson:"post_id" json:"post_id"`
	UserID       string     `bson:"user_id" json:"user_id"`
	Content      string     `bson:"content" json:"content"`
	Media        []string   `bson:"media" json:"media"`
	PostType     PostType   `bson:"post_type" json:"post_type"`
	Status       PostStatus `bson:"status" json:"status"`
	CreatedAt    time.Time  `bson:"created_at" json:"created_at"`
	LikeCount    int64      `bson:"like_count" json:"like_count"`
	CommentCount int64      `bson:"comment_count" json:"comment_count"`
	ShareCount   int64      `bson:"share_count" json:"share_count"`
	HeatScore    float64    `bson:"heat_score" json:"heat_score"`
}

type CreatePostRequest struct {
	Content string   `json:"content" binding:"required"`
	Media   []string `json:"media"`
}

type CreatePostResponse struct {
	PostID string     `json:"post_id"`
	Status PostStatus `json:"status"`
}
