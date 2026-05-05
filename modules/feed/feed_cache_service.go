package feed

import (
	"context"
	"encoding/json"
	"log"
	"time"

	"socialfeed/models"
	"socialfeed/storage"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo/options"
)

const (
	FeedCacheKeyPrefix   = "feed:cache:"
	FeedCacheTTL         = 5 * time.Minute
	FeedCacheMaxItems    = 100
)

type CachedFeedItem struct {
	FeedID       string          `json:"feed_id"`
	UserID       string          `json:"user_id"`
	PostID       string          `json:"post_id"`
	FeedPosition int64           `json:"feed_position"`
	InsertedAt   time.Time       `json:"inserted_at"`
	IsRead       bool            `json:"is_read"`
	Post         *models.Post    `json:"post,omitempty"`
}

type FeedCacheService struct {
	mongoDB *storage.MongoDB
	redisDB *storage.RedisDB
}

func NewFeedCacheService(
	mongoDB *storage.MongoDB,
	redisDB *storage.RedisDB,
) *FeedCacheService {
	return &FeedCacheService{
		mongoDB: mongoDB,
		redisDB: redisDB,
	}
}

func (s *FeedCacheService) GetFeedCacheKey(userID string) string {
	return FeedCacheKeyPrefix + userID
}

func (s *FeedCacheService) GetCachedFeed(
	ctx context.Context,
	userID string,
	page, pageSize int,
) ([]*CachedFeedItem, int64, error) {
	cacheKey := s.GetFeedCacheKey(userID)

	cachedData, err := s.redisDB.Client.Get(ctx, cacheKey).Result()
	if err == nil && cachedData != "" {
		var cachedFeed []*CachedFeedItem
		if err := json.Unmarshal([]byte(cachedData), &cachedFeed); err == nil {
			start := (page - 1) * pageSize
			end := start + pageSize

			if start > len(cachedFeed) {
				return []*CachedFeedItem{}, int64(len(cachedFeed)), nil
			}

			if end > len(cachedFeed) {
				end = len(cachedFeed)
			}

			return cachedFeed[start:end], int64(len(cachedFeed)), nil
		}
	}

	return nil, 0, nil
}

func (s *FeedCacheService) PrecomputeAndCacheFeed(
	ctx context.Context,
	userID string,
) error {
	feedFilter := bson.M{"user_id": userID}

	totalCount, err := s.mongoDB.Collections.Feeds.CountDocuments(ctx, feedFilter)
	if err != nil {
		log.Printf("FeedCacheService: Failed to count feeds for user %s: %v", userID, err)
		return err
	}

	if totalCount == 0 {
		return nil
	}

	findOpts := options.Find().
		SetSort(bson.M{"feed_position": -1}).
		SetLimit(int64(FeedCacheMaxItems))

	cursor, err := s.mongoDB.Collections.Feeds.Find(ctx, feedFilter, findOpts)
	if err != nil {
		log.Printf("FeedCacheService: Failed to find feeds for user %s: %v", userID, err)
		return err
	}
	defer cursor.Close(ctx)

	var feedItems []models.Feed
	if err := cursor.All(ctx, &feedItems); err != nil {
		log.Printf("FeedCacheService: Failed to decode feeds for user %s: %v", userID, err)
		return err
	}

	if len(feedItems) == 0 {
		return nil
	}

	postIDs := make([]string, 0, len(feedItems))
	for _, feedItem := range feedItems {
		postIDs = append(postIDs, feedItem.PostID)
	}

	postFilter := bson.M{"post_id": bson.M{"$in": postIDs}}
	postCursor, err := s.mongoDB.Collections.Posts.Find(ctx, postFilter)
	if err != nil {
		log.Printf("FeedCacheService: Failed to find posts for user %s: %v", userID, err)
		return err
	}
	defer postCursor.Close(ctx)

	var posts []models.Post
	if err := postCursor.All(ctx, &posts); err != nil {
		log.Printf("FeedCacheService: Failed to decode posts for user %s: %v", userID, err)
		return err
	}

	postMap := make(map[string]*models.Post)
	for i := range posts {
		postMap[posts[i].PostID] = &posts[i]
	}

	cachedItems := make([]*CachedFeedItem, 0, len(feedItems))
	for _, feedItem := range feedItems {
		cachedItem := &CachedFeedItem{
			FeedID:       feedItem.FeedID,
			UserID:       feedItem.UserID,
			PostID:       feedItem.PostID,
			FeedPosition: feedItem.FeedPosition,
			InsertedAt:   feedItem.InsertedAt,
			IsRead:       feedItem.IsRead,
		}
		if post, exists := postMap[feedItem.PostID]; exists {
			cachedItem.Post = post
		}
		cachedItems = append(cachedItems, cachedItem)
	}

	cacheKey := s.GetFeedCacheKey(userID)
	cacheData, err := json.Marshal(cachedItems)
	if err != nil {
		log.Printf("FeedCacheService: Failed to marshal cache data for user %s: %v", userID, err)
		return err
	}

	if err := s.redisDB.Client.Set(ctx, cacheKey, cacheData, FeedCacheTTL).Err(); err != nil {
		log.Printf("FeedCacheService: Failed to set cache for user %s: %v", userID, err)
		return err
	}

	log.Printf("FeedCacheService: Precomputed and cached feed for user %s, items: %d",
		userID, len(cachedItems))

	return nil
}

func (s *FeedCacheService) InvalidateFeedCache(
	ctx context.Context,
	userID string,
) error {
	cacheKey := s.GetFeedCacheKey(userID)
	if err := s.redisDB.Client.Del(ctx, cacheKey).Err(); err != nil {
		log.Printf("FeedCacheService: Failed to invalidate cache for user %s: %v", userID, err)
		return err
	}
	log.Printf("FeedCacheService: Invalidated cache for user %s", userID)
	return nil
}

func (s *FeedCacheService) InvalidateMultipleFeedCaches(
	ctx context.Context,
	userIDs []string,
) error {
	for _, userID := range userIDs {
		_ = s.InvalidateFeedCache(ctx, userID)
	}
	return nil
}

func (s *FeedCacheService) GetFeedCacheStats(
	ctx context.Context,
	userID string,
) (exists bool, itemCount int, ttl time.Duration) {
	cacheKey := s.GetFeedCacheKey(userID)

	exists, _ = s.redisDB.Client.Exists(ctx, cacheKey).Val() > 0
	if !exists {
		return false, 0, 0
	}

	ttl, _ = s.redisDB.Client.TTL(ctx, cacheKey).Result()

	cachedData, _ := s.redisDB.Client.Get(ctx, cacheKey).Result()
	if cachedData != "" {
		var cachedFeed []*CachedFeedItem
		if err := json.Unmarshal([]byte(cachedData), &cachedFeed); err == nil {
			itemCount = len(cachedFeed)
		}
	}

	return exists, itemCount, ttl
}

func (s *FeedCacheService) BatchPrecomputeFeedCaches(
	ctx context.Context,
	userIDs []string,
	concurrency int,
) error {
	if concurrency <= 0 {
		concurrency = 5
	}

	userCh := make(chan string, len(userIDs))
	errCh := make(chan error, len(userIDs))

	var worker func()
	worker = func() {
		for userID := range userCh {
			if err := s.PrecomputeAndCacheFeed(ctx, userID); err != nil {
				errCh <- err
			}
		}
	}

	for i := 0; i < concurrency; i++ {
		go worker()
	}

	for _, userID := range userIDs {
		userCh <- userID
	}
	close(userCh)

	var errors []error
	for i := 0; i < len(userIDs); i++ {
		select {
		case err := <-errCh:
			errors = append(errors, err)
		case <-ctx.Done():
			return ctx.Err()
		}
	}

	if len(errors) > 0 {
		log.Printf("FeedCacheService: Batch precompute completed with %d errors", len(errors))
	}

	return nil
}

func (s *FeedCacheService) RefreshFeedCacheOnPostUpdate(
	ctx context.Context,
	postID string,
	followerIDs []string,
) error {
	for _, followerID := range followerIDs {
		_ = s.InvalidateFeedCache(ctx, followerID)
		_ = s.PrecomputeAndCacheFeed(ctx, followerID)
	}
	return nil
}

func ConvertCachedFeedToFeedResponse(
	cachedItems []*CachedFeedItem,
	totalCount int64,
	page, pageSize int,
) *models.FeedListResponse {
	feedItems := make([]models.FeedItem, 0, len(cachedItems))
	for _, cached := range cachedItems {
		feedItem := models.FeedItem{
			FeedID:       cached.FeedID,
			UserID:       cached.UserID,
			PostID:       cached.PostID,
			FeedPosition: cached.FeedPosition,
			InsertedAt:   cached.InsertedAt,
			IsRead:       cached.IsRead,
		}
		if cached.Post != nil {
			feedItem.Post = cached.Post
		}
		feedItems = append(feedItems, feedItem)
	}

	totalPages := int64((totalCount + int64(pageSize) - 1) / int64(pageSize))
	if totalPages == 0 && totalCount > 0 {
		totalPages = 1
	}

	return &models.FeedListResponse{
		FeedItems:  feedItems,
		TotalCount: totalCount,
		Page:       page,
		PageSize:   pageSize,
		TotalPages: totalPages,
	}
}

func (s *FeedCacheService) MarkItemAsRead(
	ctx context.Context,
	userID, postID string,
) error {
	update := bson.M{
		"$set": bson.M{
			"is_read": true,
		},
	}

	filter := bson.M{
		"user_id": userID,
		"post_id": postID,
	}

	_, err := s.mongoDB.Collections.Feeds.UpdateOne(ctx, filter, update)
	if err != nil {
		log.Printf("FeedCacheService: Failed to mark feed as read for user %s, post %s: %v",
			userID, postID, err)
		return err
	}

	_ = s.InvalidateFeedCache(ctx, userID)

	return nil
}

func (s *FeedCacheService) GetCachedFeedPageInfo(
	ctx context.Context,
	userID string,
) (totalItems int, ttlSeconds int, cacheExists bool) {
	cacheKey := s.GetFeedCacheKey(userID)

	existsCmd := s.redisDB.Client.Exists(ctx, cacheKey)
	if existsCmd.Err() != nil || existsCmd.Val() == 0 {
		return 0, 0, false
	}

	ttlCmd := s.redisDB.Client.TTL(ctx, cacheKey)
	if ttlCmd.Err() != nil {
		return 0, 0, true
	}

	cachedData, err := s.redisDB.Client.Get(ctx, cacheKey).Result()
	if err != nil || cachedData == "" {
		return 0, int(ttlCmd.Val().Seconds()), true
	}

	var cachedFeed []*CachedFeedItem
	if err := json.Unmarshal([]byte(cachedData), &cachedFeed); err != nil {
		return 0, int(ttlCmd.Val().Seconds()), true
	}

	return len(cachedFeed), int(ttlCmd.Val().Seconds()), true
}

func (s *FeedCacheService) GetCacheStatsSummary(
	ctx context.Context,
	userIDs []string,
) map[string]interface{} {
	stats := make(map[string]interface{})
	stats["total_users_checked"] = len(userIDs)

	cachedCount := 0
	totalItems := 0
	minTTL := int64(999999)
	maxTTL := int64(0)

	for _, userID := range userIDs {
		exists, itemCount, ttl := s.GetFeedCacheStats(ctx, userID)
		if exists {
			cachedCount++
			totalItems += itemCount
			ttlSec := int64(ttl.Seconds())
			if ttlSec < minTTL {
				minTTL = ttlSec
			}
			if ttlSec > maxTTL {
				maxTTL = ttlSec
			}
		}
	}

	stats["cached_users"] = cachedCount
	stats["uncached_users"] = len(userIDs) - cachedCount
	stats["total_cached_items"] = totalItems
	if cachedCount > 0 {
		stats["avg_items_per_cache"] = float64(totalItems) / float64(cachedCount)
		stats["min_ttl_seconds"] = minTTL
		stats["max_ttl_seconds"] = maxTTL
		stats["avg_ttl_seconds"] = (minTTL + maxTTL) / 2
	}
	stats["cache_ttl_config"] = FeedCacheTTL.Seconds()
	stats["max_cache_items_config"] = FeedCacheMaxItems

	return stats
}

func (s *FeedCacheService) WarmUpCache(
	ctx context.Context,
	userIDs []string,
	concurrency int,
) map[string]interface{} {
	result := make(map[string]interface{})
	startTime := time.Now()

	if err := s.BatchPrecomputeFeedCaches(ctx, userIDs, concurrency); err != nil {
		result["error"] = err.Error()
	}

	elapsed := time.Since(startTime)
	result["elapsed_ms"] = elapsed.Milliseconds()
	result["users_processed"] = len(userIDs)

	successCount := 0
	for _, userID := range userIDs {
		exists, _, _ := s.GetFeedCacheStats(ctx, userID)
		if exists {
			successCount++
		}
	}
	result["success_count"] = successCount
	result["failure_count"] = len(userIDs) - successCount

	return result
}
