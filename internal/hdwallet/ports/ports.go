package ports

import (
	"context"
	"github.com/solocoder/session147/internal/hdwallet/domain"
)

type HDWalletRepository interface {
	CreateWallet(ctx context.Context, wallet *domain.HDWallet) error
	GetWallet(ctx context.Context, id string) (*domain.HDWallet, error)
	ListWallets(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.HDWallet, int64, error)
	UpdateWallet(ctx context.Context, wallet *domain.HDWallet) error
	DeleteWallet(ctx context.Context, id string) error

	AddDerivedAddress(ctx context.Context, addr *domain.DerivedAddress) error
	GetDerivedAddress(ctx context.Context, address string) (*domain.DerivedAddress, error)
	ListDerivedAddresses(ctx context.Context, walletID string, page, pageSize int) ([]domain.DerivedAddress, int64, error)
	UpdateDerivedAddress(ctx context.Context, addr *domain.DerivedAddress) error

	AddAddressBookEntry(ctx context.Context, entry *domain.AddressBookEntry) error
	GetAddressBookEntry(ctx context.Context, id string) (*domain.AddressBookEntry, error)
	ListAddressBookEntries(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.AddressBookEntry, int64, error)
	UpdateAddressBookEntry(ctx context.Context, entry *domain.AddressBookEntry) error
	DeleteAddressBookEntry(ctx context.Context, id string) error
}

type HDWalletService interface {
	CreateWallet(ctx context.Context, name, password string, coinType int, network string) (*domain.HDWallet, error)
	ImportWallet(ctx context.Context, mnemonic, name, password string, coinType int, network string) (*domain.HDWallet, error)
	GetWallet(ctx context.Context, id string) (*domain.HDWallet, error)
	ListWallets(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.HDWallet, int64, error)
	DeleteWallet(ctx context.Context, id string, password string) error

	DeriveAddresses(ctx context.Context, req *domain.DeriveAddressRequest) ([]domain.DerivedAddress, error)
	GetAddress(ctx context.Context, address string) (*domain.DerivedAddress, error)
	ListAddresses(ctx context.Context, walletID string, page, pageSize int) ([]domain.DerivedAddress, int64, error)
	ImportAddress(ctx context.Context, req *domain.ImportAddressRequest) (*domain.DerivedAddress, error)

	AddAddressBookEntry(ctx context.Context, req *domain.AddAddressBookRequest, createdBy string) (*domain.AddressBookEntry, error)
	GetAddressBookEntry(ctx context.Context, id string) (*domain.AddressBookEntry, error)
	ListAddressBookEntries(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.AddressBookEntry, int64, error)
	UpdateAddressBookEntry(ctx context.Context, entry *domain.AddressBookEntry) (*domain.AddressBookEntry, error)
	DeleteAddressBookEntry(ctx context.Context, id string) error
}

type KeyDeriver interface {
	GenerateMnemonic(bitSize int) (string, error)
	DeriveKey(mnemonic, password, path string) (privKey, pubKey, address string, err error)
	DeriveChildKey(masterKey, path string) (privKey, pubKey, address string, err error)
}
