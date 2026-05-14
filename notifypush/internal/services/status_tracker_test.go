package services

import (
	"notifypush/internal/models"
	"notifypush/internal/storage"
	"notifypush/internal/testdata"
	"testing"
	"time"
)

func TestStatusTracker_CreateStatusRecord(t *testing.T) {
	storage := storage.NewMemoryStorage()
	tracker := NewStatusTracker(storage)
	
	notifyID := "notify_test_001"
	channel := "sms"
	
	record := tracker.CreateStatusRecord(notifyID, channel)
	
	if record == nil {
		t.Fatal("Expected status record, got nil")
	}
	
	if record.NotifyID != notifyID {
		t.Errorf("Expected notify_id '%s', got '%s'", notifyID, record.NotifyID)
	}
	
	if record.Channel != channel {
		t.Errorf("Expected channel '%s', got '%s'", channel, record.Channel)
	}
	
	if record.SendStatus != models.SendStatusPending {
		t.Errorf("Expected send status 'pending', got '%s'", record.SendStatus)
	}
	
	if record.DeliveryStatus != models.DeliveryStatusPending {
		t.Errorf("Expected delivery status 'pending', got '%s'", record.DeliveryStatus)
	}
	
	if record.RetryAttempt != 0 {
		t.Errorf("Expected retry_attempt 0, got %d", record.RetryAttempt)
	}
}

func TestStatusTracker_UpdateSendStatus(t *testing.T) {
	storage := storage.NewMemoryStorage()
	tracker := NewStatusTracker(storage)
	
	notifyID := "notify_test_002"
	tracker.CreateStatusRecord(notifyID, "sms")
	
	tracker.UpdateSendStatus(notifyID, models.SendStatusSuccess, "", 0)
	
	record, err := tracker.GetStatus(notifyID)
	if err != nil {
		t.Fatalf("Expected no error, got: %v", err)
	}
	
	if record.SendStatus != models.SendStatusSuccess {
		t.Errorf("Expected send status 'success', got '%s'", record.SendStatus)
	}
	
	if record.SendTime == nil {
		t.Error("Expected send_time to be set on success")
	}
}

func TestStatusTracker_UpdateSendStatus_Failed(t *testing.T) {
	storage := storage.NewMemoryStorage()
	tracker := NewStatusTracker(storage)
	
	notifyID := "notify_test_003"
	tracker.CreateStatusRecord(notifyID, "sms")
	
	errorMsg := "channel connection failed"
	tracker.UpdateSendStatus(notifyID, models.SendStatusFailed, errorMsg, 1)
	
	record, _ := tracker.GetStatus(notifyID)
	
	if record.SendStatus != models.SendStatusFailed {
		t.Errorf("Expected send status 'failed', got '%s'", record.SendStatus)
	}
	
	if record.ErrorMessage != errorMsg {
		t.Errorf("Expected error message '%s', got '%s'", errorMsg, record.ErrorMessage)
	}
	
	if record.RetryAttempt != 1 {
		t.Errorf("Expected retry_attempt 1, got %d", record.RetryAttempt)
	}
}

func TestStatusTracker_UpdateDeliveryStatus(t *testing.T) {
	storage := storage.NewMemoryStorage()
	tracker := NewStatusTracker(storage)
	
	notifyID := "notify_test_004"
	tracker.CreateStatusRecord(notifyID, "sms")
	
	tracker.UpdateSendStatus(notifyID, models.SendStatusSuccess, "", 0)
	tracker.UpdateDeliveryStatus(notifyID, models.DeliveryStatusDelivered)
	
	record, _ := tracker.GetStatus(notifyID)
	
	if record.DeliveryStatus != models.DeliveryStatusDelivered {
		t.Errorf("Expected delivery status 'delivered', got '%s'", record.DeliveryStatus)
	}
	
	if record.DeliveryTime == nil {
		t.Error("Expected delivery_time to be set")
	}
}

func TestStatusTracker_StatusTransitions(t *testing.T) {
	storage := storage.NewMemoryStorage()
	tracker := NewStatusTracker(storage)
	
	notifyID := "notify_test_transition"
	tracker.CreateStatusRecord(notifyID, "sms")
	
	transitions := []struct {
		sendStatus     models.SendStatus
		deliveryStatus models.DeliveryStatus
		retryAttempt   int
		errorMsg       string
	}{
		{
			sendStatus:     models.SendStatusPending,
			deliveryStatus: models.DeliveryStatusPending,
			retryAttempt:   0,
			errorMsg:       "",
		},
		{
			sendStatus:     models.SendStatusRetrying,
			deliveryStatus: models.DeliveryStatusPending,
			retryAttempt:   1,
			errorMsg:       "temporary error",
		},
		{
			sendStatus:     models.SendStatusSuccess,
			deliveryStatus: models.DeliveryStatusPending,
			retryAttempt:   1,
			errorMsg:       "",
		},
		{
			sendStatus:     models.SendStatusSuccess,
			deliveryStatus: models.DeliveryStatusDelivered,
			retryAttempt:   1,
			errorMsg:       "",
		},
	}
	
	for i, trans := range transitions {
		t.Run("transition_"+string(rune(i)), func(t *testing.T) {
			if i > 0 {
				tracker.UpdateSendStatus(notifyID, trans.sendStatus, trans.errorMsg, trans.retryAttempt)
				if trans.deliveryStatus != models.DeliveryStatusPending {
					tracker.UpdateDeliveryStatus(notifyID, trans.deliveryStatus)
				}
			}
			
			record, _ := tracker.GetStatus(notifyID)
			
			if record.SendStatus != trans.sendStatus {
				t.Errorf("Step %d: Expected send_status '%s', got '%s'", i, trans.sendStatus, record.SendStatus)
			}
			
			if record.DeliveryStatus != trans.deliveryStatus {
				t.Errorf("Step %d: Expected delivery_status '%s', got '%s'", i, trans.deliveryStatus, record.DeliveryStatus)
			}
			
			if record.RetryAttempt != trans.retryAttempt {
				t.Errorf("Step %d: Expected retry_attempt %d, got %d", i, trans.retryAttempt, record.RetryAttempt)
			}
		})
	}
}

func TestStatusTracker_StatusUpdateTimeliness(t *testing.T) {
	storage := storage.NewMemoryStorage()
	tracker := NewStatusTracker(storage)
	
	notifyID := "notify_test_timeliness"
	tracker.CreateStatusRecord(notifyID, "sms")
	
	start := time.Now()
	tracker.UpdateSendStatus(notifyID, models.SendStatusSuccess, "", 0)
	elapsed := time.Since(start)
	
	if elapsed > 10*time.Millisecond {
		t.Errorf("Status update too slow: took %v", elapsed)
	}
	
	record, _ := tracker.GetStatus(notifyID)
	if record.SendTime == nil {
		t.Fatal("SendTime should be set")
	}
	
	timeDiff := record.SendTime.Sub(start)
	if timeDiff < 0 || timeDiff > 50*time.Millisecond {
		t.Errorf("SendTime not accurate: diff %v", timeDiff)
	}
}

func TestStatusTracker_ConcurrentUpdates(t *testing.T) {
	storage := storage.NewMemoryStorage()
	tracker := NewStatusTracker(storage)
	
	notifyIDs := make([]string, 100)
	for i := 0; i < 100; i++ {
		notifyIDs[i] = "notify_concurrent_" + string(rune(i))
		tracker.CreateStatusRecord(notifyIDs[i], "sms")
	}
	
	done := make(chan bool)
	
	for _, notifyID := range notifyIDs {
		go func(id string) {
			tracker.UpdateSendStatus(id, models.SendStatusSuccess, "", 0)
			tracker.UpdateDeliveryStatus(id, models.DeliveryStatusDelivered)
			done <- true
		}(notifyID)
	}
	
	for i := 0; i < 100; i++ {
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatal("Timeout waiting for concurrent updates")
		}
	}
	
	for _, notifyID := range notifyIDs {
		record, err := tracker.GetStatus(notifyID)
		if err != nil {
			t.Errorf("Error getting status for %s: %v", notifyID, err)
			continue
		}
		if record.SendStatus != models.SendStatusSuccess {
			t.Errorf("%s: Expected success, got %s", notifyID, record.SendStatus)
		}
		if record.DeliveryStatus != models.DeliveryStatusDelivered {
			t.Errorf("%s: Expected delivered, got %s", notifyID, record.DeliveryStatus)
		}
	}
}

func TestStatusTracker_DeliveryFailed(t *testing.T) {
	storage := storage.NewMemoryStorage()
	tracker := NewStatusTracker(storage)
	
	notifyID := "notify_test_delivery_fail"
	tracker.CreateStatusRecord(notifyID, "sms")
	
	tracker.UpdateSendStatus(notifyID, models.SendStatusSuccess, "", 0)
	tracker.UpdateDeliveryStatus(notifyID, models.DeliveryStatusFailed)
	
	record, _ := tracker.GetStatus(notifyID)
	
	if record.SendStatus != models.SendStatusSuccess {
		t.Errorf("Expected send_status 'success', got '%s'", record.SendStatus)
	}
	
	if record.DeliveryStatus != models.DeliveryStatusFailed {
		t.Errorf("Expected delivery_status 'failed', got '%s'", record.DeliveryStatus)
	}
}

func TestStatusTracker_WithTestDataBuilder(t *testing.T) {
	storage := storage.NewMemoryStorage()
	tracker := NewStatusTracker(storage)
	
	record := testdata.NewSendStatusRecordBuilder().
		WithNotifyID("notify_builder_test").
		WithChannel("email").
		WithSendStatus(models.SendStatusPending).
		WithDeliveryStatus(models.DeliveryStatusPending).
		Build()
	
	if record.NotifyID != "notify_builder_test" {
		t.Errorf("Expected notify_id 'notify_builder_test', got '%s'", record.NotifyID)
	}
	
	if record.Channel != "email" {
		t.Errorf("Expected channel 'email', got '%s'", record.Channel)
	}
}

func TestStatusTracker_AllStatusValues(t *testing.T) {
	storage := storage.NewMemoryStorage()
	tracker := NewStatusTracker(storage)
	
	testCases := []struct {
		name           string
		sendStatus     models.SendStatus
		deliveryStatus models.DeliveryStatus
	}{
		{"pending_pending", models.SendStatusPending, models.DeliveryStatusPending},
		{"success_pending", models.SendStatusSuccess, models.DeliveryStatusPending},
		{"success_delivered", models.SendStatusSuccess, models.DeliveryStatusDelivered},
		{"success_failed", models.SendStatusSuccess, models.DeliveryStatusFailed},
		{"failed_pending", models.SendStatusFailed, models.DeliveryStatusPending},
		{"retrying_pending", models.SendStatusRetrying, models.DeliveryStatusPending},
	}
	
	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			notifyID := "notify_" + tc.name
			tracker.CreateStatusRecord(notifyID, "sms")
			
			tracker.UpdateSendStatus(notifyID, tc.sendStatus, "", 0)
			if tc.deliveryStatus != models.DeliveryStatusPending {
				tracker.UpdateDeliveryStatus(notifyID, tc.deliveryStatus)
			}
			
			record, _ := tracker.GetStatus(notifyID)
			
			if record.SendStatus != tc.sendStatus {
				t.Errorf("Expected send_status '%s', got '%s'", tc.sendStatus, record.SendStatus)
			}
			
			if record.DeliveryStatus != tc.deliveryStatus {
				t.Errorf("Expected delivery_status '%s', got '%s'", tc.deliveryStatus, record.DeliveryStatus)
			}
		})
	}
}
