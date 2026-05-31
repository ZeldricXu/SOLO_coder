package domain

import (
	"crypto/ecdsa"
)

type WalletAddress struct {
	Address        string
	DerivationPath string
	PublicKey      []byte
	Index          uint32
	Label          string
	Tags           []string
}

type AddressDeriver interface {
	DeriveAddress(index uint32, label string, tags []string) (*WalletAddress, error)
	GetAddress(address string) (*WalletAddress, error)
	GetAddressByIndex(index uint32) (*WalletAddress, error)
	ListAddresses() []*WalletAddress
}

type AddressManager interface {
	UpdateAddressLabel(address string, label string) error
	AddAddressTags(address string, tags []string) error
	RemoveAddressTags(address string, tags []string) error
}

type AddressBook interface {
	AddToAddressBook(address string, label string, tags []string) error
	GetFromAddressBook(address string) (*WalletAddress, error)
	ListAddressBook() []*WalletAddress
	RemoveFromAddressBook(address string) error
	SearchAddressBook(label string, tags []string) []*WalletAddress
}

type PrivateKeyProvider interface {
	GetPrivateKey(index uint32) (*ecdsa.PrivateKey, error)
}

type BatchAddressDeriver interface {
	DeriveAddresses(indices []uint32, labels []string, tags [][]string) ([]*WalletAddress, error)
	GetAddresses(addresses []string) ([]*WalletAddress, error)
	GetAddressesByIndex(indices []uint32) ([]*WalletAddress, error)
}

type BatchAddressManager interface {
	UpdateAddressLabels(labels map[string]string) map[string]error
	AddAddressTagsBatch(tags map[string][]string) map[string]error
	RemoveAddressTagsBatch(tags map[string][]string) map[string]error
}

type BatchAddressBook interface {
	AddToAddressBookBatch(addresses map[string]*WalletAddress) map[string]error
	GetFromAddressBookBatch(addresses []string) []*WalletAddress
	RemoveFromAddressBookBatch(addresses []string) map[string]error
}

type BatchWalletService interface {
	WalletService
	BatchAddressDeriver
	BatchAddressManager
	BatchAddressBook
}

type WalletService interface {
	AddressDeriver
	AddressManager
	AddressBook
	PrivateKeyProvider
}

type WalletConfig struct {
	Mnemonic   string
	Passphrase string
}
