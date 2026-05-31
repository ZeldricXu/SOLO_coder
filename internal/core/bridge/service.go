package bridge

import (
	"context"
	"errors"
	"fmt"
	"math/big"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/v2/internal/common"
)

const (
	defaultConfirmations = 3
	defaultAsyncTimeout  = 5 * time.Minute

	fieldRequest      = "request"
	fieldSourceChain  = "source_chain"
	fieldTargetChain  = "target_chain"
	fieldChain        = "chain"
	fieldAmount       = "amount"
	fieldSender       = "sender"
	fieldRecipient    = "recipient"
	fieldAssetType    = "asset_type"

	msgRequired              = " is required"
	msgChainsMustDiffer      = "source and target chains must be different"
	msgAmountMustBePositive  = "amount must be positive"
	msgGasEstimationFailed   = "gas estimation failed"
	msgSaveRequestFailed     = "failed to save bridge request"
	msgBridgeRequestNotFound = "bridge request not found"
	msgLockConfirmFailed     = "lock confirmation failed"
	msgMintConfirmFailed     = "mint confirmation failed"

	logFieldBridgeID  = "bridge_id"
	logFieldTraceID   = "trace_id"
	logFieldTxHash    = "tx_hash"
	logFieldTargetTx  = "target_tx_hash"
	logFieldCompleted = "completed_at"
	logFieldPanic     = "panic"
)

type CrossChainBridgeService struct {
	gasEstimator GasEstimator
	lockManager  LockManager
	mintManager  MintManager
	repo         BridgeRepository
	logger       *zap.Logger
}

type BridgeServiceDependencies struct {
	GasEstimator GasEstimator
	LockManager  LockManager
	MintManager  MintManager
	Repository   BridgeRepository
	Logger       *zap.Logger
}

type bridgeContext struct {
	ctx         context.Context
	bridgeID    string
	req         *BridgeRequest
	gasEstimate *GasEstimate
}

func NewCrossChainBridgeService(deps BridgeServiceDependencies) CrossChainBridge {
	return &CrossChainBridgeService{
		gasEstimator: deps.GasEstimator,
		lockManager:  deps.LockManager,
		mintManager:  deps.MintManager,
		repo:         deps.Repository,
		logger:       deps.Logger,
	}
}

func (s *CrossChainBridgeService) InitiateBridge(
	ctx context.Context,
	req *BridgeRequest,
) (*BridgeResult, error) {
	if err := s.validateRequest(req); err != nil {
		return nil, err
	}

	bridgeCtx := &bridgeContext{
		ctx: ctx,
		req: req,
	}

	if err := s.estimateGas(bridgeCtx); err != nil {
		return nil, err
	}

	if err := s.saveBridgeRequest(bridgeCtx); err != nil {
		return nil, err
	}

	result := s.createPendingResult(bridgeCtx)

	s.updateStatusSilently(bridgeCtx, BridgeStatusLocked, "", nil)

	if err := s.executeLock(bridgeCtx, result); err != nil {
		return nil, err
	}

	go s.asyncConfirmAndMint(bridgeCtx)

	return result, nil
}

func (s *CrossChainBridgeService) ConfirmBridge(
	ctx context.Context,
	bridgeID string,
	confirmations int,
) (*BridgeResult, error) {
	bridgeCtx, err := s.loadBridgeContext(ctx, bridgeID)
	if err != nil {
		return nil, err
	}

	lockConfirmed, err := s.confirmLock(bridgeCtx, confirmations)
	if err != nil {
		return nil, err
	}
	if !lockConfirmed {
		return s.createLockedResult(bridgeID), nil
	}

	s.updateStatusSilently(bridgeCtx, BridgeStatusMinting, "", nil)

	mintTxHash, err := s.executeMint(bridgeCtx)
	if err != nil {
		return nil, err
	}

	mintConfirmed, err := s.confirmMint(bridgeCtx, confirmations)
	if err != nil {
		return nil, err
	}
	if !mintConfirmed {
		return s.createMintingResult(bridgeID, mintTxHash), nil
	}

	return s.completeBridge(bridgeCtx, mintTxHash)
}

func (s *CrossChainBridgeService) GetBridgeStatus(
	ctx context.Context,
	bridgeID string,
) (*BridgeResult, error) {
	_, _, err := s.repo.GetBridgeRequest(ctx, bridgeID)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", msgBridgeRequestNotFound, err)
	}

	return &BridgeResult{
		BridgeID: bridgeID,
		Status:   BridgeStatusPending,
	}, nil
}

func (s *CrossChainBridgeService) EstimateBridgeFee(
	ctx context.Context,
	req *BridgeRequest,
) (*GasEstimate, error) {
	if err := s.validateRequest(req); err != nil {
		return nil, err
	}

	return s.gasEstimator.EstimateGas(ctx, req.SourceChain, req.AssetType, req.FeeTier)
}

func (s *CrossChainBridgeService) validateRequest(req *BridgeRequest) error {
	if req == nil {
		return newValidationError(fieldRequest)
	}

	details := s.collectValidationErrors(req)
	if len(details) > 0 {
		return common.NewValidationError(details)
	}

	return nil
}

func (s *CrossChainBridgeService) collectValidationErrors(req *BridgeRequest) map[string]string {
	details := make(map[string]string, 7)

	if req.SourceChain == 0 {
		details[fieldSourceChain] = fieldSourceChain + msgRequired
	}
	if req.TargetChain == 0 {
		details[fieldTargetChain] = fieldTargetChain + msgRequired
	}
	if req.SourceChain == req.TargetChain {
		details[fieldChain] = msgChainsMustDiffer
	}
	if req.Amount == nil || req.Amount.Sign() <= 0 {
		details[fieldAmount] = msgAmountMustBePositive
	}
	if req.Sender == "" {
		details[fieldSender] = fieldSender + msgRequired
	}
	if req.Recipient == "" {
		details[fieldRecipient] = fieldRecipient + msgRequired
	}
	if req.AssetType == "" {
		details[fieldAssetType] = fieldAssetType + msgRequired
	}

	return details
}

func (s *CrossChainBridgeService) estimateGas(bridgeCtx *bridgeContext) error {
	gasEstimate, err := s.gasEstimator.EstimateGas(
		bridgeCtx.ctx,
		bridgeCtx.req.SourceChain,
		bridgeCtx.req.AssetType,
		bridgeCtx.req.FeeTier,
	)
	if err != nil {
		return fmt.Errorf("%s: %w", msgGasEstimationFailed, err)
	}
	bridgeCtx.gasEstimate = gasEstimate
	return nil
}

func (s *CrossChainBridgeService) saveBridgeRequest(bridgeCtx *bridgeContext) error {
	bridgeID, err := s.repo.SaveBridgeRequest(
		bridgeCtx.ctx,
		bridgeCtx.req,
		bridgeCtx.gasEstimate,
	)
	if err != nil {
		return fmt.Errorf("%s: %w", msgSaveRequestFailed, err)
	}
	bridgeCtx.bridgeID = bridgeID
	return nil
}

func (s *CrossChainBridgeService) createPendingResult(bridgeCtx *bridgeContext) *BridgeResult {
	return &BridgeResult{
		BridgeID: bridgeCtx.bridgeID,
		Status:   BridgeStatusPending,
	}
}

func (s *CrossChainBridgeService) executeLock(
	bridgeCtx *bridgeContext,
	result *BridgeResult,
) error {
	lockTxHash, err := s.lockManager.LockAsset(
		bridgeCtx.ctx,
		bridgeCtx.req,
		bridgeCtx.gasEstimate,
	)
	if err != nil {
		s.handleLockFailure(bridgeCtx.ctx, bridgeCtx.bridgeID, err)
		return err
	}

	result.SourceTxHash = lockTxHash
	s.updateStatusSilently(bridgeCtx, BridgeStatusLocked, lockTxHash, nil)
	return nil
}

func (s *CrossChainBridgeService) loadBridgeContext(
	ctx context.Context,
	bridgeID string,
) (*bridgeContext, error) {
	req, gasEstimate, err := s.repo.GetBridgeRequest(ctx, bridgeID)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", msgBridgeRequestNotFound, err)
	}

	return &bridgeContext{
		ctx:         ctx,
		bridgeID:    bridgeID,
		req:         req,
		gasEstimate: gasEstimate,
	}, nil
}

func (s *CrossChainBridgeService) confirmLock(
	bridgeCtx *bridgeContext,
	confirmations int,
) (bool, error) {
	lockConfirmed, err := s.lockManager.ConfirmLock(
		bridgeCtx.ctx,
		bridgeCtx.bridgeID,
		confirmations,
	)
	if err != nil {
		return false, fmt.Errorf("%s: %w", msgLockConfirmFailed, err)
	}
	return lockConfirmed, nil
}

func (s *CrossChainBridgeService) confirmMint(
	bridgeCtx *bridgeContext,
	confirmations int,
) (bool, error) {
	mintConfirmed, err := s.mintManager.ConfirmMint(
		bridgeCtx.ctx,
		bridgeCtx.bridgeID,
		confirmations,
	)
	if err != nil {
		return false, fmt.Errorf("%s: %w", msgMintConfirmFailed, err)
	}
	return mintConfirmed, nil
}

func (s *CrossChainBridgeService) executeMint(bridgeCtx *bridgeContext) (string, error) {
	mintTxHash, err := s.mintManager.MintAsset(
		bridgeCtx.ctx,
		bridgeCtx.req,
		bridgeCtx.gasEstimate,
	)
	if err != nil {
		s.handleMintFailure(bridgeCtx.ctx, bridgeCtx.bridgeID, err)
		return "", err
	}
	return mintTxHash, nil
}

func (s *CrossChainBridgeService) createLockedResult(bridgeID string) *BridgeResult {
	return &BridgeResult{
		BridgeID: bridgeID,
		Status:   BridgeStatusLocked,
	}
}

func (s *CrossChainBridgeService) createMintingResult(bridgeID string, mintTxHash string) *BridgeResult {
	return &BridgeResult{
		BridgeID:     bridgeID,
		Status:       BridgeStatusMinting,
		SourceTxHash: "",
		TargetTxHash: mintTxHash,
	}
}

func (s *CrossChainBridgeService) completeBridge(
	bridgeCtx *bridgeContext,
	mintTxHash string,
) (*BridgeResult, error) {
	now := time.Now()
	s.updateStatusSilently(bridgeCtx, BridgeStatusCompleted, mintTxHash, nil)

	return &BridgeResult{
		BridgeID:     bridgeCtx.bridgeID,
		Status:       BridgeStatusCompleted,
		SourceTxHash: "",
		TargetTxHash: mintTxHash,
		GasUsed:      bridgeCtx.gasEstimate.GasLimit,
		ActualFee:    bridgeCtx.gasEstimate.TotalCost,
		CompletedAt:  &now,
	}, nil
}

func (s *CrossChainBridgeService) updateStatusSilently(
	bridgeCtx *bridgeContext,
	status BridgeStatus,
	txHash string,
	err error,
) {
	if updateErr := s.repo.UpdateStatus(bridgeCtx.ctx, bridgeCtx.bridgeID, status, txHash, err); updateErr != nil {
		s.logger.Warn("Failed to update bridge status",
			zap.String(logFieldBridgeID, bridgeCtx.bridgeID),
			zap.String("status", string(status)),
			zap.Error(updateErr))
	}
}

func (s *CrossChainBridgeService) asyncConfirmAndMint(bridgeCtx *bridgeContext) {
	defer s.recoverFromPanic(bridgeCtx.bridgeID)

	confirmCtx, cancel := context.WithTimeout(context.Background(), defaultAsyncTimeout)
	defer cancel()

	asyncCtx := &bridgeContext{
		ctx:         confirmCtx,
		bridgeID:    bridgeCtx.bridgeID,
		req:         bridgeCtx.req,
		gasEstimate: bridgeCtx.gasEstimate,
	}

	if !s.asyncConfirmLock(asyncCtx) {
		return
	}

	mintTxHash, err := s.asyncExecuteMint(asyncCtx)
	if err != nil {
		return
	}

	if !s.asyncConfirmMint(asyncCtx) {
		return
	}

	s.asyncCompleteBridge(asyncCtx, mintTxHash)
}

func (s *CrossChainBridgeService) asyncConfirmLock(asyncCtx *bridgeContext) bool {
	lockConfirmed, err := s.lockManager.ConfirmLock(
		asyncCtx.ctx,
		asyncCtx.bridgeID,
		defaultConfirmations,
	)
	if err != nil || !lockConfirmed {
		s.logger.Warn("Lock confirmation failed in async process",
			zap.String(logFieldBridgeID, asyncCtx.bridgeID),
			zap.Error(err))
		return false
	}

	s.updateStatusSilently(asyncCtx, BridgeStatusMinting, "", nil)
	return true
}

func (s *CrossChainBridgeService) asyncExecuteMint(asyncCtx *bridgeContext) (string, error) {
	mintTxHash, err := s.mintManager.MintAsset(
		asyncCtx.ctx,
		asyncCtx.req,
		asyncCtx.gasEstimate,
	)
	if err != nil {
		s.handleMintFailure(asyncCtx.ctx, asyncCtx.bridgeID, err)
		return "", err
	}
	return mintTxHash, nil
}

func (s *CrossChainBridgeService) asyncConfirmMint(asyncCtx *bridgeContext) bool {
	mintConfirmed, err := s.mintManager.ConfirmMint(
		asyncCtx.ctx,
		asyncCtx.bridgeID,
		defaultConfirmations,
	)
	if err != nil || !mintConfirmed {
		s.logger.Warn("Mint confirmation failed in async process",
			zap.String(logFieldBridgeID, asyncCtx.bridgeID),
			zap.Error(err))
		return false
	}
	return true
}

func (s *CrossChainBridgeService) asyncCompleteBridge(asyncCtx *bridgeContext, mintTxHash string) {
	now := time.Now()
	s.updateStatusSilently(asyncCtx, BridgeStatusCompleted, mintTxHash, nil)

	s.logger.Info("Bridge completed successfully",
		zap.String(logFieldBridgeID, asyncCtx.bridgeID),
		zap.String(logFieldTargetTx, mintTxHash),
		zap.Time(logFieldCompleted, now))
}

func (s *CrossChainBridgeService) recoverFromPanic(bridgeID string) {
	if r := recover(); r != nil {
		s.logger.Error("Panic in async confirm and mint",
			zap.String(logFieldBridgeID, bridgeID),
			zap.Any(logFieldPanic, r))
	}
}

func (s *CrossChainBridgeService) handleLockFailure(ctx context.Context, bridgeID string, err error) {
	s.logger.Error("Asset lock failed",
		zap.String(logFieldBridgeID, bridgeID),
		zap.Error(err))

	if rollbackErr := s.lockManager.RollbackLock(ctx, bridgeID); rollbackErr != nil {
		s.logger.Error("Failed to rollback lock",
			zap.String(logFieldBridgeID, bridgeID),
			zap.Error(rollbackErr))
	}
}

func (s *CrossChainBridgeService) handleMintFailure(ctx context.Context, bridgeID string, err error) {
	s.logger.Error("Asset mint failed",
		zap.String(logFieldBridgeID, bridgeID),
		zap.Error(err))

	if updateErr := s.repo.UpdateStatus(ctx, bridgeID, BridgeStatusFailed, "", err); updateErr != nil {
		s.logger.Error("Failed to update status to failed",
			zap.String(logFieldBridgeID, bridgeID),
			zap.Error(updateErr))
	}
}

func (s *CrossChainBridgeService) buildErrorResult(
	bridgeID string,
	status BridgeStatus,
	err error,
) *BridgeResult {
	return &BridgeResult{
		BridgeID: bridgeID,
		Status:   status,
		Error:    extractErrorMessage(err),
	}
}

func extractErrorMessage(err error) string {
	var validationErr *common.ValidationError
	var timeoutErr *common.TimeoutError

	switch {
	case errors.As(err, &validationErr):
		return validationErr.Error()
	case errors.As(err, &timeoutErr):
		return timeoutErr.Message
	default:
		return err.Error()
	}
}

func newValidationError(field string) error {
	return common.NewValidationError(map[string]string{
		field: field + msgRequired,
	})
}

func (g *GasEstimate) String() string {
	return fmt.Sprintf(
		"GasEstimate{Limit=%s, Price=%s, Priority=%s, Base=%s, Total=%s, Confidence=%.2f, Source=%s}",
		g.GasLimit, g.GasPrice, g.PriorityFee, g.BaseFee, g.TotalCost, g.Confidence, g.Source,
	)
}

func (b *BridgeRequest) String() string {
	return fmt.Sprintf(
		"BridgeRequest{TraceID=%s, SourceChain=%d, TargetChain=%d, Asset=%s, Amount=%s, Sender=%s, Recipient=%s}",
		b.TraceID, b.SourceChain, b.TargetChain, b.AssetType, b.Amount, b.Sender, b.Recipient,
	)
}

func NewBigInt(value int64) *big.Int {
	return big.NewInt(value)
}
