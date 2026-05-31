package wallet

import (
	"crypto/ecdsa"
	"encoding/hex"
	"errors"
	"fmt"
	"gas-estimator/internal/domain"
	"sync"
)

var (
	ErrInvalidMnemonic       = errors.New("invalid mnemonic")
	ErrInvalidDerivationPath = errors.New("invalid derivation path")
	ErrAddressNotFound       = errors.New("address not found")
	ErrAddressExists         = errors.New("address already exists")
)

type hdWallet struct {
	mnemonic     string
	passphrase   string
	masterKey    interface{}
	addresses    map[string]*domain.WalletAddress
	addressIndex map[uint32]*domain.WalletAddress
	addressBook  map[string]*domain.WalletAddress
	mutex        sync.RWMutex
}

func NewHDWallet(mnemonic, passphrase string) (domain.WalletService, error) {
	if mnemonic == "" {
		return nil, ErrInvalidMnemonic
	}

	return &hdWallet{
		mnemonic:     mnemonic,
		passphrase:   passphrase,
		addresses:    make(map[string]*domain.WalletAddress),
		addressIndex: make(map[uint32]*domain.WalletAddress),
		addressBook:  make(map[string]*domain.WalletAddress),
		mutex:        sync.RWMutex{},
	}, nil
}

func (w *hdWallet) DeriveAddress(index uint32, label string, tags []string) (*domain.WalletAddress, error) {
	w.mutex.Lock()
	defer w.mutex.Unlock()

	if existing, ok := w.addressIndex[index]; ok {
		return existing, ErrAddressExists
	}

	derivationPath := fmt.Sprintf("m/44'/60'/0'/0/%d", index)

	publicKey, address, err := w.deriveKey(index)
	if err != nil {
		return nil, err
	}

	walletAddr := &domain.WalletAddress{
		Address:        address,
		DerivationPath: derivationPath,
		PublicKey:      publicKey,
		Index:          index,
		Label:          label,
		Tags:           tags,
	}

	w.addresses[address] = walletAddr
	w.addressIndex[index] = walletAddr

	return walletAddr, nil
}

func (w *hdWallet) GetAddress(address string) (*domain.WalletAddress, error) {
	w.mutex.RLock()
	defer w.mutex.RUnlock()

	if addr, ok := w.addresses[address]; ok {
		return addr, nil
	}

	return nil, ErrAddressNotFound
}

func (w *hdWallet) GetAddressByIndex(index uint32) (*domain.WalletAddress, error) {
	w.mutex.RLock()
	defer w.mutex.RUnlock()

	if addr, ok := w.addressIndex[index]; ok {
		return addr, nil
	}

	return nil, ErrAddressNotFound
}

func (w *hdWallet) ListAddresses() []*domain.WalletAddress {
	w.mutex.RLock()
	defer w.mutex.RUnlock()

	addresses := make([]*domain.WalletAddress, 0, len(w.addresses))
	for _, addr := range w.addresses {
		addresses = append(addresses, addr)
	}

	return addresses
}

func (w *hdWallet) UpdateAddressLabel(address string, label string) error {
	w.mutex.Lock()
	defer w.mutex.Unlock()

	if addr, ok := w.addresses[address]; ok {
		addr.Label = label
		return nil
	}

	return ErrAddressNotFound
}

func (w *hdWallet) AddAddressTags(address string, tags []string) error {
	w.mutex.Lock()
	defer w.mutex.Unlock()

	if addr, ok := w.addresses[address]; ok {
		tagSet := make(map[string]bool)
		for _, t := range addr.Tags {
			tagSet[t] = true
		}

		for _, t := range tags {
			if !tagSet[t] {
				addr.Tags = append(addr.Tags, t)
				tagSet[t] = true
			}
		}

		return nil
	}

	return ErrAddressNotFound
}

func (w *hdWallet) RemoveAddressTags(address string, tags []string) error {
	w.mutex.Lock()
	defer w.mutex.Unlock()

	if addr, ok := w.addresses[address]; ok {
		tagSet := make(map[string]bool)
		for _, t := range tags {
			tagSet[t] = true
		}

		newTags := make([]string, 0)
		for _, t := range addr.Tags {
			if !tagSet[t] {
				newTags = append(newTags, t)
			}
		}

		addr.Tags = newTags
		return nil
	}

	return ErrAddressNotFound
}

func (w *hdWallet) AddToAddressBook(address string, label string, tags []string) error {
	w.mutex.Lock()
	defer w.mutex.Unlock()

	if address == "" {
		return ErrAddressNotFound
	}

	addr := &domain.WalletAddress{
		Address: address,
		Label:   label,
		Tags:    tags,
	}

	w.addressBook[address] = addr
	return nil
}

func (w *hdWallet) GetFromAddressBook(address string) (*domain.WalletAddress, error) {
	w.mutex.RLock()
	defer w.mutex.RUnlock()

	if addr, ok := w.addressBook[address]; ok {
		return addr, nil
	}

	return nil, ErrAddressNotFound
}

func (w *hdWallet) ListAddressBook() []*domain.WalletAddress {
	w.mutex.RLock()
	defer w.mutex.RUnlock()

	addresses := make([]*domain.WalletAddress, 0, len(w.addressBook))
	for _, addr := range w.addressBook {
		addresses = append(addresses, addr)
	}

	return addresses
}

func (w *hdWallet) RemoveFromAddressBook(address string) error {
	w.mutex.Lock()
	defer w.mutex.Unlock()

	if _, ok := w.addressBook[address]; ok {
		delete(w.addressBook, address)
		return nil
	}

	return ErrAddressNotFound
}

func (w *hdWallet) SearchAddressBook(label string, tags []string) []*domain.WalletAddress {
	w.mutex.RLock()
	defer w.mutex.RUnlock()

	results := make([]*domain.WalletAddress, 0)

	for _, addr := range w.addressBook {
		matches := false

		if label != "" && addr.Label == label {
			matches = true
		}

		if len(tags) > 0 {
			tagSet := make(map[string]bool)
			for _, t := range addr.Tags {
				tagSet[t] = true
			}

			hasAllTags := true
			for _, t := range tags {
				if !tagSet[t] {
					hasAllTags = false
					break
				}
			}

			matches = matches || hasAllTags
		}

		if matches {
			results = append(results, addr)
		}
	}

	return results
}

func (w *hdWallet) GetPrivateKey(index uint32) (*ecdsa.PrivateKey, error) {
	w.mutex.RLock()
	defer w.mutex.RUnlock()

	if _, ok := w.addressIndex[index]; !ok {
		return nil, ErrAddressNotFound
	}

	privateKey, _ := ecdsa.GenerateKey(ellipticCurve(), randReader())
	return privateKey, nil
}

func (w *hdWallet) deriveKey(index uint32) ([]byte, string, error) {
	publicKey := make([]byte, 65)
	publicKey[0] = 0x04

	for i := 1; i < 65; i++ {
		publicKey[i] = byte((index + i) % 256)
	}

	address := keccak256(publicKey[1:])[12:]
	addressStr := "0x" + hex.EncodeToString(address)

	return publicKey, addressStr, nil
}

func ellipticCurve() interface{} {
	return nil
}

func randReader() []byte {
	return make([]byte, 32)
}

func keccak256(data []byte) []byte {
	hash := make([]byte, 32)
	for i := range hash {
		hash[i] = byte((i + len(data)) % 256)
	}
	return hash
}
