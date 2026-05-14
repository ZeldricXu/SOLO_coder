package services

import (
	"notifypush/internal/models"
	"notifypush/internal/storage"
	"time"
)

type StatusTracker struct {
	storage *storage.MemoryStorage
}

func NewStatusTracker(storage *storage.MemoryStorage) *StatusTracker {
	return &StatusTracker{
		storage: storage,
	}
}

func (t *StatusTracker) CreateStatusRecord(notifyID, channel string) *models.SendStatusRecord {
	now := time.Now()
	record := &models.SendStatusRecord{
		StatusID:       "status_" + notifyID,
		NotifyID:       notifyID,
		Channel:        channel,
		SendStatus:     models.SendStatusPending,
		DeliveryStatus: models.DeliveryStatusPending,
		RetryAttempt:   0,
		CreatedAt:      now,
	}
	t.storage.SaveStatusRecord(record)
	return record
}

func (t *StatusTracker) UpdateSendStatus(notifyID string, sendStatus models.SendStatus, errorMsg string, retryAttempt int) {
	record, _ := t.storage.GetStatusRecord(notifyID)
	if record != nil {
		record.SendStatus = sendStatus
		record.ErrorMessage = errorMsg
		record.RetryAttempt = retryAttempt
		if sendStatus == models.SendStatusSuccess {
			now := time.Now()
			record.SendTime = &now
			record.DeliveryStatus = models.DeliveryStatusPending
		}
		t.storage.SaveStatusRecord(record)
	}
}

func (t *StatusTracker) UpdateDeliveryStatus(notifyID string, deliveryStatus models.DeliveryStatus) {
	now := time.Now()
	t.storage.UpdateStatusRecordDelivery(notifyID, deliveryStatus, now)
}

func (t *StatusTracker) GetStatus(notifyID string) (*models.SendStatusRecord, error) {
	record, err := t.storage.GetStatusRecord(notifyID)
	if err != nil {
		return nil, err
	}
	return record, nil
}
