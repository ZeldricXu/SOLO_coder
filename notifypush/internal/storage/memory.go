package storage

import (
	"notifypush/internal/models"
	"sync"
	"time"
)

type MemoryStorage struct {
	notifications    map[string]*models.Notification
	templates        map[string]*models.Template
	statusRecords    map[string]*models.SendStatusRecord
	batchTasks       map[string]*models.BatchTask
	channelConfigs   map[string]*models.ChannelConfig
	statistics       map[string]*models.Statistics
	muNotifications  sync.RWMutex
	muTemplates      sync.RWMutex
	muStatus         sync.RWMutex
	muBatch          sync.RWMutex
	muChannels       sync.RWMutex
	muStatistics     sync.RWMutex
}

func NewMemoryStorage() *MemoryStorage {
	return &MemoryStorage{
		notifications:  make(map[string]*models.Notification),
		templates:      make(map[string]*models.Template),
		statusRecords:  make(map[string]*models.SendStatusRecord),
		batchTasks:     make(map[string]*models.BatchTask),
		channelConfigs: make(map[string]*models.ChannelConfig),
		statistics:     make(map[string]*models.Statistics),
	}
}

func (s *MemoryStorage) SaveNotification(notify *models.Notification) error {
	s.muNotifications.Lock()
	defer s.muNotifications.Unlock()
	s.notifications[notify.NotifyID] = notify
	return nil
}

func (s *MemoryStorage) GetNotification(notifyID string) (*models.Notification, error) {
	s.muNotifications.RLock()
	defer s.muNotifications.RUnlock()
	if notify, exists := s.notifications[notifyID]; exists {
		return notify, nil
	}
	return nil, nil
}

func (s *MemoryStorage) UpdateNotificationStatus(notifyID string, status models.NotifyStatus, retryCount int) error {
	s.muNotifications.Lock()
	defer s.muNotifications.Unlock()
	if notify, exists := s.notifications[notifyID]; exists {
		notify.Status = status
		notify.RetryCount = retryCount
	}
	return nil
}

func (s *MemoryStorage) SaveTemplate(template *models.Template) error {
	s.muTemplates.Lock()
	defer s.muTemplates.Unlock()
	s.templates[template.TemplateID] = template
	return nil
}

func (s *MemoryStorage) GetTemplate(templateID string) (*models.Template, error) {
	s.muTemplates.RLock()
	defer s.muTemplates.RUnlock()
	if template, exists := s.templates[templateID]; exists {
		return template, nil
	}
	return nil, nil
}

func (s *MemoryStorage) SaveStatusRecord(record *models.SendStatusRecord) error {
	s.muStatus.Lock()
	defer s.muStatus.Unlock()
	s.statusRecords[record.StatusID] = record
	return nil
}

func (s *MemoryStorage) GetStatusRecord(notifyID string) (*models.SendStatusRecord, error) {
	s.muStatus.RLock()
	defer s.muStatus.RUnlock()
	for _, record := range s.statusRecords {
		if record.NotifyID == notifyID {
			return record, nil
		}
	}
	return nil, nil
}

func (s *MemoryStorage) UpdateStatusRecordDelivery(notifyID string, deliveryStatus models.DeliveryStatus, deliveryTime time.Time) error {
	s.muStatus.Lock()
	defer s.muStatus.Unlock()
	for _, record := range s.statusRecords {
		if record.NotifyID == notifyID {
			record.DeliveryStatus = deliveryStatus
			record.DeliveryTime = &deliveryTime
			break
		}
	}
	return nil
}

func (s *MemoryStorage) SaveBatchTask(batch *models.BatchTask) error {
	s.muBatch.Lock()
	defer s.muBatch.Unlock()
	s.batchTasks[batch.BatchID] = batch
	return nil
}

func (s *MemoryStorage) GetBatchTask(batchID string) (*models.BatchTask, error) {
	s.muBatch.RLock()
	defer s.muBatch.RUnlock()
	if batch, exists := s.batchTasks[batchID]; exists {
		return batch, nil
	}
	return nil, nil
}

func (s *MemoryStorage) UpdateBatchProgress(batchID string, sentCount, successCount, failCount int) error {
	s.muBatch.Lock()
	defer s.muBatch.Unlock()
	if batch, exists := s.batchTasks[batchID]; exists {
		batch.SentCount = sentCount
		batch.SuccessCount = successCount
		batch.FailCount = failCount
	}
	return nil
}

func (s *MemoryStorage) UpdateBatchStatus(batchID string, status models.BatchStatus) error {
	s.muBatch.Lock()
	defer s.muBatch.Unlock()
	if batch, exists := s.batchTasks[batchID]; exists {
		batch.Status = status
		now := time.Now()
		if status == models.BatchStatusProcessing && batch.StartedAt == nil {
			batch.StartedAt = &now
		} else if status == models.BatchStatusCompleted || status == models.BatchStatusFailed {
			batch.CompletedAt = &now
		}
	}
	return nil
}

func (s *MemoryStorage) SaveChannelConfig(config *models.ChannelConfig) error {
	s.muChannels.Lock()
	defer s.muChannels.Unlock()
	s.channelConfigs[config.ChannelID] = config
	return nil
}

func (s *MemoryStorage) GetChannelConfig(channelType string) (*models.ChannelConfig, error) {
	s.muChannels.RLock()
	defer s.muChannels.RUnlock()
	for _, config := range s.channelConfigs {
		if string(config.ChannelType) == channelType && config.Status == models.ChannelStatusActive {
			return config, nil
		}
	}
	return nil, nil
}

func (s *MemoryStorage) GetStatistics(date string, channel string) (*models.Statistics, error) {
	s.muStatistics.RLock()
	defer s.muStatistics.RUnlock()
	key := date + "_" + channel
	if stat, exists := s.statistics[key]; exists {
		return stat, nil
	}
	return nil, nil
}

func (s *MemoryStorage) IncrementStatistics(date string, channel string, success bool) error {
	s.muStatistics.Lock()
	defer s.muStatistics.Unlock()
	key := date + "_" + channel
	stat, exists := s.statistics[key]
	if !exists {
		stat = &models.Statistics{
			StatID:   "stat_" + key,
			StatDate: date,
			Channel:  channel,
		}
		s.statistics[key] = stat
	}
	stat.SendCount++
	if success {
		stat.SuccessCount++
	} else {
		stat.FailCount++
	}
	if stat.SendCount > 0 {
		stat.DeliveryRate = float64(stat.SuccessCount) / float64(stat.SendCount) * 100
	}
	return nil
}
