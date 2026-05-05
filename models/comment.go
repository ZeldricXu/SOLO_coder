package models

import (
	"time"
)

type CommentStatus string

const (
	CommentStatusVisible CommentStatus = "visible"
	CommentStatusHidden  CommentStatus = "hidden"
	CommentStatusDeleted CommentStatus = "deleted"
)

type Comment struct {
	CommentID string        `bson:"comment_id" json:"comment_id"`
	PostID    string        `bson:"post_id" json:"post_id"`
	UserID    string        `bson:"user_id" json:"user_id"`
	Content   string        `bson:"content" json:"content"`
	CreatedAt time.Time     `bson:"created_at" json:"created_at"`
	LikeCount int64         `bson:"like_count" json:"like_count"`
	Status    CommentStatus `bson:"status" json:"status"`
}
