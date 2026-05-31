package injectors

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/core/domain"
	"github.com/chaoslab/platform/internal/core/ports"
	"go.uber.org/zap"
)

type MemoryStressInjector struct {
	mu          sync.Mutex
	running     map[string]context.CancelFunc
	allocations map[string][][]byte
	logger      *zap.Logger
}

func NewMemoryStressInjector(logger *zap.Logger) ports.ChaosInjector {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &MemoryStressInjector{
		running:     make(map[string]context.CancelFunc),
		allocations: make(map[string][][]byte),
		logger:      logger,
	}
}

func (i *MemoryStressInjector) Type() string { return "memory_stress" }

func (i *MemoryStressInjector) Inject(ctx context.Context, scope *domain.InjectionScope, params map[string]interface{}) error {
	sizeMB, ok := params["size_mb"].(int)
	if !ok {
		sizeMB = 512
	}

	runID := fmt.Sprintf("mem-%d", time.Now().UnixNano())
	ctx, cancel := context.WithCancel(ctx)

	allocs := make([][]byte, 0, sizeMB)
	for j := 0; j < sizeMB; j++ {
		allocs = append(allocs, make([]byte, 1024*1024))
	}

	i.mu.Lock()
	i.running[runID] = cancel
	i.allocations[runID] = allocs
	i.mu.Unlock()

	go func() {
		<-ctx.Done()
		i.mu.Lock()
		delete(i.allocations, runID)
		i.mu.Unlock()
	}()

	i.logger.Info("memory stress injection started",
		zap.String("run_id", runID),
		zap.Int("size_mb", sizeMB),
		zap.Any("scope", scope),
	)

	return nil
}

func (i *MemoryStressInjector) Rollback(ctx context.Context, runID string) error {
	i.mu.Lock()
	defer i.mu.Unlock()

	if cancel, exists := i.running[runID]; exists {
		cancel()
		delete(i.running, runID)
		i.logger.Info("memory stress injection rolled back",
			zap.String("run_id", runID),
		)
	}
	return nil
}
