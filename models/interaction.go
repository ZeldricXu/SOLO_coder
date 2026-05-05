package models

import (
	"time"
)

type InteractionType string

const (
	InteractionTypeLike    InteractionType = "like"
	InteractionTypeComment InteractionType = "comment"
	InteractionTypeShare   InteractionType = "share"
)

type Interaction struct {
	InteractionID   string          `bson:"interaction_id" json:"interaction_id"`
	PostID          string          `bson:"post_id" json:"post_id"`
	UserID          string          `bson:"user_id" json:"user_id"`
	InteractionType InteractionType `bson:"interaction_type" json:"interaction_type"`
	Content         *string         `bson:"content,omitempty" json:"content,omitempty"`
	CreatedAt       time.Time       `bson:"created_at" json:"created_at"`
}

type InteractRequest struct {
	PostID          string          `json:"post_id" binding:"required"`
	InteractionType InteractionType `json:"interaction_type" binding:"required"`
	Content         *string         `json:"content,omitempty"`
}

type InteractResponse struct {
	InteractionID string `json:"interaction_id"`
}
