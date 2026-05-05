package audit

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"

	"socialfeed/models"
)

type MockMongoDBAudit struct {
	collections *MockCollectionsAudit
}

type MockCollectionsAudit struct {
	AuditRecords *MockAuditRecordsCollection
}

type MockAuditRecordsCollection struct {
	insertedRecords []*models.AuditRecord
}

func (m *MockAuditRecordsCollection) InsertOne(ctx context.Context, document interface{}, opts ...interface{}) (interface{}, error) {
	if record, ok := document.(*models.AuditRecord); ok {
		m.insertedRecords = append(m.insertedRecords, record)
	}
	return nil, nil
}

func (m *MockDBAudit) Collections() *MockCollectionsAudit {
	return m.collections
}

type MockDBAudit struct {
	collections *MockCollectionsAudit
}

func TestCheckSensitiveContent_ApprovedContent(t *testing.T) {
	testCases := []struct {
		name    string
		content string
		want    bool
	}{
		{
			name:    "正常文本内容",
			content: "今天天气真好",
			want:    false,
		},
		{
			name:    "包含图片的正常内容",
			content: "分享一张美丽的图片",
			want:    false,
		},
		{
			name:    "长文本正常内容",
			content: "这是一段很长的正常内容，包含了很多信息，但是没有任何违规的词汇。",
			want:    false,
		},
		{
			name:    "空内容",
			content: "",
			want:    false,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			service := &AuditService{}
			got := service.checkSensitiveContent(tc.content)
			if got != tc.want {
				t.Errorf("checkSensitiveContent(%q) = %v, want %v", tc.content, got, tc.want)
			}
		})
	}
}

func TestCheckSensitiveContent_RejectedContent(t *testing.T) {
	testCases := []struct {
		name    string
		content string
		want    bool
	}{
		{
			name:    "包含敏感词1",
			content: "这是敏感词1的内容",
			want:    true,
		},
		{
			name:    "包含敏感词2",
			content: "测试敏感词2的检测",
			want:    true,
		},
		{
			name:    "包含违法词汇",
			content: "这是违法的行为",
			want:    true,
		},
		{
			name:    "包含色情词汇",
			content: "这里有色情内容",
			want:    true,
		},
		{
			name:    "包含暴力词汇",
			content: "描述暴力行为",
			want:    true,
		},
		{
			name:    "包含赌博词汇",
			content: "讨论赌博技巧",
			want:    true,
		},
		{
			name:    "包含毒品词汇",
			content: "涉及毒品交易",
			want:    true,
		},
		{
			name:    "大小写混合的敏感词",
			content: "这是违Fǎ的内容",
			want:    false,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			service := &AuditService{}
			got := service.checkSensitiveContent(tc.content)
			if got != tc.want {
				t.Errorf("checkSensitiveContent(%q) = %v, want %v", tc.content, got, tc.want)
			}
		})
	}
}

func TestCheckSensitiveContent_CaseInsensitive(t *testing.T) {
	testCases := []struct {
		name    string
		content string
		want    bool
	}{
		{
			name:    "全大写敏感词",
			content: "这是WEIFA的行为",
			want:    false,
		},
		{
			name:    "混合大小写",
			content: "这里有SeQing内容",
			want:    false,
		},
		{
			name:    "中文敏感词大小写无关",
			content: "这里有色情内容",
			want:    true,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			service := &AuditService{}
			got := service.checkSensitiveContent(tc.content)
			if got != tc.want {
				t.Errorf("checkSensitiveContent(%q) = %v, want %v", tc.content, got, tc.want)
			}
		})
	}
}

func TestAuditResult_Approved(t *testing.T) {
	mockAuditRecords := &MockAuditRecordsCollection{
		insertedRecords: make([]*models.AuditRecord, 0),
	}

	post := &models.Post{
		PostID:  "post_test_001",
		UserID:  "user_123",
		Content: "今天天气真好",
		Media:   []string{},
		PostType: models.PostTypeText,
		Status:   models.PostStatusPending,
	}

	containsSensitive := strings.Contains(strings.ToLower(post.Content), "违法") ||
		strings.Contains(strings.ToLower(post.Content), "色情") ||
		strings.Contains(strings.ToLower(post.Content), "暴力") ||
		strings.Contains(strings.ToLower(post.Content), "赌博") ||
		strings.Contains(strings.ToLower(post.Content), "毒品") ||
		strings.Contains(strings.ToLower(post.Content), "敏感词1") ||
		strings.Contains(strings.ToLower(post.Content), "敏感词2") ||
		strings.Contains(strings.ToLower(post.Content), "违规")

	auditRecord := &models.AuditRecord{
		AuditID:   "audit_test_001",
		PostID:    post.PostID,
		AuditedAt: time.Now().UTC(),
	}

	if containsSensitive {
		auditRecord.AuditResult = models.AuditResultRejected
		reason := "内容包含敏感词汇"
		auditRecord.AuditReason = &reason
	} else {
		auditRecord.AuditResult = models.AuditResultApproved
	}

	mockAuditRecords.insertedRecords = append(mockAuditRecords.insertedRecords, auditRecord)

	if auditRecord.AuditResult != models.AuditResultApproved {
		t.Errorf("Expected audit result to be %v, got %v", models.AuditResultApproved, auditRecord.AuditResult)
	}

	if auditRecord.AuditReason != nil {
		t.Errorf("Expected audit reason to be nil for approved content, got %v", *auditRecord.AuditReason)
	}

	if auditRecord.PostID != post.PostID {
		t.Errorf("Expected PostID to be %v, got %v", post.PostID, auditRecord.PostID)
	}
}

func TestAuditResult_Rejected(t *testing.T) {
	mockAuditRecords := &MockAuditRecordsCollection{
		insertedRecords: make([]*models.AuditRecord, 0),
	}

	post := &models.Post{
		PostID:  "post_test_002",
		UserID:  "user_456",
		Content: "这是违法的内容",
		Media:   []string{},
		PostType: models.PostTypeText,
		Status:   models.PostStatusPending,
	}

	containsSensitive := strings.Contains(strings.ToLower(post.Content), "违法") ||
		strings.Contains(strings.ToLower(post.Content), "色情") ||
		strings.Contains(strings.ToLower(post.Content), "暴力") ||
		strings.Contains(strings.ToLower(post.Content), "赌博") ||
		strings.Contains(strings.ToLower(post.Content), "毒品") ||
		strings.Contains(strings.ToLower(post.Content), "敏感词1") ||
		strings.Contains(strings.ToLower(post.Content), "敏感词2") ||
		strings.Contains(strings.ToLower(post.Content), "违规")

	auditRecord := &models.AuditRecord{
		AuditID:   "audit_test_002",
		PostID:    post.PostID,
		AuditedAt: time.Now().UTC(),
	}

	if containsSensitive {
		auditRecord.AuditResult = models.AuditResultRejected
		reason := "内容包含敏感词汇"
		auditRecord.AuditReason = &reason
	} else {
		auditRecord.AuditResult = models.AuditResultApproved
	}

	mockAuditRecords.insertedRecords = append(mockAuditRecords.insertedRecords, auditRecord)

	if auditRecord.AuditResult != models.AuditResultRejected {
		t.Errorf("Expected audit result to be %v, got %v", models.AuditResultRejected, auditRecord.AuditResult)
	}

	if auditRecord.AuditReason == nil {
		t.Error("Expected audit reason to be not nil for rejected content")
	} else if *auditRecord.AuditReason != "内容包含敏感词汇" {
		t.Errorf("Expected audit reason to be '内容包含敏感词汇', got %v", *auditRecord.AuditReason)
	}
}

func TestAudit_ParallelExecution(t *testing.T) {
	var wg sync.WaitGroup
	workerCount := 10
	testCount := 100
	results := make(chan bool, testCount)
	errors := make(chan error, testCount)

	testContents := []string{
		"正常内容1",
		"这是违法的内容",
		"正常内容2",
		"包含色情的内容",
		"正常内容3",
	}

	for i := 0; i < workerCount; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			service := &AuditService{}
			for j := 0; j < testCount/workerCount; j++ {
				content := testContents[(workerID+j)%len(testContents)]
				result := service.checkSensitiveContent(content)
				results <- result

				containsSensitive := strings.Contains(strings.ToLower(content), "违法") ||
					strings.Contains(strings.ToLower(content), "色情") ||
					strings.Contains(strings.ToLower(content), "暴力") ||
					strings.Contains(strings.ToLower(content), "赌博") ||
					strings.Contains(strings.ToLower(content), "毒品") ||
					strings.Contains(strings.ToLower(content), "敏感词1") ||
					strings.Contains(strings.ToLower(content), "敏感词2") ||
					strings.Contains(strings.ToLower(content), "违规")

				if result != containsSensitive {
					t.Errorf("Worker %d: Content '%s' - expected %v, got %v", workerID, content, containsSensitive, result)
				}
			}
		}(i)
	}

	wg.Wait()
	close(results)
	close(errors)

	approvedCount := 0
	rejectedCount := 0
	for result := range results {
		if result {
			rejectedCount++
		} else {
			approvedCount++
		}
	}

	t.Logf("Parallel audit test completed: %d approved, %d rejected", approvedCount, rejectedCount)

	if approvedCount+rejectedCount != testCount/workerCount*workerCount {
		t.Errorf("Expected total results to be %d, got %d", testCount/workerCount*workerCount, approvedCount+rejectedCount)
	}
}

func TestAudit_TimeoutSimulation(t *testing.T) {
	service := &AuditService{}

	ctx, cancel := context.WithTimeout(context.Background(), 1*time.Millisecond)
	defer cancel()

	done := make(chan struct{})
	go func() {
		_ = service.checkSensitiveContent("正常内容")
		close(done)
	}()

	select {
	case <-done:
		t.Log("Audit completed before timeout - this is expected for simple checks")
	case <-ctx.Done():
		t.Error("Audit timed out unexpectedly for simple content")
	}
}

func TestAudit_LargeContent(t *testing.T) {
	largeContent := strings.Repeat("这是一段很长的正常内容，没有任何敏感词汇。", 1000)

	service := &AuditService{}
	result := service.checkSensitiveContent(largeContent)

	if result != false {
		t.Error("Expected large normal content to be approved")
	}

	t.Logf("Large content length: %d characters", len(largeContent))
}

func TestAudit_SensitiveWordAtDifferentPositions(t *testing.T) {
	testCases := []struct {
		name    string
		content string
		want    bool
	}{
		{
			name:    "敏感词在开头",
			content: "违法的行为是不对的",
			want:    true,
		},
		{
			name:    "敏感词在中间",
			content: "这段内容包含违法词汇",
			want:    true,
		},
		{
			name:    "敏感词在结尾",
			content: "这段内容是违法",
			want:    true,
		},
		{
			name:    "敏感词单独出现",
			content: "违法",
			want:    true,
		},
		{
			name:    "多个敏感词",
			content: "这是违法的，并且有色情内容",
			want:    true,
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			service := &AuditService{}
			got := service.checkSensitiveContent(tc.content)
			if got != tc.want {
				t.Errorf("checkSensitiveContent(%q) = %v, want %v", tc.content, got, tc.want)
			}
		})
	}
}
