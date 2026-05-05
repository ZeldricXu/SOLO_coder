package post

import (
	"context"
	"errors"
	"log"
	"socialfeed/models"
	"socialfeed/storage"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
)

var (
	ErrPostContentEmpty = errors.New("post content cannot be empty")
	ErrPostNotFound     = errors.New("post not found")
)

type PostService struct {
	mongoDB           *storage.MongoDB
	auditService      AuditServiceInterface
	relationService   RelationServiceInterface
	pushService       PushServiceInterface
	popularityService PopularityServiceInterface
}

func NewPostService(
	mongoDB *storage.MongoDB,
	auditService AuditServiceInterface,
	relationService RelationServiceInterface,
	pushService PushServiceInterface,
	popularityService PopularityServiceInterface,
) *PostService {
	return &PostService{
		mongoDB:           mongoDB,
		auditService:      auditService,
		relationService:   relationService,
		pushService:       pushService,
		popularityService: popularityService,
	}
}

func (s *PostService) CreatePost(ctx context.Context, userID string, req *models.CreatePostRequest) (*models.CreatePostResponse, error) {
	if req.Content == "" {
		return nil, ErrPostContentEmpty
	}

	postType := models.PostTypeText
	if len(req.Media) > 0 {
		if containsVideo(req.Media) {
			postType = models.PostTypeVideo
		} else {
			postType = models.PostTypeImage
		}
	}

	post := &models.Post{
		PostID:       "post_" + uuid.New().String()[:8],
		UserID:       userID,
		Content:      req.Content,
		Media:        req.Media,
		PostType:     postType,
		Status:       models.PostStatusPending,
		CreatedAt:    time.Now().UTC(),
		LikeCount:    0,
		CommentCount: 0,
		ShareCount:   0,
		HeatScore:    0,
	}

	auditResult, err := s.auditService.AuditPost(ctx, post)
	if err != nil {
		post.Status = models.PostStatusPending
	} else {
		if auditResult.AuditResult == models.AuditResultApproved {
			post.Status = models.PostStatusPublished
		} else if auditResult.AuditResult == models.AuditResultRejected {
			post.Status = models.PostStatusRejected
		}
	}

	_, err = s.mongoDB.Collections.Posts.InsertOne(ctx, post)
	if err != nil {
		return nil, err
	}

	if post.Status == models.PostStatusPublished {
		s.popularityService.InitHeatScore(ctx, post.PostID)

		followers, err := s.relationService.GetFollowers(ctx, userID)
		if err == nil && len(followers) > 0 {
			go s.pushService.PushPostToFollowers(context.Background(), post, followers)
		}
	}

	return &models.CreatePostResponse{
		PostID: post.PostID,
		Status: post.Status,
	}, nil
}

type AsyncPostService struct {
	mongoDB           *storage.MongoDB
	asyncAuditService AuditServiceInterface
	asyncPushService  PushServiceInterface
	relationService   RelationServiceInterface
	popularityService PopularityServiceInterface
}

func NewPostServiceWithAsync(
	mongoDB *storage.MongoDB,
	asyncAuditService AuditServiceInterface,
	relationService RelationServiceInterface,
	asyncPushService PushServiceInterface,
	popularityService PopularityServiceInterface,
) *AsyncPostService {
	return &AsyncPostService{
		mongoDB:           mongoDB,
		asyncAuditService: asyncAuditService,
		asyncPushService:  asyncPushService,
		relationService:   relationService,
		popularityService: popularityService,
	}
}

func (s *AsyncPostService) CreatePost(ctx context.Context, userID string, req *models.CreatePostRequest) (*models.CreatePostResponse, error) {
	if req.Content == "" {
		return nil, ErrPostContentEmpty
	}

	postType := models.PostTypeText
	if len(req.Media) > 0 {
		if containsVideo(req.Media) {
			postType = models.PostTypeVideo
		} else {
			postType = models.PostTypeImage
		}
	}

	post := &models.Post{
		PostID:       "post_" + uuid.New().String()[:8],
		UserID:       userID,
		Content:      req.Content,
		Media:        req.Media,
		PostType:     postType,
		Status:       models.PostStatusPending,
		CreatedAt:    time.Now().UTC(),
		LikeCount:    0,
		CommentCount: 0,
		ShareCount:   0,
		HeatScore:    0,
	}

	_, err := s.mongoDB.Collections.Posts.InsertOne(ctx, post)
	if err != nil {
		return nil, err
	}

	log.Printf("AsyncPostService: Post created, enqueuing audit task for post %s", post.PostID)
	if auditErr := s.asyncAuditService.(interface{
		EnqueueAuditTask(ctx context.Context, post *models.Post) error
	}).EnqueueAuditTask(ctx, post); auditErr != nil {
		log.Printf("AsyncPostService: Failed to enqueue audit task: %v", auditErr)
	}

	if post.Status == models.PostStatusPublished {
		s.popularityService.InitHeatScore(ctx, post.PostID)

		followers, err := s.relationService.GetFollowers(ctx, userID)
		if err == nil && len(followers) > 0 {
			log.Printf("AsyncPostService: Enqueuing push task for post %s, followers: %d",
				post.PostID, len(followers))
			if pushErr := s.asyncPushService.EnqueuePushTask(ctx, post.PostID, userID, followers); pushErr != nil {
				log.Printf("AsyncPostService: Failed to enqueue push task: %v", pushErr)
			}
		}
	}

	return &models.CreatePostResponse{
		PostID: post.PostID,
		Status: models.PostStatusPending,
	}, nil
}

func (s *AsyncPostService) GetPostByID(ctx context.Context, postID string) (*models.Post, error) {
	var post models.Post
	filter := bson.M{"post_id": postID}
	err := s.mongoDB.Collections.Posts.FindOne(ctx, filter).Decode(&post)
	if err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, ErrPostNotFound
		}
		return nil, err
	}
	return &post, nil
}

func (s *AsyncPostService) UpdatePostCounts(ctx context.Context, postID string, likeDelta, commentDelta, shareDelta int64) error {
	filter := bson.M{"post_id": postID}
	update := bson.M{}

	if likeDelta != 0 {
		update["like_count"] = bson.M{"$inc": likeDelta}
	}
	if commentDelta != 0 {
		update["comment_count"] = bson.M{"$inc": commentDelta}
	}
	if shareDelta != 0 {
		update["share_count"] = bson.M{"$inc": shareDelta}
	}

	if len(update) == 0 {
		return nil
	}

	_, err := s.mongoDB.Collections.Posts.UpdateOne(ctx, filter, bson.M{"$set": update})
	return err
}

func (s *PostService) GetPostByID(ctx context.Context, postID string) (*models.Post, error) {
	var post models.Post
	filter := bson.M{"post_id": postID}
	err := s.mongoDB.Collections.Posts.FindOne(ctx, filter).Decode(&post)
	if err != nil {
		if err == mongo.ErrNoDocuments {
			return nil, ErrPostNotFound
		}
		return nil, err
	}
	return &post, nil
}

func (s *PostService) UpdatePostCounts(ctx context.Context, postID string, likeDelta, commentDelta, shareDelta int64) error {
	filter := bson.M{"post_id": postID}
	update := bson.M{}

	if likeDelta != 0 {
		update["like_count"] = bson.M{"$inc": likeDelta}
	}
	if commentDelta != 0 {
		update["comment_count"] = bson.M{"$inc": commentDelta}
	}
	if shareDelta != 0 {
		update["share_count"] = bson.M{"$inc": shareDelta}
	}

	if len(update) == 0 {
		return nil
	}

	_, err := s.mongoDB.Collections.Posts.UpdateOne(ctx, filter, bson.M{"$set": update})
	return err
}

func containsVideo(media []string) bool {
	videoExtensions := []string{".mp4", ".avi", ".mov", ".webm"}
	for _, m := range media {
		for _, ext := range videoExtensions {
			if len(m) >= len(ext) && m[len(m)-len(ext):] == ext {
				return true
			}
		}
	}
	return false
}
