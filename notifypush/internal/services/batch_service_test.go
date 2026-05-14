package services

import (
	"notifypush/internal/channels"
	"notifypush/internal/models"
	"notifypush/internal/storage"
	"notifypush/internal/testdata"
	"sync"
	"testing"
	"time"
)

func TestBatchService_CreateBatchTask(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	templateService := NewTemplateService(storage)
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	
	smsChannel := channels.NewSMSChannel("aliyun", "test_key", "测试签名", "SMS_123")
	channelRegistry.Register(models.ChannelTypeSMS, smsChannel)
	
	batchService := NewBatchService(storage, channelRegistry, templateService, statusTracker, retryService, statisticsService)
	
	template := testdata.NewTemplateBuilder().
		WithTemplateID("template_batch_test").
		Build()
	storage.SaveTemplate(template)
	
	req := &models.BatchSendRequest{
		TemplateID: "template_batch_test",
		Channel:    "sms",
		Receivers:  []string{"13800138000", "13800138001", "13800138002"},
		BatchSize:  100,
	}
	
	start := time.Now()
	response, err := batchService.CreateBatchTask(req)
	elapsed := time.Since(start)
	
	if err != nil {
		t.Fatalf("Expected no error, got: %v", err)
	}
	
	if response == nil {
		t.Fatal("Expected response, got nil")
	}
	
	if response.BatchID == "" {
		t.Error("Expected batch_id to be set")
	}
	
	if response.Status != string(models.BatchStatusProcessing) {
		t.Errorf("Expected status 'processing', got '%s'", response.Status)
	}
	
	if elapsed > 100*time.Millisecond {
		t.Errorf("Batch task creation too slow: took %v, should return immediately", elapsed)
	}
	
	t.Logf("Batch task created: %s in %v", response.BatchID, elapsed)
}

func TestBatchService_BatchProgress(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	templateService := NewTemplateService(storage)
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	
	smsChannel := channels.NewSMSChannel("aliyun", "test_key", "测试签名", "SMS_123")
	channelRegistry.Register(models.ChannelTypeSMS, smsChannel)
	
	batchService := NewBatchService(storage, channelRegistry, templateService, statusTracker, retryService, statisticsService)
	
	template := testdata.NewTemplateBuilder().
		WithTemplateID("template_progress_test").
		Build()
	storage.SaveTemplate(template)
	
	receivers := testdata.GeneratePhoneNumbers(50)
	
	req := &models.BatchSendRequest{
		TemplateID: "template_progress_test",
		Channel:    "sms",
		Receivers:  receivers,
		BatchSize:  10,
	}
	
	response, err := batchService.CreateBatchTask(req)
	if err != nil {
		t.Fatalf("Failed to create batch task: %v", err)
	}
	
	time.Sleep(500 * time.Millisecond)
	
	stats, err := batchService.GetBatchStatus(response.BatchID)
	if err != nil {
		t.Fatalf("Failed to get batch status: %v", err)
	}
	
	if stats.TotalCount != 50 {
		t.Errorf("Expected total count 50, got %d", stats.TotalCount)
	}
	
	t.Logf("Batch progress: total=%d, sent=%d, success=%d, fail=%d, rate=%.2f%%",
		stats.TotalCount, stats.SentCount, stats.SuccessCount, stats.FailCount, stats.SuccessRate)
}

func TestBatchService_LargeBatchNonBlocking(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	templateService := NewTemplateService(storage)
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	
	smsChannel := channels.NewSMSChannel("aliyun", "test_key", "测试签名", "SMS_123")
	channelRegistry.Register(models.ChannelTypeSMS, smsChannel)
	
	batchService := NewBatchService(storage, channelRegistry, templateService, statusTracker, retryService, statisticsService)
	
	template := testdata.NewTemplateBuilder().
		WithTemplateID("template_large_batch").
		Build()
	storage.SaveTemplate(template)
	
	receivers := testdata.GeneratePhoneNumbers(500)
	
	req := &models.BatchSendRequest{
		TemplateID: "template_large_batch",
		Channel:    "sms",
		Receivers:  receivers,
		BatchSize:  50,
	}
	
	start := time.Now()
	response, err := batchService.CreateBatchTask(req)
	elapsed := time.Since(start)
	
	if err != nil {
		t.Fatalf("Failed to create batch: %v", err)
	}
	
	if elapsed > 500*time.Millisecond {
		t.Errorf("Large batch creation blocked too long: %v, should return immediately", elapsed)
	}
	
	t.Logf("Large batch %s created in %v, total receivers: %d", response.BatchID, elapsed, len(receivers))
	
	initialStats, _ := batchService.GetBatchStatus(response.BatchID)
	t.Logf("Initial progress: sent=%d/%d", initialStats.SentCount, initialStats.TotalCount)
	
	time.Sleep(2 * time.Second)
	
	finalStats, _ := batchService.GetBatchStatus(response.BatchID)
	t.Logf("Final progress: sent=%d/%d, success=%d, fail=%d",
		finalStats.SentCount, finalStats.TotalCount, finalStats.SuccessCount, finalStats.FailCount)
}

func TestBatchService_StatusTransitions(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	templateService := NewTemplateService(storage)
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	
	smsChannel := channels.NewSMSChannel("aliyun", "test_key", "测试签名", "SMS_123")
	channelRegistry.Register(models.ChannelTypeSMS, smsChannel)
	
	batchService := NewBatchService(storage, channelRegistry, templateService, statusTracker, retryService, statisticsService)
	
	template := testdata.NewTemplateBuilder().
		WithTemplateID("template_status_trans").
		Build()
	storage.SaveTemplate(template)
	
	receivers := testdata.GeneratePhoneNumbers(100)
	
	req := &models.BatchSendRequest{
		TemplateID: "template_status_trans",
		Channel:    "sms",
		Receivers:  receivers,
		BatchSize:  20,
	}
	
	response, err := batchService.CreateBatchTask(req)
	if err != nil {
		t.Fatalf("Failed to create batch: %v", err)
	}
	
	batch, _ := storage.GetBatchTask(response.BatchID)
	if batch.Status != models.BatchStatusProcessing && batch.Status != models.BatchStatusPending {
		t.Errorf("Expected initial status 'processing' or 'pending', got '%s'", batch.Status)
	}
	
	time.Sleep(3 * time.Second)
	
	completedBatch, _ := storage.GetBatchTask(response.BatchID)
	if completedBatch.Status != models.BatchStatusCompleted {
		t.Errorf("Expected final status 'completed', got '%s'", completedBatch.Status)
	}
	
	if completedBatch.SentCount != completedBatch.TotalCount {
		t.Errorf("Expected all sent: sent=%d, total=%d", completedBatch.SentCount, completedBatch.TotalCount)
	}
	
	t.Logf("Batch completed: sent=%d, success=%d, fail=%d",
		completedBatch.SentCount, completedBatch.SuccessCount, completedBatch.FailCount)
}

func TestBatchService_MultipleBatchesConcurrent(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	templateService := NewTemplateService(storage)
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	
	smsChannel := channels.NewSMSChannel("aliyun", "test_key", "测试签名", "SMS_123")
	channelRegistry.Register(models.ChannelTypeSMS, smsChannel)
	
	batchService := NewBatchService(storage, channelRegistry, templateService, statusTracker, retryService, statisticsService)
	
	template := testdata.NewTemplateBuilder().
		WithTemplateID("template_concurrent").
		Build()
	storage.SaveTemplate(template)
	
	numBatches := 5
	var wg sync.WaitGroup
	results := make([]string, numBatches)
	
	start := time.Now()
	
	for i := 0; i < numBatches; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			receivers := testdata.GeneratePhoneNumbers(50 + idx*10)
			req := &models.BatchSendRequest{
				TemplateID: "template_concurrent",
				Channel:    "sms",
				Receivers:  receivers,
				BatchSize:  25,
			}
			resp, err := batchService.CreateBatchTask(req)
			if err != nil {
				t.Errorf("Batch %d failed: %v", idx, err)
				return
			}
			results[idx] = resp.BatchID
		}(i)
	}
	
	wg.Wait()
	elapsed := time.Since(start)
	
	t.Logf("Created %d batches in %v", numBatches, elapsed)
	
	if elapsed > 1*time.Second {
		t.Errorf("Concurrent batch creation too slow: %v", elapsed)
	}
	
	time.Sleep(3 * time.Second)
	
	for i, batchID := range results {
		if batchID == "" {
			continue
		}
		stats, err := batchService.GetBatchStatus(batchID)
		if err != nil {
			t.Errorf("Batch %d (%s) status error: %v", i, batchID, err)
			continue
		}
		t.Logf("Batch %d (%s): total=%d, sent=%d, success=%d",
			i, batchID, stats.TotalCount, stats.SentCount, stats.SuccessCount)
	}
}

func TestBatchService_WithTestDataBuilder(t *testing.T) {
	batchTask := testdata.NewBatchTaskBuilder().
		WithBatchID("batch_builder_test").
		WithChannel("sms").
		WithTemplateID("template_test").
		WithReceivers(testdata.GeneratePhoneNumbers(10)).
		WithBatchSize(5).
		WithStatus(models.BatchStatusPending).
		Build()
	
	if batchTask.BatchID != "batch_builder_test" {
		t.Errorf("Expected batch_id 'batch_builder_test', got '%s'", batchTask.BatchID)
	}
	
	if batchTask.Channel != "sms" {
		t.Errorf("Expected channel 'sms', got '%s'", batchTask.Channel)
	}
	
	if batchTask.TotalCount != 10 {
		t.Errorf("Expected total count 10, got %d", batchTask.TotalCount)
	}
	
	if batchTask.BatchSize != 5 {
		t.Errorf("Expected batch size 5, got %d", batchTask.BatchSize)
	}
}

func TestBatchService_InvalidRequest(t *testing.T) {
	storage := storage.NewMemoryStorage()
	channelRegistry := channels.NewChannelRegistry()
	templateService := NewTemplateService(storage)
	statusTracker := NewStatusTracker(storage)
	statisticsService := NewStatisticsService(storage)
	retryService := NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	
	smsChannel := channels.NewSMSChannel("aliyun", "test_key", "测试签名", "SMS_123")
	channelRegistry.Register(models.ChannelTypeSMS, smsChannel)
	
	batchService := NewBatchService(storage, channelRegistry, templateService, statusTracker, retryService, statisticsService)
	
	testCases := []struct {
		name    string
		req     *models.BatchSendRequest
		wantErr bool
	}{
		{
			name: "missing template_id",
			req: &models.BatchSendRequest{
				TemplateID: "",
				Channel:    "sms",
				Receivers:  []string{"13800138000"},
			},
			wantErr: true,
		},
		{
			name: "missing channel",
			req: &models.BatchSendRequest{
				TemplateID: "template_test",
				Channel:    "",
				Receivers:  []string{"13800138000"},
			},
			wantErr: true,
		},
		{
			name: "empty receivers",
			req: &models.BatchSendRequest{
				TemplateID: "template_test",
				Channel:    "sms",
				Receivers:  []string{},
			},
			wantErr: true,
		},
		{
			name: "unsupported channel",
			req: &models.BatchSendRequest{
				TemplateID: "template_test",
				Channel:    "unsupported_channel",
				Receivers:  []string{"13800138000"},
			},
			wantErr: true,
		},
	}
	
	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := batchService.CreateBatchTask(tc.req)
			if tc.wantErr && err == nil {
				t.Error("Expected error, got nil")
			}
			if !tc.wantErr && err != nil {
				t.Errorf("Expected no error, got: %v", err)
			}
		})
	}
}
