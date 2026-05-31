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

type DiskFailureInjector struct {
	mu      sync.Mutex
	running map[string]context.CancelFunc
	logger  *zap.Logger
}

func NewDiskFailureInjector(logger *zap.Logger) ports.ChaosInjector {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &DiskFailureInjector{
		running: make(map[string]context.CancelFunc),
		logger:  logger,
	}
}

func (i *DiskFailureInjector) Type() string { return "disk_failure" }

func (i *DiskFailureInjector) Inject(ctx context.Context, scope *domain.InjectionScope, params map[string]interface{}) error {
	mode, _ := params["mode"].(string)
	if mode == "" {
		mode = "read_only"
	}

	runID := fmt.Sprintf("disk-%d", time.Now().UnixNano())

	i.logger.Info("disk failure injection started",
		zap.String("run_id", runID),
		zap.String("mode", mode),
		zap.Any("scope", scope),
	)

	return nil
}

func (i *DiskFailureInjector) Rollback(ctx context.Context, runID string) error {
	i.mu.Lock()
	defer i.mu.Unlock()

	if cancel, exists := i.running[runID]; exists {
		cancel()
		delete(i.running, runID)
		i.logger.Info("disk failure injection rolled back",
			zap.String("run_id", runID),
		)
	}
	return nil
}
