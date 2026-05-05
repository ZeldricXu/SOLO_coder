package notification

import (
	"context"
	"socialfeed/models"
	"socialfeed/storage"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type NotificationService struct {
	mongoDB *storage.MongoDB
	redisDB *storage.RedisDB
}

func NewNotificationService(mongoDB *storage.MongoDB, redisDB *storage.RedisDB) *NotificationService {
	return &NotificationService{
		mongoDB: mongoDB,
		redisDB: redisDB,
	}
}

func (s *NotificationService) SendNotification(ctx context.Context, notification *models.Notification) error {
	_, err := s.mongoDB.Collections.Notifications.InsertOne(ctx, notification)
	return err
}

func (s *NotificationService) GetNotifications(ctx context.Context, userID string, limit int64) ([]models.Notification, error) {
	filter := bson.M{"user_id": userID}
	findOpts := options.Find().
		SetSort(bson.M{"created_at": -1}).
		SetLimit(limit)

	cursor, err := s.mongoDB.Collections.Notifications.Find(ctx, filter, findOpts)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var notifications []models.Notification
	for cursor.Next(ctx) {
		var notif models.Notification
		if err := cursor.Decode(&notif); err != nil {
			continue
		}
		notifications = append(notifications, notif)
	}

	return notifications, nil
}

func (s *NotificationService) MarkAsRead(ctx context.Context, notificationID string) error {
	filter := bson.M{"notification_id": notificationID}
	update := bson.M{"$set": bson.M{"is_read": true}}
	_, err := s.mongoDB.Collections.Notifications.UpdateOne(ctx, filter, update)
	return err
}

func (s *NotificationService) MarkAllAsRead(ctx context.Context, userID string) error {
	filter := bson.M{
		"user_id": userID,
		"is_read": false,
	}
	update := bson.M{"$set": bson.M{"is_read": true}}
	_, err := s.mongoDB.Collections.Notifications.UpdateMany(ctx, filter, update)
	return err
}

func (s *NotificationService) GetUnreadCount(ctx context.Context, userID string) (int64, error) {
	filter := bson.M{
		"user_id": userID,
		"is_read": false,
	}
	count, err := s.mongoDB.Collections.Notifications.CountDocuments(ctx, filter)
	if err != nil {
		return 0, err
	}
	return count, nil
}

func (s *NotificationService) SendLikeNotification(ctx context.Context, postAuthorID, fromUserID, postID string) error {
	notification := &models.Notification{
		NotificationID:   "notif_" + generateShortID(),
		UserID:           postAuthorID,
		FromUserID:       fromUserID,
		PostID:           &postID,
		NotificationType: models.NotificationTypeLike,
		IsRead:           false,
	}
	return s.SendNotification(ctx, notification)
}

func (s *NotificationService) SendCommentNotification(ctx context.Context, postAuthorID, fromUserID, postID, commentContent string) error {
	notification := &models.Notification{
		NotificationID:   "notif_" + generateShortID(),
		UserID:           postAuthorID,
		FromUserID:       fromUserID,
		PostID:           &postID,
		NotificationType: models.NotificationTypeComment,
		Content:          &commentContent,
		IsRead:           false,
	}
	return s.SendNotification(ctx, notification)
}

func (s *NotificationService) SendShareNotification(ctx context.Context, postAuthorID, fromUserID, postID string) error {
	notification := &models.Notification{
		NotificationID:   "notif_" + generateShortID(),
		UserID:           postAuthorID,
		FromUserID:       fromUserID,
		PostID:           &postID,
		NotificationType: models.NotificationTypeShare,
		IsRead:           false,
	}
	return s.SendNotification(ctx, notification)
}

func generateShortID() string {
	return uuid.New().String()[:8]
}
