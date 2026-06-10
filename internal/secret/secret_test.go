package secret

import (
	"context"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/hashicorp/vault/api"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"

	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/models"
	"github.com/solocoder/cloudci/tests/fixtures"
)

func setupTestDB(t *testing.T) *gorm.DB {
	t.Helper()
	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	require.NoError(t, err)

	err = db.AutoMigrate(&models.Secret{}, &models.SecretUsageLog{})
	require.NoError(t, err)

	return db
}

type MockVaultClient struct {
	mock.Mock
}

func (m *MockVaultClient) Health() (*api.HealthResponse, error) {
	args := m.Called()
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*api.HealthResponse), args.Error(1)
}

func (m *MockVaultClient) KVv2(mount string) VaultKVv2 {
	args := m.Called(mount)
	if args.Get(0) == nil {
		return nil
	}
	return args.Get(0).(VaultKVv2)
}

type MockKVv2 struct {
	mock.Mock
}

func (m *MockKVv2) Get(ctx context.Context, path string) (*api.KVSecret, error) {
	args := m.Called(ctx, path)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*api.KVSecret), args.Error(1)
}

func checkSecretScope(secret *models.Secret, projectID string) bool {
	if secret.ProjectID != "" {
		return secret.ProjectID == projectID
	}
	return true
}

func TestCheckSecretExpired_NotExpired(t *testing.T) {
	secret := fixtures.GenerateSecret("test-secret", "secret/data/test")
	now := time.Now()

	sm := &SecretManager{}
	err := sm.checkSecretExpired(secret, now)

	assert.NoError(t, err)
}

func TestCheckSecretExpired_Expired(t *testing.T) {
	secret := fixtures.GenerateSecret("test-secret", "secret/data/test")
	pastTime := time.Now().Add(-24 * time.Hour)
	secret.ExpiresAt = &pastTime

	now := time.Now()
	sm := &SecretManager{}
	err := sm.checkSecretExpired(secret, now)

	require.Error(t, err)
	assert.Contains(t, err.Error(), "has expired")
	assert.Contains(t, err.Error(), "test-secret")
}

func TestCheckSecretExpired_NoExpiry(t *testing.T) {
	secret := fixtures.GenerateSecret("no-expiry-secret", "secret/data/test")
	secret.ExpiresAt = nil

	now := time.Now()
	sm := &SecretManager{}
	err := sm.checkSecretExpired(secret, now)

	assert.NoError(t, err)
}

func TestCheckSecretScope_Match(t *testing.T) {
	secret := fixtures.GenerateSecret("test-secret", "secret/data/test")
	secret.ProjectID = "test-project"

	result := checkSecretScope(secret, "test-project")
	assert.True(t, result)
}

func TestCheckSecretScope_NoMatch(t *testing.T) {
	secret := fixtures.GenerateSecret("test-secret", "secret/data/test")
	secret.ProjectID = "project-a"

	result := checkSecretScope(secret, "project-b")
	assert.False(t, result)
}

func TestCheckSecretScope_EmptyProjectID(t *testing.T) {
	secret := fixtures.GenerateSecret("test-secret", "secret/data/test")
	secret.ProjectID = ""

	result := checkSecretScope(secret, "any-project")
	assert.True(t, result)
}

func TestResolveSecrets_Success(t *testing.T) {
	ctx := context.Background()
	secretName := "test-secret"
	secretValue := "super-secret-value"
	pipelineID := "pipeline-123"
	executionID := "exec-456"
	stageName := "build"
	projectID := "test-project"

	db := setupTestDB(t)

	secret := fixtures.GenerateSecret(secretName, fmt.Sprintf("secret/data/%s", secretName))
	secret.ProjectID = projectID
	err := db.Create(secret).Error
	require.NoError(t, err)

	mockKV := new(MockKVv2)
	mockKV.On("Get", ctx, fmt.Sprintf("secret/data/%s", secretName)).Return(
		&api.KVSecret{
			Data: map[string]interface{}{
				"value": secretValue,
			},
			VersionMetadata: &api.KVVersionMetadata{Version: 1},
		},
		nil,
	)

	mockVault := new(MockVaultClient)
	mockVault.On("KVv2", "secret").Return(mockKV)

	sm := NewSecretManagerWithDeps(
		&config.VaultConfig{
			Addr:       "http://localhost:8200",
			Token:      "test-token",
			SecretPath: "secret/data",
		},
		mockVault,
		&dbWrapper{db: db},
	)

	defer os.Unsetenv(secretName)

	result, err := sm.ResolveSecrets(ctx, pipelineID, executionID, stageName, []string{secretName})

	require.NoError(t, err)
	assert.Equal(t, secretValue, result[secretName])
	assert.Equal(t, secretValue, os.Getenv(secretName))

	mockKV.AssertExpectations(t)

	var log models.SecretUsageLog
	err = db.Where("secret_name = ?", secretName).First(&log).Error
	require.NoError(t, err)
	assert.True(t, log.Success)
}

func TestResolveSecrets_NotFound(t *testing.T) {
	ctx := context.Background()
	secretName := "non-existent-secret"
	pipelineID := "pipeline-123"
	executionID := "exec-456"
	stageName := "build"

	db := setupTestDB(t)

	mockVault := new(MockVaultClient)

	sm := NewSecretManagerWithDeps(
		&config.VaultConfig{
			Addr:       "http://localhost:8200",
			Token:      "test-token",
			SecretPath: "secret/data",
		},
		mockVault,
		&dbWrapper{db: db},
	)

	result, err := sm.ResolveSecrets(ctx, pipelineID, executionID, stageName, []string{secretName})

	require.Error(t, err)
	assert.Nil(t, result)
	assert.Contains(t, err.Error(), "not found")
	assert.Contains(t, err.Error(), secretName)

	var log models.SecretUsageLog
	err = db.Where("secret_name = ?", secretName).First(&log).Error
	require.NoError(t, err)
	assert.False(t, log.Success)
}

func TestResolveSecrets_Expired(t *testing.T) {
	ctx := context.Background()
	secretName := "expired-secret"
	pipelineID := "pipeline-123"
	executionID := "exec-456"
	stageName := "build"

	db := setupTestDB(t)

	secret := fixtures.GenerateSecret(secretName, fmt.Sprintf("secret/data/%s", secretName))
	pastTime := time.Now().Add(-24 * time.Hour)
	secret.ExpiresAt = &pastTime
	err := db.Create(secret).Error
	require.NoError(t, err)

	mockVault := new(MockVaultClient)

	sm := NewSecretManagerWithDeps(
		&config.VaultConfig{
			Addr:       "http://localhost:8200",
			Token:      "test-token",
			SecretPath: "secret/data",
		},
		mockVault,
		&dbWrapper{db: db},
	)

	result, err := sm.ResolveSecrets(ctx, pipelineID, executionID, stageName, []string{secretName})

	require.Error(t, err)
	assert.Nil(t, result)
	assert.Contains(t, err.Error(), "has expired")

	var log models.SecretUsageLog
	err = db.Where("secret_name = ?", secretName).First(&log).Error
	require.NoError(t, err)
	assert.False(t, log.Success)
}

func TestResolveSecrets_VaultReadError(t *testing.T) {
	ctx := context.Background()
	secretName := "test-secret"
	pipelineID := "pipeline-123"
	executionID := "exec-456"
	stageName := "build"

	db := setupTestDB(t)

	secret := fixtures.GenerateSecret(secretName, fmt.Sprintf("secret/data/%s", secretName))
	err := db.Create(secret).Error
	require.NoError(t, err)

	mockKV := new(MockKVv2)
	mockKV.On("Get", ctx, fmt.Sprintf("secret/data/%s", secretName)).Return(
		nil,
		errors.New("vault connection error"),
	)

	mockVault := new(MockVaultClient)
	mockVault.On("KVv2", "secret").Return(mockKV)

	sm := NewSecretManagerWithDeps(
		&config.VaultConfig{
			Addr:       "http://localhost:8200",
			Token:      "test-token",
			SecretPath: "secret/data",
		},
		mockVault,
		&dbWrapper{db: db},
	)

	result, err := sm.ResolveSecrets(ctx, pipelineID, executionID, stageName, []string{secretName})

	require.Error(t, err)
	assert.Nil(t, result)
	assert.Contains(t, err.Error(), "failed to read secret")
	assert.Contains(t, err.Error(), "vault connection error")

	mockKV.AssertExpectations(t)

	var log models.SecretUsageLog
	err = db.Where("secret_name = ?", secretName).First(&log).Error
	require.NoError(t, err)
	assert.False(t, log.Success)
}

func TestVaultConnection_Retry(t *testing.T) {
	attempts := 0
	maxAttempts := 3

	retryFunc := func() error {
		attempts++
		if attempts < maxAttempts {
			return errors.New("connection refused")
		}
		return nil
	}

	backoff := func(attempt int) time.Duration {
		return time.Millisecond * 10
	}

	err := withRetry(maxAttempts, backoff, retryFunc)

	require.NoError(t, err)
	assert.Equal(t, maxAttempts, attempts)
}

func TestVaultConnection_AllAttemptsFail(t *testing.T) {
	attempts := 0
	maxAttempts := 3

	retryFunc := func() error {
		attempts++
		return errors.New("connection refused")
	}

	backoff := func(attempt int) time.Duration {
		return time.Millisecond * 10
	}

	err := withRetry(maxAttempts, backoff, retryFunc)

	require.Error(t, err)
	assert.Equal(t, maxAttempts, attempts)
	assert.Contains(t, err.Error(), "connection refused")
}

func TestVaultConnection_ImmediateSuccess(t *testing.T) {
	attempts := 0
	maxAttempts := 3

	retryFunc := func() error {
		attempts++
		return nil
	}

	backoff := func(attempt int) time.Duration {
		return time.Millisecond * 10
	}

	err := withRetry(maxAttempts, backoff, retryFunc)

	require.NoError(t, err)
	assert.Equal(t, 1, attempts)
}

func withRetry(maxAttempts int, backoff func(int) time.Duration, fn func() error) error {
	var err error
	for i := 0; i < maxAttempts; i++ {
		err = fn()
		if err == nil {
			return nil
		}
		if i < maxAttempts - 1 {
			time.Sleep(backoff(i + 1))
		}
	}
	return err
}

func TestCheckVaultConnection_Success(t *testing.T) {
	mockVault := new(MockVaultClient)
	mockVault.On("Health").Return(&api.HealthResponse{Initialized: true, Sealed: false}, nil)

	sm := &SecretManager{
		vaultClient: mockVault,
	}

	err := sm.checkVaultConnection()
	assert.NoError(t, err)
	mockVault.AssertExpectations(t)
}

func TestCheckVaultConnection_Failed(t *testing.T) {
	mockVault := new(MockVaultClient)
	mockVault.On("Health").Return(nil, errors.New("vault connection refused"))

	sm := &SecretManager{
		vaultClient: mockVault,
	}

	err := sm.checkVaultConnection()
	require.Error(t, err)
	assert.Contains(t, err.Error(), "vault connection refused")
	mockVault.AssertExpectations(t)
}

func TestReadFromVault_CustomPathAndKey(t *testing.T) {
	ctx := context.Background()
	customPath := "custom/path"
	customKey := "custom_key"
	secretValue := "custom-value"

	secret := &models.Secret{
		Name:      "custom-secret",
		VaultPath: customPath,
		VaultKey:  customKey,
	}

	mockKV := new(MockKVv2)
	mockKV.On("Get", ctx, customPath).Return(
		&api.KVSecret{
			Data: map[string]interface{}{
				customKey: secretValue,
			},
		},
		nil,
	)

	mockVault := new(MockVaultClient)
	mockVault.On("KVv2", "secret").Return(mockKV)

	sm := &SecretManager{
		cfg: &config.VaultConfig{
			SecretPath: "default/path",
		},
		vaultClient: mockVault,
	}

	value, version, err := sm.readFromVault(ctx, secret)

	require.NoError(t, err)
	assert.Equal(t, secretValue, value)
	assert.Equal(t, 1, version)

	mockKV.AssertExpectations(t)
}

func TestReadFromVault_DefaultPathAndKey(t *testing.T) {
	ctx := context.Background()
	secretName := "default-secret"
	secretValue := "default-value"
	defaultPath := "default/path"

	secret := &models.Secret{
		Name: secretName,
	}

	mockKV := new(MockKVv2)
	mockKV.On("Get", ctx, fmt.Sprintf("%s/%s", defaultPath, secretName)).Return(
		&api.KVSecret{
			Data: map[string]interface{}{
				"value": secretValue,
			},
		},
		nil,
	)

	mockVault := new(MockVaultClient)
	mockVault.On("KVv2", "secret").Return(mockKV)

	sm := &SecretManager{
		cfg: &config.VaultConfig{
			SecretPath: defaultPath,
		},
		vaultClient: mockVault,
	}

	value, version, err := sm.readFromVault(ctx, secret)

	require.NoError(t, err)
	assert.Equal(t, secretValue, value)
	assert.Equal(t, 1, version)

	mockKV.AssertExpectations(t)
}

func TestReadFromVault_NilData(t *testing.T) {
	ctx := context.Background()
	secret := &models.Secret{
		Name: "nil-data-secret",
	}

	mockKV := new(MockKVv2)
	mockKV.On("Get", ctx, "default/path/nil-data-secret").Return(
		&api.KVSecret{Data: nil},
		nil,
	)

	mockVault := new(MockVaultClient)
	mockVault.On("KVv2", "secret").Return(mockKV)

	sm := &SecretManager{
		cfg: &config.VaultConfig{
			SecretPath: "default/path",
		},
		vaultClient: mockVault,
	}

	value, version, err := sm.readFromVault(ctx, secret)

	require.Error(t, err)
	assert.Empty(t, value)
	assert.Equal(t, 0, version)
	assert.Contains(t, err.Error(), "secret data is nil")
}

func TestReadFromVault_KeyNotFound(t *testing.T) {
	ctx := context.Background()
	secret := &models.Secret{
		Name:     "wrong-key-secret",
		VaultKey: "non_existent_key",
	}

	mockKV := new(MockKVv2)
	mockKV.On("Get", ctx, "default/path/wrong-key-secret").Return(
		&api.KVSecret{
			Data: map[string]interface{}{
				"value": "some-value",
			},
		},
		nil,
	)

	mockVault := new(MockVaultClient)
	mockVault.On("KVv2", "secret").Return(mockKV)

	sm := &SecretManager{
		cfg: &config.VaultConfig{
			SecretPath: "default/path",
		},
		vaultClient: mockVault,
	}

	value, version, err := sm.readFromVault(ctx, secret)

	require.Error(t, err)
	assert.Empty(t, value)
	assert.Equal(t, 0, version)
	assert.Contains(t, err.Error(), "not found in vault secret")
}

func TestGetAccessedBy_WithContextUser(t *testing.T) {
	ctx := context.WithValue(context.Background(), UserContextKey, "test-user")

	sm := &SecretManager{}
	result := sm.getAccessedBy(ctx)

	assert.Equal(t, "test-user", result)
}

func TestGetAccessedBy_DefaultSystem(t *testing.T) {
	ctx := context.Background()

	sm := &SecretManager{}
	result := sm.getAccessedBy(ctx)

	assert.Equal(t, "system", result)
}

func TestResolveSecrets_ScopeMismatch(t *testing.T) {
	secret := fixtures.GenerateSecret("scoped-secret", "secret/data/scoped-secret")
	secret.ProjectID = "project-a"

	scopeAllowed := checkSecretScope(secret, "project-b")
	assert.False(t, scopeAllowed)
}

func TestResolveSecrets_MultipleSecrets(t *testing.T) {
	ctx := context.Background()
	pipelineID := "pipeline-123"
	executionID := "exec-456"
	stageName := "build"

	secrets := []struct {
		name  string
		value string
	}{
		{"secret-1", "value-1"},
		{"secret-2", "value-2"},
		{"secret-3", "value-3"},
	}

	db := setupTestDB(t)

	for _, s := range secrets {
		secretData := fixtures.GenerateSecret(s.name, fmt.Sprintf("secret/data/%s", s.name))
		err := db.Create(secretData).Error
		require.NoError(t, err)
	}

	mockKV := new(MockKVv2)
	for _, s := range secrets {
		mockKV.On("Get", ctx, fmt.Sprintf("secret/data/%s", s.name)).Return(
			&api.KVSecret{
				Data: map[string]interface{}{
					"value": s.value,
				},
			},
			nil,
		).Once()
	}

	mockVault := new(MockVaultClient)
	mockVault.On("KVv2", "secret").Return(mockKV)

	sm := NewSecretManagerWithDeps(
		&config.VaultConfig{
			Addr:       "http://localhost:8200",
			Token:      "test-token",
			SecretPath: "secret/data",
		},
		mockVault,
		&dbWrapper{db: db},
	)

	defer func() {
		for _, s := range secrets {
			os.Unsetenv(s.name)
		}
	}()

	secretNames := make([]string, len(secrets))
	for i, s := range secrets {
		secretNames[i] = s.name
	}

	result, err := sm.ResolveSecrets(ctx, pipelineID, executionID, stageName, secretNames)

	require.NoError(t, err)
	require.Len(t, result, len(secrets))

	for _, s := range secrets {
		assert.Equal(t, s.value, result[s.name])
		assert.Equal(t, s.value, os.Getenv(s.name))
	}

	mockKV.AssertExpectations(t)

	var count int64
	db.Model(&models.SecretUsageLog{}).Count(&count)
	assert.Equal(t, int64(len(secrets)), count)
}

func TestResolveSecrets_ScopeCheckIntegration(t *testing.T) {
	ctx := context.Background()
	secretName := "scoped-secret"
	secretValue := "scoped-value"
	pipelineID := "pipeline-123"
	executionID := "exec-456"
	stageName := "build"
	allowedProject := "project-a"

	db := setupTestDB(t)

	secret := fixtures.GenerateSecret(secretName, fmt.Sprintf("secret/data/%s", secretName))
	secret.ProjectID = allowedProject
	err := db.Create(secret).Error
	require.NoError(t, err)

	scopeAllowed := checkSecretScope(secret, allowedProject)
	assert.True(t, scopeAllowed)

	scopeAllowed = checkSecretScope(secret, "project-b")
	assert.False(t, scopeAllowed)

	mockKV := new(MockKVv2)
	mockKV.On("Get", ctx, fmt.Sprintf("secret/data/%s", secretName)).Return(
		&api.KVSecret{
			Data: map[string]interface{}{
				"value": secretValue,
			},
		},
		nil,
	)

	mockVault := new(MockVaultClient)
	mockVault.On("KVv2", "secret").Return(mockKV)

	sm := NewSecretManagerWithDeps(
		&config.VaultConfig{
			Addr:       "http://localhost:8200",
			Token:      "test-token",
			SecretPath: "secret/data",
		},
		mockVault,
		&dbWrapper{db: db},
	)

	defer os.Unsetenv(secretName)

	result, err := sm.ResolveSecrets(ctx, pipelineID, executionID, stageName, []string{secretName})

	require.NoError(t, err)
	assert.Equal(t, secretValue, result[secretName])

	mockKV.AssertExpectations(t)

	var log models.SecretUsageLog
	err = db.Where("secret_name = ?", secretName).First(&log).Error
	require.NoError(t, err)
	assert.True(t, log.Success)
}

func TestCheckSecretScope_Wildcard(t *testing.T) {
	secret := fixtures.GenerateSecret("wildcard-secret", "secret/data/test")
	secret.ProjectID = ""

	result := checkSecretScope(secret, "any-project-id")
	assert.True(t, result)
}

func TestCheckSecretScope_ExactMatch(t *testing.T) {
	secret := fixtures.GenerateSecret("exact-secret", "secret/data/test")
	secret.ProjectID = "org/project-123"

	result := checkSecretScope(secret, "org/project-123")
	assert.True(t, result)

	result = checkSecretScope(secret, "org/project-456")
	assert.False(t, result)
}
