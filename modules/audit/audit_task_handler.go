package audit

import (
	"context"
	"encoding/json"
	"log"
	"strings"
	"time"

	"socialfeed/models"
	"socialfeed/modules/queue"
	"socialfeed/storage"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/mongo"
)

type AsyncAuditService struct {
	mongoDB       *storage.MongoDB
	queueManager  *queue.QueueManager
	sensitiveWords []string
}

func NewAsyncAuditService(
	mongoDB *storage.MongoDB,
	queueManager *queue.QueueManager,
) *AsyncAuditService {
	return &AsyncAuditService{
		mongoDB:      mongoDB,
		queueManager: queueManager,
		sensitiveWords: []string{
			"违法", "违规", "色情", "暴力", "赌博", "毒品",
			"敏感词1", "敏感词2",
		},
	}
}

func (s *AsyncAuditService) EnqueueAuditTask(ctx context.Context, post *models.Post) error {
	payload := queue.AuditTaskPayload{
		PostID:  post.PostID,
		UserID:  post.UserID,
		Content: post.Content,
		Media:   post.Media,
	}

	task := &queue.BaseTask{
		ID:         "audit_task_" + uuid.New().String()[:8],
		Type:       queue.TaskTypeAudit,
		Payload:    payload,
		CreatedAt:  time.Now().UTC(),
		Status:     queue.TaskStatusPending,
		RetryCount: 0,
		MaxRetries: 3,
	}

	return s.queueManager.EnqueueAuditTask(ctx, task)
}

func (s *AsyncAuditService) GetSensitiveWords() []string {
	return s.sensitiveWords
}

func (s *AsyncAuditService) AuditPost(ctx context.Context, post *models.Post) (*models.AuditRecord, error) {
	err := s.EnqueueAuditTask(ctx, post)
	if err != nil {
		return nil, err
	}

	return &models.AuditRecord{
		AuditID:   "async_" + uuid.New().String()[:8],
		PostID:    post.PostID,
		Result:    models.AuditStatusPending,
		CreatedAt: time.Now().UTC(),
	}, nil
}

type AuditTaskHandler struct {
	mongoDB       *storage.MongoDB
	sensitiveWords []string
}

func NewAuditTaskHandler(
	mongoDB *storage.MongoDB,
	sensitiveWords []string,
) *AuditTaskHandler {
	if sensitiveWords == nil {
		sensitiveWords = []string{
			"违法", "违规", "色情", "暴力", "赌博", "毒品",
			"敏感词1", "敏感词2",
		}
	}
	return &AuditTaskHandler{
		mongoDB:       mongoDB,
		sensitiveWords: sensitiveWords,
	}
}

func (h *AuditTaskHandler) Handle(ctx context.Context, task queue.Task) error {
	payloadBytes, err := json.Marshal(task.GetPayload())
	if err != nil {
		log.Printf("AuditTaskHandler: Failed to marshal payload: %v", err)
		return err
	}

	var payload queue.AuditTaskPayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		log.Printf("AuditTaskHandler: Failed to unmarshal payload: %v", err)
		return err
	}

	log.Printf("AuditTaskHandler: Processing audit task for post %s", payload.PostID)

	auditResult := models.AuditStatusApproved
	rejectReason := ""

	if h.checkSensitiveContent(payload.Content) {
		auditResult = models.AuditStatusRejected
		rejectReason = "内容包含敏感词"
	}

	auditRecord := &models.AuditRecord{
		AuditID:    "audit_" + uuid.New().String()[:8],
		PostID:     payload.PostID,
		UserID:     payload.UserID,
		Result:     auditResult,
		Reason:     rejectReason,
		CreatedAt:  time.Now().UTC(),
	}

	_, err = h.mongoDB.Collections.AuditRecords.InsertOne(ctx, auditRecord)
	if err != nil {
		log.Printf("AuditTaskHandler: Failed to insert audit record: %v", err)
		return err
	}

	updateResult := bson.M{
		"$set": bson.M{
			"status": auditResult,
		},
	}

	_, err = h.mongoDB.Collections.Posts.UpdateOne(
		ctx,
		bson.M{"post_id": payload.PostID},
		updateResult,
	)
	if err != nil {
		if err != mongo.ErrNoDocuments {
			log.Printf("AuditTaskHandler: Failed to update post status: %v", err)
		}
	}

	log.Printf("AuditTaskHandler: Completed audit for post %s, result: %s",
		payload.PostID, auditResult)

	return nil
}

func (h *AuditTaskHandler) checkSensitiveContent(content string) bool {
	if content == "" {
		return false
	}

	lowerContent := strings.ToLower(content)
	for _, word := range h.sensitiveWords {
		if strings.Contains(lowerContent, strings.ToLower(word)) {
			return true
		}
	}
	return false
}
