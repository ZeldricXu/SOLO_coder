package audit

import (
	"context"
	"socialfeed/models"
	"socialfeed/storage"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/bson"
)

var sensitiveWords = []string{
	"敏感词1",
	"敏感词2",
	"违法",
	"违规",
	"色情",
	"暴力",
	"赌博",
	"毒品",
}

type AuditService struct {
	mongoDB *storage.MongoDB
}

func NewAuditService(mongoDB *storage.MongoDB) *AuditService {
	return &AuditService{
		mongoDB: mongoDB,
	}
}

func (s *AuditService) AuditPost(ctx context.Context, post *models.Post) (*models.AuditRecord, error) {
	auditRecord := &models.AuditRecord{
		AuditID:   "audit_" + uuid.New().String()[:8],
		PostID:    post.PostID,
		AuditedAt: time.Now().UTC(),
	}

	containsSensitive := s.checkSensitiveContent(post.Content)

	if containsSensitive {
		auditRecord.AuditResult = models.AuditResultRejected
		reason := "内容包含敏感词汇"
		auditRecord.AuditReason = &reason
	} else {
		auditRecord.AuditResult = models.AuditResultApproved
	}

	_, err := s.mongoDB.Collections.AuditRecords.InsertOne(ctx, auditRecord)
	if err != nil {
		return nil, err
	}

	return auditRecord, nil
}

func (s *AuditService) checkSensitiveContent(content string) bool {
	lowerContent := strings.ToLower(content)
	for _, word := range sensitiveWords {
		if strings.Contains(lowerContent, strings.ToLower(word)) {
			return true
		}
	}
	return false
}

func (s *AuditService) GetAuditRecord(ctx context.Context, postID string) (*models.AuditRecord, error) {
	var record models.AuditRecord
	filter := bson.M{"post_id": postID}
	err := s.mongoDB.Collections.AuditRecords.FindOne(ctx, filter).Decode(&record)
	if err != nil {
		return nil, err
	}
	return &record, nil
}

func (s *AuditService) GetSensitiveWords() []string {
	return sensitiveWords
}
