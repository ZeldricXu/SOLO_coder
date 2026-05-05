package popularity

import (
	"context"
	"socialfeed/models"
	"socialfeed/storage"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type PopularityService struct {
	mongoDB *storage.MongoDB
	redisDB *storage.RedisDB
}

func NewPopularityService(mongoDB *storage.MongoDB, redisDB *storage.RedisDB) *PopularityService {
	return &PopularityService{
		mongoDB: mongoDB,
		redisDB: redisDB,
	}
}

func (s *PopularityService) InitHeatScore(ctx context.Context, postID string) error {
	filter := bson.M{"post_id": postID}
	update := bson.M{"$set": bson.M{"heat_score": 0.0}}
	_, err := s.mongoDB.Collections.Posts.UpdateOne(ctx, filter, update)
	return err
}

func (s *PopularityService) CalculateHeatScore(ctx context.Context, postID string) error {
	var post models.Post
	filter := bson.M{"post_id": postID}
	err := s.mongoDB.Collections.Posts.FindOne(ctx, filter).Decode(&post)
	if err != nil {
		return err
	}

	now := time.Now().UTC()
	hoursSinceCreated := now.Sub(post.CreatedAt).Hours()
	if hoursSinceCreated < 1 {
		hoursSinceCreated = 1
	}

	heatScore := s.computeHeatScore(
		post.LikeCount,
		post.CommentCount,
		post.ShareCount,
		hoursSinceCreated,
	)

	update := bson.M{"$set": bson.M{"heat_score": heatScore}}
	_, err = s.mongoDB.Collections.Posts.UpdateOne(ctx, filter, update)
	return err
}

func (s *PopularityService) computeHeatScore(likes, comments, shares int64, hours float64) float64 {
	likeWeight := 1.0
	commentWeight := 2.0
	shareWeight := 3.0
	timeDecayFactor := 0.98

	score := float64(likes)*likeWeight + float64(comments)*commentWeight + float64(shares)*shareWeight
	decayFactor := 1.0
	if hours > 1 {
		decayFactor = float64(timeDecayFactor)
		for i := 1.0; i < hours; i++ {
			decayFactor *= timeDecayFactor
		}
	}
	return score * decayFactor
}

func (s *PopularityService) UpdateHeatScoreOnInteraction(ctx context.Context, postID string, interactionType models.InteractionType) error {
	var delta float64
	switch interactionType {
	case models.InteractionTypeLike:
		delta = 1.0
	case models.InteractionTypeComment:
		delta = 2.0
	case models.InteractionTypeShare:
		delta = 3.0
	default:
		delta = 0.5
	}

	filter := bson.M{"post_id": postID}
	update := bson.M{"$inc": bson.M{"heat_score": delta}}
	_, err := s.mongoDB.Collections.Posts.UpdateOne(ctx, filter, update)
	return err
}

func (s *PopularityService) GetTopHotPosts(ctx context.Context, limit int64) ([]models.Post, error) {
	filter := bson.M{
		"status": models.PostStatusPublished,
	}
	findOpts := options.Find().
		SetSort(bson.M{"heat_score": -1}).
		SetLimit(limit)

	cursor, err := s.mongoDB.Collections.Posts.Find(ctx, filter, findOpts)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var posts []models.Post
	for cursor.Next(ctx) {
		var post models.Post
		if err := cursor.Decode(&post); err != nil {
			continue
		}
		posts = append(posts, post)
	}

	return posts, nil
}
