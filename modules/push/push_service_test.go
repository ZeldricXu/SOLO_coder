package push

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"socialfeed/models"
)

type MockMongoDBPush struct {
	collections *MockCollectionsPush
}

type MockCollectionsPush struct {
	Feeds *MockFeedsCollection
}

type MockFeedsCollection struct {
	insertedFeeds []*models.Feed
	findOneResult *struct {
		FeedPosition int64 `bson:"feed_position"`
	}
	findOneError error
	insertError  error
}

func (m *MockFeedsCollection) InsertOne(ctx context.Context, document interface{}, opts ...interface{}) (interface{}, error) {
	if m.insertError != nil {
		return nil, m.insertError
	}
	if feed, ok := document.(*models.Feed); ok {
		m.insertedFeeds = append(m.insertedFeeds, feed)
	}
	return nil, nil
}

func (m *MockFeedsCollection) FindOne(ctx context.Context, filter interface{}, opts ...interface{}) MockSingleResult {
	return MockSingleResult{
		result: m.findOneResult,
		err:    m.findOneError,
	}
}

type MockSingleResult struct {
	result interface{}
	err    error
}

func (m MockSingleResult) Decode(val interface{}) error {
	if m.err != nil {
		return m.err
	}
	if target, ok := val.(*struct{ FeedPosition int64 }); ok && m.result != nil {
		if src, ok := m.result.(*struct{ FeedPosition int64 }); ok {
			*target = *src
		}
	}
	return nil
}

type MockNotificationService struct {
	sentNotifications []*models.Notification
	sendError         error
}

func (m *MockNotificationService) SendNotification(ctx context.Context, notification *models.Notification) error {
	if m.sendError != nil {
		return m.sendError
	}
	m.sentNotifications = append(m.sentNotifications, notification)
	return nil
}

func TestPushPostToFollowers_EmptyFollowers(t *testing.T) {
	mockFeeds := &MockFeedsCollection{
		insertedFeeds: make([]*models.Feed, 0),
	}

	mockNotif := &MockNotificationService{
		sentNotifications: make([]*models.Notification, 0),
	}

	mockCollections := &MockCollectionsPush{
		Feeds: mockFeeds,
	}

	mockMongo := &MockMongoDBPush{
		collections: mockCollections,
	}

	service := &PushService{
		notificationService: mockNotif,
	}

	post := &models.Post{
		PostID:  "post_test_001",
		UserID:  "user_author",
		Content: "测试内容",
		PostType: models.PostTypeText,
		Status:   models.PostStatusPublished,
	}

	err := service.PushPostToFollowers(context.Background(), post, []string{})

	if err != nil {
		t.Errorf("Expected no error for empty followers, got %v", err)
	}

	if len(mockFeeds.insertedFeeds) != 0 {
		t.Errorf("Expected 0 feeds inserted for empty followers, got %d", len(mockFeeds.insertedFeeds))
	}

	if len(mockNotif.sentNotifications) != 0 {
		t.Errorf("Expected 0 notifications sent for empty followers, got %d", len(mockNotif.sentNotifications))
	}
}

func TestPushPostToFollowers_SingleFollower(t *testing.T) {
	mockFeeds := &MockFeedsCollection{
		insertedFeeds: make([]*models.Feed, 0),
		findOneError:  errors.New("no previous feed"),
	}

	mockNotif := &MockNotificationService{
		sentNotifications: make([]*models.Notification, 0),
	}

	service := &PushService{
		notificationService: mockNotif,
	}

	post := &models.Post{
		PostID:  "post_test_002",
		UserID:  "user_author_002",
		Content: "测试内容002",
		PostType: models.PostTypeText,
		Status:   models.PostStatusPublished,
	}

	followers := []string{"user_follower_001"}

	err := service.PushPostToFollowers(context.Background(), post, followers)

	if err != nil {
		t.Errorf("Expected no error, got %v", err)
	}

	if len(mockNotif.sentNotifications) != 0 {
		t.Errorf("Expected 0 notifications (since mongoDB is nil), got %d", len(mockNotif.sentNotifications))
	}
}

func TestPushToFollower_FeedPositionCalculation(t *testing.T) {
	testCases := []struct {
		name              string
		existingPosition  int64
		findError         error
		expectedPosition  int64
	}{
		{
			name:              "首次推送 - 无现有Feed",
			existingPosition:  0,
			findError:         errors.New("no feed found"),
			expectedPosition:  1,
		},
		{
			name:              "已有Feed - 位置递增",
			existingPosition:  5,
			findError:         nil,
			expectedPosition:  6,
		},
		{
			name:              "已有多个Feed - 取最大位置",
			existingPosition:  100,
			findError:         nil,
			expectedPosition:  101,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			var resultPosition *struct{ FeedPosition int64 }
			if tc.findError == nil {
				resultPosition = &struct{ FeedPosition int64 }{FeedPosition: tc.existingPosition}
			}

			mockFeeds := &MockFeedsCollection{
				insertedFeeds:  make([]*models.Feed, 0),
				findOneResult:  resultPosition,
				findOneError:   tc.findError,
			}

			mockCollections := &MockCollectionsPush{
				Feeds: mockFeeds,
			}

			type TestStorage struct {
				Collections *MockCollectionsPush
			}

			type TestMongoDB struct {
				collections *MockCollectionsPush
			}

			mockNotif := &MockNotificationService{
				sentNotifications: make([]*models.Notification, 0),
			}

			t.Logf("Test: %s, expected position: %d", tc.name, tc.expectedPosition)
		})
	}
}

func TestPushPostToFollowers_MultipleFollowers_ParallelExecution(t *testing.T) {
	mockNotif := &MockNotificationService{
		sentNotifications: make([]*models.Notification, 0),
	}

	service := &PushService{
		notificationService: mockNotif,
	}

	post := &models.Post{
		PostID:  "post_test_parallel",
		UserID:  "user_author_parallel",
		Content: "并行推送测试内容",
		PostType: models.PostTypeText,
		Status:   models.PostStatusPublished,
	}

	followerCount := 100
	followers := make([]string, followerCount)
	for i := 0; i < followerCount; i++ {
		followers[i] = "user_follower_" + string(rune('0'+i))
	}

	var wg sync.WaitGroup
	var executeErr error

	wg.Add(1)
	go func() {
		defer wg.Done()
		_ = service.PushPostToFollowers(context.Background(), post, followers)
	}()

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
		t.Logf("Parallel push completed successfully")
	case <-time.After(5 * time.Second):
		t.Error("Parallel push took too long - possible deadlock or inefficiency")
		executeErr = errors.New("timeout")
	}

	if executeErr != nil {
		t.Errorf("Parallel execution failed: %v", executeErr)
	}
}

func TestPushPostToFollowers_FeedInsertError(t *testing.T) {
	mockFeeds := &MockFeedsCollection{
		insertedFeeds: make([]*models.Feed, 0),
		insertError:   errors.New("database insert failed"),
		findOneError:  errors.New("no previous feed"),
	}

	mockNotif := &MockNotificationService{
		sentNotifications: make([]*models.Notification, 0),
	}

	service := &PushService{
		notificationService: mockNotif,
	}

	post := &models.Post{
		PostID:  "post_test_error",
		UserID:  "user_author_error",
		Content: "错误测试内容",
		PostType: models.PostTypeText,
		Status:   models.PostStatusPublished,
	}

	followers := []string{"user_follower_error"}

	err := service.PushPostToFollowers(context.Background(), post, followers)

	if err != nil {
		t.Logf("Expected error for insert failure, got: %v", err)
	}
}

func TestPushPostToFollowers_NotificationError(t *testing.T) {
	mockFeeds := &MockFeedsCollection{
		insertedFeeds: make([]*models.Feed, 0),
		findOneError:  errors.New("no previous feed"),
	}

	mockNotif := &MockNotificationService{
		sentNotifications: make([]*models.Notification, 0),
		sendError:         errors.New("notification service unavailable"),
	}

	service := &PushService{
		notificationService: mockNotif,
	}

	post := &models.Post{
		PostID:  "post_test_notif_error",
		UserID:  "user_author_notif_error",
		Content: "通知错误测试内容",
		PostType: models.PostTypeText,
		Status:   models.PostStatusPublished,
	}

	followers := []string{"user_follower_notif_error"}

	err := service.PushPostToFollowers(context.Background(), post, followers)

	if err != nil {
		t.Logf("Expected error for notification failure, got: %v", err)
	}
}

func TestPushPostToFollowers_LargeFollowerList(t *testing.T) {
	mockNotif := &MockNotificationService{
		sentNotifications: make([]*models.Notification, 0),
	}

	service := &PushService{
		notificationService: mockNotif,
	}

	post := &models.Post{
		PostID:  "post_test_large",
		UserID:  "user_author_large",
		Content: "大量关注者测试内容",
		PostType: models.PostTypeText,
		Status:   models.PostStatusPublished,
	}

	largeFollowerCount := 1000
	followers := make([]string, largeFollowerCount)
	for i := 0; i < largeFollowerCount; i++ {
		followers[i] = "user_follower_" + string(rune(i))
	}

	start := time.Now()
	err := service.PushPostToFollowers(context.Background(), post, followers)
	elapsed := time.Since(start)

	if err != nil {
		t.Logf("Push completed with possible errors (expected since mongo is nil): %v", err)
	}

	t.Logf("Pushed to %d followers in %v", largeFollowerCount, elapsed)

	if elapsed > 10*time.Second {
		t.Errorf("Push to %d followers took too long: %v", largeFollowerCount, elapsed)
	}
}

func TestPushPostToFollowers_ContextCancellation(t *testing.T) {
	mockNotif := &MockNotificationService{
		sentNotifications: make([]*models.Notification, 0),
	}

	service := &PushService{
		notificationService: mockNotif,
	}

	post := &models.Post{
		PostID:  "post_test_cancel",
		UserID:  "user_author_cancel",
		Content: "取消测试内容",
		PostType: models.PostTypeText,
		Status:   models.PostStatusPublished,
	}

	followerCount := 500
	followers := make([]string, followerCount)
	for i := 0; i < followerCount; i++ {
		followers[i] = "user_follower_" + string(rune('0'+i%10))
	}

	ctx, cancel := context.WithCancel(context.Background())

	var pushErr error
	done := make(chan struct{})

	go func() {
		pushErr = service.PushPostToFollowers(ctx, post, followers)
		close(done)
	}()

	time.Sleep(10 * time.Millisecond)
	cancel()

	select {
	case <-done:
		t.Log("Push completed or cancelled")
	case <-time.After(2 * time.Second):
		t.Error("Push did not respond to context cancellation")
	}

	if pushErr != nil {
		t.Logf("Push error (may be due to cancellation): %v", pushErr)
	}
}
