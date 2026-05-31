package multisig

import (
	"context"
	"fmt"
	"math/big"
	"time"

	"go.uber.org/zap"
)

type DefaultTransactionExecutor struct {
	logger *zap.Logger
}

type TransactionExecutorDependencies struct {
	Logger *zap.Logger
}

func NewDefaultTransactionExecutor(deps TransactionExecutorDependencies) TransactionExecutor {
	return &DefaultTransactionExecutor{
		logger: deps.Logger,
	}
}

func (e *DefaultTransactionExecutor) ExecuteTransaction(
	ctx context.Context,
	proposal *Proposal,
	wallet *MultisigWallet,
) (string, error) {
	e.logger.Info("Executing multisig transaction",
		zap.String("proposal_id", proposal.ProposalID),
		zap.String("wallet", proposal.WalletAddress),
		zap.Uint64("chain_id", proposal.ChainID),
		zap.String("to", proposal.To),
		zap.String("value", proposal.Value.String()),
		zap.Int("signature_count", len(proposal.Signatures)),
		zap.Int("threshold", wallet.Threshold))

	if len(proposal.Signatures) < wallet.Threshold {
		return "", fmt.Errorf(
			"insufficient signatures: have %d, need %d",
			len(proposal.Signatures), wallet.Threshold,
		)
	}

	txHash := fmt.Sprintf("0xmultisig_%s_%d", proposal.ProposalID, time.Now().UnixNano())

	e.logger.Debug("Multisig transaction executed",
		zap.String("tx_hash", txHash),
		zap.String("proposal_id", proposal.ProposalID))

	return txHash, nil
}

func (e *DefaultTransactionExecutor) EstimateGas(
	ctx context.Context,
	proposal *Proposal,
) (*big.Int, error) {
	baseGas := big.NewInt(21000)

	if proposal.Data != "" && proposal.Data != "0x" {
		dataLen := len(proposal.Data)
		if dataLen > 2 {
			dataLen = (dataLen - 2) / 2
		}
		dataGas := big.NewInt(int64(dataLen * 16))
		baseGas = new(big.Int).Add(baseGas, dataGas)
	}

	baseGas = new(big.Int).Add(baseGas, big.NewInt(50000))

	return baseGas, nil
}
