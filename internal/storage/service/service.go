package service

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"time"

	"github.com/solocoder/session147/internal/common/errors"
	"github.com/solocoder/session147/internal/common/logger"
	"github.com/solocoder/session147/internal/common/utils"
	"github.com/solocoder/session147/internal/storage/domain"
	"github.com/solocoder/session147/internal/storage/ports"
	"go.uber.org/zap"
)

type storageService struct {
	repo      ports.StorageRepository
	providers map[string]ports.StorageProvider
}

func NewStorageService(repo ports.StorageRepository) ports.StorageService {
	return &storageService{
		repo:      repo,
		providers: make(map[string]ports.StorageProvider),
	}
}

func (s *storageService) RegisterProvider(name string, provider ports.StorageProvider) {
	s.providers[name] = provider
}

func (s *storageService) Store(ctx context.Context, req *domain.StoreRequest) (*domain.StoreResponse, error) {
	logger.Info("storing content", zap.String("storage_type", req.StorageType), zap.Int("size", len(req.Content)))

	provider, exists := s.providers[req.StorageType]
	if !exists {
		return nil, errors.BadRequest("unsupported storage type", nil)
	}

	content := req.Content
	if req.Encrypt {
		content = s.encryptContent(content, req.EncryptionKey)
	}

	hash := sha256.Sum256(content)
	contentHash := hex.EncodeToString(hash[:])

	cid, urls, size, err := provider.Store(ctx, content, req.FileName)
	if err != nil {
		return nil, errors.Internal("failed to store content", err)
	}

	pinStatus := domain.PinStatusUnpinned
	var pinTime *time.Time
	if req.Pin {
		pinStatus = domain.PinStatusPinning
		go s.asyncPin(req.StorageType, cid, req.FileName)
	}

	stored := &domain.StoredContent{
		ID:           utils.GenerateID("store"),
		ContentID:    cid,
		StorageType:  req.StorageType,
		ContentHash:  contentHash,
		Size:         size,
		OriginalName: req.FileName,
		PinStatus:    pinStatus,
		PinTimestamp: pinTime,
		ExpiresAt:    req.ExpiresAt,
		URLs:         urls,
		Encrypted:    req.Encrypt,
		CreatedAt:    time.Now(),
		UpdatedAt:    time.Now(),
		Metadata:     req.Metadata,
	}

	if err := s.repo.StoreContent(ctx, stored); err != nil {
		return nil, errors.Internal("failed to store content metadata", err)
	}

	return &domain.StoreResponse{
		ContentID:   cid,
		StorageType: req.StorageType,
		ContentHash: contentHash,
		Size:        size,
		URLs:        urls,
		PinStatus:   pinStatus,
	}, nil
}

func (s *storageService) Retrieve(ctx context.Context, req *domain.RetrieveRequest) ([]byte, error) {
	logger.Info("retrieving content", zap.String("content_id", req.ContentID))

	provider, exists := s.providers[req.StorageType]
	if !exists {
		return nil, errors.BadRequest("unsupported storage type", nil)
	}

	data, err := provider.Retrieve(ctx, req.ContentID)
	if err != nil {
		return nil, errors.Internal("failed to retrieve content", err)
	}

	return data, nil
}

func (s *storageService) GetContentInfo(ctx context.Context, id string) (*domain.StoredContent, error) {
	return s.repo.GetContent(ctx, id)
}

func (s *storageService) ListContents(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.StoredContent, int64, error) {
	return s.repo.ListContents(ctx, filter, page, pageSize)
}

func (s *storageService) DeleteContent(ctx context.Context, id string) error {
	content, err := s.repo.GetContent(ctx, id)
	if err != nil {
		return errors.NotFound("content not found", err)
	}

	if provider, exists := s.providers[content.StorageType]; exists {
		_ = provider.Unpin(ctx, content.ContentID)
	}

	return s.repo.DeleteContent(ctx, id)
}

func (s *storageService) Pin(ctx context.Context, req *domain.PinRequest) (*domain.PinOperation, error) {
	logger.Info("pinning content", zap.String("content_id", req.ContentID))

	provider, exists := s.providers[req.StorageType]
	if !exists {
		return nil, errors.BadRequest("unsupported storage type", nil)
	}

	op := &domain.PinOperation{
		ID:          utils.GenerateID("pin"),
		ContentID:   req.ContentID,
		StorageType: req.StorageType,
		Operation:   "pin",
		Status:      "pending",
		StartedAt:   time.Now(),
	}

	requestID, err := provider.Pin(ctx, req.ContentID, req.Name)
	if err != nil {
		op.Status = "failed"
		op.Error = err.Error()
		_ = s.repo.CreatePinOperation(ctx, op)
		return nil, errors.Internal("failed to pin content", err)
	}

	op.RequestID = requestID
	op.Status = "pinning"
	if err := s.repo.CreatePinOperation(ctx, op); err != nil {
		logger.Error("failed to create pin operation", zap.Error(err))
	}

	return op, nil
}

func (s *storageService) Unpin(ctx context.Context, contentID string, storageType string) error {
	provider, exists := s.providers[storageType]
	if !exists {
		return errors.BadRequest("unsupported storage type", nil)
	}

	return provider.Unpin(ctx, contentID)
}

func (s *storageService) GetPinStatus(ctx context.Context, contentID string, storageType string) (string, error) {
	provider, exists := s.providers[storageType]
	if !exists {
		return "", errors.BadRequest("unsupported storage type", nil)
	}

	return provider.GetPinStatus(ctx, contentID)
}

func (s *storageService) asyncPin(storageType, cid, name string) {
	ctx := context.Background()
	provider := s.providers[storageType]
	if provider == nil {
		return
	}

	_, err := provider.Pin(ctx, cid, name)
	if err != nil {
		logger.Error("async pin failed", zap.Error(err))
		return
	}

	content, err := s.repo.GetContentByCID(ctx, cid, storageType)
	if err == nil && content != nil {
		now := time.Now()
		content.PinStatus = domain.PinStatusPinned
		content.PinTimestamp = &now
		_ = s.repo.UpdateContent(ctx, content)
	}
}

func (s *storageService) encryptContent(data []byte, key string) []byte {
	if key == "" {
		return data
	}
	result := make([]byte, len(data))
	keyBytes := []byte(key)
	for i, b := range data {
		result[i] = b ^ keyBytes[i%len(keyBytes)]
	}
	return result
}
