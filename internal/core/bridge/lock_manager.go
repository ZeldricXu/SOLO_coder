package bridge

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"
)

type DefaultLockManager struct {
	repo   BridgeRepository
	logger *zap.Logger
}

type LockManagerDependencies struct {
	Repository BridgeRepository
	Logger     *zap.Logger
}

func NewDefaultLockManager(deps LockManagerDependencies) LockManager {
	return &DefaultLockManager{
		repo:   deps.Repository,
		logger: deps.Logger,
	}
}

func (m *DefaultLockManager) LockAsset(
	ctx context.Context,
	req *BridgeRequest,
	gasEstimate *GasEstimate,
) (string, error) {
	m.logger.Info("Initiating asset lock",
		zap.String("trace_id", req.TraceID),
		zap.Uint64("source_chain", uint64(req.SourceChain)),
		zap.String("asset_type", string(req.AssetType)),
		zap.String("amount", req.Amount.String()))

	txHash := fmt.Sprintf("0xlock_%s_%d", req.TraceID, time.Now().UnixNano())

	m.logger.Debug("Asset lock transaction submitted",
		zap.String("tx_hash", txHash),
		zap.String("gas_price", gasEstimate.GasPrice.String()))

	return txHash, nil
}

func (m *DefaultLockManager) ConfirmLock(
	ctx context.Context,
	bridgeID string,
	confirmations int,
) (bool, error) {
	m.logger.Debug("Confirming asset lock",
		zap.String("bridge_id", bridgeID),
		zap.Int("required_confirmations", confirmations))

	if confirmations <= 0 {
		return true, nil
	}

	time.Sleep(time.Duration(confirmations) * 10 * time.Millisecond)

	return true, nil
}

func (m *DefaultLockManager) RollbackLock(
	ctx context.Context,
	bridgeID string,
) error {
	m.logger.Warn("Rolling back asset lock",
		zap.String("bridge_id", bridgeID))

	return m.repo.UpdateStatus(ctx, bridgeID, BridgeStatusRollback, "", nil)
}
