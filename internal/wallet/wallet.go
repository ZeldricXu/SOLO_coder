package wallet

import (
	"context"
	"fmt"
	"math/big"
	"strings"
	"sync"

	"github.com/btcsuite/btcd/btcutil/hdkeychain"
	"github.com/btcsuite/btcd/chaincfg"
	"github.com/ethereum/go-ethereum/accounts"
	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/crypto"
	"go.uber.org/zap"
	"gorm.io/gorm"

	"github.com/blockchain-middleware/core/internal/common/config"
	"github.com/blockchain-middleware/core/internal/common/errors"
	"github.com/blockchain-middleware/core/internal/common/logger"
	"github.com/blockchain-middleware/core/internal/common/models"
)

type DerivationPath struct {
	Purpose  uint32
	CoinType uint32
	Account  uint32
	Change   uint32
	Index    uint32
}

type Wallet struct {
	mnemonic    string
	password    string
	masterKey   *hdkeychain.ExtendedKey
	addresses   map[string]common.Address
	addressesMu sync.RWMutex
	db          *gorm.DB
}

func NewWallet(mnemonic, password string, db *gorm.DB) (*Wallet, error) {
	if mnemonic == "" {
		return nil, errors.New(400, "mnemonic is required")
	}

	seed := accounts.NewSeed(mnemonic, password)
	masterKey, err := hdkeychain.NewMaster(seed, &chaincfg.MainNetParams)
	if err != nil {
		return nil, fmt.Errorf("failed to create master key: %w", err)
	}

	return &Wallet{
		mnemonic:  mnemonic,
		password:  password,
		masterKey: masterKey,
		addresses: make(map[string]common.Address),
		db:        db,
	}, nil
}

func (w *Wallet) DeriveAddress(path string) (common.Address, error) {
	w.addressesMu.RLock()
	if addr, exists := w.addresses[path]; exists {
		w.addressesMu.RUnlock()
		return addr, nil
	}
	w.addressesMu.RUnlock()

	derivationPath, err := ParseDerivationPath(path)
	if err != nil {
		return common.Address{}, err
	}

	key := w.masterKey
	components := []uint32{
		derivationPath.Purpose,
		derivationPath.CoinType,
		derivationPath.Account,
		derivationPath.Change,
		derivationPath.Index,
	}

	for _, component := range components {
		key, err = key.Derive(component)
		if err != nil {
			return common.Address{}, fmt.Errorf("failed to derive key: %w", err)
		}
	}

	privKey, err := key.ECPrivKey()
	if err != nil {
		return common.Address{}, fmt.Errorf("failed to get private key: %w", err)
	}

	addr := crypto.PubkeyToAddress(privKey.PubKey().ToECDSA())

	w.addressesMu.Lock()
	w.addresses[path] = addr
	w.addressesMu.Unlock()

	return addr, nil
}

func (w *Wallet) DeriveAddresses(basePath string, start, count uint32) ([]common.Address, []string, error) {
	addresses := make([]common.Address, count)
	paths := make([]string, count)

	basePath = strings.TrimSuffix(basePath, "/0")

	for i := uint32(0); i < count; i++ {
		path := fmt.Sprintf("%s/%d", basePath, start+i)
		addr, err := w.DeriveAddress(path)
		if err != nil {
			return nil, nil, err
		}
		addresses[i] = addr
		paths[i] = path
	}

	return addresses, paths, nil
}

func (w *Wallet) GetPrivateKey(path string) (*big.Int, error) {
	derivationPath, err := ParseDerivationPath(path)
	if err != nil {
		return nil, err
	}

	key := w.masterKey
	components := []uint32{
		derivationPath.Purpose,
		derivationPath.CoinType,
		derivationPath.Account,
		derivationPath.Change,
		derivationPath.Index,
	}

	for _, component := range components {
		key, err = key.Derive(component)
		if err != nil {
			return nil, fmt.Errorf("failed to derive key: %w", err)
		}
	}

	privKey, err := key.ECPrivKey()
	if err != nil {
		return nil, fmt.Errorf("failed to get private key: %w", err)
	}

	return privKey.D, nil
}

func (w *Wallet) GetPublicKeyHex(path string) (string, error) {
	derivationPath, err := ParseDerivationPath(path)
	if err != nil {
		return "", err
	}

	key := w.masterKey
	components := []uint32{
		derivationPath.Purpose,
		derivationPath.CoinType,
		derivationPath.Account,
		derivationPath.Change,
		derivationPath.Index,
	}

	for _, component := range components {
		key, err = key.Derive(component)
		if err != nil {
			return "", fmt.Errorf("failed to derive key: %w", err)
		}
	}

	pubKey, err := key.ECPubKey()
	if err != nil {
		return "", fmt.Errorf("failed to get public key: %w", err)
	}

	return common.Bytes2Hex(pubKey.SerializeCompressed()), nil
}

func ParseDerivationPath(path string) (*DerivationPath, error) {
	parts := strings.Split(strings.TrimPrefix(path, "m/"), "/")
	if len(parts) != 5 {
		return nil, fmt.Errorf("invalid derivation path: %s", path)
	}

	dp := &DerivationPath{}
	var err error

	dp.Purpose, err = parsePathComponent(parts[0])
	if err != nil {
		return nil, err
	}
	dp.CoinType, err = parsePathComponent(parts[1])
	if err != nil {
		return nil, err
	}
	dp.Account, err = parsePathComponent(parts[2])
	if err != nil {
		return nil, err
	}
	dp.Change, err = parsePathComponent(parts[3])
	if err != nil {
		return nil, err
	}
	dp.Index, err = parsePathComponent(parts[4])
	if err != nil {
		return nil, err
	}

	return dp, nil
}

func parsePathComponent(s string) (uint32, error) {
	hardened := strings.HasSuffix(s, "'")
	s = strings.TrimSuffix(s, "'")

	var val uint64
	_, err := fmt.Sscanf(s, "%d", &val)
	if err != nil {
		return 0, err
	}

	if hardened {
		val += 0x80000000
	}

	return uint32(val), nil
}

type AddressBookManager struct {
	db *gorm.DB
	mu sync.RWMutex
}

func NewAddressBookManager(db *gorm.DB) *AddressBookManager {
	return &AddressBookManager{db: db}
}

func (ab *AddressBookManager) AddAddress(ctx context.Context, address, label string, chainID uint64, tags map[string]string, derivationPath, note string) (*models.AddressBook, error) {
	ab.mu.Lock()
	defer ab.mu.Unlock()

	entry := &models.AddressBook{
		Address:        address,
		Label:          label,
		ChainID:        chainID,
		Tags:           tags,
		DerivationPath: derivationPath,
		Note:           note,
	}

	if err := ab.db.Create(entry).Error; err != nil {
		return nil, fmt.Errorf("failed to add address: %w", err)
	}

	logger.Log.Info("Address added to address book", zap.String("address", address), zap.String("label", label))
	return entry, nil
}

func (ab *AddressBookManager) GetAddress(ctx context.Context, address string) (*models.AddressBook, error) {
	var entry models.AddressBook
	err := ab.db.Where("address = ?", address).First(&entry).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, err
	}
	return &entry, nil
}

func (ab *AddressBookManager) UpdateAddress(ctx context.Context, address string, updates map[string]interface{}) error {
	ab.mu.Lock()
	defer ab.mu.Unlock()

	result := ab.db.Model(&models.AddressBook{}).Where("address = ?", address).Updates(updates)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.ErrNotFound
	}
	return nil
}

func (ab *AddressBookManager) DeleteAddress(ctx context.Context, address string) error {
	ab.mu.Lock()
	defer ab.mu.Unlock()

	result := ab.db.Where("address = ?", address).Delete(&models.AddressBook{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.ErrNotFound
	}
	return nil
}

func (ab *AddressBookManager) ListAddresses(ctx context.Context, chainID uint64, label, tag string, offset, limit int) ([]models.AddressBook, int64, error) {
	var entries []models.AddressBook
	var total int64

	query := ab.db.Model(&models.AddressBook{})
	if chainID > 0 {
		query = query.Where("chain_id = ?", chainID)
	}
	if label != "" {
		query = query.Where("label ILIKE ?", "%"+label+"%")
	}

	query.Count(&total)
	err := query.Offset(offset).Limit(limit).Order("created_at DESC").Find(&entries).Error

	return entries, total, err
}

func (ab *AddressBookManager) SearchByTag(ctx context.Context, tagKey, tagValue string) ([]models.AddressBook, error) {
	var entries []models.AddressBook
	err := ab.db.Where("tags->>? = ?", tagKey, tagValue).Find(&entries).Error
	return entries, err
}

func InitializeFromConfig(db *gorm.DB) (*Wallet, error) {
	if config.AppConfig.HDWallet.Mnemonic == "" {
		return nil, nil
	}
	return NewWallet(config.AppConfig.HDWallet.Mnemonic, config.AppConfig.HDWallet.Password, db)
}
