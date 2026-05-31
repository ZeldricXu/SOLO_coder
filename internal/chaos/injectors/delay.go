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

type DelayInjector struct {
	mu      sync.Mutex
	running map[string]context.CancelFunc
	logger  *zap.Logger
}

func NewDelayInjector(logger *zap.Logger) ports.ChaosInjector {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &DelayInjector{
		running: make(map[string]context.CancelFunc),
		logger:  logger,
	}
}

func (i *DelayInjector) Type() string { return "network_delay" }

func (i *DelayInjector) Inject(ctx context.Context, scope *domain.InjectionScope, params map[string]interface{}) error {
	delayMs, ok := params["delay_ms"].(int)
	if !ok {
		delayMs = 100
	}
	jitterMs, _ := params["jitter_ms"].(int)

	runID := fmt.Sprintf("delay-%d", time.Now().UnixNano())

	i.logger.Info("delay injection started",
		zap.String("run_id", runID),
		zap.Int("delay_ms", delayMs),
		zap.Int("jitter_ms", jitterMs),
		zap.Any("scope", scope),
	)

	return nil
}

func (i *DelayInjector) Rollback(ctx context.Context, runID string) error {
	i.mu.Lock()
	defer i.mu.Unlock()

	if cancel, exists := i.running[runID]; exists {
		cancel()
		delete(i.running, runID)
		i.logger.Info("delay injection rolled back",
			zap.String("run_id", runID),
		)
	}
	return nil
}
