package testdata

import (
	"math/rand"
	"socialfeed/models"
	"socialfeed/modules/queue"
	"strconv"
	"time"

	"github.com/google/uuid"
)

type TestDataBuilder struct {
	rand *rand.Rand
}

func NewTestDataBuilder() *TestDataBuilder {
	return &TestDataBuilder{
		rand: rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

func (b *TestDataBuilder) GenerateUserID(prefix ...string) string {
	if len(prefix) > 0 {
		return prefix[0] + "_" + uuid.New().String()[:8]
	}
	return "user_" + uuid.New().String()[:8]
}

func (b *TestDataBuilder) GeneratePostID(prefix ...string) string {
	if len(prefix) > 0 {
		return prefix[0] + "_" + uuid.New().String()[:8]
	}
	return "post_" + uuid.New().String()[:8]
}

func (b *TestDataBuilder) GenerateFeedID() string {
	return "feed_" + uuid.New().String()[:8]
}

func (b *TestDataBuilder) GenerateNotificationID() string {
	return "notif_" + uuid.New().String()[:8]
}

func (b *TestDataBuilder) GenerateAuditID() string {
	return "audit_" + uuid.New().String()[:8]
}

type PostBuilder struct {
	post *models.Post
}

func (b *TestDataBuilder) NewPost() *PostBuilder {
	now := time.Now().UTC()
	return &PostBuilder{
		post: &models.Post{
			PostID:       b.GeneratePostID(),
			UserID:       b.GenerateUserID(),
			Content:      "",
			Media:        []string{},
			PostType:     models.PostTypeText,
			Status:       models.PostStatusPending,
			LikesCount:   0,
			CommentsCount: 0,
			SharesCount:  0,
			HeatScore:    0,
			CreatedAt:    now,
			UpdatedAt:    now,
		},
	}
}

func (pb *PostBuilder) WithPostID(postID string) *PostBuilder {
	pb.post.PostID = postID
	return pb
}

func (pb *PostBuilder) WithUserID(userID string) *PostBuilder {
	pb.post.UserID = userID
	return pb
}

func (pb *PostBuilder) WithContent(content string) *PostBuilder {
	pb.post.Content = content
	return pb
}

func (pb *PostBuilder) WithApprovedContent() *PostBuilder {
	pb.post.Content = "这是一条正常的动态内容"
	return pb
}

func (pb *PostBuilder) WithRejectedContent() *PostBuilder {
	pb.post.Content = "这是包含敏感词1的内容"
	return pb
}

func (pb *PostBuilder) WithMedia(media []string) *PostBuilder {
	pb.post.Media = media
	return pb
}

func (pb *PostBuilder) WithPostType(postType models.PostType) *PostBuilder {
	pb.post.PostType = postType
	return pb
}

func (pb *PostBuilder) WithStatus(status models.PostStatus) *PostBuilder {
	pb.post.Status = status
	return pb
}

func (pb *PostBuilder) WithStatusApproved() *PostBuilder {
	pb.post.Status = models.PostStatusApproved
	return pb
}

func (pb *PostBuilder) WithStatusRejected() *PostBuilder {
	pb.post.Status = models.PostStatusRejected
	return pb
}

func (pb *PostBuilder) WithLikes(count int64) *PostBuilder {
	pb.post.LikesCount = count
	return pb
}

func (pb *PostBuilder) WithComments(count int64) *PostBuilder {
	pb.post.CommentsCount = count
	return pb
}

func (pb *PostBuilder) WithShares(count int64) *PostBuilder {
	pb.post.SharesCount = count
	return pb
}

func (pb *PostBuilder) WithHeatScore(score float64) *PostBuilder {
	pb.post.HeatScore = score
	return pb
}

func (pb *PostBuilder) WithHighEngagement() *PostBuilder {
	pb.post.LikesCount = 1000
	pb.post.CommentsCount = 500
	pb.post.SharesCount = 200
	pb.post.HeatScore = 5000.0
	return pb
}

func (pb *PostBuilder) WithCreatedAt(t time.Time) *PostBuilder {
	pb.post.CreatedAt = t
	return pb
}

func (pb *PostBuilder) Build() *models.Post {
	return pb.post
}

type FeedBuilder struct {
	feed *models.Feed
}

func (b *TestDataBuilder) NewFeed() *FeedBuilder {
	now := time.Now().UTC()
	return &FeedBuilder{
		feed: &models.Feed{
			FeedID:       b.GenerateFeedID(),
			UserID:       b.GenerateUserID(),
			PostID:       b.GeneratePostID(),
			FeedPosition: 1,
			InsertedAt:   now,
			IsRead:       false,
		},
	}
}

func (fb *FeedBuilder) WithFeedID(feedID string) *FeedBuilder {
	fb.feed.FeedID = feedID
	return fb
}

func (fb *FeedBuilder) WithUserID(userID string) *FeedBuilder {
	fb.feed.UserID = userID
	return fb
}

func (fb *FeedBuilder) WithPostID(postID string) *FeedBuilder {
	fb.feed.PostID = postID
	return fb
}

func (fb *FeedBuilder) WithFeedPosition(position int64) *FeedBuilder {
	fb.feed.FeedPosition = position
	return fb
}

func (fb *FeedBuilder) WithInsertedAt(t time.Time) *FeedBuilder {
	fb.feed.InsertedAt = t
	return fb
}

func (fb *FeedBuilder) WithIsRead(isRead bool) *FeedBuilder {
	fb.feed.IsRead = isRead
	return fb
}

func (fb *FeedBuilder) Build() *models.Feed {
	return fb.feed
}

type FeedItemBuilder struct {
	feedItem *models.FeedItem
}

func (b *TestDataBuilder) NewFeedItem() *FeedItemBuilder {
	now := time.Now().UTC()
	return &FeedItemBuilder{
		feedItem: &models.FeedItem{
			FeedID:       b.GenerateFeedID(),
			UserID:       b.GenerateUserID(),
			PostID:       b.GeneratePostID(),
			FeedPosition: 1,
			InsertedAt:   now,
			IsRead:       false,
			Post:         nil,
		},
	}
}

func (fib *FeedItemBuilder) WithFeedID(feedID string) *FeedItemBuilder {
	fib.feedItem.FeedID = feedID
	return fib
}

func (fib *FeedItemBuilder) WithUserID(userID string) *FeedItemBuilder {
	fib.feedItem.UserID = userID
	return fib
}

func (fib *FeedItemBuilder) WithPostID(postID string) *FeedItemBuilder {
	fib.feedItem.PostID = postID
	return fib
}

func (fib *FeedItemBuilder) WithFeedPosition(position int64) *FeedItemBuilder {
	fib.feedItem.FeedPosition = position
	return fib
}

func (fib *FeedItemBuilder) WithInsertedAt(t time.Time) *FeedItemBuilder {
	fib.feedItem.InsertedAt = t
	return fib
}

func (fib *FeedItemBuilder) WithIsRead(isRead bool) *FeedItemBuilder {
	fib.feedItem.IsRead = isRead
	return fib
}

func (fib *FeedItemBuilder) WithPost(post *models.Post) *FeedItemBuilder {
	fib.feedItem.Post = post
	return fib
}

func (fib *FeedItemBuilder) Build() *models.FeedItem {
	return fib.feedItem
}

type AuditRecordBuilder struct {
	record *models.AuditRecord
}

func (b *TestDataBuilder) NewAuditRecord() *AuditRecordBuilder {
	now := time.Now().UTC()
	return &AuditRecordBuilder{
		record: &models.AuditRecord{
			AuditID:   b.GenerateAuditID(),
			PostID:    b.GeneratePostID(),
			UserID:    b.GenerateUserID(),
			Result:    models.AuditStatusPending,
			Reason:    "",
			CreatedAt: now,
		},
	}
}

func (arb *AuditRecordBuilder) WithAuditID(auditID string) *AuditRecordBuilder {
	arb.record.AuditID = auditID
	return arb
}

func (arb *AuditRecordBuilder) WithPostID(postID string) *AuditRecordBuilder {
	arb.record.PostID = postID
	return arb
}

func (arb *AuditRecordBuilder) WithUserID(userID string) *AuditRecordBuilder {
	arb.record.UserID = userID
	return arb
}

func (arb *AuditRecordBuilder) WithResult(result models.AuditStatus) *AuditRecordBuilder {
	arb.record.Result = result
	return arb
}

func (arb *AuditRecordBuilder) WithApproved() *AuditRecordBuilder {
	arb.record.Result = models.AuditStatusApproved
	arb.record.Reason = ""
	return arb
}

func (arb *AuditRecordBuilder) WithRejected(reason string) *AuditRecordBuilder {
	arb.record.Result = models.AuditStatusRejected
	arb.record.Reason = reason
	return arb
}

func (arb *AuditRecordBuilder) WithReason(reason string) *AuditRecordBuilder {
	arb.record.Reason = reason
	return arb
}

func (arb *AuditRecordBuilder) WithCreatedAt(t time.Time) *AuditRecordBuilder {
	arb.record.CreatedAt = t
	return arb
}

func (arb *AuditRecordBuilder) Build() *models.AuditRecord {
	return arb.record
}

type NotificationBuilder struct {
	notification *models.Notification
}

func (b *TestDataBuilder) NewNotification() *NotificationBuilder {
	now := time.Now().UTC()
	return &NotificationBuilder{
		notification: &models.Notification{
			NotificationID:   b.GenerateNotificationID(),
			UserID:           b.GenerateUserID(),
			FromUserID:       b.GenerateUserID(),
			PostID:           nil,
			CommentID:        nil,
			NotificationType: models.NotificationTypeLike,
			CreatedAt:        now,
			IsRead:           false,
		},
	}
}

func (nb *NotificationBuilder) WithNotificationID(id string) *NotificationBuilder {
	nb.notification.NotificationID = id
	return nb
}

func (nb *NotificationBuilder) WithUserID(userID string) *NotificationBuilder {
	nb.notification.UserID = userID
	return nb
}

func (nb *NotificationBuilder) WithFromUserID(userID string) *NotificationBuilder {
	nb.notification.FromUserID = userID
	return nb
}

func (nb *NotificationBuilder) WithPostID(postID string) *NotificationBuilder {
	nb.notification.PostID = &postID
	return nb
}

func (nb *NotificationBuilder) WithCommentID(commentID string) *NotificationBuilder {
	nb.notification.CommentID = &commentID
	return nb
}

func (nb *NotificationBuilder) WithNotificationType(nt models.NotificationType) *NotificationBuilder {
	nb.notification.NotificationType = nt
	return nb
}

func (nb *NotificationBuilder) WithTypeLike() *NotificationBuilder {
	nb.notification.NotificationType = models.NotificationTypeLike
	return nb
}

func (nb *NotificationBuilder) WithTypeComment() *NotificationBuilder {
	nb.notification.NotificationType = models.NotificationTypeComment
	return nb
}

func (nb *NotificationBuilder) WithTypeShare() *NotificationBuilder {
	nb.notification.NotificationType = models.NotificationTypeShare
	return nb
}

func (nb *NotificationBuilder) WithTypeNewPost() *NotificationBuilder {
	nb.notification.NotificationType = models.NotificationTypeNewPost
	return nb
}

func (nb *NotificationBuilder) WithCreatedAt(t time.Time) *NotificationBuilder {
	nb.notification.CreatedAt = t
	return nb
}

func (nb *NotificationBuilder) WithIsRead(isRead bool) *NotificationBuilder {
	nb.notification.IsRead = isRead
	return nb
}

func (nb *NotificationBuilder) Build() *models.Notification {
	return nb.notification
}

type RelationBuilder struct {
	relation *models.Relation
}

func (b *TestDataBuilder) NewRelation() *RelationBuilder {
	now := time.Now().UTC()
	return &RelationBuilder{
		relation: &models.Relation{
			RelationID: "relation_" + uuid.New().String()[:8],
			FollowerID: b.GenerateUserID(),
			FollowingID: b.GenerateUserID(),
			CreatedAt:  now,
		},
	}
}

func (rb *RelationBuilder) WithRelationID(id string) *RelationBuilder {
	rb.relation.RelationID = id
	return rb
}

func (rb *RelationBuilder) WithFollowerID(userID string) *RelationBuilder {
	rb.relation.FollowerID = userID
	return rb
}

func (rb *RelationBuilder) WithFollowingID(userID string) *RelationBuilder {
	rb.relation.FollowingID = userID
	return rb
}

func (rb *RelationBuilder) WithCreatedAt(t time.Time) *RelationBuilder {
	rb.relation.CreatedAt = t
	return rb
}

func (rb *RelationBuilder) Build() *models.Relation {
	return rb.relation
}

type PushTaskBuilder struct {
	task *queue.BaseTask
}

func (b *TestDataBuilder) NewPushTask() *PushTaskBuilder {
	now := time.Now().UTC()
	return &PushTaskBuilder{
		task: &queue.BaseTask{
			ID:         "push_task_" + uuid.New().String()[:8],
			Type:       queue.TaskTypePush,
			Payload:    queue.PushTaskPayload{},
			CreatedAt:  now,
			Status:     queue.TaskStatusPending,
			RetryCount: 0,
			MaxRetries: 3,
		},
	}
}

func (ptb *PushTaskBuilder) WithTaskID(id string) *PushTaskBuilder {
	ptb.task.ID = id
	return ptb
}

func (ptb *PushTaskBuilder) WithPostID(postID string) *PushTaskBuilder {
	if payload, ok := ptb.task.Payload.(queue.PushTaskPayload); ok {
		payload.PostID = postID
		ptb.task.Payload = payload
	}
	return ptb
}

func (ptb *PushTaskBuilder) WithAuthorID(authorID string) *PushTaskBuilder {
	if payload, ok := ptb.task.Payload.(queue.PushTaskPayload); ok {
		payload.AuthorID = authorID
		ptb.task.Payload = payload
	}
	return ptb
}

func (ptb *PushTaskBuilder) WithFollowers(followers []string) *PushTaskBuilder {
	if payload, ok := ptb.task.Payload.(queue.PushTaskPayload); ok {
		payload.Followers = followers
		ptb.task.Payload = payload
	}
	return ptb
}

func (ptb *PushTaskBuilder) WithStatus(status queue.TaskStatus) *PushTaskBuilder {
	ptb.task.Status = status
	return ptb
}

func (ptb *PushTaskBuilder) WithRetryCount(count int) *PushTaskBuilder {
	ptb.task.RetryCount = count
	return ptb
}

func (ptb *PushTaskBuilder) WithMaxRetries(max int) *PushTaskBuilder {
	ptb.task.MaxRetries = max
	return ptb
}

func (ptb *PushTaskBuilder) Build() *queue.BaseTask {
	return ptb.task
}

type AuditTaskBuilder struct {
	task *queue.BaseTask
}

func (b *TestDataBuilder) NewAuditTask() *AuditTaskBuilder {
	now := time.Now().UTC()
	return &AuditTaskBuilder{
		task: &queue.BaseTask{
			ID:         "audit_task_" + uuid.New().String()[:8],
			Type:       queue.TaskTypeAudit,
			Payload:    queue.AuditTaskPayload{},
			CreatedAt:  now,
			Status:     queue.TaskStatusPending,
			RetryCount: 0,
			MaxRetries: 3,
		},
	}
}

func (atb *AuditTaskBuilder) WithTaskID(id string) *AuditTaskBuilder {
	atb.task.ID = id
	return atb
}

func (atb *AuditTaskBuilder) WithPostID(postID string) *AuditTaskBuilder {
	if payload, ok := atb.task.Payload.(queue.AuditTaskPayload); ok {
		payload.PostID = postID
		atb.task.Payload = payload
	}
	return atb
}

func (atb *AuditTaskBuilder) WithUserID(userID string) *AuditTaskBuilder {
	if payload, ok := atb.task.Payload.(queue.AuditTaskPayload); ok {
		payload.UserID = userID
		atb.task.Payload = payload
	}
	return atb
}

func (atb *AuditTaskBuilder) WithContent(content string) *AuditTaskBuilder {
	if payload, ok := atb.task.Payload.(queue.AuditTaskPayload); ok {
		payload.Content = content
		atb.task.Payload = payload
	}
	return atb
}

func (atb *AuditTaskBuilder) WithApprovedContent() *AuditTaskBuilder {
	if payload, ok := atb.task.Payload.(queue.AuditTaskPayload); ok {
		payload.Content = "这是一条正常的动态内容"
		atb.task.Payload = payload
	}
	return atb
}

func (atb *AuditTaskBuilder) WithRejectedContent() *AuditTaskBuilder {
	if payload, ok := atb.task.Payload.(queue.AuditTaskPayload); ok {
		payload.Content = "这是包含敏感词1的内容"
		atb.task.Payload = payload
	}
	return atb
}

func (atb *AuditTaskBuilder) WithMedia(media []string) *AuditTaskBuilder {
	if payload, ok := atb.task.Payload.(queue.AuditTaskPayload); ok {
		payload.Media = media
		atb.task.Payload = payload
	}
	return atb
}

func (atb *AuditTaskBuilder) WithStatus(status queue.TaskStatus) *AuditTaskBuilder {
	atb.task.Status = status
	return atb
}

func (atb *AuditTaskBuilder) Build() *queue.BaseTask {
	return atb.task
}

func (b *TestDataBuilder) GenerateFollowers(count int, prefix ...string) []string {
	prefixStr := "follower"
	if len(prefix) > 0 {
		prefixStr = prefix[0]
	}

	followers := make([]string, count)
	for i := 0; i < count; i++ {
		followers[i] = prefixStr + "_" + strconv.Itoa(i+1) + "_" + uuid.New().String()[:4]
	}
	return followers
}

func (b *TestDataBuilder) GenerateRandomContent(length int) string {
	const letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 "
	content := make([]byte, length)
	for i := range content {
		content[i] = letters[b.rand.Intn(len(letters))]
	}
	return string(content)
}

func (b *TestDataBuilder) GenerateMediaURLs(count int) []string {
	media := make([]string, count)
	for i := 0; i < count; i++ {
		media[i] = "https://example.com/media/" + uuid.New().String()[:8] + ".jpg"
	}
	return media
}

type TestScenario struct {
	Name        string
	Description string
	Setup       func() interface{}
	Cleanup     func()
}

var defaultBuilder = NewTestDataBuilder()

func DefaultBuilder() *TestDataBuilder {
	return defaultBuilder
}

func GenerateTestPost() *models.Post {
	return DefaultBuilder().NewPost().
		WithApprovedContent().
		WithStatusApproved().
		Build()
}

func GenerateTestFeed() *models.Feed {
	return DefaultBuilder().NewFeed().Build()
}

func GenerateTestFeedWithPost(post *models.Post) *models.FeedItem {
	return DefaultBuilder().NewFeedItem().
		WithPostID(post.PostID).
		WithPost(post).
		Build()
}

func GenerateTestAuditRecordApproved() *models.AuditRecord {
	return DefaultBuilder().NewAuditRecord().WithApproved().Build()
}

func GenerateTestAuditRecordRejected(reason string) *models.AuditRecord {
	return DefaultBuilder().NewAuditRecord().WithRejected(reason).Build()
}

func GenerateTestNotificationLike() *models.Notification {
	return DefaultBuilder().NewNotification().WithTypeLike().Build()
}

func GenerateTestNotificationComment() *models.Notification {
	return DefaultBuilder().NewNotification().WithTypeComment().Build()
}

func GenerateTestFollowers(count int) []string {
	return DefaultBuilder().GenerateFollowers(count)
}
