package multisig

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/v2/internal/common"
)

type MultisigCoordinatorService struct {
	nonceManager    NonceManager
	sigCollector    SignatureCollector
	txExecutor      TransactionExecutor
	repo            ProposalRepository
	logger          *zap.Logger
}

type CoordinatorDependencies struct {
	NonceManager    NonceManager
	SignatureCollector SignatureCollector
	TransactionExecutor TransactionExecutor
	Repository      ProposalRepository
	Logger          *zap.Logger
}

func NewMultisigCoordinatorService(deps CoordinatorDependencies) MultisigCoordinator {
	return &MultisigCoordinatorService{
		nonceManager:    deps.NonceManager,
		sigCollector:    deps.SignatureCollector,
		txExecutor:      deps.TransactionExecutor,
		repo:            deps.Repository,
		logger:          deps.Logger,
	}
}

func (s *MultisigCoordinatorService) CreateProposal(
	ctx context.Context,
	req *ProposalRequest,
) (*ProposalResult, error) {
	if err := s.validateProposalRequest(req); err != nil {
		return nil, err
	}

	nonce, err := s.nonceManager.GetNextNonce(ctx, req.WalletAddress, req.ChainID)
	if err != nil {
		return nil, fmt.Errorf("failed to get next nonce: %w", err)
	}

	proposalID, err := s.repo.CreateProposal(ctx, req, nonce)
	if err != nil {
		_ = s.nonceManager.ReleaseNonce(ctx, req.WalletAddress, req.ChainID, nonce)
		return nil, fmt.Errorf("failed to create proposal: %w", err)
	}

	s.logger.Info("Multisig proposal created",
		zap.String("proposal_id", proposalID),
		zap.String("wallet", req.WalletAddress),
		zap.Uint64("chain_id", req.ChainID),
		zap.String("nonce", nonce.String()),
		zap.String("creator", req.Creator))

	return &ProposalResult{
		ProposalID: proposalID,
		Status:     ProposalStatusPending,
		Nonce:      nonce,
	}, nil
}

func (s *MultisigCoordinatorService) ApproveProposal(
	ctx context.Context,
	proposalID string,
	signer string,
	signature string,
) (*ProposalResult, error) {
	if err := s.validateApprovalRequest(proposalID, signer, signature); err != nil {
		return nil, err
	}

	proposal, err := s.sigCollector.AddSignature(ctx, proposalID, signer, signature)
	if err != nil {
		return nil, err
	}

	s.logger.Info("Proposal approved",
		zap.String("proposal_id", proposalID),
		zap.String("signer", signer),
		zap.Int("signature_count", len(proposal.Signatures)),
		zap.String("status", string(proposal.Status)))

	return &ProposalResult{
		ProposalID: proposalID,
		Status:     proposal.Status,
		Nonce:      proposal.Nonce,
	}, nil
}

func (s *MultisigCoordinatorService) RejectProposal(
	ctx context.Context,
	proposalID string,
	signer string,
) (*ProposalResult, error) {
	proposal, err := s.repo.GetProposal(ctx, proposalID)
	if err != nil {
		return nil, fmt.Errorf("proposal not found: %w", err)
	}

	if proposal.Status != ProposalStatusPending {
		return nil, common.NewInvalidStateError(
			fmt.Sprintf("cannot reject proposal in status: %s", proposal.Status),
		)
	}

	wallet, err := s.repo.GetWallet(ctx, proposal.WalletAddress, proposal.ChainID)
	if err != nil {
		return nil, fmt.Errorf("failed to get wallet: %w", err)
	}

	isSigner := false
	for _, s := range wallet.Signers {
		if s == signer {
			isSigner = true
			break
		}
	}

	if !isSigner {
		return nil, common.NewValidationError(map[string]string{
			"signer": fmt.Sprintf("signer %s is not a wallet signer", signer),
		})
	}

	if err := s.repo.UpdateProposalStatus(ctx, proposalID, ProposalStatusRejected, ""); err != nil {
		return nil, fmt.Errorf("failed to reject proposal: %w", err)
	}

	_ = s.nonceManager.ReleaseNonce(ctx, proposal.WalletAddress, proposal.ChainID, proposal.Nonce)

	s.logger.Info("Proposal rejected",
		zap.String("proposal_id", proposalID),
		zap.String("rejected_by", signer))

	return &ProposalResult{
		ProposalID: proposalID,
		Status:     ProposalStatusRejected,
		Nonce:      proposal.Nonce,
	}, nil
}

func (s *MultisigCoordinatorService) ExecuteProposal(
	ctx context.Context,
	proposalID string,
) (*ProposalResult, error) {
	proposal, err := s.repo.GetProposal(ctx, proposalID)
	if err != nil {
		return nil, fmt.Errorf("proposal not found: %w", err)
	}

	if proposal.Status != ProposalStatusApproved {
		return nil, common.NewInvalidStateError(
			fmt.Sprintf("cannot execute proposal in status: %s", proposal.Status),
		)
	}

	wallet, err := s.repo.GetWallet(ctx, proposal.WalletAddress, proposal.ChainID)
	if err != nil {
		return nil, fmt.Errorf("failed to get wallet: %w", err)
	}

	txHash, err := s.txExecutor.ExecuteTransaction(ctx, proposal, wallet)
	if err != nil {
		_ = s.repo.UpdateProposalStatus(ctx, proposalID, ProposalStatusFailed, "")
		return &ProposalResult{
			ProposalID: proposalID,
			Status:     ProposalStatusFailed,
			Nonce:      proposal.Nonce,
			Error:      err.Error(),
		}, err
	}

	now := time.Now()
	proposal.ExecutedAt = &now
	proposal.TxHash = txHash

	if err := s.repo.UpdateProposalStatus(ctx, proposalID, ProposalStatusExecuted, txHash); err != nil {
		s.logger.Error("Failed to update proposal status to executed",
			zap.String("proposal_id", proposalID),
			zap.Error(err))
	}

	_ = s.nonceManager.ConsumeNonce(ctx, proposal.WalletAddress, proposal.ChainID, proposal.Nonce)

	s.logger.Info("Proposal executed successfully",
		zap.String("proposal_id", proposalID),
		zap.String("tx_hash", txHash),
		zap.Time("executed_at", now))

	return &ProposalResult{
		ProposalID: proposalID,
		Status:     ProposalStatusExecuted,
		Nonce:      proposal.Nonce,
		TxHash:     txHash,
	}, nil
}

func (s *MultisigCoordinatorService) CancelProposal(
	ctx context.Context,
	proposalID string,
	signer string,
) (*ProposalResult, error) {
	proposal, err := s.repo.GetProposal(ctx, proposalID)
	if err != nil {
		return nil, fmt.Errorf("proposal not found: %w", err)
	}

	if proposal.Status != ProposalStatusPending && proposal.Status != ProposalStatusApproved {
		return nil, common.NewInvalidStateError(
			fmt.Sprintf("cannot cancel proposal in status: %s", proposal.Status),
		)
	}

	wallet, err := s.repo.GetWallet(ctx, proposal.WalletAddress, proposal.ChainID)
	if err != nil {
		return nil, fmt.Errorf("failed to get wallet: %w", err)
	}

	isSigner := false
	for _, s := range wallet.Signers {
		if s == signer {
			isSigner = true
			break
		}
	}

	if !isSigner {
		return nil, common.NewValidationError(map[string]string{
			"signer": fmt.Sprintf("signer %s is not a wallet signer", signer),
		})
	}

	if err := s.repo.UpdateProposalStatus(ctx, proposalID, ProposalStatusCancelled, ""); err != nil {
		return nil, fmt.Errorf("failed to cancel proposal: %w", err)
	}

	_ = s.nonceManager.ReleaseNonce(ctx, proposal.WalletAddress, proposal.ChainID, proposal.Nonce)

	s.logger.Info("Proposal cancelled",
		zap.String("proposal_id", proposalID),
		zap.String("cancelled_by", signer))

	return &ProposalResult{
		ProposalID: proposalID,
		Status:     ProposalStatusCancelled,
		Nonce:      proposal.Nonce,
	}, nil
}

func (s *MultisigCoordinatorService) GetProposal(
	ctx context.Context,
	proposalID string,
) (*Proposal, error) {
	return s.repo.GetProposal(ctx, proposalID)
}

func (s *MultisigCoordinatorService) ListProposals(
	ctx context.Context,
	walletAddress string,
	status ProposalStatus,
) ([]*Proposal, error) {
	return s.repo.ListProposals(ctx, walletAddress, status)
}

func (s *MultisigCoordinatorService) validateProposalRequest(req *ProposalRequest) error {
	if req == nil {
		return common.NewValidationError(map[string]string{
			"request": "request is required",
		})
	}

	details := make(map[string]string)

	if req.WalletAddress == "" {
		details["wallet_address"] = "wallet_address is required"
	}
	if req.ChainID == 0 {
		details["chain_id"] = "chain_id is required"
	}
	if req.To == "" {
		details["to"] = "to is required"
	}
	if req.Value == nil {
		details["value"] = "value is required"
	}
	if req.Value != nil && req.Value.Sign() < 0 {
		details["value"] = "value cannot be negative"
	}
	if req.Creator == "" {
		details["creator"] = "creator is required"
	}

	if len(details) > 0 {
		return common.NewValidationError(details)
	}

	return nil
}

func (s *MultisigCoordinatorService) validateApprovalRequest(
	proposalID string,
	signer string,
	signature string,
) error {
	details := make(map[string]string)

	if proposalID == "" {
		details["proposal_id"] = "proposal_id is required"
	}
	if signer == "" {
		details["signer"] = "signer is required"
	}
	if signature == "" {
		details["signature"] = "signature is required"
	}

	if len(details) > 0 {
		return common.NewValidationError(details)
	}

	return nil
}

func (p *Proposal) String() string {
	return fmt.Sprintf(
		"Proposal{ID=%s, Wallet=%s, ChainID=%d, Nonce=%s, Status=%s, Signatures=%d}",
		p.ProposalID, p.WalletAddress, p.ChainID, p.Nonce, p.Status, len(p.Signatures),
	)
}

func (w *MultisigWallet) String() string {
	return fmt.Sprintf(
		"MultisigWallet{Address=%s, ChainID=%d, Threshold=%d/%d, Nonce=%s}",
		w.Address, w.ChainID, w.Threshold, len(w.Signers), w.Nonce,
	)
}
