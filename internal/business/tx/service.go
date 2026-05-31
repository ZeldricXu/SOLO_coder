package tx

import (
	"container/list"
	"context"
	"encoding/json"
	"fmt"
	"math/big"
	"sync"
	"time"

	"github.com/gasestimator/platform/internal/domain/model"
	"github.com/gasestimator/platform/internal/domain/repository"
	"github.com/gasestimator/platform/internal/infrastructure/logger"
	"github.com/gasestimator/platform/pkg/common"
	"go.uber.org/zap"
)

type CreateTransactionRequest struct {
	ChainID         string                 `json:"chain_id"`
	FromAddress     string                 `json:"from_address"`
	ToAddress       string                 `json:"to_address"`
	Value           string                 `json:"value"`
	Data            []byte                 `json:"data"`
	Nonce           *uint64                `json:"nonce"`
	GasLimit        *uint64                `json:"gas_limit"`
	GasOptimization bool                   `json:"gas_optimization"`
	MaxFeePerGas    string                 `json:"max_fee_per_gas"`
	MaxPriorityFee  string                 `json:"max_priority_fee"`
	MultisigID      string                 `json:"multisig_id"`
	CacheBypass     bool                   `json:"cache_bypass,omitempty"`
}

type SignRequest struct {
	TxID        string `json:"tx_id"`
	Signer      string `json:"signer"`
	Signature   string `json:"signature"`
	SignerIndex uint32 `json:"signer_index"`
}

type CacheStats struct {
	L1Hits        int64 `json:"l1_hits"`
	L1Misses      int64 `json:"l1_misses"`
	L2Hits        int64 `json:"l2_hits"`
	L2Misses      int64 `json:"l2_misses"`
	DBFetches     int64 `json:"db_fetches"`
	Evictions     int64 `json:"evictions"`
	CacheSize     int   `json:"cache_size"`
	CacheCapacity int   `json:"cache_capacity"`
}

type CacheLevel string

const (
	CacheLevelL1   CacheLevel = "l1"
	CacheLevelL2   CacheLevel = "l2"
	CacheLevelNone CacheLevel = "none"
)

type GasEstimator interface {
	Estimate(ctx context.Context, chainID, contract, method string, data []byte) (uint64, error)
	GetLatestPrices(ctx context.Context, chainID string) (map[string]string, error)
}

type Cache interface {
	Get(ctx context.Context, key string) (*model.Transaction, bool)
	Set(ctx context.Context, key string, value *model.Transaction)
	Delete(ctx context.Context, key string)
	Size() int
	Capacity() int
	Clear()
	Hits() int64
	Misses() int64
	Evictions() int64
}

type cacheEntry struct {
	key         string
	data        *model.Transaction
	expiresAt   time.Time
	accessCount int64
}

type lruCache struct {
	data      map[string]*list.Element
	order     *list.List
	capacity  int
	ttl       time.Duration
	mu        sync.Mutex
	hits      int64
	misses    int64
	evictions int64
}

func newLRUCache(capacity int, ttl time.Duration) Cache {
	return &lruCache{
		data:     make(map[string]*list.Element, capacity),
		order:    list.New(),
		capacity: capacity,
		ttl:      ttl,
	}
}

func (c *lruCache) Get(ctx context.Context, key string) (*model.Transaction, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	elem, exists := c.data[key]
	if !exists {
		c.misses++
		return nil, false
	}

	entry := elem.Value.(*cacheEntry)
	if time.Now().After(entry.expiresAt) {
		c.removeElement(elem)
		c.evictions++
		c.misses++
		return nil, false
	}

	c.order.MoveToFront(elem)
	entry.accessCount++
	c.hits++

	return entry.data, true
}

func (c *lruCache) Set(ctx context.Context, key string, value *model.Transaction) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, exists := c.data[key]; exists {
		c.order.MoveToFront(elem)
		entry := elem.Value.(*cacheEntry)
		entry.data = value
		entry.expiresAt = time.Now().Add(c.ttl)
		entry.accessCount++
		return
	}

	if c.order.Len() >= c.capacity {
		c.evictOldest()
	}

	entry := &cacheEntry{
		key:         key,
		data:        value,
		expiresAt:   time.Now().Add(c.ttl),
		accessCount: 1,
	}
	elem := c.order.PushFront(entry)
	c.data[key] = elem
}

func (c *lruCache) Delete(ctx context.Context, key string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, exists := c.data[key]; exists {
		c.removeElement(elem)
	}
}

func (c *lruCache) Size() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.order.Len()
}

func (c *lruCache) Capacity() int {
	return c.capacity
}

func (c *lruCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.data = make(map[string]*list.Element, c.capacity)
	c.order.Init()
}

func (c *lruCache) Hits() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.hits
}

func (c *lruCache) Misses() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.misses
}

func (c *lruCache) Evictions() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.evictions
}

func (c *lruCache) removeElement(elem *list.Element) {
	c.order.Remove(elem)
	entry := elem.Value.(*cacheEntry)
	delete(c.data, entry.key)
}

func (c *lruCache) evictOldest() {
	oldest := c.order.Back()
	if oldest != nil {
		c.removeElement(oldest)
		c.evictions++
	}
}

type RemoteCache interface {
	Get(ctx context.Context, key string) (*model.Transaction, error)
	Set(ctx context.Context, key string, value *model.Transaction, ttl time.Duration) error
	Delete(ctx context.Context, key string) error
	Exists(ctx context.Context, key string) (bool, error)
}

type redisCache struct {
	client interface {
		Get(ctx context.Context, key string) (string, error)
		Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error
		Del(ctx context.Context, key string) error
		Exists(ctx context.Context, key string) (bool, error)
	}
	prefix string
	ttl    time.Duration
	mu     sync.Mutex
	hits   int64
	misses int64
}

func newRedisCache(client interface{}, prefix string, ttl time.Duration) RemoteCache {
	return &redisCache{
		client: client,
		prefix: prefix,
		ttl:    ttl,
	}
}

func (c *redisCache) Get(ctx context.Context, key string) (*model.Transaction, error) {
	fullKey := c.prefix + key
	val, err := c.client.Get(ctx, fullKey)
	if err != nil {
		c.mu.Lock()
		c.misses++
		c.mu.Unlock()
		return nil, err
	}

	var tx model.Transaction
	if err := json.Unmarshal([]byte(val), &tx); err != nil {
		c.mu.Lock()
		c.misses++
		c.mu.Unlock()
		return nil, err
	}

	c.mu.Lock()
	c.hits++
	c.mu.Unlock()

	return &tx, nil
}

func (c *redisCache) Set(ctx context.Context, key string, value *model.Transaction, ttl time.Duration) error {
	fullKey := c.prefix + key
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return c.client.Set(ctx, fullKey, string(data), ttl)
}

func (c *redisCache) Delete(ctx context.Context, key string) error {
	fullKey := c.prefix + key
	return c.client.Del(ctx, fullKey)
}

func (c *redisCache) Exists(ctx context.Context, key string) (bool, error) {
	fullKey := c.prefix + key
	return c.client.Exists(ctx, fullKey)
}

func (c *redisCache) Hits() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.hits
}

func (c *redisCache) Misses() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.misses
}

type ServiceOption func(*Service)

func WithGasEstimator(g GasEstimator) ServiceOption {
	return func(s *Service) { s.gasEstimator = g }
}

func WithL1Cache(c Cache) ServiceOption {
	return func(s *Service) { s.l1Cache = c }
}

func WithL2Cache(c RemoteCache) ServiceOption {
	return func(s *Service) { s.l2Cache = c }
}

func WithCacheEnabled(enabled bool) ServiceOption {
	return func(s *Service) { s.cacheEnabled = enabled }
}

type Service struct {
	txRepo        repository.TransactionRepository
	gasEstimator  GasEstimator
	l1Cache       Cache
	l2Cache       RemoteCache
	cacheEnabled  bool
	warmedUp      bool
	warmUpOnce    sync.Once
}

func NewService(txRepo repository.TransactionRepository, opts ...ServiceOption) *Service {
	s := &Service{
		txRepo:       txRepo,
		l1Cache:      newLRUCache(10000, 5*time.Minute),
		cacheEnabled: true,
	}
	for _, opt := range opts {
		opt(s)
	}
	return s
}

func (s *Service) SetGasService(gs interface{}) {
	if g, ok := gs.(GasEstimator); ok {
		s.gasEstimator = g
	}
}

func (s *Service) EnableL2Cache(client interface{}, prefix string) {
	s.l2Cache = newRedisCache(client, prefix, 15*time.Minute)
}

func (s *Service) WarmUpCache(ctx context.Context) error {
	var err error
	s.warmUpOnce.Do(func() {
		logger.L().Info("starting transaction cache warm-up")

		pendingTxs, txErr := s.txRepo.ListPending(ctx, "")
		if txErr != nil {
			err = txErr
			return
		}

		for _, tx := range pendingTxs {
			s.l1Cache.Set(ctx, tx.ID, tx)
			if s.l2Cache != nil {
				_ = s.l2Cache.Set(ctx, tx.ID, tx, 15*time.Minute)
			}
		}

		s.warmedUp = true
		logger.L().Info("transaction cache warm-up completed",
			zap.Int("loaded_count", len(pendingTxs)),
		)
	})
	return err
}

func (s *Service) GetCacheStats() CacheStats {
	l1Hits := s.l1Cache.Hits()
	l1Misses := s.l1Cache.Misses()
	evictions := s.l1Cache.Evictions()
	cacheSize := s.l1Cache.Size()
	cacheCap := s.l1Cache.Capacity()

	l2Hits := int64(0)
	l2Misses := int64(0)
	if s.l2Cache != nil {
		if rc, ok := s.l2Cache.(*redisCache); ok {
			l2Hits = rc.Hits()
			l2Misses = rc.Misses()
		}
	}

	return CacheStats{
		L1Hits:        l1Hits,
		L1Misses:      l1Misses,
		L2Hits:        l2Hits,
		L2Misses:      l2Misses,
		DBFetches:     l1Misses + l2Misses,
		Evictions:     evictions,
		CacheSize:     cacheSize,
		CacheCapacity: cacheCap,
	}
}

func (s *Service) getTxFromCache(ctx context.Context, id string) (*model.Transaction, CacheLevel) {
	if !s.cacheEnabled {
		return nil, CacheLevelNone
	}

	if tx, hit := s.l1Cache.Get(ctx, id); hit {
		return tx, CacheLevelL1
	}

	if s.l2Cache != nil {
		if tx, err := s.l2Cache.Get(ctx, id); err == nil && tx != nil {
			s.l1Cache.Set(ctx, id, tx)
			return tx, CacheLevelL2
		}
	}

	return nil, CacheLevelNone
}

func (s *Service) cacheTx(ctx context.Context, tx *model.Transaction) {
	if !s.cacheEnabled || tx == nil {
		return
	}

	s.l1Cache.Set(ctx, tx.ID, tx)

	if s.l2Cache != nil {
		_ = s.l2Cache.Set(ctx, tx.ID, tx, 15*time.Minute)
	}
}

func (s *Service) invalidateTxCache(ctx context.Context, id string) {
	s.l1Cache.Delete(ctx, id)

	if s.l2Cache != nil {
		_ = s.l2Cache.Delete(ctx, id)
	}

	pattern := fmt.Sprintf("tx:%s:*", id)
	_ = pattern
}

func (s *Service) CreateTransaction(ctx context.Context, req *CreateTransactionRequest) (*model.Transaction, error) {
	tx := s.buildTransaction(req)

	s.applyGasSettings(ctx, tx, req)

	if err := s.txRepo.Create(ctx, tx); err != nil {
		logger.L().Error("failed to create transaction", zap.Error(err))
		return nil, common.NewInternalError("failed to create transaction")
	}

	s.cacheTx(ctx, tx)

	return tx, nil
}

func (s *Service) buildTransaction(req *CreateTransactionRequest) *model.Transaction {
	tx := &model.Transaction{
		ID:          common.GenerateID("tx"),
		ChainID:     req.ChainID,
		FromAddress: req.FromAddress,
		ToAddress:   req.ToAddress,
		Value:       req.Value,
		Data:        req.Data,
		Status:      "created",
		MultisigID:  req.MultisigID,
		CreatedAt:   time.Now(),
	}

	if req.Nonce != nil {
		tx.Nonce = *req.Nonce
	}

	return tx
}

func (s *Service) applyGasSettings(ctx context.Context, tx *model.Transaction, req *CreateTransactionRequest) {
	if req.GasLimit != nil {
		tx.GasLimit = *req.GasLimit
	} else if req.GasOptimization && s.gasEstimator != nil {
		if estimated, err := s.gasEstimator.Estimate(ctx, req.ChainID, req.ToAddress, "", req.Data); err == nil {
			tx.GasLimit = estimated
		} else {
			tx.GasLimit = 21000
		}
	} else {
		tx.GasLimit = 21000
	}

	if req.GasOptimization && s.gasEstimator != nil {
		s.applyGasPriceOptimization(ctx, tx, req.ChainID)
	} else {
		tx.MaxFeePerGas = req.MaxFeePerGas
		tx.MaxPriorityFee = req.MaxPriorityFee
	}
}

func (s *Service) applyGasPriceOptimization(ctx context.Context, tx *model.Transaction, chainID string) {
	prices, err := s.gasEstimator.GetLatestPrices(ctx, chainID)
	if err != nil {
		return
	}

	if p, ok := prices["avg"]; ok {
		tx.GasPrice = p
	}
	if p, ok := prices["max_fee"]; ok {
		tx.MaxFeePerGas = p
	}
	if p, ok := prices["priority_fee"]; ok {
		tx.MaxPriorityFee = p
	}
}

func (s *Service) SignTransaction(ctx context.Context, req *SignRequest) error {
	tx, err := s.GetByID(ctx, req.TxID)
	if err != nil {
		return common.NewNotFoundError("transaction", req.TxID)
	}

	sigs := s.parseSignatures(tx.Signatures)
	sigs = append(sigs, s.buildSignature(req))
	tx.Signatures = s.marshalSignatures(sigs)
	tx.Status = "partially_signed"

	if err := s.txRepo.Update(ctx, tx); err != nil {
		logger.L().Error("failed to update transaction signature", zap.Error(err))
		return common.NewInternalError("failed to add signature")
	}

	s.cacheTx(ctx, tx)

	logger.L().Info("transaction signed",
		zap.String("tx_id", req.TxID),
		zap.String("signer", req.Signer),
	)

	return nil
}

func (s *Service) parseSignatures(data []byte) []map[string]interface{} {
	var sigs []map[string]interface{}
	if len(data) > 0 {
		_ = json.Unmarshal(data, &sigs)
	}
	return sigs
}

func (s *Service) buildSignature(req *SignRequest) map[string]interface{} {
	return map[string]interface{}{
		"signer":       req.Signer,
		"signature":    req.Signature,
		"signer_index": req.SignerIndex,
		"signed_at":    time.Now().UTC().Format(time.RFC3339),
	}
}

func (s *Service) marshalSignatures(sigs []map[string]interface{}) []byte {
	data, _ := json.Marshal(sigs)
	return data
}

func (s *Service) Submit(ctx context.Context, txID string) (string, error) {
	tx, err := s.GetByID(ctx, txID)
	if err != nil {
		return "", common.NewNotFoundError("transaction", txID)
	}

	txHash := "0x" + common.GenerateRandomHex(32)
	tx.TxHash = txHash
	tx.Status = "submitted"

	if err := s.txRepo.Update(ctx, tx); err != nil {
		logger.L().Error("failed to submit transaction", zap.Error(err))
		return "", common.NewInternalError("failed to submit transaction")
	}

	s.cacheTx(ctx, tx)

	return txHash, nil
}

func (s *Service) OptimizeGas(ctx context.Context, txID string) error {
	tx, err := s.GetByID(ctx, txID)
	if err != nil {
		return common.NewNotFoundError("transaction", txID)
	}

	if s.gasEstimator != nil {
		s.applyGasOptimization(ctx, tx)
	}

	if err := s.txRepo.Update(ctx, tx); err != nil {
		return common.NewInternalError("failed to optimize gas")
	}

	s.cacheTx(ctx, tx)

	return nil
}

func (s *Service) applyGasOptimization(ctx context.Context, tx *model.Transaction) {
	if estimated, err := s.gasEstimator.Estimate(ctx, tx.ChainID, tx.ToAddress, "", tx.Data); err == nil {
		tx.GasLimit = uint64(float64(estimated) * 1.1)
	}

	prices, err := s.gasEstimator.GetLatestPrices(ctx, tx.ChainID)
	if err != nil {
		return
	}

	if p, ok := prices["max_fee"]; ok {
		tx.MaxFeePerGas = p
	}
	if p, ok := prices["priority_fee"]; ok {
		tx.MaxPriorityFee = p
	}
}

func (s *Service) GetByID(ctx context.Context, id string) (*model.Transaction, error) {
	if cachedTx, level := s.getTxFromCache(ctx, id); cachedTx != nil {
		logger.L().Debug("transaction cache hit",
			zap.String("tx_id", id),
			zap.String("cache_level", string(level)),
		)
		return cachedTx, nil
	}

	tx, err := s.txRepo.GetByID(ctx, id)
	if err != nil {
		return nil, common.NewNotFoundError("transaction", id)
	}

	s.cacheTx(ctx, tx)

	return tx, nil
}

func (s *Service) List(ctx context.Context, chainID, address, status string, limit, offset int) ([]*model.Transaction, int64, error) {
	txs, total, err := s.txRepo.List(ctx, chainID, address, status, limit, offset)
	if err != nil {
		return nil, 0, err
	}

	for _, tx := range txs {
		if _, level := s.getTxFromCache(ctx, tx.ID); level == CacheLevelNone {
			s.cacheTx(ctx, tx)
		}
	}

	return txs, total, nil
}

func (s *Service) InvalidateCache(ctx context.Context, txID string) {
	s.invalidateTxCache(ctx, txID)
	logger.L().Info("transaction cache invalidated", zap.String("tx_id", txID))
}

func (s *Service) ClearCache() {
	s.l1Cache.Clear()
	logger.L().Info("transaction cache cleared")
}

func weiToGwei(wei *big.Int) *big.Float {
	return new(big.Float).Quo(new(big.Float).SetInt(wei), big.NewFloat(1e9))
}
