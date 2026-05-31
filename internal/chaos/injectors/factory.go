package injectors

import (
	"fmt"
	"sync"

	"github.com/chaoslab/platform/internal/core/ports"
	"go.uber.org/zap"
)

type Registry struct {
	mu        sync.RWMutex
	injectors map[string]ports.ChaosInjector
	logger    *zap.Logger
}

func NewInjectorRegistry(logger *zap.Logger, injectors ...ports.ChaosInjector) ports.InjectorRegistry {
	if logger == nil {
		logger = zap.NewNop()
	}
	r := &Registry{
		injectors: make(map[string]ports.ChaosInjector),
		logger:    logger,
	}

	for _, inj := range injectors {
		r.register(inj)
	}

	return r
}

func (r *Registry) register(inj ports.ChaosInjector) {
	r.injectors[inj.Type()] = inj
}

func (r *Registry) Get(injectorType string) (ports.ChaosInjector, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	inj, exists := r.injectors[injectorType]
	if !exists {
		return nil, fmt.Errorf("unsupported injector type: %s", injectorType)
	}
	return inj, nil
}

func (r *Registry) ListTypes() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()

	types := make([]string, 0, len(r.injectors))
	for t := range r.injectors {
		types = append(types, t)
	}
	return types
}
