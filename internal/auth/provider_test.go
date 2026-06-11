package auth

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"DF1-56/internal/models"
	"github.com/stretchr/testify/assert"
)

type MockAuthProvider struct {
	name          string
	authenticated bool
	subject       string
	permissions   []string
}

func (m *MockAuthProvider) Name() string {
	return m.name
}

func (m *MockAuthProvider) Validate(ctx context.Context, req *http.Request) (*AuthResult, error) {
	return &AuthResult{
		Authenticated: m.authenticated,
		Subject:       m.subject,
		Permissions:   m.permissions,
		Claims: map[string]interface{}{
			"custom": "value",
		},
	}, nil
}

func (m *MockAuthProvider) Configure(config interface{}) error {
	return nil
}

func TestProviderRegistry_RegisterAndGetProvider(t *testing.T) {
	registry := NewProviderRegistry()

	provider := &MockAuthProvider{name: "mock"}

	registry.Register("mock", provider)

	got, exists := registry.Get("mock")
	assert.True(t, exists)
	assert.Equal(t, provider, got)

	_, exists = registry.Get("nonexistent")
	assert.False(t, exists)
}

func TestProviderRegistry_RegisterDuplicateProvider(t *testing.T) {
	registry := NewProviderRegistry()

	provider1 := &MockAuthProvider{name: "mock"}
	provider2 := &MockAuthProvider{name: "mock"}

	registry.Register("mock", provider1)

	_, exists := registry.Get("mock")
	assert.True(t, exists)

	registry.Register("mock", provider2)

	got, exists := registry.Get("mock")
	assert.True(t, exists)
	assert.Equal(t, provider2, got)
}

func TestProviderRegistry_RegisterFactory(t *testing.T) {
	registry := NewProviderRegistry()

	factoryCalled := false
	factory := func() AuthProvider {
		factoryCalled = true
		return &MockAuthProvider{name: "mock_factory"}
	}

	registry.RegisterFactory("mock_factory", factory)

	provider, err := registry.Create("mock_factory", nil)
	assert.NoError(t, err)
	assert.True(t, factoryCalled)
	assert.Equal(t, "mock_factory", provider.Name())
}

func TestProviderRegistry_CreateProviderNotFound(t *testing.T) {
	registry := NewProviderRegistry()

	_, err := registry.Create("nonexistent", nil)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "not found")
}

func TestRegisterDefaultProviders(t *testing.T) {
	RegisterDefaultProviders()
	registry := GetDefaultRegistry()

	_, exists := registry.Get("jwt")
	assert.True(t, exists)

	_, exists = registry.Get("api_key")
	assert.True(t, exists)

	_, exists = registry.Get("oauth2")
	assert.True(t, exists)
}

func TestJWTProvider_ConfigureAndValidate(t *testing.T) {
	provider := &JWTProvider{}

	cfg := &models.JWTConfig{
		Secret: "test-secret",
	}

	err := provider.Configure(cfg)
	assert.NoError(t, err)

	req := httptest.NewRequest("GET", "/test", nil)
	token := "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjE3MTYyMzkwMjJ9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
	req.Header.Set("Authorization", "Bearer "+token)

	result, err := provider.Validate(context.Background(), req)
	assert.Error(t, err)
	assert.Nil(t, result)
}

func TestAPIKeyProvider_ConfigureAndValidate(t *testing.T) {
	provider := &APIKeyProvider{}

	cfg := &models.APIKeyConfig{
		HeaderName: "X-API-Key",
	}

	err := provider.Configure(cfg)
	assert.NoError(t, err)

	provider.SetValidKeys(map[string]string{
		"valid-key": "user123",
	})

	req := httptest.NewRequest("GET", "/test", nil)
	req.Header.Set("X-API-Key", "valid-key")

	result, err := provider.Validate(context.Background(), req)
	assert.NoError(t, err)
	assert.NotNil(t, result)
	assert.True(t, result.Authenticated)
	assert.Equal(t, "user123", result.Subject)

	req2 := httptest.NewRequest("GET", "/test", nil)
	req2.Header.Set("X-API-Key", "invalid-key")

	result2, err := provider.Validate(context.Background(), req2)
	assert.Error(t, err)
	assert.Nil(t, result2)
}

func TestAPIKeyProvider_Optional(t *testing.T) {
	provider := &APIKeyProvider{}
	provider.SetOptional(true)

	cfg := &models.APIKeyConfig{
		HeaderName: "X-API-Key",
	}

	err := provider.Configure(cfg)
	assert.NoError(t, err)

	provider.SetValidKeys(map[string]string{})

	req := httptest.NewRequest("GET", "/test", nil)

	result, err := provider.Validate(context.Background(), req)
	assert.NoError(t, err)
	assert.NotNil(t, result)
	assert.False(t, result.Authenticated)
}

func TestCreateProvider(t *testing.T) {
	provider, err := CreateProvider("api_key", map[string]interface{}{
		"header_name": "X-Custom-Key",
	})
	assert.NoError(t, err)
	assert.NotNil(t, provider)
	assert.Equal(t, "api_key", provider.Name())
}

func TestOAuth2Provider_NonStandardFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"code": 200,
			"data": {
				"is_valid": true,
				"owner": "user123",
				"user_identifier": "u_456",
				"authorities": "read write",
				"client_id": "test-client"
			}
		}`))
	}))
	defer server.Close()

	provider := &OAuth2Provider{}

	cfg := &models.OAuth2Config{
		IntrospectionURL: server.URL,
		ClientID:         "client123",
		ClientSecret:     "secret123",
		Headers: map[string]string{
			"X-Custom-Response-Format": "non-standard",
		},
	}

	err := provider.Configure(cfg)
	assert.NoError(t, err)

	req := httptest.NewRequest("GET", "/test", nil)
	req.Header.Set("Authorization", "Bearer test-token-123")

	result, err := provider.Validate(context.Background(), req)
	assert.NoError(t, err)
	assert.NotNil(t, result)
	assert.True(t, result.Authenticated)
	assert.Equal(t, "u_456", result.Subject)
	assert.Contains(t, result.Permissions, "read")
	assert.Contains(t, result.Permissions, "write")
}

func TestOAuth2Provider_StandardFormat(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"active": true,
			"sub": "user789",
			"scope": "read write admin",
			"client_id": "test-client",
			"exp": 1900000000
		}`))
	}))
	defer server.Close()

	provider := &OAuth2Provider{}

	cfg := &models.OAuth2Config{
		IntrospectionURL: server.URL,
		ClientID:         "client123",
		ClientSecret:     "secret123",
	}

	err := provider.Configure(cfg)
	assert.NoError(t, err)

	req := httptest.NewRequest("GET", "/test", nil)
	req.Header.Set("Authorization", "Bearer test-token-456")

	result, err := provider.Validate(context.Background(), req)
	assert.NoError(t, err)
	assert.NotNil(t, result)
	assert.True(t, result.Authenticated)
	assert.Equal(t, "user789", result.Subject)
	assert.Contains(t, result.Permissions, "read")
	assert.Contains(t, result.Permissions, "admin")
}
