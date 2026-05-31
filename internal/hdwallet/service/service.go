package service

import (
	"context"
	"fmt"
	"time"

	"github.com/solocoder/session147/internal/common/errors"
	"github.com/solocoder/session147/internal/common/logger"
	"github.com/solocoder/session147/internal/common/utils"
	"github.com/solocoder/session147/internal/hdwallet/domain"
	"github.com/solocoder/session147/internal/hdwallet/ports"
	"go.uber.org/zap"
)

type hdWalletService struct {
	repo    ports.HDWalletRepository
	deriver ports.KeyDeriver
}

func NewHDWalletService(repo ports.HDWalletRepository, deriver ports.KeyDeriver) ports.HDWalletService {
	return &hdWalletService{
		repo:    repo,
		deriver: deriver,
	}
}

func (s *hdWalletService) CreateWallet(ctx context.Context, name, password string, coinType int, network string) (*domain.HDWallet, error) {
	logger.Info("creating HD wallet", zap.String("name", name))

	mnemonic, err := s.deriver.GenerateMnemonic(128)
	if err != nil {
		return nil, errors.Internal("failed to generate mnemonic", err)
	}

	wallet := &domain.HDWallet{
		ID:             utils.GenerateID("hd"),
		Name:           name,
		Mnemonic:       mnemonic,
		DerivationPath: s.getDerivationPath(coinType, network),
		CoinType:       coinType,
		Network:        network,
		Status:         "active",
		CreatedAt:      time.Now(),
		UpdatedAt:      time.Now(),
	}

	if err := s.repo.CreateWallet(ctx, wallet); err != nil {
		return nil, errors.Internal("failed to create wallet", err)
	}

	return wallet, nil
}

func (s *hdWalletService) ImportWallet(ctx context.Context, mnemonic, name, password string, coinType int, network string) (*domain.HDWallet, error) {
	logger.Info("importing HD wallet", zap.String("name", name))

	wallet := &domain.HDWallet{
		ID:             utils.GenerateID("hd"),
		Name:           name,
		Mnemonic:       mnemonic,
		DerivationPath: s.getDerivationPath(coinType, network),
		CoinType:       coinType,
		Network:        network,
		Status:         "active",
		CreatedAt:      time.Now(),
		UpdatedAt:      time.Now(),
	}

	if err := s.repo.CreateWallet(ctx, wallet); err != nil {
		return nil, errors.Internal("failed to import wallet", err)
	}

	return wallet, nil
}

func (s *hdWalletService) GetWallet(ctx context.Context, id string) (*domain.HDWallet, error) {
	return s.repo.GetWallet(ctx, id)
}

func (s *hdWalletService) ListWallets(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.HDWallet, int64, error) {
	return s.repo.ListWallets(ctx, filter, page, pageSize)
}

func (s *hdWalletService) DeleteWallet(ctx context.Context, id string, password string) error {
	_, err := s.repo.GetWallet(ctx, id)
	if err != nil {
		return errors.NotFound("wallet not found", err)
	}
	return s.repo.DeleteWallet(ctx, id)
}

func (s *hdWalletService) DeriveAddresses(ctx context.Context, req *domain.DeriveAddressRequest) ([]domain.DerivedAddress, error) {
	logger.Info("deriving addresses", zap.String("wallet_id", req.WalletID))

	wallet, err := s.repo.GetWallet(ctx, req.WalletID)
	if err != nil {
		return nil, errors.NotFound("wallet not found", err)
	}

	count := req.Count
	if count == 0 {
		count = 1
	}

	addresses := make([]domain.DerivedAddress, 0, count)
	for i := uint32(0); i < count; i++ {
		index := req.Index + i
		change := 0
		if req.IsChange {
			change = 1
		}
		path := fmt.Sprintf("%s/%d/%d", wallet.DerivationPath, change, index)

		_, pubKey, addr, err := s.deriver.DeriveKey(wallet.Mnemonic, "", path)
		if err != nil {
			logger.Warn("derive address failed", zap.Error(err))
			continue
		}

		derived := domain.DerivedAddress{
			ID:             utils.GenerateID("addr"),
			WalletID:       req.WalletID,
			Address:        addr,
			PublicKey:      pubKey,
			DerivationPath: path,
			Index:          index,
			IsChange:       req.IsChange,
			Status:         domain.AddressStatusUnused,
			CreatedAt:      time.Now(),
			UpdatedAt:      time.Now(),
		}

		if err := s.repo.AddDerivedAddress(ctx, &derived); err != nil {
			logger.Warn("store address failed", zap.Error(err))
			continue
		}

		addresses = append(addresses, derived)
	}

	return addresses, nil
}

func (s *hdWalletService) GetAddress(ctx context.Context, address string) (*domain.DerivedAddress, error) {
	return s.repo.GetDerivedAddress(ctx, address)
}

func (s *hdWalletService) ListAddresses(ctx context.Context, walletID string, page, pageSize int) ([]domain.DerivedAddress, int64, error) {
	return s.repo.ListDerivedAddresses(ctx, walletID, page, pageSize)
}

func (s *hdWalletService) ImportAddress(ctx context.Context, req *domain.ImportAddressRequest) (*domain.DerivedAddress, error) {
	addr := &domain.DerivedAddress{
		ID:        utils.GenerateID("addr"),
		WalletID:  req.WalletID,
		Address:   req.Address,
		PublicKey: req.PublicKey,
		Status:    domain.AddressStatusActive,
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
		Metadata:  req.Metadata,
	}

	if err := s.repo.AddDerivedAddress(ctx, addr); err != nil {
		return nil, errors.Internal("failed to import address", err)
	}

	return addr, nil
}

func (s *hdWalletService) AddAddressBookEntry(ctx context.Context, req *domain.AddAddressBookRequest, createdBy string) (*domain.AddressBookEntry, error) {
	entry := &domain.AddressBookEntry{
		ID:          utils.GenerateID("book"),
		Address:     req.Address,
		Name:        req.Name,
		Description: req.Description,
		ChainID:     req.ChainID,
		Tags:        req.Tags,
		Category:    req.Category,
		CreatedBy:   createdBy,
		IsFavorite:  req.IsFavorite,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
		Metadata:    req.Metadata,
	}

	if err := s.repo.AddAddressBookEntry(ctx, entry); err != nil {
		return nil, errors.Internal("failed to add address book entry", err)
	}

	return entry, nil
}

func (s *hdWalletService) GetAddressBookEntry(ctx context.Context, id string) (*domain.AddressBookEntry, error) {
	return s.repo.GetAddressBookEntry(ctx, id)
}

func (s *hdWalletService) ListAddressBookEntries(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.AddressBookEntry, int64, error) {
	return s.repo.ListAddressBookEntries(ctx, filter, page, pageSize)
}

func (s *hdWalletService) UpdateAddressBookEntry(ctx context.Context, entry *domain.AddressBookEntry) (*domain.AddressBookEntry, error) {
	existing, err := s.repo.GetAddressBookEntry(ctx, entry.ID)
	if err != nil {
		return nil, errors.NotFound("entry not found", err)
	}

	entry.CreatedAt = existing.CreatedAt
	entry.CreatedBy = existing.CreatedBy
	entry.UpdatedAt = time.Now()

	if err := s.repo.UpdateAddressBookEntry(ctx, entry); err != nil {
		return nil, errors.Internal("failed to update entry", err)
	}

	return entry, nil
}

func (s *hdWalletService) DeleteAddressBookEntry(ctx context.Context, id string) error {
	_, err := s.repo.GetAddressBookEntry(ctx, id)
	if err != nil {
		return errors.NotFound("entry not found", err)
	}
	return s.repo.DeleteAddressBookEntry(ctx, id)
}

func (s *hdWalletService) getDerivationPath(coinType int, network string) string {
	switch coinType {
	case domain.CoinTypeETH:
		return "m/44'/60'/0'/0"
	case domain.CoinTypeBTC:
		if network == "mainnet" {
			return "m/44'/0'/0'/0"
		}
		return "m/44'/1'/0'/0"
	case domain.CoinTypeSOL:
		return "m/44'/501'/0'/0"
	default:
		return fmt.Sprintf("m/44'/%d'/0'/0", coinType)
	}
}
