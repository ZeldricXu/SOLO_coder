package service

import (
	"context"
	"math/big"
	"time"

	"github.com/solocoder/session147/internal/common/errors"
	"github.com/solocoder/session147/internal/common/logger"
	"github.com/solocoder/session147/internal/common/plugin"
	"github.com/solocoder/session147/internal/common/utils"
	"github.com/solocoder/session147/internal/txbuilder/domain"
	"github.com/solocoder/session147/internal/txbuilder/ports"
	"go.uber.org/zap"
)

type txBuilderService struct {
	repo        ports.TxRepository
	broadcaster ports.ChainBroadcaster
	gasProvider ports.GasProvider
	pluginManager *plugin.PluginManager
}

func NewTxBuilderService(
	repo ports.TxRepository,
	broadcaster ports.ChainBroadcaster,
	gasProvider ports.GasProvider,
	pluginManager *plugin.PluginManager,
) ports.TxBuilderService {
	if pluginManager == nil {
		pluginManager = plugin.NewPluginManager()
	}

	return &txBuilderService{
		repo:          repo,
		broadcaster:   broadcaster,
		gasProvider:   gasProvider,
		pluginManager: pluginManager,
	}
}

func (s *txBuilderService) BuildTransaction(ctx context.Context, req *domain.BuildRequest) (*domain.Transaction, error) {
	logger.Info("building transaction", zap.String("from", req.From), zap.Int64("chain_id", req.ChainID))

	hookCtx, err := s.pluginManager.ExecuteHook(ctx, plugin.HookBeforeTxBuild, map[string]interface{}{
		"request": req,
		"chain_id": req.ChainID,
		"from":     req.From,
		"to":       req.To,
	})
	if err != nil {
		logger.Warn("before_tx_build hook failed", zap.Error(err))
	}
	if hookCtx.StopPropagation {
		return nil, errors.BadRequest("transaction build blocked by plugin", nil)
	}

	nonce := req.Nonce
	if nonce == nil {
		n, err := s.broadcaster.GetNonce(ctx, req.From)
		if err != nil {
			logger.Warn("failed to get nonce, using 0", zap.Error(err))
			n = 0
		}
		nonce = &n
	}

	gasLimit := req.GasLimit
	if gasLimit == 0 {
		estimated, err := s.EstimateGas(ctx, req.ChainID, req.To, req.Data, new(big.Int))
		if err == nil {
			gasLimit = estimated
		} else {
			gasLimit = 21000
		}
	}

	txType := domain.TxTypeLegacy
	if req.MaxFeePerGas != "" || req.PriorityFee != "" {
		txType = domain.TxTypeDynamic
	}

	tx := &domain.Transaction{
		ID:           utils.GenerateID("tx"),
		ChainID:      req.ChainID,
		From:         req.From,
		To:           req.To,
		Value:        req.Value,
		Data:         req.Data,
		Nonce:        *nonce,
		GasLimit:     gasLimit,
		GasPrice:     req.GasPrice,
		MaxFeePerGas: req.MaxFeePerGas,
		PriorityFee:  req.PriorityFee,
		Type:         txType,
		Status:       domain.TxStatusPending,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		Metadata:     req.Metadata,
	}

	if req.Optimization.Enabled {
		gasHookCtx, err := s.pluginManager.ExecuteHook(ctx, plugin.HookGasOptimization, map[string]interface{}{
			"tx":       tx,
			"strategy": req.Optimization.Strategy,
		})
		if err == nil {
			if optimizedTx, ok := gasHookCtx.Data["tx"].(*domain.Transaction); ok {
				tx = optimizedTx
			}
		}

		if err := s.OptimizeGas(ctx, tx, req.Optimization.Strategy); err != nil {
			logger.Warn("gas optimization failed", zap.Error(err))
		}
	}

	if err := s.repo.CreateTx(ctx, tx); err != nil {
		return nil, errors.Internal("failed to create transaction", err)
	}

	_, _ = s.pluginManager.ExecuteHook(ctx, plugin.HookAfterTxBuild, map[string]interface{}{
		"tx_id": tx.ID,
		"tx":    tx,
	})

	return tx, nil
}

func (s *txBuilderService) SignTransaction(ctx context.Context, req *domain.SignRequest) (*domain.Transaction, error) {
	logger.Info("signing transaction", zap.String("tx_id", req.TxID))

	tx, err := s.repo.GetTx(ctx, req.TxID)
	if err != nil {
		return nil, errors.NotFound("transaction not found", err)
	}

	if tx.Status == domain.TxStatusConfirmed || tx.Status == domain.TxStatusBroadcast {
		return nil, errors.BadRequest("transaction already broadcast or confirmed", nil)
	}

	hookCtx, err := s.pluginManager.ExecuteHook(ctx, plugin.HookBeforeTxSign, map[string]interface{}{
		"tx_id":    req.TxID,
		"tx":       tx,
		"signer":   req.Signer,
		"chain_id": tx.ChainID,
	})
	if err != nil {
		logger.Warn("before_tx_sign hook failed", zap.Error(err))
	}
	if hookCtx.StopPropagation {
		return nil, errors.BadRequest("transaction signing blocked by plugin", nil)
	}

	if err := s.repo.AddSignature(ctx, req.TxID, req.Signature); err != nil {
		return nil, errors.Internal("failed to add signature", err)
	}

	tx.Signatures = append(tx.Signatures, req.Signature)
	tx.Status = domain.TxStatusSigned
	tx.UpdatedAt = time.Now()

	_, _ = s.pluginManager.ExecuteHook(ctx, plugin.HookAfterTxSign, map[string]interface{}{
		"tx_id":      req.TxID,
		"tx":         tx,
		"signatures": tx.Signatures,
	})

	return tx, nil
}

func (s *txBuilderService) BroadcastTransaction(ctx context.Context, txID string) (*domain.BroadcastResponse, error) {
	logger.Info("broadcasting transaction", zap.String("tx_id", txID))

	tx, err := s.repo.GetTx(ctx, txID)
	if err != nil {
		return nil, errors.NotFound("transaction not found", err)
	}

	if tx.Status != domain.TxStatusSigned {
		return nil, errors.BadRequest("transaction not signed", nil)
	}

	hookCtx, err := s.pluginManager.ExecuteHook(ctx, plugin.HookBeforeTxBroadcast, map[string]interface{}{
		"tx_id": txID,
		"tx":    tx,
	})
	if err != nil {
		logger.Warn("before_tx_broadcast hook failed", zap.Error(err))
	}
	if hookCtx.StopPropagation {
		return nil, errors.BadRequest("transaction broadcast blocked by plugin", nil)
	}

	hash, err := s.broadcaster.Broadcast(ctx, tx.RawTx)
	if err != nil {
		tx.Status = domain.TxStatusFailed
		tx.Error = err.Error()
		tx.UpdatedAt = time.Now()
		_ = s.repo.UpdateTx(ctx, tx)

		_, _ = s.pluginManager.ExecuteHook(ctx, plugin.HookTxFailed, map[string]interface{}{
			"tx_id": txID,
			"tx":    tx,
			"error": err.Error(),
		})

		return nil, errors.Internal("failed to broadcast transaction", err)
	}

	tx.Hash = hash
	tx.Status = domain.TxStatusBroadcast
	tx.UpdatedAt = time.Now()

	if err := s.repo.UpdateTx(ctx, tx); err != nil {
		logger.Error("failed to update tx status", zap.Error(err))
	}

	_, _ = s.pluginManager.ExecuteHook(ctx, plugin.HookAfterTxBroadcast, map[string]interface{}{
		"tx_id": txID,
		"tx":    tx,
		"hash":  hash,
	})

	return &domain.BroadcastResponse{
		TxID:   txID,
		Hash:   hash,
		Status: domain.TxStatusBroadcast,
	}, nil
}

func (s *txBuilderService) GetTransaction(ctx context.Context, id string) (*domain.Transaction, error) {
	return s.repo.GetTx(ctx, id)
}

func (s *txBuilderService) ListTransactions(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.Transaction, int64, error) {
	return s.repo.ListTxs(ctx, filter, page, pageSize)
}

func (s *txBuilderService) EstimateGas(ctx context.Context, chainID int64, to, data string, value *big.Int) (uint64, error) {
	return 21000, nil
}

func (s *txBuilderService) OptimizeGas(ctx context.Context, tx *domain.Transaction, strategy string) error {
	gasPrice, priorityFee, err := s.gasProvider.GetOptimalGasPrice(ctx, tx.ChainID, strategy)
	if err != nil {
		return err
	}

	if tx.Type == domain.TxTypeDynamic {
		tx.MaxFeePerGas = gasPrice.String()
		tx.PriorityFee = priorityFee.String()
	} else {
		tx.GasPrice = gasPrice.String()
	}

	return nil
}

func (s *txBuilderService) CancelTransaction(ctx context.Context, txID string) error {
	tx, err := s.repo.GetTx(ctx, txID)
	if err != nil {
		return errors.NotFound("transaction not found", err)
	}

	if tx.Status != domain.TxStatusPending && tx.Status != domain.TxStatusSigned {
		return errors.BadRequest("cannot cancel transaction in current state", nil)
	}

	tx.Status = domain.TxStatusRejected
	tx.UpdatedAt = time.Now()
	return s.repo.UpdateTx(ctx, tx)
}

func (s *txBuilderService) ReplaceTransaction(ctx context.Context, txID string, newGasPrice string) (*domain.Transaction, error) {
	existingTx, err := s.repo.GetTx(ctx, txID)
	if err != nil {
		return nil, errors.NotFound("transaction not found", err)
	}

	if existingTx.Status == domain.TxStatusConfirmed {
		return nil, errors.BadRequest("transaction already confirmed", nil)
	}

	replacementTx := *existingTx
	replacementTx.ID = utils.GenerateID("tx")
	replacementTx.GasPrice = newGasPrice
	replacementTx.Status = domain.TxStatusPending
	replacementTx.CreatedAt = time.Now()
	replacementTx.UpdatedAt = time.Now()
	replacementTx.Signatures = nil

	if err := s.repo.CreateTx(ctx, &replacementTx); err != nil {
		return nil, errors.Internal("failed to create replacement transaction", err)
	}

	existingTx.Status = domain.TxStatusRejected
	existingTx.UpdatedAt = time.Now()
	_ = s.repo.UpdateTx(ctx, existingTx)

	return &replacementTx, nil
}

func (s *txBuilderService) RegisterPlugin(p plugin.Plugin, config map[string]interface{}) error {
	return s.pluginManager.Register(p, config)
}

func (s *txBuilderService) UnregisterPlugin(pluginID string) error {
	return s.pluginManager.Unregister(pluginID)
}

func (s *txBuilderService) ListPlugins() []plugin.PluginInfo {
	return s.pluginManager.ListPlugins()
}

func (s *txBuilderService) EnablePlugin(pluginID string) error {
	return s.pluginManager.Enable(pluginID)
}

func (s *txBuilderService) DisablePlugin(pluginID string) error {
	return s.pluginManager.Disable(pluginID)
}

func (s *txBuilderService) GetPluginManager() *plugin.PluginManager {
	return s.pluginManager
}
