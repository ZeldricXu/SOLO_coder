package services

import (
	"notifypush/internal/config"
	"notifypush/internal/models"
	"notifypush/internal/storage"
	"sync"
	"time"
)

type UrgencyLevel string

const (
	UrgencyLevelUrgent UrgencyLevel = "urgent"
	UrgencyLevelHigh   UrgencyLevel = "high"
	UrgencyLevelMedium UrgencyLevel = "medium"
	UrgencyLevelLow    UrgencyLevel = "low"
	UrgencyLevelDefault UrgencyLevel = "default"
)

type PendingQuery struct {
	NotifyID       string
	UrgencyLevel   UrgencyLevel
	NextQueryAt    time.Time
	QueryCount     int
	MaxQueries     int
	LastStatus     models.SendStatus
	LastDelivery   models.DeliveryStatus
}

type StatusQueryService struct {
	storage       *storage.MemoryStorage
	cfg           *config.StatusQueryConfig
	statusTracker *StatusTracker
	pending       map[string]*PendingQuery
	mu            sync.Mutex
	stopChan      chan struct{}
	running       bool
}

func NewStatusQueryService(
	storage *storage.MemoryStorage,
	cfg *config.StatusQueryConfig,
	statusTracker *StatusTracker,
) *StatusQueryService {
	return &StatusQueryService{
		storage:       storage,
		cfg:           cfg,
		statusTracker: statusTracker,
		pending:       make(map[string]*PendingQuery),
		stopChan:      make(chan struct{}),
	}
}

func (s *StatusQueryService) Start() {
	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return
	}
	s.running = true
	s.stopChan = make(chan struct{})
	s.mu.Unlock()
	go s.queryLoop()
}

func (s *StatusQueryService) Stop() {
	s.mu.Lock()
	if !s.running {
		s.mu.Unlock()
		return
	}
	s.running = false
	close(s.stopChan)
	s.mu.Unlock()
}

func (s *StatusQueryService) GetQueryInterval(urgency UrgencyLevel) int {
	switch urgency {
	case UrgencyLevelUrgent:
		return s.cfg.UrgentIntervalSec
	case UrgencyLevelHigh:
		return s.cfg.HighIntervalSec
	case UrgencyLevelMedium:
		return s.cfg.MediumIntervalSec
	case UrgencyLevelLow:
		return s.cfg.LowIntervalSec
	default:
		return s.cfg.DefaultIntervalSec
	}
}

func PriorityToUrgency(priority int) UrgencyLevel {
	switch {
	case priority <= 2:
		return UrgencyLevelUrgent
	case priority <= 4:
		return UrgencyLevelHigh
	case priority <= 6:
		return UrgencyLevelMedium
	case priority <= 8:
		return UrgencyLevelLow
	default:
		return UrgencyLevelDefault
	}
}

func (s *StatusQueryService) RegisterForQuery(notifyID string, priority int) {
	urgency := PriorityToUrgency(priority)
	interval := s.GetQueryInterval(urgency)
	query := &PendingQuery{
		NotifyID:     notifyID,
		UrgencyLevel: urgency,
		NextQueryAt:  time.Now().Add(time.Duration(interval) * time.Second),
		QueryCount:   0,
		MaxQueries:   10,
		LastStatus:   models.SendStatusPending,
		LastDelivery: models.DeliveryStatusPending,
	}
	s.mu.Lock()
	s.pending[notifyID] = query
	s.mu.Unlock()
}

func (s *StatusQueryService) UnregisterFromQuery(notifyID string) {
	s.mu.Lock()
	delete(s.pending, notifyID)
	s.mu.Unlock()
}

func (s *StatusQueryService) queryLoop() {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-s.stopChan:
			return
		case <-ticker.C:
			s.processPendingQueries()
		}
	}
}

func (s *StatusQueryService) processPendingQueries() {
	s.mu.Lock()
	now := time.Now()
	var toQuery []*PendingQuery
	for notifyID, query := range s.pending {
		if now.After(query.NextQueryAt) {
			toQuery = append(toQuery, query)
			query.QueryCount++
			if query.QueryCount >= query.MaxQueries {
				delete(s.pending, notifyID)
			} else {
				interval := s.GetQueryInterval(query.UrgencyLevel)
				query.NextQueryAt = now.Add(time.Duration(interval) * time.Second)
			}
		}
	}
	s.mu.Unlock()
	for _, query := range toQuery {
		s.performQuery(query)
	}
}

func (s *StatusQueryService) performQuery(query *PendingQuery) {
	record, err := s.statusTracker.GetStatus(query.NotifyID)
	if err != nil {
		return
	}
	if record == nil {
		return
	}
	if record.SendStatus != query.LastStatus {
		query.LastStatus = record.SendStatus
	}
	if record.DeliveryStatus != query.LastDelivery {
		query.LastDelivery = record.DeliveryStatus
	}
	if record.DeliveryStatus == models.DeliveryStatusDelivered ||
		record.DeliveryStatus == models.DeliveryStatusFailed {
		s.UnregisterFromQuery(query.NotifyID)
	}
}

func (s *StatusQueryService) GetPendingCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.pending)
}

func (s *StatusQueryService) IsRunning() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.running
}
