package service

import (
	"context"
	"sync"
	"time"

	"github.com/solocoder/session147/internal/common/errors"
	"github.com/solocoder/session147/internal/common/logger"
	"github.com/solocoder/session147/internal/common/utils"
	"github.com/solocoder/session147/internal/eventlistener/domain"
	"github.com/solocoder/session147/internal/eventlistener/ports"
	"go.uber.org/zap"
)

type eventListenerService struct {
	repo       ports.EventRepository
	fetcher    ports.EventFetcher
	callback   ports.CallbackExecutor
	mu         sync.RWMutex
	listeners  map[string]context.CancelFunc
}

func NewEventListenerService(repo ports.EventRepository, fetcher ports.EventFetcher, callback ports.CallbackExecutor) ports.EventListenerService {
	return &eventListenerService{
		repo:      repo,
		fetcher:   fetcher,
		callback:  callback,
		listeners: make(map[string]context.CancelFunc),
	}
}

func (s *eventListenerService) CreateSubscription(ctx context.Context, req *domain.CreateSubscriptionRequest, createdBy string) (*domain.EventSubscription, error) {
	logger.Info("creating event subscription", zap.String("name", req.Name), zap.Int64("chain_id", req.ChainID))

	sub := &domain.EventSubscription{
		ID:          utils.GenerateID("sub"),
		ChainID:     req.ChainID,
		Name:        req.Name,
		Description: req.Description,
		Contract:    req.Contract,
		EventName:   req.EventName,
		EventSig:    req.EventSig,
		Topics:      req.Topics,
		FromBlock:   req.FromBlock,
		ToBlock:     req.ToBlock,
		CallbackURL: req.CallbackURL,
		CallbackType: req.CallbackType,
		Filter:      req.Filter,
		Status:      domain.SubscriptionStatusActive,
		Abi:         req.Abi,
		CreatedBy:   createdBy,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
	}

	if err := s.repo.CreateSubscription(ctx, sub); err != nil {
		return nil, errors.Internal("failed to create subscription", err)
	}

	go s.startListener(sub)
	return sub, nil
}

func (s *eventListenerService) GetSubscription(ctx context.Context, id string) (*domain.EventSubscription, error) {
	return s.repo.GetSubscription(ctx, id)
}

func (s *eventListenerService) ListSubscriptions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.EventSubscription, int64, error) {
	return s.repo.ListSubscriptions(ctx, filter, page, pageSize)
}

func (s *eventListenerService) UpdateSubscription(ctx context.Context, sub *domain.EventSubscription) (*domain.EventSubscription, error) {
	existing, err := s.repo.GetSubscription(ctx, sub.ID)
	if err != nil {
		return nil, errors.NotFound("subscription not found", err)
	}

	sub.CreatedAt = existing.CreatedAt
	sub.CreatedBy = existing.CreatedBy
	sub.UpdatedAt = time.Now()

	if err := s.repo.UpdateSubscription(ctx, sub); err != nil {
		return nil, errors.Internal("failed to update subscription", err)
	}

	return sub, nil
}

func (s *eventListenerService) PauseSubscription(ctx context.Context, id string) error {
	sub, err := s.repo.GetSubscription(ctx, id)
	if err != nil {
		return errors.NotFound("subscription not found", err)
	}

	sub.Status = domain.SubscriptionStatusPaused
	sub.UpdatedAt = time.Now()

	s.mu.Lock()
	if cancel, exists := s.listeners[id]; exists {
		cancel()
		delete(s.listeners, id)
	}
	s.mu.Unlock()

	return s.repo.UpdateSubscription(ctx, sub)
}

func (s *eventListenerService) ResumeSubscription(ctx context.Context, id string) error {
	sub, err := s.repo.GetSubscription(ctx, id)
	if err != nil {
		return errors.NotFound("subscription not found", err)
	}

	sub.Status = domain.SubscriptionStatusActive
	sub.UpdatedAt = time.Now()

	if err := s.repo.UpdateSubscription(ctx, sub); err != nil {
		return err
	}

	go s.startListener(sub)
	return nil
}

func (s *eventListenerService) DeleteSubscription(ctx context.Context, id string) error {
	_, err := s.repo.GetSubscription(ctx, id)
	if err != nil {
		return errors.NotFound("subscription not found", err)
	}

	s.mu.Lock()
	if cancel, exists := s.listeners[id]; exists {
		cancel()
		delete(s.listeners, id)
	}
	s.mu.Unlock()

	return s.repo.DeleteSubscription(ctx, id)
}

func (s *eventListenerService) GetEventLog(ctx context.Context, id string) (*domain.EventLogEntry, error) {
	return s.repo.GetEventLog(ctx, id)
}

func (s *eventListenerService) ListEventLogs(ctx context.Context, subscriptionID string, page, pageSize int) ([]domain.EventLogEntry, int64, error) {
	return s.repo.ListEventLogs(ctx, subscriptionID, page, pageSize)
}

func (s *eventListenerService) RetryCallback(ctx context.Context, logID string) error {
	log, err := s.repo.GetEventLog(ctx, logID)
	if err != nil {
		return errors.NotFound("event log not found", err)
	}

	sub, err := s.repo.GetSubscription(ctx, log.SubscriptionID)
	if err != nil {
		return errors.NotFound("subscription not found", err)
	}

	callbackData := domain.EventCallbackResponse{
		SubscriptionID: sub.ID,
		Event:          log.EventName,
		Params:         log.DecodedParams,
		TxHash:         log.TxHash,
		BlockNumber:    log.BlockNumber,
		Timestamp:      log.Timestamp,
	}

	if err := s.callback.Execute(ctx, sub.CallbackURL, callbackData); err != nil {
		_ = s.repo.UpdateEventLogCallback(ctx, logID, domain.CallbackStatusFailed, err.Error())
		return err
	}

	_ = s.repo.UpdateEventLogCallback(ctx, logID, domain.CallbackStatusSuccess, "")
	return nil
}

func (s *eventListenerService) startListener(sub *domain.EventSubscription) {
	ctx, cancel := context.WithCancel(context.Background())
	s.mu.Lock()
	s.listeners[sub.ID] = cancel
	s.mu.Unlock()

	defer func() {
		s.mu.Lock()
		delete(s.listeners, sub.ID)
		s.mu.Unlock()
	}()

	ticker := time.NewTicker(time.Second * 15)
	defer ticker.Stop()

	currentBlock := sub.FromBlock
	if currentBlock == 0 {
		currentBlock = 1
	}

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			latestSub, err := s.repo.GetSubscription(ctx, sub.ID)
			if err != nil || latestSub.Status != domain.SubscriptionStatusActive {
				return
			}

			logs, err := s.fetcher.GetLogs(ctx, currentBlock, currentBlock+100, []string{sub.Contract}, sub.Topics)
			if err != nil {
				logger.Warn("fetch logs failed", zap.Error(err))
				continue
			}

			for _, rawLog := range logs {
				s.processEvent(ctx, sub, rawLog)
			}
			currentBlock += 100
		}
	}
}

func (s *eventListenerService) processEvent(ctx context.Context, sub *domain.EventSubscription, rawLog interface{}) {
	eventLog := &domain.EventLogEntry{
		ID:             utils.GenerateID("log"),
		SubscriptionID: sub.ID,
		ChainID:        sub.ChainID,
		EventName:      sub.EventName,
		Timestamp:      time.Now(),
		CallbackStatus: domain.CallbackStatusPending,
		CreatedAt:      time.Now(),
	}

	if err := s.repo.StoreEventLog(ctx, eventLog); err != nil {
		logger.Error("store event log failed", zap.Error(err))
		return
	}

	callbackData := domain.EventCallbackResponse{
		SubscriptionID: sub.ID,
		Event:          sub.EventName,
		Params:         map[string]interface{}{},
		BlockNumber:    eventLog.BlockNumber,
		Timestamp:      eventLog.Timestamp,
	}

	go func() {
		if err := s.callback.Execute(ctx, sub.CallbackURL, callbackData); err != nil {
			_ = s.repo.UpdateEventLogCallback(ctx, eventLog.ID, domain.CallbackStatusFailed, err.Error())
			return
		}
		now := time.Now()
		eventLog.CallbackTime = &now
		_ = s.repo.UpdateEventLogCallback(ctx, eventLog.ID, domain.CallbackStatusSuccess, "")
	}()
}
