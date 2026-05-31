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

type FailureInjector struct {
	mu      sync.Mutex
	running map[string]context.CancelFunc
	logger  *zap.Logger
}

func NewFailureInjector(logger *zap.Logger) ports.ChaosInjector {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &FailureInjector{
		running: make(map[string]context.CancelFunc),
		logger:  logger,
	}
}

func (i *FailureInjector) Type() string { return "network_failure" }

func (i *FailureInjector) Inject(ctx context.Context, scope *domain.InjectionScope, params map[string]interface{}) error {
	rate, ok := params["failure_rate"].(float64)
	if !ok {
		rate = 0.1
	}
	errorCode, _ := params["error_code"].(int)
	if errorCode == 0 {
		errorCode = 500
	}

	runID := fmt.Sprintf("failure-%d", time.Now().UnixNano())

	i.logger.Info("failure injection started",
		zap.String("run_id", runID),
		zap.Float64("failure_rate", rate),
		zap.Int("error_code", errorCode),
		zap.Any("scope", scope),
	)

	return nil
}

func (i *FailureInjector) Rollback(ctx context.Context, runID string) error {
	i.mu.Lock()
	defer i.mu.Unlock()

	if cancel, exists := i.running[runID]; exists {
		cancel()
		delete(i.running, runID)
		i.logger.Info("failure injection rolled back",
			zap.String("run_id", runID),
		)
	}
	return nil
}
