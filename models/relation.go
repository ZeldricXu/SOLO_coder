package models

import (
	"time"
)

type RelationType string

const (
	RelationTypeFollow RelationType = "follow"
)

type RelationStatus string

const (
	RelationStatusActive  RelationStatus = "active"
	RelationStatusBlocked RelationStatus = "blocked"
)

type Relation struct {
	RelationID   string         `bson:"relation_id" json:"relation_id"`
	UserID       string         `bson:"user_id" json:"user_id"`
	TargetUserID string         `bson:"target_user_id" json:"target_user_id"`
	RelationType RelationType   `bson:"relation_type" json:"relation_type"`
	CreatedAt    time.Time      `bson:"created_at" json:"created_at"`
	Status       RelationStatus `bson:"status" json:"status"`
}
