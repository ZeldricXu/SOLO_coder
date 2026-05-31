package differentialprivacy

import (
	"sync"
	"time"

	"github.com/solocoder/session136/pkg/common/utils"
	"go.uber.org/zap"
)

type BudgetConfig struct {
	TotalEpsilon float64
	TotalDelta   float64
	ResetPeriod  time.Duration
}

type BudgetManager interface {
	Consume(ctx context.Context, epsilon, delta float64) error
	Remaining() (float64, float64)
	Reset()
	CheckAndReset()
	CanConsume(epsilon, delta float64) bool
}

type DefaultBudgetManager struct {
	config        *BudgetConfig
	usedEpsilon   float64
	usedDelta     float64
	lastResetTime time.Time
	logger        *zap.Logger
	mu            sync.Mutex
}

func NewDefaultBudgetManager(config *BudgetConfig) *DefaultBudgetManager {
	if config == nil {
		config = &BudgetConfig{
			TotalEpsilon: 10.0,
			TotalDelta:   1e-5,
			ResetPeriod:  24 * time.Hour,
		}
	}

	return &DefaultBudgetManager{
		config:        config,
		usedEpsilon:   0,
		usedDelta:     0,
		lastResetTime: time.Now(),
		logger:        utils.GetLogger(),
	}
}

func (b *DefaultBudgetManager) CheckAndReset() {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.config.ResetPeriod > 0 && time.Since(b.lastResetTime) >= b.config.ResetPeriod {
		b.usedEpsilon = 0
		b.usedDelta = 0
		b.lastResetTime = time.Now()
		b.logger.Info("Privacy budget reset")
	}
}

func (b *DefaultBudgetManager) Consume(ctx context.Context, epsilon, delta float64) error {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.CheckAndReset()

	if b.usedEpsilon+epsilon > b.config.TotalEpsilon || b.usedDelta+delta > b.config.TotalDelta {
		return utils.ErrBudgetExhausted
	}

	b.usedEpsilon += epsilon
	b.usedDelta += delta

	b.logger.Info("Budget consumed",
		zap.Float64("epsilon", epsilon),
		zap.Float64("delta", delta),
		zap.Float64("remaining_epsilon", b.config.TotalEpsilon-b.usedEpsilon),
	)

	return nil
}

func (b *DefaultBudgetManager) Remaining() (float64, float64) {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.CheckAndReset()

	return b.config.TotalEpsilon - b.usedEpsilon, b.config.TotalDelta - b.usedDelta
}

func (b *DefaultBudgetManager) Reset() {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.usedEpsilon = 0
	b.usedDelta = 0
	b.lastResetTime = time.Now()
	b.logger.Info("Privacy budget manually reset")
}

func (b *DefaultBudgetManager) CanConsume(epsilon, delta float64) bool {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.CheckAndReset()

	return b.usedEpsilon+epsilon <= b.config.TotalEpsilon && b.usedDelta+delta <= b.config.TotalDelta
}
