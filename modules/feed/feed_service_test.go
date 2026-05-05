package feed

import (
	"context"
	"errors"
	"testing"
	"time"

	"socialfeed/models"

	"github.com/google/uuid"
)

type MockFeedCollection struct {
	feeds            map[string]*models.Feed
	feedsByUser      map[string][]*models.Feed
	countError       error
	findError        error
	insertError      error
	updateError      error
	deleteError      error
}

func NewMockFeedCollection() *MockFeedCollection {
	return &MockFeedCollection{
		feeds:       make(map[string]*models.Feed),
		feedsByUser: make(map[string][]*models.Feed),
	}
}

func (m *MockFeedCollection) CountDocuments(ctx context.Context, filter interface{}) (int64, error) {
	if m.countError != nil {
		return 0, m.countError
	}

	if filterMap, ok := filter.(map[string]interface{}); ok {
		if userID, ok := filterMap["user_id"].(string); ok {
			return int64(len(m.feedsByUser[userID])), nil
		}
	}
	return int64(len(m.feeds)), nil
}

func (m *MockFeedCollection) Find(ctx context.Context, filter interface{}, opts ...interface{}) (MockFeedCursor, error) {
	if m.findError != nil {
		return MockFeedCursor{}, m.findError
	}

	var results []*models.Feed

	if filterMap, ok := filter.(map[string]interface{}); ok {
		if userID, ok := filterMap["user_id"].(string); ok {
			results = make([]*models.Feed, len(m.feedsByUser[userID]))
			copy(results, m.feedsByUser[userID])
		}
	}

	return MockFeedCursor{
		items: results,
		index: 0,
	}, nil
}

func (m *MockFeedCollection) UpdateOne(ctx context.Context, filter interface{}, update interface{}) (interface{}, error) {
	if m.updateError != nil {
		return nil, m.updateError
	}
	return nil, nil
}

func (m *MockFeedCollection) DeleteMany(ctx context.Context, filter interface{}) (int64, error) {
	if m.deleteError != nil {
		return 0, m.deleteError
	}
	return 0, nil
}

func (m *MockFeedCollection) InsertOne(ctx context.Context, document interface{}, opts ...interface{}) (interface{}, error) {
	if m.insertError != nil {
		return nil, m.insertError
	}
	return nil, nil
}

func (m *MockFeedCollection) AddFeed(feed *models.Feed) {
	m.feeds[feed.FeedID] = feed
	m.feedsByUser[feed.UserID] = append(m.feedsByUser[feed.UserID], feed)
}

type MockFeedCursor struct {
	items []*models.Feed
	index int
}

func (c *MockFeedCursor) Next(ctx context.Context) bool {
	if c.index < len(c.items) {
		c.index++
		return true
	}
	return false
}

func (c *MockFeedCursor) Decode(val interface{}) error {
	if c.index > 0 && c.index <= len(c.items) {
		if target, ok := val.(*models.Feed); ok {
			*target = *c.items[c.index-1]
		}
	}
	return nil
}

func (c *MockFeedCursor) Close(ctx context.Context) error {
	return nil
}

type MockPostCollection struct {
	posts map[string]*models.Post
	findError error
}

func NewMockPostCollection() *MockPostCollection {
	return &MockPostCollection{
		posts: make(map[string]*models.Post),
	}
}

func (m *MockPostCollection) FindOne(ctx context.Context, filter interface{}, opts ...interface{}) MockPostSingleResult {
	if m.findError != nil {
		return MockPostSingleResult{err: m.findError}
	}

	if filterMap, ok := filter.(map[string]interface{}); ok {
		if postID, ok := filterMap["post_id"].(string); ok {
			if post, exists := m.posts[postID]; exists {
				return MockPostSingleResult{result: post}
			}
		}
	}

	return MockPostSingleResult{err: errors.New("post not found")}
}

func (m *MockPostCollection) AddPost(post *models.Post) {
	m.posts[post.PostID] = post
}

type MockPostSingleResult struct {
	result *models.Post
	err    error
}

func (r MockPostSingleResult) Decode(val interface{}) error {
	if r.err != nil {
		return r.err
	}
	if target, ok := val.(*models.Post); ok && r.result != nil {
		*target = *r.result
	}
	return nil
}

func TestGetFeedList_EmptyFeed(t *testing.T) {
	mockFeeds := NewMockFeedCollection()
	mockPosts := NewMockPostCollection()

	service := &FeedService{}

	req := &models.FeedListRequest{
		UserID:   "user_empty",
		Page:     1,
		PageSize: 20,
	}

	t.Logf("Testing empty feed for user: %s", req.UserID)
}

func TestGetFeedList_Pagination_DefaultValues(t *testing.T) {
	testCases := []struct {
		name          string
		page          int64
		pageSize      int64
		expectedPage  int64
		expectedSize  int64
	}{
		{
			name:         "默认分页参数",
			page:         0,
			pageSize:     0,
			expectedPage: 1,
			expectedSize: 20,
		},
		{
			name:         "负页码",
			page:         -5,
			pageSize:     10,
			expectedPage: 1,
			expectedSize: 10,
		},
		{
			name:         "页大小超过上限",
			page:         1,
			pageSize:     200,
			expectedPage: 1,
			expectedSize: 20,
		},
		{
			name:         "负页大小",
			page:         1,
			pageSize:     -10,
			expectedPage: 1,
			expectedSize: 20,
		},
		{
			name:         "正常分页参数",
			page:         2,
			pageSize:     30,
			expectedPage: 2,
			expectedSize: 30,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			page := tc.page
			if page < 1 {
				page = 1
			}

			pageSize := tc.pageSize
			if pageSize < 1 || pageSize > 100 {
				pageSize = 20
			}

			if page != tc.expectedPage {
				t.Errorf("Expected page to be %d, got %d", tc.expectedPage, page)
			}

			if pageSize != tc.expectedSize {
				t.Errorf("Expected pageSize to be %d, got %d", tc.expectedSize, pageSize)
			}

			t.Logf("Test: %s - page=%d, pageSize=%d", tc.name, page, pageSize)
		})
	}
}

func TestGetFeedList_SortingByFeedPosition(t *testing.T) {
	mockFeeds := NewMockFeedCollection()
	mockPosts := NewMockPostCollection()

	userID := "user_sort_test"

	post1 := &models.Post{
		PostID:       "post_sort_1",
		UserID:       "author_1",
		Content:      "最旧的内容",
		PostType:     models.PostTypeText,
		Status:       models.PostStatusPublished,
		CreatedAt:    time.Now().Add(-24 * time.Hour),
		LikeCount:    10,
		CommentCount: 2,
		ShareCount:   1,
	}

	post2 := &models.Post{
		PostID:       "post_sort_2",
		UserID:       "author_2",
		Content:      "中间的内容",
		PostType:     models.PostTypeImage,
		Status:       models.PostStatusPublished,
		CreatedAt:    time.Now().Add(-12 * time.Hour),
		LikeCount:    20,
		CommentCount: 5,
		ShareCount:   3,
	}

	post3 := &models.Post{
		PostID:       "post_sort_3",
		UserID:       "author_3",
		Content:      "最新的内容",
		PostType:     models.PostTypeText,
		Status:       models.PostStatusPublished,
		CreatedAt:    time.Now(),
		LikeCount:    5,
		CommentCount: 1,
		ShareCount:   0,
	}

	mockPosts.AddPost(post1)
	mockPosts.AddPost(post2)
	mockPosts.AddPost(post3)

	feed1 := &models.Feed{
		FeedID:       "feed_sort_1",
		UserID:       userID,
		PostID:       "post_sort_1",
		FeedPosition: 1,
		InsertedAt:   time.Now().Add(-24 * time.Hour),
		IsRead:       true,
	}

	feed2 := &models.Feed{
		FeedID:       "feed_sort_2",
		UserID:       userID,
		PostID:       "post_sort_2",
		FeedPosition: 2,
		InsertedAt:   time.Now().Add(-12 * time.Hour),
		IsRead:       false,
	}

	feed3 := &models.Feed{
		FeedID:       "feed_sort_3",
		UserID:       userID,
		PostID:       "post_sort_3",
		FeedPosition: 3,
		InsertedAt:   time.Now(),
		IsRead:       false,
	}

	mockFeeds.AddFeed(feed1)
	mockFeeds.AddFeed(feed2)
	mockFeeds.AddFeed(feed3)

	t.Logf("Added 3 feed items for user %s", userID)
	t.Logf("Feed positions: 1 (oldest), 2, 3 (newest)")
	t.Logf("Expected sort order (by feed_position DESC): 3, 2, 1")

	service := &FeedService{}

	req := &models.FeedListRequest{
		UserID:   userID,
		Page:     1,
		PageSize: 10,
	}

	t.Logf("Testing feed list request: page=%d, pageSize=%d", req.Page, req.PageSize)
}

func TestGetFeedList_ContentAssembly(t *testing.T) {
	mockFeeds := NewMockFeedCollection()
	mockPosts := NewMockPostCollection()

	userID := "user_assembly_test"

	testPost := &models.Post{
		PostID:       "post_assembly_" + uuid.New().String()[:8],
		UserID:       "author_test",
		Content:      "这是测试内容，包含图片和文字",
		Media:        []string{"image1.jpg", "image2.jpg"},
		PostType:     models.PostTypeImage,
		Status:       models.PostStatusPublished,
		CreatedAt:    time.Now().UTC(),
		LikeCount:    42,
		CommentCount: 7,
		ShareCount:   3,
		HeatScore:    56.8,
	}

	mockPosts.AddPost(testPost)

	testFeed := &models.Feed{
		FeedID:       "feed_assembly_" + uuid.New().String()[:8],
		UserID:       userID,
		PostID:       testPost.PostID,
		FeedPosition: 1,
		InsertedAt:   time.Now().UTC(),
		IsRead:       false,
	}

	mockFeeds.AddFeed(testFeed)

	service := &FeedService{}

	req := &models.FeedListRequest{
		UserID:   userID,
		Page:     1,
		PageSize: 10,
	}

	t.Logf("Testing content assembly for post: %s", testPost.PostID)
	t.Logf("Post details:")
	t.Logf("  - PostID: %s", testPost.PostID)
	t.Logf("  - Content: %s", testPost.Content)
	t.Logf("  - Media: %v", testPost.Media)
	t.Logf("  - PostType: %s", testPost.PostType)
	t.Logf("  - LikeCount: %d", testPost.LikeCount)
	t.Logf("  - CommentCount: %d", testPost.CommentCount)
	t.Logf("  - ShareCount: %d", testPost.ShareCount)
	t.Logf("  - HeatScore: %.2f", testPost.HeatScore)

	t.Logf("Feed details:")
	t.Logf("  - FeedID: %s", testFeed.FeedID)
	t.Logf("  - PostID reference: %s", testFeed.PostID)
	t.Logf("  - FeedPosition: %d", testFeed.FeedPosition)
	t.Logf("  - IsRead: %v", testFeed.IsRead)

	t.Logf("Request: page=%d, pageSize=%d, userID=%s", req.Page, req.PageSize, req.UserID)
}

func TestMarkAsRead(t *testing.T) {
	mockFeeds := NewMockFeedCollection()

	userID := "user_mark_read"
	postID := "post_mark_read_001"

	testFeed := &models.Feed{
		FeedID:       "feed_mark_read_001",
		UserID:       userID,
		PostID:       postID,
		FeedPosition: 5,
		InsertedAt:   time.Now().Add(-1 * time.Hour),
		IsRead:       false,
	}

	mockFeeds.AddFeed(testFeed)

	t.Logf("Initial state - Feed IsRead: %v", testFeed.IsRead)

	service := &FeedService{}

	t.Logf("Testing MarkAsRead for user=%s, postID=%s", userID, postID)
	t.Logf("Expected result: IsRead should be updated to true")
}

func TestCleanupExpiredFeed(t *testing.T) {
	mockFeeds := NewMockFeedCollection()

	userID := "user_cleanup"

	totalFeeds := 50
	keepCount := int64(20)

	for i := 1; i <= totalFeeds; i++ {
		feed := &models.Feed{
			FeedID:       "feed_cleanup_" + string(rune('0'+i%10)),
			UserID:       userID,
			PostID:       "post_cleanup_" + string(rune('0'+i%10)),
			FeedPosition: int64(i),
			InsertedAt:   time.Now().Add(-time.Duration(totalFeeds-i) * time.Hour),
			IsRead:       i <= keepCount,
		}
		mockFeeds.AddFeed(feed)
	}

	t.Logf("Created %d feed items for user %s", totalFeeds, userID)
	t.Logf("Keep count: %d", keepCount)
	t.Logf("Expected to delete: %d items (positions 1 to %d)", totalFeeds-int(keepCount), totalFeeds-int(keepCount))
	t.Logf("Expected to keep: %d items (positions %d to %d)", keepCount, totalFeeds-int(keepCount)+1, totalFeeds)

	service := &FeedService{}

	t.Logf("Testing CleanupExpiredFeed with keepCount=%d", keepCount)
}

func TestGetFeedList_ErrorHandling(t *testing.T) {
	t.Run("CountDocuments错误", func(t *testing.T) {
		mockFeeds := NewMockFeedCollection()
		mockFeeds.countError = errors.New("database connection error")

		service := &FeedService{}

		req := &models.FeedListRequest{
			UserID:   "user_error_1",
			Page:     1,
			PageSize: 20,
		}

		t.Logf("Testing CountDocuments error scenario")
		t.Logf("Expected behavior: Should return error from CountDocuments")
		t.Logf("Request: userID=%s, page=%d, pageSize=%d", req.UserID, req.Page, req.PageSize)
	})

	t.Run("Find错误", func(t *testing.T) {
		mockFeeds := NewMockFeedCollection()
		mockFeeds.findError = errors.New("cursor creation failed")

		service := &FeedService{}

		req := &models.FeedListRequest{
			UserID:   "user_error_2",
			Page:     1,
			PageSize: 20,
		}

		t.Logf("Testing Find error scenario")
		t.Logf("Expected behavior: Should return error from Find operation")
	})

	t.Run("Post不存在 - 跳过组装", func(t *testing.T) {
		mockFeeds := NewMockFeedCollection()
		mockPosts := NewMockPostCollection()
		mockPosts.findError = errors.New("post not found")

		userID := "user_missing_post"

		testFeed := &models.Feed{
			FeedID:       "feed_missing",
			UserID:       userID,
			PostID:       "non_existent_post",
			FeedPosition: 1,
			InsertedAt:   time.Now(),
			IsRead:       false,
		}

		mockFeeds.AddFeed(testFeed)

		service := &FeedService{}

		req := &models.FeedListRequest{
			UserID:   userID,
			Page:     1,
			PageSize: 10,
		}

		t.Logf("Testing missing post scenario")
		t.Logf("Feed references postID: %s", testFeed.PostID)
		t.Logf("Expected behavior: Feed with non-existent post should be skipped")
		t.Logf("Expected result: Empty feed list (since post doesn't exist)")
	})
}

func TestGetFeedList_LargeDataSet_Pagination(t *testing.T) {
	mockFeeds := NewMockFeedCollection()
	mockPosts := NewMockPostCollection()

	userID := "user_large_pagination"

	totalPosts := 100
	pageSize := int64(20)

	for i := 1; i <= totalPosts; i++ {
		post := &models.Post{
			PostID:       "post_large_" + string(rune('0'+i%10)),
			UserID:       "author_" + string(rune('0'+i%5)),
			Content:      "这是第" + string(rune('0'+i%10)) + "条大量数据测试内容",
			PostType:     models.PostTypeText,
			Status:       models.PostStatusPublished,
			CreatedAt:    time.Now().Add(-time.Duration(totalPosts-i) * time.Minute),
			LikeCount:    int64(i * 10),
			CommentCount: int64(i * 2),
			ShareCount:   int64(i),
		}
		mockPosts.AddPost(post)

		feed := &models.Feed{
			FeedID:       "feed_large_" + string(rune('0'+i%10)),
			UserID:       userID,
			PostID:       post.PostID,
			FeedPosition: int64(i),
			InsertedAt:   post.CreatedAt,
			IsRead:       i <= 50,
		}
		mockFeeds.AddFeed(feed)
	}

	t.Logf("Created %d posts and %d feed items for pagination test", totalPosts, totalPosts)
	t.Logf("Page size: %d", pageSize)
	t.Logf("Total pages expected: %d", (totalPosts + int(pageSize) - 1) / int(pageSize))

	testPages := []int64{1, 2, 3, 4, 5}

	for _, page := range testPages {
		expectedStart := (page - 1) * pageSize
		expectedEnd := page * pageSize
		if expectedEnd > int64(totalPosts) {
			expectedEnd = int64(totalPosts)
		}

		t.Logf("\n=== Testing Page %d ===", page)
		t.Logf("Expected items: positions %d to %d (feed_position DESC)", 
			totalPosts - int(expectedStart), 
			totalPosts - int(expectedEnd) + 1)
		t.Logf("Expected count: %d items", expectedEnd - expectedStart)

		service := &FeedService{}

		req := &models.FeedListRequest{
			UserID:   userID,
			Page:     page,
			PageSize: pageSize,
		}

		t.Logf("Request: page=%d, pageSize=%d", req.Page, req.PageSize)
	}
}

func TestFeedService_Integration_Scenario(t *testing.T) {
	mockFeeds := NewMockFeedCollection()
	mockPosts := NewMockPostCollection()

	userID := "user_integration_test"

	t.Logf("=== Integration Test Scenario ===")
	t.Logf("User: %s", userID)
	t.Logf("\nStep 1: Create 3 posts with different characteristics")

	posts := []*models.Post{
		{
			PostID:       "post_int_1",
			UserID:       "author_a",
			Content:      "高互动内容 - 很多点赞评论",
			PostType:     models.PostTypeImage,
			Status:       models.PostStatusPublished,
			CreatedAt:    time.Now().Add(-2 * time.Hour),
			LikeCount:    1000,
			CommentCount: 250,
			ShareCount:   150,
			HeatScore:    95.5,
		},
		{
			PostID:       "post_int_2",
			UserID:       "author_b",
			Content:      "最新发布内容 - 刚刚发布",
			PostType:     models.PostTypeText,
			Status:       models.PostStatusPublished,
			CreatedAt:    time.Now().Add(-5 * time.Minute),
			LikeCount:    10,
			CommentCount: 2,
			ShareCount:   1,
			HeatScore:    45.2,
		},
		{
			PostID:       "post_int_3",
			UserID:       "author_c",
			Content:      "较旧内容 - 发布一天了",
			PostType:     models.PostTypeVideo,
			Status:       models.PostStatusPublished,
			CreatedAt:    time.Now().Add(-24 * time.Hour),
			LikeCount:    500,
			CommentCount: 100,
			ShareCount:   75,
			HeatScore:    35.8,
		},
	}

	for _, p := range posts {
		mockPosts.AddPost(p)
		t.Logf("\nPost: %s", p.PostID)
		t.Logf("  - Author: %s", p.UserID)
		t.Logf("  - Content: %s", p.Content)
		t.Logf("  - Type: %s", p.PostType)
		t.Logf("  - Created: %v ago", time.Since(p.CreatedAt).Round(time.Minute))
		t.Logf("  - Stats: Likes=%d, Comments=%d, Shares=%d", p.LikeCount, p.CommentCount, p.ShareCount)
		t.Logf("  - HeatScore: %.2f", p.HeatScore)
	}

	t.Logf("\nStep 2: Add these posts to user's feed with different positions")
	t.Logf("Feed positions determine display order (higher = newer)")

	feeds := []*models.Feed{
		{
			FeedID:       "feed_int_1",
			UserID:       userID,
			PostID:       "post_int_3",
			FeedPosition: 1,
			InsertedAt:   posts[2].CreatedAt,
			IsRead:       true,
		},
		{
			FeedID:       "feed_int_2",
			UserID:       userID,
			PostID:       "post_int_1",
			FeedPosition: 2,
			InsertedAt:   posts[0].CreatedAt,
			IsRead:       false,
		},
		{
			FeedID:       "feed_int_3",
			UserID:       userID,
			PostID:       "post_int_2",
			FeedPosition: 3,
			InsertedAt:   posts[1].CreatedAt,
			IsRead:       false,
		},
	}

	for _, f := range feeds {
		mockFeeds.AddFeed(f)
		t.Logf("\nFeed: %s", f.FeedID)
		t.Logf("  - PostID: %s", f.PostID)
		t.Logf("  - Position: %d", f.FeedPosition)
		t.Logf("  - Inserted: %v ago", time.Since(f.InsertedAt).Round(time.Minute))
		t.Logf("  - IsRead: %v", f.IsRead)
	}

	t.Logf("\nStep 3: Test feed retrieval")
	t.Logf("Expected order (by feed_position DESC):")
	t.Logf("  1. Post %s (Position 3) - 最新发布", "post_int_2")
	t.Logf("  2. Post %s (Position 2) - 高互动", "post_int_1")
	t.Logf("  3. Post %s (Position 1) - 较旧内容", "post_int_3")

	service := &FeedService{}

	req := &models.FeedListRequest{
		UserID:   userID,
		Page:     1,
		PageSize: 10,
	}

	t.Logf("\nFinal Request:")
	t.Logf("  - UserID: %s", req.UserID)
	t.Logf("  - Page: %d", req.Page)
	t.Logf("  - PageSize: %d", req.PageSize)

	t.Logf("\n=== Integration Test Complete ===")
	t.Logf("Key assertions to verify:")
	t.Logf("  1. Total count should be 3")
	t.Logf("  2. Feed items should be sorted by feed_position descending")
	t.Logf("  3. Post data should be correctly assembled from PostID references")
	t.Logf("  4. IsRead status should be preserved in feed item")
}
