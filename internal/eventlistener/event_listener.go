package eventlistener

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
	"sync"
	"time"

	"github.com/ethereum/go-ethereum"
	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/core/types"
	"go.uber.org/zap"
	"gorm.io/gorm"

	"github.com/blockchain-middleware/core/internal/common/config"
	"github.com/blockchain-middleware/core/internal/common/errors"
	"github.com/blockchain-middleware/core/internal/common/logger"
	"github.com/blockchain-middleware/core/internal/common/models"
)

type EventCallback func(ctx context.Context, log types.Log, subscription *models.EventSubscription) error

type EventListener struct {
	db          *gorm.DB
	chainRPC    ChainRPCInterface
	subscribers map[string]EventCallback
	activeSubs  map[string]*models.EventSubscription
	mu          sync.RWMutex
	ctx         context.Context
	cancel      context.CancelFunc
	wg          sync.WaitGroup
}

type ChainRPCInterface interface {
	GetLatestBlockNumber(ctx context.Context) (uint64, error)
	FilterLogs(ctx context.Context, query ethereum.FilterQuery) ([]types.Log, error)
	SubscribeNewHead(ctx context.Context, ch chan<- *types.Header) (ethereum.Subscription, error)
}

func NewEventListener(db *gorm.DB, chainRPC ChainRPCInterface) *EventListener {
	ctx, cancel := context.WithCancel(context.Background())
	return &EventListener{
		db:          db,
		chainRPC:    chainRPC,
		subscribers: make(map[string]EventCallback),
		activeSubs:  make(map[string]*models.EventSubscription),
		ctx:         ctx,
		cancel:      cancel,
	}
}

func (el *EventListener) RegisterCallback(name string, callback EventCallback) {
	el.mu.Lock()
	defer el.mu.Unlock()
	el.subscribers[name] = callback
}

func (el *EventListener) Subscribe(ctx context.Context, chainID uint64, contractAddress, eventSignature, callbackURL string, fromBlock uint64, filters map[string]interface{}) (*models.EventSubscription, error) {
	el.mu.Lock()
	defer el.mu.Unlock()

	sub := &models.EventSubscription{
		ChainID:           chainID,
		ContractAddress:   contractAddress,
		EventSignature:    eventSignature,
		CallbackURL:       callbackURL,
		FromBlock:         fromBlock,
		LastProcessedBlock: fromBlock,
		Filters:           filters,
		Active:            true,
	}

	if err := el.db.Create(sub).Error; err != nil {
		return nil, fmt.Errorf("failed to create subscription: %w", err)
	}

	el.activeSubs[sub.ID] = sub

	logger.Log.Info("Event subscription created",
		zap.String("id", sub.ID),
		zap.String("contract", contractAddress),
		zap.String("event", eventSignature))

	return sub, nil
}

func (el *EventListener) Unsubscribe(ctx context.Context, subscriptionID string) error {
	el.mu.Lock()
	defer el.mu.Unlock()

	result := el.db.Model(&models.EventSubscription{}).
		Where("id = ?", subscriptionID).
		Update("active", false)

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.ErrNotFound
	}

	delete(el.activeSubs, subscriptionID)
	logger.Log.Info("Event subscription unsubscribed", zap.String("id", subscriptionID))

	return nil
}

func (el *EventListener) GetSubscription(ctx context.Context, subscriptionID string) (*models.EventSubscription, error) {
	var sub models.EventSubscription
	err := el.db.Where("id = ?", subscriptionID).First(&sub).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, err
	}
	return &sub, nil
}

func (el *EventListener) ListSubscriptions(ctx context.Context, chainID uint64, active *bool, offset, limit int) ([]models.EventSubscription, int64, error) {
	var subs []models.EventSubscription
	var total int64

	query := el.db.Model(&models.EventSubscription{})
	if chainID > 0 {
		query = query.Where("chain_id = ?", chainID)
	}
	if active != nil {
		query = query.Where("active = ?", *active)
	}

	query.Count(&total)
	err := query.Offset(offset).Limit(limit).Order("created_at DESC").Find(&subs).Error

	return subs, total, err
}

func (el *EventListener) Start(ctx context.Context) error {
	logger.Log.Info("Starting event listener")

	var subs []models.EventSubscription
	if err := el.db.Where("active = ?", true).Find(&subs).Error; err != nil {
		return err
	}

	el.mu.Lock()
	for i := range subs {
		el.activeSubs[subs[i].ID] = &subs[i]
	}
	el.mu.Unlock()

	go el.syncLoop()

	return nil
}

func (el *EventListener) Stop() {
	logger.Log.Info("Stopping event listener")
	el.cancel()
	el.wg.Wait()
}

func (el *EventListener) syncLoop() {
	interval := time.Duration(config.AppConfig.EventListener.SyncInterval) * time.Second
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-el.ctx.Done():
			return
		case <-ticker.C:
			el.syncSubscriptions()
		}
	}
}

func (el *EventListener) syncSubscriptions() {
	el.mu.RLock()
	subs := make([]*models.EventSubscription, 0, len(el.activeSubs))
	for _, sub := range el.activeSubs {
		subs = append(subs, sub)
	}
	el.mu.RUnlock()

	latestBlock, err := el.chainRPC.GetLatestBlockNumber(el.ctx)
	if err != nil {
		logger.Log.Error("Failed to get latest block", zap.Error(err))
		return
	}

	for _, sub := range subs {
		el.wg.Add(1)
		go func(s *models.EventSubscription) {
			defer el.wg.Done()
			el.processSubscription(s, latestBlock)
		}(sub)
	}
}

func (el *EventListener) processSubscription(sub *models.EventSubscription, latestBlock uint64) {
	fromBlock := sub.LastProcessedBlock + 1
	if fromBlock > latestBlock {
		return
	}

	toBlock := fromBlock + uint64(config.AppConfig.EventListener.MaxBlocksPerSync) - 1
	if toBlock > latestBlock {
		toBlock = latestBlock
	}

	query := ethereum.FilterQuery{
		FromBlock: big.NewInt(int64(fromBlock)),
		ToBlock:   big.NewInt(int64(toBlock)),
		Addresses: []common.Address{common.HexToAddress(sub.ContractAddress)},
	}

	if sub.EventSignature != "" {
		query.Topics = [][]common.Hash{{common.HexToHash(sub.EventSignature)}}
	}

	logs, err := el.chainRPC.FilterLogs(el.ctx, query)
	if err != nil {
		logger.Log.Error("Failed to filter logs",
			zap.String("sub_id", sub.ID),
			zap.Error(err))
		return
	}

	for _, log := range logs {
		if err := el.dispatchEvent(log, sub); err != nil {
			logger.Log.Error("Failed to dispatch event",
				zap.String("sub_id", sub.ID),
				zap.Error(err))
		}
	}

	el.mu.Lock()
	sub.LastProcessedBlock = toBlock
	el.mu.Unlock()

	el.db.Model(sub).Update("last_processed_block", toBlock)
}

func (el *EventListener) dispatchEvent(log types.Log, sub *models.EventSubscription) error {
	el.mu.RLock()
	callback, hasCallback := el.subscribers[sub.EventSignature]
	el.mu.RUnlock()

	if hasCallback {
		if err := callback(el.ctx, log, sub); err != nil {
			logger.Log.Error("Callback execution failed", zap.Error(err))
		}
	}

	if sub.CallbackURL != "" {
		if err := el.sendWebhook(log, sub); err != nil {
			return err
		}
	}

	return nil
}

func (el *EventListener) sendWebhook(log types.Log, sub *models.EventSubscription) error {
	topics := make([]string, len(log.Topics))
	for i, topic := range log.Topics {
		topics[i] = topic.Hex()
	}

	payload := map[string]interface{}{
		"subscription_id": sub.ID,
		"chain_id":        sub.ChainID,
		"address":         log.Address.Hex(),
		"topics":          topics,
		"data":            common.Bytes2Hex(log.Data),
		"block_number":    log.BlockNumber,
		"block_hash":      log.BlockHash.Hex(),
		"transaction_hash": log.TxHash.Hex(),
		"log_index":       log.Index,
		"removed":         log.Removed,
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	req, err := http.NewRequestWithContext(el.ctx, "POST", sub.CallbackURL, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		return fmt.Errorf("webhook returned status %d", resp.StatusCode)
	}

	return nil
}

func (el *EventListener) LoadActiveSubscriptions() error {
	var subs []models.EventSubscription
	if err := el.db.Where("active = ?", true).Find(&subs).Error; err != nil {
		return err
	}

	el.mu.Lock()
	defer el.mu.Unlock()

	el.activeSubs = make(map[string]*models.EventSubscription)
	for i := range subs {
		el.activeSubs[subs[i].ID] = &subs[i]
	}

	return nil
}
