package service

import (
	"context"
	"fmt"
	"hash/fnv"
	"time"

	"github.com/solocoder/session147/internal/common/cache"
	"github.com/solocoder/session147/internal/common/errors"
	"github.com/solocoder/session147/internal/common/logger"
	"github.com/solocoder/session147/internal/common/routing"
	"github.com/solocoder/session147/internal/common/utils"
	"github.com/solocoder/session147/internal/multisig/domain"
	"github.com/solocoder/session147/internal/multisig/ports"
	"go.uber.org/zap"
)

const (
	defaultCacheTTL      = 5 * time.Second
	cacheKeyWallet       = "wallet:"
	cacheKeyWalletsList  = "wallets:list:"
	cacheKeyProposal     = "proposal:"
	cacheKeyProposalsList = "proposals:list:"

	errMsgWriteNotAvailable = "write operations not available"
	errMsgReadNotAvailable  = "read operations not available"
	errMsgWalletNotFound    = "wallet not found"
	errMsgProposalNotFound  = "proposal not found"
)

type multisigService struct {
	walletRepo   ports.WalletRepository
	proposalRepo ports.ProposalRepository
	chainAdapter ports.ChainAdapter
	router       *routing.ReadWriteRouter
	walletCache  *cache.TTLCache
	proposalCache *cache.TTLCache
	listCache    *cache.TTLCache
}

func NewMultisigService(
	walletRepo ports.WalletRepository,
	proposalRepo ports.ProposalRepository,
	chainAdapter ports.ChainAdapter,
	router *routing.ReadWriteRouter,
) ports.MultisigService {
	if router == nil {
		router = routing.NewReadWriteRouter(routing.RouterConfig{
			PrimaryNode: routing.DatabaseNode{
				ID:     "primary",
				Role:   string(routing.RolePrimary),
				Weight: 10,
			},
		})
	}

	return &multisigService{
		walletRepo:    walletRepo,
		proposalRepo:  proposalRepo,
		chainAdapter:  chainAdapter,
		router:        router,
		walletCache:   cache.NewTTLCache(defaultCacheTTL),
		proposalCache: cache.NewTTLCache(defaultCacheTTL),
		listCache:     cache.NewTTLCache(defaultCacheTTL),
	}
}

func (s *multisigService) CreateWallet(ctx context.Context, wallet *domain.Wallet) (*domain.Wallet, error) {
	if err := s.checkWriteRoute(ctx); err != nil {
		return nil, err
	}

	logger.Info("creating multisig wallet", zap.String("creator", wallet.Creator))

	if wallet.ID == "" {
		wallet.ID = utils.GenerateID("wallet")
	}
	wallet.Status = domain.WalletStatusActive
	wallet.CreatedAt = time.Now()
	wallet.UpdatedAt = time.Now()

	s.initializeWalletSigners(wallet)

	if err := s.validateWalletThreshold(wallet); err != nil {
		return nil, err
	}

	if err := s.walletRepo.CreateWallet(ctx, wallet); err != nil {
		logger.Error("failed to create wallet", zap.Error(err))
		return nil, errors.Internal("failed to create wallet", err)
	}

	s.invalidateWalletCache(wallet.ID)
	return wallet, nil
}

func (s *multisigService) GetWallet(ctx context.Context, id string) (*domain.Wallet, error) {
	if err := s.checkReadRoute(ctx); err != nil {
		return nil, err
	}

	if cached, ok := s.walletCache.Get(cacheKeyWallet + id); ok {
		if wallet, ok := cached.(*domain.Wallet); ok {
			return wallet, nil
		}
	}

	wallet, err := s.walletRepo.GetWallet(ctx, id)
	if err != nil {
		return nil, errors.NotFound(errMsgWalletNotFound, err)
	}

	s.walletCache.Set(cacheKeyWallet+id, wallet)
	return wallet, nil
}

func (s *multisigService) ListWallets(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.Wallet, int64, error) {
	if err := s.checkReadRoute(ctx); err != nil {
		return nil, 0, err
	}

	cacheKey := cacheKeyWalletsList + generateListCacheKey(filter, page, pageSize)
	if cached, ok := s.listCache.Get(cacheKey); ok {
		if result, ok := cached.(*listWalletsResult); ok {
			return result.wallets, result.total, nil
		}
	}

	wallets, total, err := s.walletRepo.ListWallets(ctx, filter, page, pageSize)
	if err != nil {
		return nil, 0, err
	}

	s.listCache.Set(cacheKey, &listWalletsResult{wallets: wallets, total: total})
	return wallets, total, nil
}

func (s *multisigService) UpdateWallet(ctx context.Context, wallet *domain.Wallet) (*domain.Wallet, error) {
	if err := s.checkWriteRoute(ctx); err != nil {
		return nil, err
	}

	existing, err := s.walletRepo.GetWallet(ctx, wallet.ID)
	if err != nil {
		return nil, errors.NotFound(errMsgWalletNotFound, err)
	}

	wallet.CreatedAt = existing.CreatedAt
	wallet.UpdatedAt = time.Now()

	if err := s.walletRepo.UpdateWallet(ctx, wallet); err != nil {
		logger.Error("failed to update wallet", zap.Error(err))
		return nil, errors.Internal("failed to update wallet", err)
	}

	s.invalidateWalletCache(wallet.ID)
	return wallet, nil
}

func (s *multisigService) AddSigner(ctx context.Context, walletID string, signer domain.Signer) error {
	if err := s.checkWriteRoute(ctx); err != nil {
		return err
	}

	wallet, err := s.GetWallet(ctx, walletID)
	if err != nil {
		return err
	}

	if s.hasSigner(wallet, signer.Address) {
		return errors.Conflict(walletID, fmt.Errorf("signer already exists"))
	}

	if signer.Weight <= 0 {
		signer.Weight = 1
	}
	signer.AddedAt = time.Now()

	wallet.Signers = append(wallet.Signers, signer)
	wallet.TotalWeight += signer.Weight
	wallet.UpdatedAt = time.Now()

	s.invalidateWalletCache(walletID)
	return s.walletRepo.UpdateWallet(ctx, wallet)
}

func (s *multisigService) RemoveSigner(ctx context.Context, walletID string, address string) error {
	if err := s.checkWriteRoute(ctx); err != nil {
		return err
	}

	wallet, err := s.GetWallet(ctx, walletID)
	if err != nil {
		return err
	}

	newSigners, removedWeight := s.filterSigners(wallet.Signers, address)
	if len(newSigners) == len(wallet.Signers) {
		return errors.NotFound("signer not found", nil)
	}

	wallet.Signers = newSigners
	wallet.TotalWeight -= removedWeight
	wallet.UpdatedAt = time.Now()

	if wallet.Threshold > wallet.TotalWeight {
		wallet.Threshold = wallet.TotalWeight/2 + 1
	}

	s.invalidateWalletCache(walletID)
	return s.walletRepo.UpdateWallet(ctx, wallet)
}

func (s *multisigService) CreateProposal(ctx context.Context, req *domain.CreateProposalRequest, createdBy string) (*domain.Proposal, error) {
	if err := s.checkWriteRoute(ctx); err != nil {
		return nil, err
	}

	logger.Info("creating proposal",
		zap.String("wallet_id", req.WalletID),
		zap.String("type", req.Type))

	wallet, err := s.GetWallet(ctx, req.WalletID)
	if err != nil {
		return nil, err
	}

	if wallet.Status != domain.WalletStatusActive {
		return nil, errors.BadRequest("wallet is not active", nil)
	}

	proposal := &domain.Proposal{
		ID:          utils.GenerateID("prop"),
		WalletID:    req.WalletID,
		Title:       req.Title,
		Description: req.Description,
		Type:        req.Type,
		Status:      domain.ProposalStatusPending,
		ChainID:     wallet.ChainID,
		ToAddress:   req.ToAddress,
		Value:       req.Value,
		Data:        req.Data,
		Nonce:       wallet.Nonce,
		GasLimit:    req.GasLimit,
		Signatures:  make([]domain.Signature, 0),
		Threshold:   wallet.Threshold,
		TotalWeight: wallet.TotalWeight,
		ExpiresAt:   req.ExpiresAt,
		CreatedBy:   createdBy,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
		Metadata:    req.Metadata,
	}

	if proposal.GasLimit == 0 {
		proposal.GasLimit = s.estimateProposalGas(ctx, req)
	}

	if err := s.proposalRepo.CreateProposal(ctx, proposal); err != nil {
		logger.Error("failed to create proposal", zap.Error(err))
		return nil, errors.Internal("failed to create proposal", err)
	}

	s.invalidateProposalCache(proposal.ID)
	return proposal, nil
}

func (s *multisigService) GetProposal(ctx context.Context, id string) (*domain.Proposal, error) {
	if err := s.checkReadRoute(ctx); err != nil {
		return nil, err
	}

	if cached, ok := s.proposalCache.Get(cacheKeyProposal + id); ok {
		if proposal, ok := cached.(*domain.Proposal); ok {
			return proposal, nil
		}
	}

	proposal, err := s.proposalRepo.GetProposal(ctx, id)
	if err != nil {
		return nil, errors.NotFound(errMsgProposalNotFound, err)
	}

	s.proposalCache.Set(cacheKeyProposal+id, proposal)
	return proposal, nil
}

func (s *multisigService) ListProposals(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.Proposal, int64, error) {
	if err := s.checkReadRoute(ctx); err != nil {
		return nil, 0, err
	}

	cacheKey := cacheKeyProposalsList + generateListCacheKey(filter, page, pageSize)
	if cached, ok := s.listCache.Get(cacheKey); ok {
		if result, ok := cached.(*listProposalsResult); ok {
			return result.proposals, result.total, nil
		}
	}

	proposals, total, err := s.proposalRepo.ListProposals(ctx, filter, page, pageSize)
	if err != nil {
		return nil, 0, err
	}

	s.listCache.Set(cacheKey, &listProposalsResult{proposals: proposals, total: total})
	return proposals, total, nil
}

func (s *multisigService) SignProposal(ctx context.Context, req *domain.SignProposalRequest) (*domain.Proposal, error) {
	if err := s.checkWriteRoute(ctx); err != nil {
		return nil, err
	}

	logger.Info("signing proposal",
		zap.String("proposal_id", req.ProposalID),
		zap.String("signer", req.Signer))

	proposal, err := s.proposalRepo.GetProposal(ctx, req.ProposalID)
	if err != nil {
		return nil, errors.NotFound(errMsgProposalNotFound, err)
	}

	if err := s.validateProposalForSigning(proposal); err != nil {
		return nil, err
	}

	if s.hasSignature(proposal, req.Signer) {
		return proposal, nil
	}

	wallet, err := s.GetWallet(ctx, proposal.WalletID)
	if err != nil {
		return nil, err
	}

	if !s.isValidSigner(wallet, req.Signer) {
		return nil, errors.Forbidden("signer is not authorized for this wallet", nil)
	}

	signature := domain.Signature{
		Signer:    req.Signer,
		Signature: req.Signature,
		SignedAt:  time.Now(),
	}

	if _, err := s.VerifySignature(ctx, proposal, signature); err != nil {
		return nil, errors.BadRequest("invalid signature", err)
	}

	if err := s.proposalRepo.AddSignature(ctx, proposal.ID, signature); err != nil {
		logger.Error("failed to add signature", zap.Error(err))
		return nil, errors.Internal("failed to add signature", err)
	}

	proposal.Signatures = append(proposal.Signatures, signature)
	proposal.UpdatedAt = time.Now()

	if approved, _, err := s.CheckThreshold(ctx, proposal); err == nil && approved {
		proposal.Status = domain.ProposalStatusApproved
		_ = s.proposalRepo.UpdateProposal(ctx, proposal)
	}

	s.invalidateProposalCache(proposal.ID)
	return proposal, nil
}

func (s *multisigService) ExecuteProposal(ctx context.Context, req *domain.ExecuteProposalRequest) (string, error) {
	if err := s.checkWriteRoute(ctx); err != nil {
		return "", err
	}

	logger.Info("executing proposal", zap.String("proposal_id", req.ProposalID))

	proposal, err := s.proposalRepo.GetProposal(ctx, req.ProposalID)
	if err != nil {
		return "", errors.NotFound(errMsgProposalNotFound, err)
	}

	if proposal.Status != domain.ProposalStatusApproved {
		return "", errors.BadRequest(fmt.Sprintf("proposal is not approved, status: %s", proposal.Status), nil)
	}

	approved, _, err := s.CheckThreshold(ctx, proposal)
	if err != nil || !approved {
		return "", errors.BadRequest("proposal does not meet threshold", err)
	}

	wallet, err := s.GetWallet(ctx, proposal.WalletID)
	if err != nil {
		return "", err
	}

	txHash, err := s.chainAdapter.SendTransaction(ctx, []byte(proposal.Data))
	if err != nil {
		proposal.Status = domain.ProposalStatusFailed
		proposal.UpdatedAt = time.Now()
		_ = s.proposalRepo.UpdateProposal(ctx, proposal)
		return "", errors.Internal("failed to execute transaction", err)
	}

	now := time.Now()
	proposal.Status = domain.ProposalStatusExecuted
	proposal.ExecutedAt = &now
	proposal.TxHash = txHash
	proposal.UpdatedAt = now

	if err := s.proposalRepo.UpdateProposal(ctx, proposal); err != nil {
		logger.Error("failed to update proposal status", zap.Error(err))
	}

	_ = s.walletRepo.IncrementNonce(ctx, wallet.ID)

	s.invalidateProposalCache(proposal.ID)
	s.invalidateWalletCache(proposal.WalletID)

	return txHash, nil
}

func (s *multisigService) CancelProposal(ctx context.Context, proposalID string) error {
	if err := s.checkWriteRoute(ctx); err != nil {
		return err
	}

	proposal, err := s.proposalRepo.GetProposal(ctx, proposalID)
	if err != nil {
		return errors.NotFound(errMsgProposalNotFound, err)
	}

	if proposal.Status != domain.ProposalStatusPending {
		return errors.BadRequest("only pending proposals can be cancelled", nil)
	}

	proposal.Status = domain.ProposalStatusCancelled
	proposal.UpdatedAt = time.Now()

	s.invalidateProposalCache(proposalID)
	return s.proposalRepo.UpdateProposal(ctx, proposal)
}

func (s *multisigService) VerifySignature(ctx context.Context, proposal *domain.Proposal, signature domain.Signature) (bool, error) {
	return true, nil
}

func (s *multisigService) CheckThreshold(ctx context.Context, proposal *domain.Proposal) (bool, int, error) {
	wallet, err := s.GetWallet(ctx, proposal.WalletID)
	if err != nil {
		return false, 0, err
	}

	signerWeights := s.buildSignerWeightMap(wallet.Signers)
	totalSignedWeight := s.calculateSignedWeight(proposal.Signatures, signerWeights)

	return totalSignedWeight >= proposal.Threshold, totalSignedWeight, nil
}

func (s *multisigService) SetReadWriteMode(mode routing.ReadWriteMode) {
	s.router.SetMode(mode)
}

func (s *multisigService) GetRouter() *routing.ReadWriteRouter {
	return s.router
}

func (s *multisigService) checkReadRoute(ctx context.Context) error {
	if _, err := s.router.Route(ctx, routing.RouteRead); err != nil {
		return errors.Internal(errMsgReadNotAvailable, err)
	}
	return nil
}

func (s *multisigService) checkWriteRoute(ctx context.Context) error {
	if _, err := s.router.Route(ctx, routing.RouteWrite); err != nil {
		return errors.Internal(errMsgWriteNotAvailable, err)
	}
	return nil
}

func (s *multisigService) initializeWalletSigners(wallet *domain.Wallet) {
	totalWeight := 0
	for i := range wallet.Signers {
		if wallet.Signers[i].Weight <= 0 {
			wallet.Signers[i].Weight = 1
		}
		wallet.Signers[i].AddedAt = time.Now()
		totalWeight += wallet.Signers[i].Weight
	}
	wallet.TotalWeight = totalWeight

	if wallet.Threshold <= 0 {
		wallet.Threshold = totalWeight/2 + 1
	}
}

func (s *multisigService) validateWalletThreshold(wallet *domain.Wallet) error {
	if wallet.Threshold > wallet.TotalWeight {
		return errors.BadRequest("threshold exceeds total weight", nil)
	}
	return nil
}

func (s *multisigService) hasSigner(wallet *domain.Wallet, address string) bool {
	for _, s := range wallet.Signers {
		if s.Address == address {
			return true
		}
	}
	return false
}

func (s *multisigService) filterSigners(signers []domain.Signer, address string) ([]domain.Signer, int) {
	newSigners := make([]domain.Signer, 0, len(signers))
	removedWeight := 0
	for _, s := range signers {
		if s.Address != address {
			newSigners = append(newSigners, s)
		} else {
			removedWeight = s.Weight
		}
	}
	return newSigners, removedWeight
}

func (s *multisigService) estimateProposalGas(ctx context.Context, req *domain.CreateProposalRequest) uint64 {
	estimated, err := s.chainAdapter.EstimateGas(ctx, req.ToAddress, []byte(req.Data), req.Value)
	if err == nil {
		return estimated
	}
	return 21000
}

func (s *multisigService) validateProposalForSigning(proposal *domain.Proposal) error {
	if proposal.Status != domain.ProposalStatusPending {
		return errors.BadRequest(fmt.Sprintf("proposal is not pending, status: %s", proposal.Status), nil)
	}

	if proposal.ExpiresAt != nil && time.Now().After(*proposal.ExpiresAt) {
		proposal.Status = domain.ProposalStatusExpired
		_ = s.proposalRepo.UpdateProposal(ctx, proposal)
		return errors.BadRequest("proposal has expired", nil)
	}

	return nil
}

func (s *multisigService) hasSignature(proposal *domain.Proposal, signer string) bool {
	for _, sig := range proposal.Signatures {
		if sig.Signer == signer {
			return true
		}
	}
	return false
}

func (s *multisigService) isValidSigner(wallet *domain.Wallet, signer string) bool {
	for _, s := range wallet.Signers {
		if s.Address == signer {
			return true
		}
	}
	return false
}

func (s *multisigService) buildSignerWeightMap(signers []domain.Signer) map[string]int {
	signerWeights := make(map[string]int, len(signers))
	for _, signer := range signers {
		signerWeights[signer.Address] = signer.Weight
	}
	return signerWeights
}

func (s *multisigService) calculateSignedWeight(signatures []domain.Signature, signerWeights map[string]int) int {
	totalSignedWeight := 0
	for _, sig := range signatures {
		if weight, ok := signerWeights[sig.Signer]; ok {
			totalSignedWeight += weight
		}
	}
	return totalSignedWeight
}

func (s *multisigService) invalidateWalletCache(walletID string) {
	s.walletCache.Delete(cacheKeyWallet + walletID)
	s.listCache.DeleteWithPrefix(cacheKeyWalletsList)
}

func (s *multisigService) invalidateProposalCache(proposalID string) {
	s.proposalCache.Delete(cacheKeyProposal + proposalID)
	s.listCache.DeleteWithPrefix(cacheKeyProposalsList)
}

type listWalletsResult struct {
	wallets []domain.Wallet
	total   int64
}

type listProposalsResult struct {
	proposals []domain.Proposal
	total     int64
}

func generateListCacheKey(filter map[string]interface{}, page, pageSize int) string {
	h := fnv.New32a()
	h.Write([]byte(fmt.Sprintf("%v:%d:%d", filter, page, pageSize)))
	return fmt.Sprintf("%x", h.Sum32())
}
