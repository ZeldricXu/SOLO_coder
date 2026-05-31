package storage

import (
	"fmt"
	"sync"

	"github.com/solocoder/task-scheduler/internal/contracts"
)

type ProviderRegistry struct {
	providers map[string]contracts.StorageProvider
	mu        sync.RWMutex
}

func NewProviderRegistry() *ProviderRegistry {
	return &ProviderRegistry{
		providers: make(map[string]contracts.StorageProvider),
	}
}

func (r *ProviderRegistry) RegisterProvider(provider contracts.StorageProvider) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.providers[provider.Name()] = provider
}

func (r *ProviderRegistry) GetProvider(name string) (contracts.StorageProvider, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	provider, exists := r.providers[name]
	if !exists {
		return nil, fmt.Errorf("provider not found: %s", name)
	}
	return provider, nil
}

func (r *ProviderRegistry) ListProviders() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()

	providers := make([]string, 0, len(r.providers))
	for name := range r.providers {
		providers = append(providers, name)
	}
	return providers
}
