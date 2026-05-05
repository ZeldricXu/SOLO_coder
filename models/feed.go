package models

import (
	"time"
)

type Feed struct {
	FeedID       string    `bson:"feed_id" json:"feed_id"`
	UserID       string    `bson:"user_id" json:"user_id"`
	PostID       string    `bson:"post_id" json:"post_id"`
	FeedPosition int64     `bson:"feed_position" json:"feed_position"`
	InsertedAt   time.Time `bson:"inserted_at" json:"inserted_at"`
	IsRead       bool      `bson:"is_read" json:"is_read"`
}

type FeedListRequest struct {
	UserID   string `form:"user_id" binding:"required"`
	Page     int64  `form:"page"`
	PageSize int64  `form:"page_size"`
}

type FeedListResponse struct {
	Feed []Post `json:"feed"`
	Total int64 `json:"total"`
}
