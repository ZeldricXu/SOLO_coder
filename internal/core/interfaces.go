package core

import (
	"context"

	"session130/pkg/models"
)

type Pool interface {
	Acquire(ctx context.Context) (*PooledResource, error)
	Release(res *PooledResource)
	Stats() map[string]interface{}
	Close()
}

type RequestProcessor interface {
	Execute(ctx context.Context, request *models.APIRequest) (*models.APIResponse, error)
	RegisterPool(name string, cfg PoolConfig, factory func() (*PooledResource, error))
	GetPool(name string) (*ResourcePool, bool)
	GetStats() map[string]interface{}
}
