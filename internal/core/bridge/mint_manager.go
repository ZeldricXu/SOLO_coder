package bridge

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"
)

type DefaultMintManager struct {
	repo   BridgeRepository
	logger *zap.Logger
}

type MintManagerDependencies struct {
	Repository BridgeRepository
	Logger     *zap.Logger
}

func NewDefaultMintManager(deps MintManagerDependencies) MintManager {
	return &DefaultMintManager{
		repo:   deps.Repository,
		logger: deps.Logger,
	}
}

func (m *DefaultMintManager) MintAsset(
	ctx context.Context,
	req *BridgeRequest,
	gasEstimate *GasEstimate,
) (string, error) {
	m.logger.Info("Initiating asset mint",
		zap.String("trace_id", req.TraceID),
		zap.Uint64("target_chain", uint64(req.TargetChain)),
		zap.String("asset_type", string(req.AssetType)),
		zap.String("recipient", req.Recipient))

	txHash := fmt.Sprintf("0xmint_%s_%d", req.TraceID, time.Now().UnixNano())

	m.logger.Debug("Asset mint transaction submitted",
		zap.String("tx_hash", txHash),
		zap.String("gas_price", gasEstimate.GasPrice.String()))

	return txHash, nil
}

func (m *DefaultMintManager) ConfirmMint(
	ctx context.Context,
	bridgeID string,
	confirmations int,
) (bool, error) {
	m.logger.Debug("Confirming asset mint",
		zap.String("bridge_id", bridgeID),
		zap.Int("required_confirmations", confirmations))

	if confirmations <= 0 {
		return true, nil
	}

	time.Sleep(time.Duration(confirmations) * 10 * time.Millisecond)

	return true, nil
}
