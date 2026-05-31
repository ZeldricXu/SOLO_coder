package multisig

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/v2/internal/common"
)

type DefaultSignatureCollector struct {
	repo   ProposalRepository
	logger *zap.Logger
}

type SignatureCollectorDependencies struct {
	Repository ProposalRepository
	Logger     *zap.Logger
}

func NewDefaultSignatureCollector(deps SignatureCollectorDependencies) SignatureCollector {
	return &DefaultSignatureCollector{
		repo:   deps.Repository,
		logger: deps.Logger,
	}
}

func (c *DefaultSignatureCollector) AddSignature(
	ctx context.Context,
	proposalID string,
	signer string,
	signature string,
) (*Proposal, error) {
	proposal, err := c.repo.GetProposal(ctx, proposalID)
	if err != nil {
		return nil, fmt.Errorf("proposal not found: %w", err)
	}

	if proposal.Status != ProposalStatusPending && proposal.Status != ProposalStatusApproved {
		return nil, common.NewInvalidStateError(
			fmt.Sprintf("cannot add signature to proposal in status: %s", proposal.Status),
		)
	}

	if c.HasSigned(proposal, signer) {
		return nil, common.NewValidationError(map[string]string{
			"signer": fmt.Sprintf("signer %s has already signed", signer),
		})
	}

	valid, err := c.ValidateSignature(ctx, proposal, signer, signature)
	if err != nil {
		return nil, fmt.Errorf("signature validation failed: %w", err)
	}
	if !valid {
		return nil, common.NewValidationError(map[string]string{
			"signature": "invalid signature",
		})
	}

	sig := &Signature{
		Signer:    signer,
		Signature: signature,
		Timestamp: time.Now(),
	}

	if err := c.repo.AddProposalSignature(ctx, proposalID, sig); err != nil {
		return nil, fmt.Errorf("failed to add signature: %w", err)
	}

	proposal.Signatures = append(proposal.Signatures, *sig)

	wallet, err := c.repo.GetWallet(ctx, proposal.WalletAddress, proposal.ChainID)
	if err != nil {
		c.logger.Warn("Failed to get wallet for threshold check",
			zap.String("proposal_id", proposalID),
			zap.Error(err))
		return proposal, nil
	}

	if c.IsThresholdReached(proposal, wallet.Threshold) {
		if err := c.repo.UpdateProposalStatus(ctx, proposalID, ProposalStatusApproved, ""); err != nil {
			c.logger.Error("Failed to update proposal status to approved",
				zap.String("proposal_id", proposalID),
				zap.Error(err))
		}
		proposal.Status = ProposalStatusApproved
	}

	return proposal, nil
}

func (c *DefaultSignatureCollector) ValidateSignature(
	ctx context.Context,
	proposal *Proposal,
	signer string,
	signature string,
) (bool, error) {
	wallet, err := c.repo.GetWallet(ctx, proposal.WalletAddress, proposal.ChainID)
	if err != nil {
		return false, fmt.Errorf("failed to get wallet: %w", err)
	}

	isSigner := false
	for _, s := range wallet.Signers {
		if s == signer {
			isSigner = true
			break
		}
	}

	if !isSigner {
		return false, common.NewValidationError(map[string]string{
			"signer": fmt.Sprintf("signer %s is not a wallet signer", signer),
		})
	}

	if len(signature) < 130 || len(signature) > 132 {
		return false, nil
	}

	return true, nil
}

func (c *DefaultSignatureCollector) HasSigned(proposal *Proposal, signer string) bool {
	for _, sig := range proposal.Signatures {
		if sig.Signer == signer {
			return true
		}
	}
	return false
}

func (c *DefaultSignatureCollector) IsThresholdReached(proposal *Proposal, threshold int) bool {
	return len(proposal.Signatures) >= threshold
}
