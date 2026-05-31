package event

import (
	"context"
	"encoding/json"
	"sync"
	"time"

	"github.com/gasestimator/platform/internal/domain/model"
	"github.com/gasestimator/platform/internal/domain/repository"
	"github.com/gasestimator/platform/internal/infrastructure/logger"
	"github.com/gasestimator/platform/pkg/common"
	"go.uber.org/zap"
)

type EventHandler func(ctx context.Context, event *model.ContractEvent) error

type ListenerConfig struct {
	ChainID         string
	ContractAddress string
	EventName       string
	Topics          []string
	FromBlock       uint64
}

type Service struct {
	eventRepo  repository.ContractEventRepository
	handlers   map[string][]EventHandler
	handlersMu sync.RWMutex
}

func NewService(eventRepo repository.ContractEventRepository) *Service {
	return &Service{
		eventRepo: eventRepo,
		handlers:  make(map[string][]EventHandler),
	}
}

func (s *Service) RegisterHandler(eventName string, handler EventHandler) {
	s.handlersMu.Lock()
	defer s.handlersMu.Unlock()
	s.handlers[eventName] = append(s.handlers[eventName], handler)
}

func (s *Service) ProcessEvent(ctx context.Context, event *model.ContractEvent) error {
	if event.ID == "" {
		event.ID = common.GenerateID("evt")
		event.CreatedAt = time.Now()
		if err := s.eventRepo.Create(ctx, event); err != nil {
			return err
		}
	}

	s.handlersMu.RLock()
	handlers := s.handlers[event.EventName]
	s.handlersMu.RUnlock()

	var err error
	for _, h := range handlers {
		if handlerErr := h(ctx, event); handlerErr != nil {
			logger.L().Error("event handler failed",
				zap.String("event_id", event.ID),
				zap.String("event_name", event.EventName),
				zap.Error(handlerErr),
			)
			err = handlerErr
		}
	}

	event.Processed = true
	now := time.Now()
	event.ProcessedAt = &now
	if updateErr := s.eventRepo.Update(ctx, event); updateErr != nil {
		logger.L().Error("failed to mark event processed", zap.Error(updateErr))
	}

	logger.L().Info("contract event processed",
		zap.String("event_id", event.ID),
		zap.String("event_name", event.EventName),
		zap.Int("handler_count", len(handlers)),
	)

	return err
}

func (s *Service) StartListener(ctx context.Context, cfg ListenerConfig) error {
	logger.L().Info("starting event listener",
		zap.String("chain_id", cfg.ChainID),
		zap.String("contract", cfg.ContractAddress),
		zap.String("event", cfg.EventName),
	)

	go s.listenerLoop(ctx, cfg)
	return nil
}

func (s *Service) listenerLoop(ctx context.Context, cfg ListenerConfig) {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			mockEvents := s.fetchMockEvents(cfg)
			for _, evt := range mockEvents {
				_ = s.ProcessEvent(ctx, evt)
			}
		}
	}
}

func (s *Service) fetchMockEvents(cfg ListenerConfig) []*model.ContractEvent {
	return []*model.ContractEvent{
		{
			ID:              "",
			ChainID:         cfg.ChainID,
			ContractAddress: cfg.ContractAddress,
			EventName:       cfg.EventName,
			BlockNumber:     1000,
			TxHash:          "0x" + common.GenerateRandomHex(32),
			LogIndex:        0,
			Topics:          cfg.Topics,
			Data:            json.RawMessage(`{"value": "1000000000000000000"}`),
		},
	}
}

func (s *Service) ProcessUnprocessed(ctx context.Context) error {
	events, err := s.eventRepo.ListUnprocessed(ctx)
	if err != nil {
		return err
	}

	for _, evt := range events {
		_ = s.ProcessEvent(ctx, evt)
	}

	return nil
}

func (s *Service) GetByID(ctx context.Context, id string) (*model.ContractEvent, error) {
	event, err := s.eventRepo.GetByID(ctx, id)
	if err != nil {
		return nil, common.NewNotFoundError("event", id)
	}
	return event, nil
}

func (s *Service) List(ctx context.Context, chainID, contractAddress, eventName string, limit, offset int) ([]*model.ContractEvent, int64, error) {
	return s.eventRepo.List(ctx, chainID, contractAddress, eventName, limit, offset)
}
