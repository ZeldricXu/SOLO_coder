package secret

import (
	"context"
	"fmt"
	"os"
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

type SecretManager struct {
	cfg        *config.VaultConfig
	vaultClient *api.Client
	db         *gorm.DB
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
		cfg:        cfg,
		vaultClient: client,
		db:         storage.GetDB(),
	}

	if err := sm.checkVaultConnection(); err != nil {
		logger.Warn("vault connection check failed", zap.Error(err))
	}

	logger.Info("secret manager initialized successfully")
	return sm, nil
}

func (sm *SecretManager) checkVaultConnection() error {
	_, err := sm.vaultClient.Sys().Health()
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
		return sm.db.WithContext(ctx).Model(secret).Updates(map[string]interface{}{
			"version":    version,
			"rotated_at": &now,
			"updated_at": now,
		}).Error
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

func (sm *SecretManager) getAccessedBy(ctx context.Context) string {
	type ctxKey string
	const userKey ctxKey = "user"

	if user, ok := ctx.Value(userKey).(string); ok {
		return user
	}

	return "system"
}
