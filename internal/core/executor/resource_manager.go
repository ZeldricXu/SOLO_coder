package executor

import (
	"context"
	"errors"

	"github.com/solocoder/task-scheduler/v2/internal/common"
	"github.com/solocoder/task-scheduler/v2/internal/core/ports"
)

type ResourceManager struct {
	resourcePool ports.ResourcePool
}

func NewResourceManager(resourcePool ports.ResourcePool) *ResourceManager {
	return &ResourceManager{
		resourcePool: resourcePool,
	}
}

func (m *ResourceManager) Acquire(ctx context.Context) (func(), error) {
	_, err := m.resourcePool.Acquire(ctx)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			return nil, common.NewTimeoutError("上游服务响应超时")
		}
		return nil, errors.New("resource acquisition failed")
	}

	release := func() {
		m.resourcePool.Release()
	}

	return release, nil
}
