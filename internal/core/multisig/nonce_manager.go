package multisig

import (
	"context"
	"fmt"
	"hash/fnv"
	"math/big"
	"sync"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/v2/internal/common"
)

const (
	shardCount    = 16
	lockTimeout   = 5 * time.Second
	nonceCacheTTL = 30 * time.Second
)

type nonceCacheEntry struct {
	nonce     *big.Int
	timestamp time.Time
}

type nonceShard struct {
	mu     sync.Mutex
	nonces map[string]nonceCacheEntry
}

type LockFreeNonceManager struct {
	shards [shardCount]nonceShard
	repo   ProposalRepository
	logger *zap.Logger
}

type NonceManagerDependencies struct {
	Repository ProposalRepository
	Logger     *zap.Logger
}

func NewLockFreeNonceManager(deps NonceManagerDependencies) NonceManager {
	manager := &LockFreeNonceManager{
		repo:   deps.Repository,
		logger: deps.Logger,
	}

	for i := 0; i < shardCount; i++ {
		manager.shards[i] = nonceShard{
			nonces: make(map[string]nonceCacheEntry),
		}
	}

	return manager
}

func (m *LockFreeNonceManager) GetNextNonce(
	ctx context.Context,
	walletAddress string,
	chainID uint64,
) (*big.Int, error) {
	key := m.buildKey(walletAddress, chainID)
	shard := m.getShard(key)

	shard.mu.Lock()
	defer shard.mu.Unlock()

	if cached, ok := shard.nonces[key]; ok && time.Since(cached.timestamp) < nonceCacheTTL {
		nextNonce := new(big.Int).Add(cached.nonce, big.NewInt(1))
		shard.nonces[key] = nonceCacheEntry{
			nonce:     nextNonce,
			timestamp: time.Now(),
		}
		return cached.nonce, nil
	}

	wallet, err := m.repo.GetWallet(ctx, walletAddress, chainID)
	if err != nil {
		return nil, fmt.Errorf("failed to get wallet: %w", err)
	}

	activeCount, err := m.repo.GetActiveProposalCount(ctx, walletAddress, chainID)
	if err != nil {
		m.logger.Warn("Failed to get active proposal count, using wallet nonce",
			zap.String("wallet", walletAddress),
			zap.Uint64("chain_id", chainID),
			zap.Error(err))
		activeCount = 0
	}

	nextNonce := new(big.Int).Add(wallet.Nonce, big.NewInt(int64(activeCount)))

	shard.nonces[key] = nonceCacheEntry{
		nonce:     new(big.Int).Add(nextNonce, big.NewInt(1)),
		timestamp: time.Now(),
	}

	return nextNonce, nil
}

func (m *LockFreeNonceManager) ConsumeNonce(
	ctx context.Context,
	walletAddress string,
	chainID uint64,
	nonce *big.Int,
) error {
	key := m.buildKey(walletAddress, chainID)
	shard := m.getShard(key)

	shard.mu.Lock()
	defer shard.mu.Unlock()

	if cached, ok := shard.nonces[key]; ok {
		if cached.nonce.Cmp(nonce) <= 0 {
			shard.nonces[key] = nonceCacheEntry{
				nonce:     new(big.Int).Add(nonce, big.NewInt(1)),
				timestamp: time.Now(),
			}
		}
	}

	return nil
}

func (m *LockFreeNonceManager) ReleaseNonce(
	ctx context.Context,
	walletAddress string,
	chainID uint64,
	nonce *big.Int,
) error {
	key := m.buildKey(walletAddress, chainID)
	shard := m.getShard(key)

	shard.mu.Lock()
	defer shard.mu.Unlock()

	if cached, ok := shard.nonces[key]; ok {
		if cached.nonce.Cmp(new(big.Int).Add(nonce, big.NewInt(1))) == 0 {
			shard.nonces[key] = nonceCacheEntry{
				nonce:     nonce,
				timestamp: time.Now(),
			}
		}
	}

	return nil
}

func (m *LockFreeNonceManager) GetCurrentNonce(
	ctx context.Context,
	walletAddress string,
	chainID uint64,
) (*big.Int, error) {
	key := m.buildKey(walletAddress, chainID)
	shard := m.getShard(key)

	shard.mu.Lock()
	defer shard.mu.Unlock()

	if cached, ok := shard.nonces[key]; ok && time.Since(cached.timestamp) < nonceCacheTTL {
		return new(big.Int).Set(cached.nonce), nil
	}

	wallet, err := m.repo.GetWallet(ctx, walletAddress, chainID)
	if err != nil {
		return nil, fmt.Errorf("failed to get wallet: %w", err)
	}

	activeCount, err := m.repo.GetActiveProposalCount(ctx, walletAddress, chainID)
	if err != nil {
		return nil, fmt.Errorf("failed to get active proposal count: %w", err)
	}

	currentNonce := new(big.Int).Add(wallet.Nonce, big.NewInt(int64(activeCount)))

	shard.nonces[key] = nonceCacheEntry{
		nonce:     new(big.Int).Set(currentNonce),
		timestamp: time.Now(),
	}

	return currentNonce, nil
}

func (m *LockFreeNonceManager) buildKey(walletAddress string, chainID uint64) string {
	return fmt.Sprintf("%s_%d", walletAddress, chainID)
}

func (m *LockFreeNonceManager) getShard(key string) *nonceShard {
	hash := fnv.New32()
	_, _ = hash.Write([]byte(key))
	shardIndex := hash.Sum32() % shardCount
	return &m.shards[shardIndex]
}

func validateNonceOperation(walletAddress string, chainID uint64) error {
	details := make(map[string]string)

	if walletAddress == "" {
		details["wallet_address"] = "wallet_address is required"
	}
	if chainID == 0 {
		details["chain_id"] = "chain_id is required"
	}

	if len(details) > 0 {
		return common.NewValidationError(details)
	}

	return nil
}
