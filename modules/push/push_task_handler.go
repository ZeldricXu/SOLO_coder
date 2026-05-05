package push

import (
	"context"
	"encoding/json"
	"log"
	"socialfeed/models"
	"socialfeed/modules/queue"
	"socialfeed/storage"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type AsyncPushService struct {
	mongoDB             *storage.MongoDB
	redisDB             *storage.RedisDB
	notificationService queue.NotificationService
	queueManager        *queue.QueueManager
}

func NewAsyncPushService(
	mongoDB *storage.MongoDB,
	redisDB *storage.RedisDB,
	notificationService queue.NotificationService,
	queueManager *queue.QueueManager,
) *AsyncPushService {
	return &AsyncPushService{
		mongoDB:             mongoDB,
		redisDB:             redisDB,
		notificationService: notificationService,
		queueManager:        queueManager,
	}
}

func (s *AsyncPushService) EnqueuePushTask(ctx context.Context, postID, authorID string, followers []string) error {
	if len(followers) == 0 {
		return nil
	}

	payload := queue.PushTaskPayload{
		PostID:    postID,
		AuthorID:  authorID,
		Followers: followers,
	}

	task := &queue.BaseTask{
		ID:         "push_task_" + uuid.New().String()[:8],
		Type:       queue.TaskTypePush,
		Payload:    payload,
		CreatedAt:  time.Now().UTC(),
		Status:     queue.TaskStatusPending,
		RetryCount: 0,
		MaxRetries: 3,
	}

	return s.queueManager.EnqueuePushTask(ctx, task)
}

func (s *AsyncPushService) PushPostToFollowers(ctx context.Context, post *models.Post, followers []string) error {
	if len(followers) == 0 {
		return nil
	}

	return s.EnqueuePushTask(ctx, post.PostID, post.UserID, followers)
}

type PushTaskHandler struct {
	mongoDB             *storage.MongoDB
	redisDB             *storage.RedisDB
	notificationService queue.NotificationService
}

func NewPushTaskHandler(
	mongoDB *storage.MongoDB,
	redisDB *storage.RedisDB,
	notificationService queue.NotificationService,
) *PushTaskHandler {
	return &PushTaskHandler{
		mongoDB:             mongoDB,
		redisDB:             redisDB,
		notificationService: notificationService,
	}
}

func (h *PushTaskHandler) Handle(ctx context.Context, task queue.Task) error {
	payloadBytes, err := json.Marshal(task.GetPayload())
	if err != nil {
		log.Printf("PushTaskHandler: Failed to marshal payload: %v", err)
		return err
	}

	var payload queue.PushTaskPayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		log.Printf("PushTaskHandler: Failed to unmarshal payload: %v", err)
		return err
	}

	log.Printf("PushTaskHandler: Processing push task for post %s, author %s, followers count: %d",
		payload.PostID, payload.AuthorID, len(payload.Followers))

	post, err := h.getPostByID(ctx, payload.PostID)
	if err != nil {
		log.Printf("PushTaskHandler: Failed to get post %s: %v", payload.PostID, err)
		return err
	}

	if post == nil {
		log.Printf("PushTaskHandler: Post %s not found", payload.PostID)
		return nil
	}

	for _, followerID := range payload.Followers {
		select {
		case <-ctx.Done():
			log.Printf("PushTaskHandler: Context cancelled, stopping push for post %s", payload.PostID)
			return ctx.Err()
		default:
			if err := h.pushToSingleFollower(ctx, post, followerID); err != nil {
				log.Printf("PushTaskHandler: Failed to push to follower %s: %v", followerID, err)
			}
		}
	}

	log.Printf("PushTaskHandler: Completed push task for post %s", payload.PostID)
	return nil
}

func (h *PushTaskHandler) getPostByID(ctx context.Context, postID string) (*models.Post, error) {
	var post models.Post
	filter := bson.M{"post_id": postID}
	err := h.mongoDB.Collections.Posts.FindOne(ctx, filter).Decode(&post)
	if err != nil {
		return nil, err
	}
	return &post, nil
}

func (h *PushTaskHandler) pushToSingleFollower(ctx context.Context, post *models.Post, followerID string) error {
	position, err := h.getNextFeedPosition(ctx, followerID)
	if err != nil {
		position = 1
	}

	feedItem := &models.Feed{
		FeedID:       "feed_" + uuid.New().String()[:8],
		UserID:       followerID,
		PostID:       post.PostID,
		FeedPosition: position,
		InsertedAt:   time.Now().UTC(),
		IsRead:       false,
	}

	_, err = h.mongoDB.Collections.Feeds.InsertOne(ctx, feedItem)
	if err != nil {
		return err
	}

	notification := &models.Notification{
		NotificationID:   "notif_" + uuid.New().String()[:8],
		UserID:           followerID,
		FromUserID:       post.UserID,
		PostID:           &post.PostID,
		NotificationType: models.NotificationTypeNewPost,
		CreatedAt:        time.Now().UTC(),
		IsRead:           false,
	}

	_ = h.notificationService.SendNotification(ctx, notification)

	return nil
}

func (h *PushTaskHandler) getNextFeedPosition(ctx context.Context, userID string) (int64, error) {
	filter := bson.M{"user_id": userID}

	var result struct {
		FeedPosition int64 `bson:"feed_position"`
	}

	findOneOpts := options.FindOne().SetSort(bson.M{"feed_position": -1})
	err := h.mongoDB.Collections.Feeds.FindOne(ctx, filter, findOneOpts).Decode(&result)
	if err != nil {
		return 1, err
	}
	return result.FeedPosition + 1, nil
}
