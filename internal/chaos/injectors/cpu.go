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

type CPUStressInjector struct {
	mu      sync.Mutex
	running map[string]context.CancelFunc
	logger  *zap.Logger
}

func NewCPUStressInjector(logger *zap.Logger) ports.ChaosInjector {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &CPUStressInjector{
		running: make(map[string]context.CancelFunc),
		logger:  logger,
	}
}

func (i *CPUStressInjector) Type() string { return "cpu_stress" }

func (i *CPUStressInjector) Inject(ctx context.Context, scope *domain.InjectionScope, params map[string]interface{}) error {
	load, ok := params["load_percent"].(int)
	if !ok {
		load = 80
	}

	runID := fmt.Sprintf("cpu-%d", time.Now().UnixNano())
	ctx, cancel := context.WithCancel(ctx)

	i.mu.Lock()
	i.running[runID] = cancel
	i.mu.Unlock()

	go func() {
		ticker := time.NewTicker(100 * time.Millisecond)
		defer ticker.Stop()

		for {
			select {
			case <-ctx.Done():
				i.logger.Info("cpu stress stopped",
					zap.String("run_id", runID),
				)
				return
			case <-ticker.C:
			}
		}
	}()

	i.logger.Info("cpu stress injection started",
		zap.String("run_id", runID),
		zap.Int("load_percent", load),
		zap.Any("scope", scope),
	)

	return nil
}

func (i *CPUStressInjector) Rollback(ctx context.Context, runID string) error {
	i.mu.Lock()
	defer i.mu.Unlock()

	if cancel, exists := i.running[runID]; exists {
		cancel()
		delete(i.running, runID)
		i.logger.Info("cpu stress injection rolled back",
			zap.String("run_id", runID),
		)
	}
	return nil
}
