package post

import (
	"context"
	"socialfeed/models"
)

type AuditServiceInterface interface {
	AuditPost(ctx context.Context, post *models.Post) (*models.AuditRecord, error)
	GetSensitiveWords() []string
}

type PushServiceInterface interface {
	PushPostToFollowers(ctx context.Context, post *models.Post, followers []string) error
	EnqueuePushTask(ctx context.Context, postID, authorID string, followers []string) error
}

type RelationServiceInterface interface {
	GetFollowers(ctx context.Context, userID string) ([]string, error)
	GetFollowing(ctx context.Context, userID string) ([]string, error)
	IsFollowing(ctx context.Context, followerID, followingID string) (bool, error)
	Follow(ctx context.Context, followerID, followingID string) error
	Unfollow(ctx context.Context, followerID, followingID string) error
}

type PopularityServiceInterface interface {
	InitHeatScore(ctx context.Context, postID string) error
	UpdateHeatScoreOnInteraction(ctx context.Context, postID string) error
}
