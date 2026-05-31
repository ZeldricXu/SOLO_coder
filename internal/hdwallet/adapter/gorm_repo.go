package adapter

import (
	"context"
	"github.com/solocoder/session147/internal/hdwallet/domain"
	"github.com/solocoder/session147/internal/hdwallet/ports"
	"gorm.io/gorm"
)

type gormHDWalletRepo struct {
	db *gorm.DB
}

func NewGormHDWalletRepository(db *gorm.DB) ports.HDWalletRepository {
	return &gormHDWalletRepo{db: db}
}

func (r *gormHDWalletRepo) CreateWallet(ctx context.Context, wallet *domain.HDWallet) error {
	return r.db.WithContext(ctx).Create(wallet).Error
}

func (r *gormHDWalletRepo) GetWallet(ctx context.Context, id string) (*domain.HDWallet, error) {
	var wallet domain.HDWallet
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&wallet).Error
	if err != nil {
		return nil, err
	}
	return &wallet, nil
}

func (r *gormHDWalletRepo) ListWallets(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.HDWallet, int64, error) {
	var wallets []domain.HDWallet
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.HDWallet{})
	for k, v := range filter {
		query = query.Where(k+" = ?", v)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&wallets).Error
	return wallets, total, err
}

func (r *gormHDWalletRepo) UpdateWallet(ctx context.Context, wallet *domain.HDWallet) error {
	return r.db.WithContext(ctx).Save(wallet).Error
}

func (r *gormHDWalletRepo) DeleteWallet(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Delete(&domain.HDWallet{}, "id = ?", id).Error
}

func (r *gormHDWalletRepo) AddDerivedAddress(ctx context.Context, addr *domain.DerivedAddress) error {
	return r.db.WithContext(ctx).Create(addr).Error
}

func (r *gormHDWalletRepo) GetDerivedAddress(ctx context.Context, address string) (*domain.DerivedAddress, error) {
	var addr domain.DerivedAddress
	err := r.db.WithContext(ctx).Where("address = ?", address).First(&addr).Error
	if err != nil {
		return nil, err
	}
	return &addr, nil
}

func (r *gormHDWalletRepo) ListDerivedAddresses(ctx context.Context, walletID string, page, pageSize int) ([]domain.DerivedAddress, int64, error) {
	var addrs []domain.DerivedAddress
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.DerivedAddress{}).Where("wallet_id = ?", walletID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("index ASC").Find(&addrs).Error
	return addrs, total, err
}

func (r *gormHDWalletRepo) UpdateDerivedAddress(ctx context.Context, addr *domain.DerivedAddress) error {
	return r.db.WithContext(ctx).Save(addr).Error
}

func (r *gormHDWalletRepo) AddAddressBookEntry(ctx context.Context, entry *domain.AddressBookEntry) error {
	return r.db.WithContext(ctx).Create(entry).Error
}

func (r *gormHDWalletRepo) GetAddressBookEntry(ctx context.Context, id string) (*domain.AddressBookEntry, error) {
	var entry domain.AddressBookEntry
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&entry).Error
	if err != nil {
		return nil, err
	}
	return &entry, nil
}

func (r *gormHDWalletRepo) ListAddressBookEntries(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.AddressBookEntry, int64, error) {
	var entries []domain.AddressBookEntry
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.AddressBookEntry{})
	for k, v := range filter {
		query = query.Where(k+" = ?", v)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&entries).Error
	return entries, total, err
}

func (r *gormHDWalletRepo) UpdateAddressBookEntry(ctx context.Context, entry *domain.AddressBookEntry) error {
	return r.db.WithContext(ctx).Save(entry).Error
}

func (r *gormHDWalletRepo) DeleteAddressBookEntry(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Delete(&domain.AddressBookEntry{}, "id = ?", id).Error
}
