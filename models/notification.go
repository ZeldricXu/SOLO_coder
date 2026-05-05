package models

import (
	"time"
)

type NotificationType string

const (
	NotificationTypeLike    NotificationType = "like"
	NotificationTypeComment NotificationType = "comment"
	NotificationTypeShare   NotificationType = "share"
	NotificationTypeFollow  NotificationType = "follow"
	NotificationTypeNewPost NotificationType = "new_post"
)

type Notification struct {
	NotificationID   string           `bson:"notification_id" json:"notification_id"`
	UserID           string           `bson:"user_id" json:"user_id"`
	FromUserID       string           `bson:"from_user_id" json:"from_user_id"`
	PostID           *string          `bson:"post_id,omitempty" json:"post_id,omitempty"`
	NotificationType NotificationType `bson:"notification_type" json:"notification_type"`
	Content          *string          `bson:"content,omitempty" json:"content,omitempty"`
	CreatedAt        time.Time        `bson:"created_at" json:"created_at"`
	IsRead           bool             `bson:"is_read" json:"is_read"`
}
