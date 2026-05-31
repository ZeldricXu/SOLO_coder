package storageadapter

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/ipfs/go-ipfs-api"
	"go.uber.org/zap"

	"github.com/blockchain-middleware/core/internal/common/config"
	"github.com/blockchain-middleware/core/internal/common/errors"
	"github.com/blockchain-middleware/core/internal/common/logger"
	"github.com/blockchain-middleware/core/internal/common/models"
	"gorm.io/gorm"
)

type StorageType string

const (
	StorageTypeIPFS     StorageType = "ipfs"
	StorageTypeArweave  StorageType = "arweave"
	StorageTypeFilecoin StorageType = "filecoin"
)

type StoreOptions struct {
	Pin        bool
	PinDuration time.Duration
	Metadata   map[string]string
}

type RetrieveOptions struct {
	Timeout time.Duration
}

type ContentInfo struct {
	CID         string
	Size        int64
	StorageType StorageType
	Pinned      bool
	Timestamp   time.Time
	Metadata    map[string]string
}

type StorageAdapter interface {
	Store(ctx context.Context, data []byte, options StoreOptions) (*ContentInfo, error)
	Retrieve(ctx context.Context, cid string, options RetrieveOptions) ([]byte, error)
	Pin(ctx context.Context, cid string, duration time.Duration) error
	Unpin(ctx context.Context, cid string) error
	IsPinned(ctx context.Context, cid string) (bool, error)
}

type IPFSAdapter struct {
	client *shell.Shell
	config config.IPFSConfig
}

func NewIPFSAdapter(cfg config.IPFSConfig) *IPFSAdapter {
	return &IPFSAdapter{
		client: shell.NewShell(cfg.APIURL),
		config: cfg,
	}
}

func (a *IPFSAdapter) Store(ctx context.Context, data []byte, options StoreOptions) (*ContentInfo, error) {
	cid, err := a.client.Add(bytes.NewReader(data), shell.Pin(options.Pin))
	if err != nil {
		return nil, fmt.Errorf("ipfs add failed: %w", err)
	}

	size := int64(len(data))

	return &ContentInfo{
		CID:         cid,
		Size:        size,
		StorageType: StorageTypeIPFS,
		Pinned:      options.Pin,
		Timestamp:   time.Now(),
		Metadata:    options.Metadata,
	}, nil
}

func (a *IPFSAdapter) Retrieve(ctx context.Context, cid string, options RetrieveOptions) ([]byte, error) {
	reader, err := a.client.Cat(cid)
	if err != nil {
		return nil, fmt.Errorf("ipfs cat failed: %w", err)
	}
	defer reader.Close()

	return io.ReadAll(reader)
}

func (a *IPFSAdapter) Pin(ctx context.Context, cid string, duration time.Duration) error {
	return a.client.Pin(cid)
}

func (a *IPFSAdapter) Unpin(ctx context.Context, cid string) error {
	return a.client.Unpin(cid)
}

func (a *IPFSAdapter) IsPinned(ctx context.Context, cid string) (bool, error) {
	pins, err := a.client.Pins()
	if err != nil {
		return false, err
	}
	_, exists := pins[cid]
	return exists, nil
}

type ArweaveAdapter struct {
	baseURL string
	client  *http.Client
}

func NewArweaveAdapter(baseURL string) *ArweaveAdapter {
	return &ArweaveAdapter{
		baseURL: baseURL,
		client: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

func (a *ArweaveAdapter) Store(ctx context.Context, data []byte, options StoreOptions) (*ContentInfo, error) {
	req, err := http.NewRequestWithContext(ctx, "POST", a.baseURL+"/tx", bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/octet-stream")

	resp, err := a.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("arweave tx failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("arweave error %d: %s", resp.StatusCode, string(body))
	}

	txID, _ := io.ReadAll(resp.Body)
	txIDStr := strings.TrimSpace(string(txID))

	return &ContentInfo{
		CID:         txIDStr,
		Size:        int64(len(data)),
		StorageType: StorageTypeArweave,
		Pinned:      true,
		Timestamp:   time.Now(),
		Metadata:    options.Metadata,
	}, nil
}

func (a *ArweaveAdapter) Retrieve(ctx context.Context, txID string, options RetrieveOptions) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", a.baseURL+"/"+txID, nil)
	if err != nil {
		return nil, err
	}

	resp, err := a.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("arweave retrieve failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("arweave error %d", resp.StatusCode)
	}

	return io.ReadAll(resp.Body)
}

func (a *ArweaveAdapter) Pin(ctx context.Context, cid string, duration time.Duration) error {
	return nil
}

func (a *ArweaveAdapter) Unpin(ctx context.Context, cid string) error {
	return nil
}

func (a *ArweaveAdapter) IsPinned(ctx context.Context, cid string) (bool, error) {
	return true, nil
}

type StorageManager struct {
	adapters map[StorageType]StorageAdapter
	db       *gorm.DB
	mu       sync.RWMutex
}

func NewStorageManager(db *gorm.DB) *StorageManager {
	return &StorageManager{
		adapters: make(map[StorageType]StorageAdapter),
		db:       db,
	}
}

func (sm *StorageManager) RegisterAdapter(storageType StorageType, adapter StorageAdapter) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.adapters[storageType] = adapter
}

func (sm *StorageManager) Store(ctx context.Context, storageType StorageType, data []byte, options StoreOptions) (*ContentInfo, error) {
	sm.mu.RLock()
	adapter, exists := sm.adapters[storageType]
	sm.mu.RUnlock()

	if !exists {
		return nil, errors.New(400, "unsupported storage type", string(storageType))
	}

	info, err := adapter.Store(ctx, data, options)
	if err != nil {
		return nil, errors.Wrap(err, "storage operation failed")
	}

	content := &models.StoredContent{
		ContentID:   info.CID,
		StorageType: string(storageType),
		CID:         info.CID,
		Size:        info.Size,
		Pinned:      info.Pinned,
		Metadata:    info.Metadata,
	}

	if err := sm.db.Create(content).Error; err != nil {
		logger.Log.Warn("Failed to persist content record", zap.Error(err))
	}

	return info, nil
}

func (sm *StorageManager) Retrieve(ctx context.Context, storageType StorageType, cid string, options RetrieveOptions) ([]byte, error) {
	sm.mu.RLock()
	adapter, exists := sm.adapters[storageType]
	sm.mu.RUnlock()

	if !exists {
		return nil, errors.New(400, "unsupported storage type", string(storageType))
	}

	data, err := adapter.Retrieve(ctx, cid, options)
	if err != nil {
		return nil, errors.Wrap(err, "retrieve failed")
	}

	return data, nil
}

func (sm *StorageManager) Pin(ctx context.Context, storageType StorageType, cid string, duration time.Duration) error {
	sm.mu.RLock()
	adapter, exists := sm.adapters[storageType]
	sm.mu.RUnlock()

	if !exists {
		return errors.New(400, "unsupported storage type", string(storageType))
	}

	if err := adapter.Pin(ctx, cid, duration); err != nil {
		return errors.Wrap(err, "pin failed")
	}

	sm.db.Model(&models.StoredContent{}).Where("cid = ?", cid).Update("pinned", true)

	return nil
}

func (sm *StorageManager) Unpin(ctx context.Context, storageType StorageType, cid string) error {
	sm.mu.RLock()
	adapter, exists := sm.adapters[storageType]
	sm.mu.RUnlock()

	if !exists {
		return errors.New(400, "unsupported storage type", string(storageType))
	}

	if err := adapter.Unpin(ctx, cid); err != nil {
		return errors.Wrap(err, "unpin failed")
	}

	sm.db.Model(&models.StoredContent{}).Where("cid = ?", cid).Update("pinned", false)

	return nil
}

func (sm *StorageManager) GetContent(ctx context.Context, cid string) (*models.StoredContent, error) {
	var content models.StoredContent
	err := sm.db.Where("cid = ?", cid).First(&content).Error
	if err != nil {
		return nil, err
	}
	return &content, nil
}

func (sm *StorageManager) ListContents(ctx context.Context, storageType StorageType, pinned *bool, offset, limit int) ([]models.StoredContent, int64, error) {
	var contents []models.StoredContent
	var total int64

	query := sm.db.Model(&models.StoredContent{})
	if storageType != "" {
		query = query.Where("storage_type = ?", string(storageType))
	}
	if pinned != nil {
		query = query.Where("pinned = ?", *pinned)
	}

	query.Count(&total)
	err := query.Offset(offset).Limit(limit).Order("created_at DESC").Find(&contents).Error

	return contents, total, err
}

func (sm *StorageManager) InitializeFromConfig() {
	if config.AppConfig.IPFS.APIURL != "" {
		ipfs := NewIPFSAdapter(config.AppConfig.IPFS)
		sm.RegisterAdapter(StorageTypeIPFS, ipfs)
		logger.Log.Info("IPFS adapter registered", zap.String("url", config.AppConfig.IPFS.APIURL))
	}
}
