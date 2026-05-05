package feed

import (
	"context"
	"socialfeed/models"
	"socialfeed/storage"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type FeedService struct {
	mongoDB *storage.MongoDB
	redisDB *storage.RedisDB
}

func NewFeedService(mongoDB *storage.MongoDB, redisDB *storage.RedisDB) *FeedService {
	return &FeedService{
		mongoDB: mongoDB,
		redisDB: redisDB,
	}
}

func (s *FeedService) GetFeedList(ctx context.Context, req *models.FeedListRequest) (*models.FeedListResponse, error) {
	page := req.Page
	if page < 1 {
		page = 1
	}
	pageSize := req.PageSize
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	skip := (page - 1) * pageSize

	filter := bson.M{"user_id": req.UserID}

	total, err := s.mongoDB.Collections.Feeds.CountDocuments(ctx, filter)
	if err != nil {
		return nil, err
	}

	findOpts := options.Find().
		SetSort(bson.M{"feed_position": -1}).
		SetSkip(int64(skip)).
		SetLimit(int64(pageSize))

	cursor, err := s.mongoDB.Collections.Feeds.Find(ctx, filter, findOpts)
	if err != nil {
		return nil, err
	}
	defer cursor.Close(ctx)

	var feedItems []models.Feed
	for cursor.Next(ctx) {
		var item models.Feed
		if err := cursor.Decode(&item); err != nil {
			continue
		}
		feedItems = append(feedItems, item)
	}

	posts := make([]models.Post, 0, len(feedItems))
	for _, item := range feedItems {
		post, err := s.getPostByID(ctx, item.PostID)
		if err == nil {
			posts = append(posts, *post)
		}
	}

	return &models.FeedListResponse{
		Feed:  posts,
		Total: total,
	}, nil
}

func (s *FeedService) getPostByID(ctx context.Context, postID string) (*models.Post, error) {
	var post models.Post
	filter := bson.M{"post_id": postID}
	err := s.mongoDB.Collections.Posts.FindOne(ctx, filter).Decode(&post)
	if err != nil {
		return nil, err
	}
	return &post, nil
}

func (s *FeedService) MarkAsRead(ctx context.Context, userID, postID string) error {
	filter := bson.M{
		"user_id": userID,
		"post_id": postID,
	}
	update := bson.M{"$set": bson.M{"is_read": true}}
	_, err := s.mongoDB.Collections.Feeds.UpdateOne(ctx, filter, update)
	return err
}

func (s *FeedService) CleanupExpiredFeed(ctx context.Context, userID string, keepCount int64) error {
	filter := bson.M{"user_id": userID}

	findOpts := options.Find().
		SetSort(bson.M{"feed_position": 1}).
		SetLimit(1000)

	cursor, err := s.mongoDB.Collections.Feeds.Find(ctx, filter, findOpts)
	if err != nil {
		return err
	}
	defer cursor.Close(ctx)

	var feedIDs []string
	count := int64(0)
	for cursor.Next(ctx) {
		var item models.Feed
		if err := cursor.Decode(&item); err != nil {
			continue
		}
		count++
		if count > keepCount {
			feedIDs = append(feedIDs, item.FeedID)
		}
	}

	if len(feedIDs) > 0 {
		_, err = s.mongoDB.Collections.Feeds.DeleteMany(ctx, bson.M{"feed_id": bson.M{"$in": feedIDs}})
		return err
	}

	return nil
}
