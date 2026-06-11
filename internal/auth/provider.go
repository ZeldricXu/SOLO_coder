package auth

import (
	"context"
	"net/http"
	"sync"
)

type AuthResult struct {
	Subject     string
	Permissions []string
	Claims      map[string]interface{}
	Authenticated bool
}

type AuthProvider interface {
	Name() string
	Validate(ctx context.Context, req *http.Request) (*AuthResult, error)
	Configure(config interface{}) error
}

type ProviderFactory func() AuthProvider

type ProviderRegistry struct {
	providers map[string]AuthProvider
	factories map[string]ProviderFactory
	mu        sync.RWMutex
}

var defaultRegistry = &ProviderRegistry{
	providers: make(map[string]AuthProvider),
	factories: make(map[string]ProviderFactory),
}

func NewProviderRegistry() *ProviderRegistry {
	return &ProviderRegistry{
		providers: make(map[string]AuthProvider),
		factories: make(map[string]ProviderFactory),
	}
}

func GetDefaultRegistry() *ProviderRegistry {
	return defaultRegistry
}

func (r *ProviderRegistry) Register(name string, provider AuthProvider) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.providers[name] = provider
}

func (r *ProviderRegistry) RegisterFactory(name string, factory ProviderFactory) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.factories[name] = factory
}

func (r *ProviderRegistry) Get(name string) (AuthProvider, bool) {
	r.mu.RLock()
	provider, exists := r.providers[name]
	if exists {
		r.mu.RUnlock()
		return provider, true
	}

	factory, exists := r.factories[name]
	if !exists {
		r.mu.RUnlock()
		return nil, false
	}

	r.mu.RUnlock()
	r.mu.Lock()
	defer r.mu.Unlock()

	if provider, exists := r.providers[name]; exists {
		return provider, true
	}

	provider = factory()
	r.providers[name] = provider
	return provider, true
}

func (r *ProviderRegistry) Create(name string, config interface{}) (AuthProvider, error) {
	r.mu.RLock()
	factory, exists := r.factories[name]
	r.mu.RUnlock()

	if !exists {
		return nil, &ErrProviderNotFound{Name: name}
	}

	provider := factory()
	if err := provider.Configure(config); err != nil {
		return nil, err
	}

	r.mu.Lock()
	defer r.mu.Unlock()
	r.providers[name] = provider

	return provider, nil
}

func (r *ProviderRegistry) List() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()

	names := make([]string, 0, len(r.providers)+len(r.factories))
	for name := range r.providers {
		names = append(names, name)
	}
	for name := range r.factories {
		if _, exists := r.providers[name]; !exists {
			names = append(names, name)
		}
	}
	return names
}

type ErrProviderNotFound struct {
	Name string
}

func (e *ErrProviderNotFound) Error() string {
	return "auth provider not found: " + e.Name
}

func RegisterDefaultProviders() {
	RegisterProviderFactory("jwt", func() AuthProvider {
		return &JWTProvider{}
	})

	RegisterProviderFactory("api_key", func() AuthProvider {
		return &APIKeyProvider{}
	})

	RegisterProviderFactory("oauth2", func() AuthProvider {
		return &OAuth2Provider{}
	})
}

func RegisterProvider(name string, provider AuthProvider) {
	defaultRegistry.Register(name, provider)
}

func RegisterProviderFactory(name string, factory ProviderFactory) {
	defaultRegistry.RegisterFactory(name, factory)
}

func GetProvider(name string) (AuthProvider, bool) {
	return defaultRegistry.Get(name)
}

func CreateProvider(name string, config interface{}) (AuthProvider, error) {
	return defaultRegistry.Create(name, config)
}
