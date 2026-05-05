package interaction

import (
	"context"
	"errors"
	"socialfeed/models"
	"socialfeed/modules/notification"
	"socialfeed/modules/popularity"
	"socialfeed/modules/post"
	"socialfeed/storage"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

var (
	ErrPostNotPublished = errors.New("post is not published")
	ErrAlreadyLiked     = errors.New("user already liked this post")
)

type InteractionService struct {
	mongoDB             *storage.MongoDB
	postService         *post.PostService
	notificationService *notification.NotificationService
	popularityService   *popularity.PopularityService
}

func NewInteractionService(
	mongoDB *storage.MongoDB,
	postService *post.PostService,
	notificationService *notification.NotificationService,
	popularityService *popularity.PopularityService,
) *InteractionService {
	return &InteractionService{
		mongoDB:             mongoDB,
		postService:         postService,
		notificationService: notificationService,
		popularityService:   popularityService,
	}
}

func (s *InteractionService) HandleInteraction(ctx context.Context, userID string, req *models.InteractRequest) (*models.InteractResponse, error) {
	post, err := s.postService.GetPostByID(ctx, req.PostID)
	if err != nil {
		return nil, err
	}

	if post.Status != models.PostStatusPublished {
		return nil, ErrPostNotPublished
	}

	switch req.InteractionType {
	case models.InteractionTypeLike:
		return s.handleLike(ctx, userID, post, req)
	case models.InteractionTypeComment:
		return s.handleComment(ctx, userID, post, req)
	case models.InteractionTypeShare:
		return s.handleShare(ctx, userID, post, req)
	default:
		return s.handleGeneralInteraction(ctx, userID, post, req)
	}
}

func (s *InteractionService) handleLike(ctx context.Context, userID string, post *models.Post, req *models.InteractRequest) (*models.InteractResponse, error) {
	existingLike, err := s.getUserInteraction(ctx, userID, post.PostID, models.InteractionTypeLike)
	if err != nil && err != mongo.ErrNoDocuments {
		return nil, err
	}

	if existingLike != nil {
		return nil, ErrAlreadyLiked
	}

	interaction := &models.Interaction{
		InteractionID:   "interact_" + uuid.New().String()[:8],
		PostID:          post.PostID,
		UserID:          userID,
		InteractionType: models.InteractionTypeLike,
		CreatedAt:       time.Now().UTC(),
	}

	_, err = s.mongoDB.Collections.Interactions.InsertOne(ctx, interaction)
	if err != nil {
		return nil, err
	}

	err = s.postService.UpdatePostCounts(ctx, post.PostID, 1, 0, 0)
	if err != nil {
		return nil, err
	}

	if post.UserID != userID {
		go s.notificationService.SendLikeNotification(context.Background(), post.UserID, userID, post.PostID)
	}

	go s.popularityService.UpdateHeatScoreOnInteraction(context.Background(), post.PostID, models.InteractionTypeLike)

	return &models.InteractResponse{
		InteractionID: interaction.InteractionID,
	}, nil
}

func (s *InteractionService) handleComment(ctx context.Context, userID string, post *models.Post, req *models.InteractRequest) (*models.InteractResponse, error) {
	if req.Content == nil || *req.Content == "" {
		return nil, errors.New("comment content is required")
	}

	comment := &models.Comment{
		CommentID: "comment_" + uuid.New().String()[:8],
		PostID:    post.PostID,
		UserID:    userID,
		Content:   *req.Content,
		CreatedAt: time.Now().UTC(),
		LikeCount: 0,
		Status:    models.CommentStatusVisible,
	}

	_, err := s.mongoDB.Collections.Comments.InsertOne(ctx, comment)
	if err != nil {
		return nil, err
	}

	interaction := &models.Interaction{
		InteractionID:   "interact_" + uuid.New().String()[:8],
		PostID:          post.PostID,
		UserID:          userID,
		InteractionType: models.InteractionTypeComment,
		Content:         req.Content,
		CreatedAt:       time.Now().UTC(),
	}

	_, err = s.mongoDB.Collections.Interactions.InsertOne(ctx, interaction)
	if err != nil {
		return nil, err
	}

	err = s.postService.UpdatePostCounts(ctx, post.PostID, 0, 1, 0)
	if err != nil {
		return nil, err
	}

	if post.UserID != userID {
		go s.notificationService.SendCommentNotification(context.Background(), post.UserID, userID, post.PostID, *req.Content)
	}

	go s.popularityService.UpdateHeatScoreOnInteraction(context.Background(), post.PostID, models.InteractionTypeComment)

	return &models.InteractResponse{
		InteractionID: interaction.InteractionID,
	}, nil
}

func (s *InteractionService) handleShare(ctx context.Context, userID string, post *models.Post, req *models.InteractRequest) (*models.InteractResponse, error) {
	interaction := &models.Interaction{
		InteractionID:   "interact_" + uuid.New().String()[:8],
		PostID:          post.PostID,
		UserID:          userID,
		InteractionType: models.InteractionTypeShare,
		Content:         req.Content,
		CreatedAt:       time.Now().UTC(),
	}

	_, err := s.mongoDB.Collections.Interactions.InsertOne(ctx, interaction)
	if err != nil {
		return nil, err
	}

	err = s.postService.UpdatePostCounts(ctx, post.PostID, 0, 0, 1)
	if err != nil {
		return nil, err
	}

	if post.UserID != userID {
		go s.notificationService.SendShareNotification(context.Background(), post.UserID, userID, post.PostID)
	}

	go s.popularityService.UpdateHeatScoreOnInteraction(context.Background(), post.PostID, models.InteractionTypeShare)

	return &models.InteractResponse{
		InteractionID: interaction.InteractionID,
	}, nil
}

func (s *InteractionService) handleGeneralInteraction(ctx context.Context, userID string, post *models.Post, req *models.InteractRequest) (*models.InteractResponse, error) {
	interaction := &models.Interaction{
		InteractionID:   "interact_" + uuid.New().String()[:8],
		PostID:          post.PostID,
		UserID:          userID,
		InteractionType: req.InteractionType,
		Content:         req.Content,
		CreatedAt:       time.Now().UTC(),
	}

	_, err := s.mongoDB.Collections.Interactions.InsertOne(ctx, interaction)
	if err != nil {
		return nil, err
	}

	go s.popularityService.UpdateHeatScoreOnInteraction(context.Background(), post.PostID, req.InteractionType)

	return &models.InteractResponse{
		InteractionID: interaction.InteractionID,
	}, nil
}

func (s *InteractionService) getUserInteraction(ctx context.Context, userID, postID string, interactionType models.InteractionType) (*models.Interaction, error) {
	var interaction models.Interaction
	filter := bson.M{
		"user_id":          userID,
		"post_id":          postID,
		"interaction_type": interactionType,
	}
	err := s.mongoDB.Collections.Interactions.FindOne(ctx, filter).Decode(&interaction)
	if err != nil {
		return nil, err
	}
	return &interaction, nil
}

func (s *InteractionService) GetPostComments(ctx context.Context, postID string, page, pageSize int64) ([]models.Comment, int64, error) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	skip := (page - 1) * pageSize

	filter := bson.M{
		"post_id": postID,
		"status":  models.CommentStatusVisible,
	}

	total, err := s.mongoDB.Collections.Comments.CountDocuments(ctx, filter)
	if err != nil {
		return nil, 0, err
	}

	findOpts := options.Find().
		SetSort(bson.M{"created_at": -1}).
		SetSkip(skip).
		SetLimit(pageSize)

	cursor, err := s.mongoDB.Collections.Comments.Find(ctx, filter, findOpts)
	if err != nil {
		return nil, 0, err
	}
	defer cursor.Close(ctx)

	var comments []models.Comment
	for cursor.Next(ctx) {
		var comment models.Comment
		if err := cursor.Decode(&comment); err != nil {
			continue
		}
		comments = append(comments, comment)
	}

	return comments, total, nil
}
