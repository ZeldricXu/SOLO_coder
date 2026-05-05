package utils

import (
	"context"
	"socialfeed/models"
	"time"

	"github.com/google/uuid"
)

func GenerateTestPost(userID string, content string, media []string) *models.Post {
	postType := models.PostTypeText
	if len(media) > 0 {
		postType = models.PostTypeImage
	}

	return &models.Post{
		PostID:       "post_" + uuid.New().String()[:8],
		UserID:       userID,
		Content:      content,
		Media:        media,
		PostType:     postType,
		Status:       models.PostStatusPublished,
		CreatedAt:    time.Now().UTC(),
		LikeCount:    0,
		CommentCount: 0,
		ShareCount:   0,
		HeatScore:    0,
	}
}

func GenerateTestFeedItem(userID, postID string, position int64) *models.Feed {
	return &models.Feed{
		FeedID:       "feed_" + uuid.New().String()[:8],
		UserID:       userID,
		PostID:       postID,
		FeedPosition: position,
		InsertedAt:   time.Now().UTC(),
		IsRead:       false,
	}
}

func GenerateTestInteraction(userID, postID string, interactionType models.InteractionType, content *string) *models.Interaction {
	return &models.Interaction{
		InteractionID:   "interact_" + uuid.New().String()[:8],
		PostID:          postID,
		UserID:          userID,
		InteractionType: interactionType,
		Content:         content,
		CreatedAt:       time.Now().UTC(),
	}
}

func GenerateTestNotification(userID, fromUserID string, notifType models.NotificationType, postID *string) *models.Notification {
	return &models.Notification{
		NotificationID:   "notif_" + uuid.New().String()[:8],
		UserID:           userID,
		FromUserID:       fromUserID,
		PostID:           postID,
		NotificationType: notifType,
		CreatedAt:        time.Now().UTC(),
		IsRead:           false,
	}
}

func GenerateTestRelation(userID, targetUserID string) *models.Relation {
	return &models.Relation{
		RelationID:   "relation_" + uuid.New().String()[:8],
		UserID:       userID,
		TargetUserID: targetUserID,
		RelationType: models.RelationTypeFollow,
		CreatedAt:    time.Now().UTC(),
		Status:       models.RelationStatusActive,
	}
}

func AssertEqual(t Testable, expected, actual interface{}, msg string) {
	if expected != actual {
		t.Errorf("%s: expected %v, got %v", msg, expected, actual)
	}
}

func AssertTrue(t Testable, condition bool, msg string) {
	if !condition {
		t.Errorf("%s: expected true, got false", msg)
	}
}

func AssertFalse(t Testable, condition bool, msg string) {
	if condition {
		t.Errorf("%s: expected false, got true", msg)
	}
}

func AssertNotNil(t Testable, obj interface{}, msg string) {
	if obj == nil {
		t.Errorf("%s: expected not nil, got nil", msg)
	}
}

func AssertNil(t Testable, obj interface{}, msg string) {
	if obj != nil {
		t.Errorf("%s: expected nil, got %v", msg, obj)
	}
}

type Testable interface {
	Errorf(format string, args ...interface{})
}

type MockMongoCollection struct {
	insertOneFunc   func(ctx context.Context, document interface{}) (interface{}, error)
	findOneFunc     func(ctx context.Context, filter interface{}, opts ...interface{}) interface{}
	updateOneFunc   func(ctx context.Context, filter interface{}, update interface{}) (interface{}, error)
	findFunc        func(ctx context.Context, filter interface{}, opts ...interface{}) (MockCursor, error)
	countFunc       func(ctx context.Context, filter interface{}) (int64, error)
	deleteManyFunc  func(ctx context.Context, filter interface{}) (int64, error)
}

type MockCursor struct {
	items []interface{}
	index int
}

func (m *MockCursor) Next(ctx context.Context) bool {
	if m.index < len(m.items) {
		m.index++
		return true
	}
	return false
}

func (m *MockCursor) Decode(val interface{}) error {
	if m.index > 0 && m.index <= len(m.items) {
		if target, ok := val.(*interface{}); ok {
			*target = m.items[m.index-1]
		}
	}
	return nil
}

func (m *MockCursor) Close(ctx context.Context) error {
	return nil
}

type MockRedisClient struct {
	setFunc   func(ctx context.Context, key string, value interface{}) error
	getFunc   func(ctx context.Context, key string) (string, error)
	delFunc   func(ctx context.Context, keys ...string) error
	existsFunc func(ctx context.Context, keys ...string) (int64, error)
}
