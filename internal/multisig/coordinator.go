package multisig

import (
	"crypto/ecdsa"
	"errors"
	"gas-estimator/internal/tx"
	"gas-estimator/pkg/models"
	"math/big"
	"sync"
	"time"
)

var (
	ErrProposalNotFound     = errors.New("proposal not found")
	ErrInsufficientSignatures = errors.New("insufficient signatures")
	ErrProposalAlreadyExecuted = errors.New("proposal already executed")
	ErrInvalidSigner        = errors.New("invalid signer")
	ErrDuplicateSignature   = errors.New("duplicate signature")
)

type MultisigCoordinator struct {
	signers          map[string]bool
	threshold        int
	proposals        map[string]*models.MultisigProposal
	proposalByTxHash map[string]*models.MultisigProposal
	txBuilder        *tx.TransactionBuilder
	mutex            sync.RWMutex
}

type ProposalConfig struct {
	Threshold  int
	Signers    []string
}

func NewMultisigCoordinator(cfg ProposalConfig, chainID int64) *MultisigCoordinator {
	signerMap := make(map[string]bool)
	for _, s := range cfg.Signers {
		signerMap[s] = true
	}
	
	if cfg.Threshold <= 0 {
		cfg.Threshold = 1
	}
	
	if cfg.Threshold > len(cfg.Signers) {
		cfg.Threshold = len(cfg.Signers)
	}
	
	return &MultisigCoordinator{
		signers:          signerMap,
		threshold:        cfg.Threshold,
		proposals:        make(map[string]*models.MultisigProposal),
		proposalByTxHash: make(map[string]*models.MultisigProposal),
		txBuilder:        tx.NewTransactionBuilder(big.NewInt(chainID)),
		mutex:            sync.RWMutex{},
	}
}

func (mc *MultisigCoordinator) CreateProposal(transaction *models.Transaction, id string) (*models.MultisigProposal, error) {
	if transaction == nil {
		return nil, errors.New("transaction cannot be nil")
	}
	
	mc.mutex.Lock()
	defer mc.mutex.Unlock()
	
	if existing, ok := mc.proposals[id]; ok {
		return existing, nil
	}
	
	if id == "" {
		id = generateProposalID()
	}
	
	proposal := &models.MultisigProposal{
		ID:              id,
		Transaction:     transaction,
		RequiredSigners: mc.threshold,
		Status:          "pending",
		Signatures:      make([]models.Signature, 0),
		CreatedAt:       time.Now(),
	}
	
	mc.proposals[id] = proposal
	
	return proposal, nil
}

func (mc *MultisigCoordinator) GetProposal(id string) (*models.MultisigProposal, error) {
	mc.mutex.RLock()
	defer mc.mutex.RUnlock()
	
	if proposal, ok := mc.proposals[id]; ok {
		return proposal, nil
	}
	
	return nil, ErrProposalNotFound
}

func (mc *MultisigCoordinator) ListProposals(status string) []*models.MultisigProposal {
	mc.mutex.RLock()
	defer mc.mutex.RUnlock()
	
	proposals := make([]*models.MultisigProposal, 0)
	
	for _, p := range mc.proposals {
		if status == "" || p.Status == status {
			proposals = append(proposals, p)
		}
	}
	
	return proposals
}

func (mc *MultisigCoordinator) AddSignature(proposalID string, signature models.Signature) error {
	mc.mutex.Lock()
	defer mc.mutex.Unlock()
	
	proposal, ok := mc.proposals[proposalID]
	if !ok {
		return ErrProposalNotFound
	}
	
	if proposal.Status == "executed" {
		return ErrProposalAlreadyExecuted
	}
	
	if !mc.signers[signature.Signer] {
		return ErrInvalidSigner
	}
	
	for _, existingSig := range proposal.Signatures {
		if existingSig.Signer == signature.Signer {
			return ErrDuplicateSignature
		}
	}
	
	if !mc.txBuilder.VerifySignature(proposal.Transaction, signature) {
		return errors.New("invalid signature")
	}
	
	proposal.Signatures = append(proposal.Signatures, signature)
	
	if len(proposal.Signatures) >= proposal.RequiredSigners {
		proposal.Status = "ready"
	}
	
	return nil
}

func (mc *MultisigCoordinator) SignProposal(proposalID string, signerAddress string, privateKey *ecdsa.PrivateKey) error {
	mc.mutex.Lock()
	defer mc.mutex.Unlock()
	
	proposal, ok := mc.proposals[proposalID]
	if !ok {
		return ErrProposalNotFound
	}
	
	if proposal.Status == "executed" {
		return ErrProposalAlreadyExecuted
	}
	
	if !mc.signers[signerAddress] {
		return ErrInvalidSigner
	}
	
	for _, existingSig := range proposal.Signatures {
		if existingSig.Signer == signerAddress {
			return ErrDuplicateSignature
		}
	}
	
	if privateKey == nil {
		return errors.New("private key cannot be nil")
	}
	
	signature := models.Signature{
		Signer: signerAddress,
		Weight: 1,
		V:      big.NewInt(0),
		R:      big.NewInt(0),
		S:      big.NewInt(0),
	}
	
	err := mc.txBuilder.Sign(proposal.Transaction, privateKey, 1)
	if err != nil {
		return err
	}
	
	if len(proposal.Transaction.Signatures) > 0 {
		signature.V = proposal.Transaction.Signatures[0].V
		signature.R = proposal.Transaction.Signatures[0].R
		signature.S = proposal.Transaction.Signatures[0].S
	}
	
	proposal.Signatures = append(proposal.Signatures, signature)
	
	if len(proposal.Signatures) >= proposal.RequiredSigners {
		proposal.Status = "ready"
	}
	
	return nil
}

func (mc *MultisigCoordinator) CanExecute(proposalID string) bool {
	mc.mutex.RLock()
	defer mc.mutex.RUnlock()
	
	proposal, ok := mc.proposals[proposalID]
	if !ok {
		return false
	}
	
	return len(proposal.Signatures) >= proposal.RequiredSigners
}

func (mc *MultisigCoordinator) ExecuteProposal(proposalID string) (*models.Transaction, error) {
	mc.mutex.Lock()
	defer mc.mutex.Unlock()
	
	proposal, ok := mc.proposals[proposalID]
	if !ok {
		return nil, ErrProposalNotFound
	}
	
	if proposal.Status == "executed" {
		return nil, ErrProposalAlreadyExecuted
	}
	
	if len(proposal.Signatures) < proposal.RequiredSigners {
		return nil, ErrInsufficientSignatures
	}
	
	for _, sig := range proposal.Signatures {
		proposal.Transaction.Signatures = append(proposal.Transaction.Signatures, sig)
	}
	
	proposal.Status = "executed"
	now := time.Now()
	proposal.ExecutedAt = &now
	
	return proposal.Transaction, nil
}

func (mc *MultisigCoordinator) GetSignedTransaction(proposalID string) (*models.Transaction, error) {
	mc.mutex.RLock()
	defer mc.mutex.RUnlock()
	
	proposal, ok := mc.proposals[proposalID]
	if !ok {
		return nil, ErrProposalNotFound
	}
	
	if len(proposal.Signatures) < proposal.RequiredSigners {
		return nil, ErrInsufficientSignatures
	}
	
	signedTx := *proposal.Transaction
	signedTx.Signatures = make([]models.Signature, len(proposal.Signatures))
	copy(signedTx.Signatures, proposal.Signatures)
	
	return &signedTx, nil
}

func (mc *MultisigCoordinator) GetProposalStatus(proposalID string) (string, int, int, error) {
	mc.mutex.RLock()
	defer mc.mutex.RUnlock()
	
	proposal, ok := mc.proposals[proposalID]
	if !ok {
		return "", 0, 0, ErrProposalNotFound
	}
	
	return proposal.Status, len(proposal.Signatures), proposal.RequiredSigners, nil
}

func (mc *MultisigCoordinator) RejectProposal(proposalID string, reason string) error {
	mc.mutex.Lock()
	defer mc.mutex.Unlock()
	
	proposal, ok := mc.proposals[proposalID]
	if !ok {
		return ErrProposalNotFound
	}
	
	proposal.Status = "rejected"
	
	return nil
}

func (mc *MultisigCoordinator) GetThreshold() int {
	return mc.threshold
}

func (mc *MultisigCoordinator) GetSigners() []string {
	mc.mutex.RLock()
	defer mc.mutex.RUnlock()
	
	signers := make([]string, 0, len(mc.signers))
	for s := range mc.signers {
		signers = append(signers, s)
	}
	
	return signers
}

func (mc *MultisigCoordinator) IsSigner(address string) bool {
	mc.mutex.RLock()
	defer mc.mutex.RUnlock()
	
	return mc.signers[address]
}

func (mc *MultisigCoordinator) RemoveProposal(proposalID string) error {
	mc.mutex.Lock()
	defer mc.mutex.Unlock()
	
	if _, ok := mc.proposals[proposalID]; ok {
		delete(mc.proposals, proposalID)
		return nil
	}
	
	return ErrProposalNotFound
}

func generateProposalID() string {
	return "proposal_" + time.Now().Format("20060102150405")
}
