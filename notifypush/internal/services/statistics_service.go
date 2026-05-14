package services

import (
	"notifypush/internal/models"
	"notifypush/internal/storage"
	"time"
)

type StatisticsService struct {
	storage *storage.MemoryStorage
}

func NewStatisticsService(storage *storage.MemoryStorage) *StatisticsService {
	return &StatisticsService{
		storage: storage,
	}
}

func (s *StatisticsService) RecordSend(channel string, success bool) error {
	today := time.Now().Format("2006-01-02")
	return s.storage.IncrementStatistics(today, channel, success)
}

func (s *StatisticsService) GetStatistics(date string, channel string) (*models.Statistics, error) {
	stats, err := s.storage.GetStatistics(date, channel)
	if err != nil {
		return nil, err
	}
	if stats == nil {
		return &models.Statistics{
			StatDate:     date,
			Channel:      channel,
			SendCount:    0,
			SuccessCount: 0,
			FailCount:    0,
			DeliveryRate: 0,
		}, nil
	}
	return stats, nil
}

func (s *StatisticsService) GetTodayStatistics(channel string) (*models.Statistics, error) {
	today := time.Now().Format("2006-01-02")
	return s.GetStatistics(today, channel)
}
