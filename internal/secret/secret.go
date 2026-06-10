package secret

import (
	"context"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/hashicorp/vault/api"
	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/logger"
	"github.com/solocoder/cloudci/internal/models"
	"github.com/solocoder/cloudci/internal/storage"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type VaultKVv2 interface {
	Get(ctx context.Context, path string) (*api.KVSecret, error)
}

type VaultClient interface {
	Health() (*api.HealthResponse, error)
	KVv2(mount string) VaultKVv2
}

type DBClient interface {
	WithContext(ctx context.Context) *gorm.DB
	Where(query interface{}, args ...interface{}) *gorm.DB
	First(dest interface{}, conds ...interface{}) *gorm.DB
	Model(value interface{}) *gorm.DB
	Updates(values interface{}) *gorm.DB
	Create(value interface{}) *gorm.DB
}

type vaultClientWrapper struct {
	client *api.Client
}

func (v *vaultClientWrapper) Health() (*api.HealthResponse, error) {
	return v.client.Sys().Health()
}

func (v *vaultClientWrapper) KVv2(mount string) VaultKVv2 {
	return v.client.KVv2(mount)
}

type dbWrapper struct {
	db *gorm.DB
}

func (d *dbWrapper) WithContext(ctx context.Context) *gorm.DB {
	return d.db.WithContext(ctx)
}

func (d *dbWrapper) Where(query interface{}, args ...interface{}) *gorm.DB {
	return d.db.Where(query, args...)
}

func (d *dbWrapper) First(dest interface{}, conds ...interface{}) *gorm.DB {
	return d.db.First(dest, conds...)
}

func (d *dbWrapper) Model(value interface{}) *gorm.DB {
	return d.db.Model(value)
}

func (d *dbWrapper) Updates(values interface{}) *gorm.DB {
	return d.db.Updates(values)
}

func (d *dbWrapper) Create(value interface{}) *gorm.DB {
	return d.db.Create(value)
}

type SecretManager struct {
	cfg         *config.VaultConfig
	vaultClient VaultClient
	db          DBClient
	cache       *secretCache
}

type secretCacheEntry struct {
	value     string
	version   int
	expiresAt time.Time
}

type secretCache struct {
	entries map[string]*secretCacheEntry
	ttl     time.Duration
	mu      sync.RWMutex
}

func NewSecretManagerWithDeps(cfg *config.VaultConfig, vaultClient VaultClient, db DBClient) *SecretManager {
	sm := &SecretManager{
		cfg:         cfg,
		vaultClient: vaultClient,
		db:          db,
	}
	sm.initCache()
	return sm
}

func NewSecretManager(cfg *config.VaultConfig) (*SecretManager, error) {
	logger.Info("initializing secret manager",
		zap.String("vault_addr", cfg.Addr),
		zap.String("secret_path", cfg.SecretPath),
	)

	vaultCfg := api.DefaultConfig()
	vaultCfg.Address = cfg.Addr

	client, err := api.NewClient(vaultCfg)
	if err != nil {
		return nil, fmt.Errorf("failed to create vault client: %w", err)
	}

	client.SetToken(cfg.Token)

	sm := &SecretManager{
		cfg:         cfg,
		vaultClient: &vaultClientWrapper{client: client},
		db:          &dbWrapper{db: storage.GetDB()},
	}
	sm.initCache()

	if err := sm.checkVaultConnection(); err != nil {
		logger.Warn("vault connection check failed", zap.Error(err))
	}

	logger.Info("secret manager initialized successfully")
	return sm, nil
}

func (sm *SecretManager) initCache() {
	ttl := 5 * time.Minute
	if sm.cfg.CacheTTL > 0 {
		ttl = sm.cfg.CacheTTL
	}

	sm.cache = &secretCache{
		entries: make(map[string]*secretCacheEntry),
		ttl:     ttl,
	}

	go sm.cache.startCleanupLoop()
}

func (c *secretCache) startCleanupLoop() {
	ticker := time.NewTicker(c.ttl / 2)
	defer ticker.Stop()

	for range ticker.C {
		c.cleanupExpired()
	}
}

func (c *secretCache) cleanupExpired() {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now()
	for key, entry := range c.entries {
		if now.After(entry.expiresAt) {
			delete(c.entries, key)
		}
	}
}

func (c *secretCache) get(key string) (string, int, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	entry, exists := c.entries[key]
	if !exists {
		return "", 0, false
	}

	if time.Now().After(entry.expiresAt) {
		return "", 0, false
	}

	return entry.value, entry.version, true
}

func (c *secretCache) set(key string, value string, version int) {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.entries[key] = &secretCacheEntry{
		value:     value,
		version:   version,
		expiresAt: time.Now().Add(c.ttl),
	}
}

func (c *secretCache) invalidate(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	delete(c.entries, key)
}

func (sm *SecretManager) cacheKey(secret *models.Secret) string {
	return fmt.Sprintf("%s:%s", secret.VaultPath, secret.VaultKey)
}

func (sm *SecretManager) checkVaultConnection() error {
	_, err := sm.vaultClient.Health()
	return err
}

func (sm *SecretManager) ResolveSecrets(
	ctx context.Context,
	pipelineID, executionID, stageName string,
	secretNames []string,
) (map[string]string, error) {
	result := make(map[string]string)
	now := time.Now()

	for _, secretName := range secretNames {
		secret, err := sm.getSecretMetadata(ctx, secretName)
		if err != nil {
			sm.logUsage(ctx, pipelineID, executionID, stageName, secretName, "", false, fmt.Sprintf("secret not found: %v", err))
			return nil, fmt.Errorf("secret %s not found: %w", secretName, err)
		}

		if err := sm.checkSecretExpired(secret, now); err != nil {
			sm.logUsage(ctx, pipelineID, executionID, stageName, secretName, string(secret.ID), false, err.Error())
			return nil, err
		}

		secretValue, version, err := sm.readFromVault(ctx, secret)
		if err != nil {
			sm.logUsage(ctx, pipelineID, executionID, stageName, secretName, string(secret.ID), false, fmt.Sprintf("vault read failed: %v", err))
			return nil, fmt.Errorf("failed to read secret %s from vault: %w", secretName, err)
		}

		envVarName := secret.EnvVarName
		if envVarName == "" {
			envVarName = secretName
		}

		if err := os.Setenv(envVarName, secretValue); err != nil {
			sm.logUsage(ctx, pipelineID, executionID, stageName, secretName, string(secret.ID), false, fmt.Sprintf("env set failed: %v", err))
			return nil, fmt.Errorf("failed to set env var %s: %w", envVarName, err)
		}

		result[secretName] = secretValue

		if err := sm.updateSecretVersion(ctx, secret, version); err != nil {
			logger.Warn("failed to update secret version",
				zap.String("secret", secretName),
				zap.Error(err))
		}

		sm.logUsage(ctx, pipelineID, executionID, stageName, secretName, string(secret.ID), true, "")

		logger.Debug("secret resolved successfully",
			zap.String("secret", secretName),
			zap.String("env_var", envVarName),
			zap.Int("version", version))
	}

	return result, nil
}

func (sm *SecretManager) getSecretMetadata(ctx context.Context, secretName string) (*models.Secret, error) {
	var secret models.Secret
	err := sm.db.WithContext(ctx).Where("name = ?", secretName).First(&secret).Error
	if err != nil {
		return nil, err
	}
	return &secret, nil
}

func (sm *SecretManager) checkSecretExpired(secret *models.Secret, now time.Time) error {
	if secret.ExpiresAt != nil && now.After(*secret.ExpiresAt) {
		return fmt.Errorf("secret %s has expired at %s", secret.Name, secret.ExpiresAt.Format(time.RFC3339))
	}
	return nil
}

func (sm *SecretManager) readFromVault(ctx context.Context, secret *models.Secret) (string, int, error) {
	cacheKey := sm.cacheKey(secret)
	if sm.cache != nil {
		if value, version, ok := sm.cache.get(cacheKey); ok {
			logger.Debug("cache hit for secret",
				zap.String("secret", secret.Name),
				zap.String("cache_key", cacheKey))
			return value, version, nil
		}
		logger.Debug("cache miss for secret",
			zap.String("secret", secret.Name),
			zap.String("cache_key", cacheKey))
	}

	value, version, err := sm.fetchFromVault(ctx, secret)
	if err != nil {
		return "", 0, err
	}

	if sm.cache != nil {
		sm.cache.set(cacheKey, value, version)
		logger.Debug("cache set for secret",
			zap.String("secret", secret.Name),
			zap.String("cache_key", cacheKey))
	}

	return value, version, nil
}

func (sm *SecretManager) fetchFromVault(ctx context.Context, secret *models.Secret) (string, int, error) {
	vaultPath := secret.VaultPath
	if vaultPath == "" {
		vaultPath = fmt.Sprintf("%s/%s", sm.cfg.SecretPath, secret.Name)
	}

	vaultKey := secret.VaultKey
	if vaultKey == "" {
		vaultKey = "value"
	}

	secretData, err := sm.vaultClient.KVv2("secret").Get(ctx, vaultPath)
	if err != nil {
		return "", 0, err
	}

	if secretData == nil || secretData.Data == nil {
		return "", 0, fmt.Errorf("secret data is nil")
	}

	value, ok := secretData.Data[vaultKey].(string)
	if !ok {
		return "", 0, fmt.Errorf("key %s not found in vault secret", vaultKey)
	}

	return value, 1, nil
}

func (sm *SecretManager) updateSecretVersion(ctx context.Context, secret *models.Secret, version int) error {
	if secret.Version != version {
		now := time.Now()
		err := sm.db.WithContext(ctx).Model(secret).Updates(map[string]interface{}{
			"version":    version,
			"rotated_at": &now,
			"updated_at": now,
		}).Error
		if err == nil && sm.cache != nil {
			sm.cache.invalidate(sm.cacheKey(secret))
			logger.Debug("cache invalidated for secret",
				zap.String("secret", secret.Name))
		}
		return err
	}
	return nil
}

func (sm *SecretManager) logUsage(
	ctx context.Context,
	pipelineID, executionID, stageName, secretName, secretID string,
	success bool,
	reason string,
) {
	log := &models.SecretUsageLog{
		ID:          types.NewID(),
		SecretName:  secretName,
		ExecutionID: types.ID(executionID),
		PipelineID:  types.ID(pipelineID),
		StageName:   stageName,
		RequestedBy: sm.getAccessedBy(ctx),
		Success:     success,
		Reason:      reason,
	}

	if secretID != "" {
		log.SecretID = types.ID(secretID)
	}

	if err := sm.db.WithContext(ctx).Create(log).Error; err != nil {
		logger.Error("failed to record secret usage log",
			zap.String("secret", secretName),
			zap.Error(err))
	}
}

type ctxKey string

const UserContextKey ctxKey = "user"

func (sm *SecretManager) getAccessedBy(ctx context.Context) string {
	if user, ok := ctx.Value(UserContextKey).(string); ok {
		return user
	}

	return "system"
}
