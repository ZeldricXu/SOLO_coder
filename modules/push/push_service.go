package push

import (
	"context"
	"sync"
	"time"

	"socialfeed/models"
	"socialfeed/storage"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type NotificationService interface {
	SendNotification(ctx context.Context, notification *models.Notification) error
}

type PushService struct {
	mongoDB             *storage.MongoDB
	redisDB             *storage.RedisDB
	notificationService NotificationService
}

func NewPushService(
	mongoDB *storage.MongoDB,
	redisDB *storage.RedisDB,
	notificationService NotificationService,
) *PushService {
	return &PushService{
		mongoDB:             mongoDB,
		redisDB:             redisDB,
		notificationService: notificationService,
	}
}

func (s *PushService) PushPostToFollowers(ctx context.Context, post *models.Post, followers []string) error {
	if len(followers) == 0 {
		return nil
	}

	var wg sync.WaitGroup
	workerCount := 10
	jobChan := make(chan string, len(followers))
	errChan := make(chan error, len(followers))

	for i := 0; i < workerCount; i++ {
		go s.pushWorker(ctx, post, &wg, jobChan, errChan)
	}

	for _, followerID := range followers {
		wg.Add(1)
		jobChan <- followerID
	}

	close(jobChan)
	wg.Wait()
	close(errChan)

	var errs []error
	for err := range errChan {
		errs = append(errs, err)
	}

	if len(errs) > 0 {
		return errs[0]
	}

	return nil
}

func (s *PushService) pushWorker(
	ctx context.Context,
	post *models.Post,
	wg *sync.WaitGroup,
	jobChan <-chan string,
	errChan chan<- error,
) {
	for followerID := range jobChan {
		err := s.pushToFollower(ctx, post, followerID)
		if err != nil {
			errChan <- err
		}
		wg.Done()
	}
}

func (s *PushService) pushToFollower(ctx context.Context, post *models.Post, followerID string) error {
	position, err := s.getNextFeedPosition(ctx, followerID)
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

	_, err = s.mongoDB.Collections.Feeds.InsertOne(ctx, feedItem)
	if err != nil {
		return err
	}

	err = s.notificationService.SendNotification(ctx, &models.Notification{
		NotificationID:   "notif_" + uuid.New().String()[:8],
		UserID:           followerID,
		FromUserID:       post.UserID,
		PostID:           &post.PostID,
		NotificationType: models.NotificationTypeNewPost,
		CreatedAt:        time.Now().UTC(),
		IsRead:           false,
	})

	return err
}

func (s *PushService) getNextFeedPosition(ctx context.Context, userID string) (int64, error) {
	filter := bson.M{"user_id": userID}

	var result struct {
		FeedPosition int64 `bson:"feed_position"`
	}

	findOneOpts := options.FindOne().SetSort(bson.M{"feed_position": -1})
	err := s.mongoDB.Collections.Feeds.FindOne(ctx, filter, findOneOpts).Decode(&result)
	if err != nil {
		return 1, err
	}
	return result.FeedPosition + 1, nil
}

func (s *PushService) EnqueuePushTask(ctx context.Context, postID, authorID string, followers []string) error {
	if len(followers) == 0 {
		return nil
	}

	var post *models.Post
	filter := bson.M{"post_id": postID}
	err := s.mongoDB.Collections.Posts.FindOne(ctx, filter).Decode(&post)
	if err != nil {
		return err
	}

	return s.PushPostToFollowers(ctx, post, followers)
}
