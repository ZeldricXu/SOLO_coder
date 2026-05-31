package address

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"time"

	"github.com/gasestimator/platform/internal/domain/model"
	"github.com/gasestimator/platform/internal/domain/repository"
	"github.com/gasestimator/platform/internal/infrastructure/logger"
	"github.com/gasestimator/platform/pkg/common"
	"go.uber.org/zap"
)

type CreateWalletRequest struct {
	UserID         string   `json:"user_id"`
	Name           string   `json:"name"`
	MasterPubKey   []byte   `json:"master_pub_key"`
	ChainCode      []byte   `json:"chain_code"`
	DerivationPath string   `json:"derivation_path"`
	EncryptedSeed  []byte   `json:"encrypted_seed"`
}

type DeriveAddressRequest struct {
	WalletID      string   `json:"wallet_id"`
	ChainID       string   `json:"chain_id"`
	AddressIndex  uint32   `json:"address_index"`
	IsChange      bool     `json:"is_change"`
	Labels        []string `json:"labels"`
}

type Service struct {
	walletRepo  repository.HDWalletRepository
	addressRepo repository.DerivedAddressRepository
}

func NewService(
	walletRepo repository.HDWalletRepository,
	addressRepo repository.DerivedAddressRepository,
) *Service {
	return &Service{
		walletRepo:  walletRepo,
		addressRepo: addressRepo,
	}
}

func (s *Service) CreateWallet(ctx context.Context, req *CreateWalletRequest) (*model.HDWallet, error) {
	wallet := &model.HDWallet{
		ID:             common.GenerateID("wal"),
		UserID:         req.UserID,
		Name:           req.Name,
		MasterPubKey:   req.MasterPubKey,
		ChainCode:      req.ChainCode,
		DerivationPath: req.DerivationPath,
		EncryptedSeed:  req.EncryptedSeed,
		CreatedAt:      time.Now(),
	}

	if err := s.walletRepo.Create(ctx, wallet); err != nil {
		logger.L().Error("failed to create HD wallet", zap.Error(err))
		return nil, common.NewInternalError("failed to create wallet")
	}

	logger.L().Info("HD wallet created",
		zap.String("wallet_id", wallet.ID),
		zap.String("user_id", req.UserID),
	)

	return wallet, nil
}

func (s *Service) DeriveAddress(ctx context.Context, req *DeriveAddressRequest) (*model.DerivedAddress, error) {
	wallet, err := s.walletRepo.GetByID(ctx, req.WalletID)
	if err != nil {
		return nil, common.NewNotFoundError("wallet", req.WalletID)
	}

	address := s.deriveEthereumAddress(wallet, req.AddressIndex, req.IsChange)
	derivationPath := fmt.Sprintf("%s/%d/%d", wallet.DerivationPath, boolToInt(req.IsChange), req.AddressIndex)

	addr := &model.DerivedAddress{
		ID:             common.GenerateID("addr"),
		WalletID:       req.WalletID,
		Address:        address,
		AddressIndex:   req.AddressIndex,
		DerivationPath: derivationPath,
		ChainID:        req.ChainID,
		Labels:         req.Labels,
		IsChange:       req.IsChange,
		Balance:        "0",
		CreatedAt:      time.Now(),
	}

	if err := s.addressRepo.Create(ctx, addr); err != nil {
		logger.L().Error("failed to derive address", zap.Error(err))
		return nil, common.NewInternalError("failed to derive address")
	}

	logger.L().Info("address derived",
		zap.String("address_id", addr.ID),
		zap.String("wallet_id", req.WalletID),
		zap.String("address", address),
	)

	return addr, nil
}

func (s *Service) deriveEthereumAddress(wallet *model.HDWallet, index uint32, isChange bool) string {
	change := byte(0)
	if isChange {
		change = 1
	}

	data := append(wallet.MasterPubKey, byte(index>>24), byte(index>>16), byte(index>>8), byte(index), change)
	hash := sha256.Sum256(data)
	keccak := sha256.Sum256(hash[:])
	address := hex.EncodeToString(keccak[12:])

	return "0x" + address
}

func (s *Service) GetWallet(ctx context.Context, id string) (*model.HDWallet, error) {
	wallet, err := s.walletRepo.GetByID(ctx, id)
	if err != nil {
		return nil, common.NewNotFoundError("wallet", id)
	}
	return wallet, nil
}

func (s *Service) ListWallets(ctx context.Context, userID string) ([]*model.HDWallet, error) {
	return s.walletRepo.ListByUserID(ctx, userID)
}

func (s *Service) GetAddress(ctx context.Context, id string) (*model.DerivedAddress, error) {
	addr, err := s.addressRepo.GetByID(ctx, id)
	if err != nil {
		return nil, common.NewNotFoundError("address", id)
	}
	return addr, nil
}

func (s *Service) GetByAddress(ctx context.Context, address string) (*model.DerivedAddress, error) {
	addr, err := s.addressRepo.GetByAddress(ctx, address)
	if err != nil {
		return nil, common.NewNotFoundError("address", address)
	}
	return addr, nil
}

func (s *Service) ListAddressesByWallet(ctx context.Context, walletID, chainID string) ([]*model.DerivedAddress, error) {
	return s.addressRepo.ListByWalletID(ctx, walletID, chainID)
}

func (s *Service) ListAddressesByLabels(ctx context.Context, labels []string) ([]*model.DerivedAddress, error) {
	return s.addressRepo.ListByLabels(ctx, labels)
}

func (s *Service) UpdateAddressLabels(ctx context.Context, addressID string, labels []string) (*model.DerivedAddress, error) {
	addr, err := s.addressRepo.GetByID(ctx, addressID)
	if err != nil {
		return nil, common.NewNotFoundError("address", addressID)
	}

	addr.Labels = labels
	if err := s.addressRepo.Update(ctx, addr); err != nil {
		return nil, common.NewInternalError("failed to update labels")
	}

	return addr, nil
}

func (s *Service) SyncAddressBalance(ctx context.Context, addressID string, balance string) error {
	addr, err := s.addressRepo.GetByID(ctx, addressID)
	if err != nil {
		return common.NewNotFoundError("address", addressID)
	}

	addr.Balance = balance
	now := time.Now()
	addr.LastSync = &now

	return s.addressRepo.Update(ctx, addr)
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
