package ports

import (
	"context"
	"github.com/solocoder/session147/internal/storage/domain"
)

type StorageRepository interface {
	StoreContent(ctx context.Context, content *domain.StoredContent) error
	GetContent(ctx context.Context, id string) (*domain.StoredContent, error)
	GetContentByCID(ctx context.Context, cid string, storageType string) (*domain.StoredContent, error)
	ListContents(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.StoredContent, int64, error)
	UpdateContent(ctx context.Context, content *domain.StoredContent) error
	DeleteContent(ctx context.Context, id string) error

	CreatePinOperation(ctx context.Context, op *domain.PinOperation) error
	GetPinOperation(ctx context.Context, id string) (*domain.PinOperation, error)
	UpdatePinOperation(ctx context.Context, op *domain.PinOperation) error
}

type StorageService interface {
	Store(ctx context.Context, req *domain.StoreRequest) (*domain.StoreResponse, error)
	Retrieve(ctx context.Context, req *domain.RetrieveRequest) ([]byte, error)
	GetContentInfo(ctx context.Context, id string) (*domain.StoredContent, error)
	ListContents(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.StoredContent, int64, error)
	DeleteContent(ctx context.Context, id string) error

	Pin(ctx context.Context, req *domain.PinRequest) (*domain.PinOperation, error)
	Unpin(ctx context.Context, contentID string, storageType string) error
	GetPinStatus(ctx context.Context, contentID string, storageType string) (string, error)
}

type StorageProvider interface {
	Store(ctx context.Context, data []byte, fileName string) (cid string, urls []string, size int64, err error)
	Retrieve(ctx context.Context, cid string) ([]byte, error)
	Pin(ctx context.Context, cid string, name string) (string, error)
	Unpin(ctx context.Context, cid string) error
	GetPinStatus(ctx context.Context, cid string) (string, error)
}
