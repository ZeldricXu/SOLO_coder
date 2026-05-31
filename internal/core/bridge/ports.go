package bridge

import (
	"context"
	"math/big"
	"time"

	"github.com/solocoder/task-scheduler/v2/internal/common"
)

type ChainID uint64

type AssetType string

const (
	AssetTypeETH  AssetType = "ETH"
	AssetTypeERC20 AssetType = "ERC20"
	AssetTypeNFT  AssetType = "NFT"
)

type BridgeStatus string

const (
	BridgeStatusPending    BridgeStatus = "pending"
	BridgeStatusLocked     BridgeStatus = "locked"
	BridgeStatusMinting    BridgeStatus = "minting"
	BridgeStatusCompleted  BridgeStatus = "completed"
	BridgeStatusFailed     BridgeStatus = "failed"
	BridgeStatusRollback   BridgeStatus = "rollback"
)

type GasEstimate struct {
	GasLimit     *big.Int
	GasPrice     *big.Int
	PriorityFee  *big.Int
	BaseFee      *big.Int
	TotalCost    *big.Int
	Confidence   float64
	Source       string
}

type BridgeRequest struct {
	TraceID       string
	SourceChain   ChainID
	TargetChain   ChainID
	AssetType     AssetType
	AssetAddress  string
	Amount        *big.Int
	Sender        string
	Recipient     string
	FeeTier       string
}

type BridgeResult struct {
	BridgeID     string
	Status       BridgeStatus
	SourceTxHash string
	TargetTxHash string
	GasUsed      *big.Int
	ActualFee    *big.Int
	CompletedAt  *time.Time
	Error        string
}

type HistoricalGasData struct {
	ChainID     ChainID
	Timestamp   time.Time
	GasPrice    *big.Int
	GasUsed     *big.Int
	BlockNumber uint64
	TxCount     int
}

type NetworkStatus struct {
	ChainID         ChainID
	BlockHeight     uint64
	PendingTxCount  int
	AvgGasPrice     *big.Int
	BaseFee         *big.Int
	CongestionLevel float64
}

type GasEstimator interface {
	EstimateGas(ctx context.Context, chainID ChainID, assetType AssetType, feeTier string) (*GasEstimate, error)
	GetHistoricalData(ctx context.Context, chainID ChainID, lookback time.Duration) ([]HistoricalGasData, error)
	GetNetworkStatus(ctx context.Context, chainID ChainID) (*NetworkStatus, error)
}

type LockManager interface {
	LockAsset(ctx context.Context, req *BridgeRequest, gasEstimate *GasEstimate) (txHash string, err error)
	ConfirmLock(ctx context.Context, bridgeID string, confirmations int) (bool, error)
	RollbackLock(ctx context.Context, bridgeID string) error
}

type MintManager interface {
	MintAsset(ctx context.Context, req *BridgeRequest, gasEstimate *GasEstimate) (txHash string, err error)
	ConfirmMint(ctx context.Context, bridgeID string, confirmations int) (bool, error)
}

type BridgeRepository interface {
	SaveBridgeRequest(ctx context.Context, req *BridgeRequest, estimate *GasEstimate) (bridgeID string, err error)
	UpdateStatus(ctx context.Context, bridgeID string, status BridgeStatus, txHash string, err error) error
	GetBridgeRequest(ctx context.Context, bridgeID string) (*BridgeRequest, *GasEstimate, error)
	ListPendingBridges(ctx context.Context, chainID ChainID) ([]string, error)
	SaveGasHistory(ctx context.Context, data *HistoricalGasData) error
}

type CrossChainBridge interface {
	InitiateBridge(ctx context.Context, req *BridgeRequest) (*BridgeResult, error)
	ConfirmBridge(ctx context.Context, bridgeID string, confirmations int) (*BridgeResult, error)
	GetBridgeStatus(ctx context.Context, bridgeID string) (*BridgeResult, error)
	EstimateBridgeFee(ctx context.Context, req *BridgeRequest) (*GasEstimate, error)
}
