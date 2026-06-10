package testcontainers

import (
	"context"
	"fmt"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
	"github.com/redis/go-redis/v9"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/minio"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/modules/redis"
	"github.com/testcontainers/testcontainers-go/modules/vault"
	pgdriver "gorm.io/driver/postgres"
	"gorm.io/gorm"

	vaultapi "github.com/hashicorp/vault/api"
)

type TestInfrastructure struct {
	PostgresContainer *postgres.PostgresContainer
	RedisContainer    *redis.RedisContainer
	MinioContainer    *minio.MinioContainer
	VaultContainer    *vault.VaultContainer

	PostgresConfig *config.DatabaseConfig
	RedisConfig    *config.RedisConfig
	MinioConfig    *config.MinIOConfig
	VaultConfig    *config.VaultConfig

	DB    *gorm.DB
	Redis *redis.Client
	Minio *minio.Client
	Vault *vaultapi.Client
}

func SetupInfrastructure(ctx context.Context) (*TestInfrastructure, error) {
	infra := &TestInfrastructure{}

	pgContainer, err := postgres.Run(ctx,
		"postgres:16-alpine",
		postgres.WithDatabase("cloudci_test"),
		postgres.WithUsername("test"),
		postgres.WithPassword("test"),
		postgres.WithInitScripts("../../migrations/001_initial_schema.sql"),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to start postgres: %w", err)
	}
	infra.PostgresContainer = pgContainer

	pgHost, err := pgContainer.Host(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to get postgres host: %w", err)
	}
	pgPort, err := pgContainer.MappedPort(ctx, "5432")
	if err != nil {
		return nil, fmt.Errorf("failed to get postgres port: %w", err)
	}

	infra.PostgresConfig = &config.DatabaseConfig{
		Host:     pgHost,
		Port:     pgPort.Int(),
		User:     "test",
		Password: "test",
		Name:     "cloudci_test",
		SSLMode:  "disable",
	}

	dsn := fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=disable",
		infra.PostgresConfig.Host, infra.PostgresConfig.Port,
		infra.PostgresConfig.User, infra.PostgresConfig.Password, infra.PostgresConfig.Name)

	infra.DB, err = gorm.Open(pgdriver.Open(dsn), &gorm.Config{})
	if err != nil {
		return nil, fmt.Errorf("failed to connect to postgres: %w", err)
	}

	redisContainer, err := redis.Run(ctx,
		"redis:7-alpine",
		redis.WithLogLevel(redis.LogLevelVerbose),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to start redis: %w", err)
	}
	infra.RedisContainer = redisContainer

	redisHost, err := redisContainer.Host(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to get redis host: %w", err)
	}
	redisPort, err := redisContainer.MappedPort(ctx, "6379")
	if err != nil {
		return nil, fmt.Errorf("failed to get redis port: %w", err)
	}

	infra.RedisConfig = &config.RedisConfig{
		Host: redisHost,
		Port: redisPort.Int(),
		DB:   0,
	}

	infra.Redis = redis.NewClient(&redis.Options{
		Addr: fmt.Sprintf("%s:%d", infra.RedisConfig.Host, infra.RedisConfig.Port),
		DB:   infra.RedisConfig.DB,
	})

	minioContainer, err := minio.Run(ctx,
		"minio/minio:latest",
		minio.WithUsername("minioadmin"),
		minio.WithPassword("minioadmin"),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to start minio: %w", err)
	}
	infra.MinioContainer = minioContainer

	minioHost, err := minioContainer.Host(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to get minio host: %w", err)
	}
	minioPort, err := minioContainer.MappedPort(ctx, "9000")
	if err != nil {
		return nil, fmt.Errorf("failed to get minio port: %w", err)
	}

	infra.MinioConfig = &config.MinIOConfig{
		Endpoint:  fmt.Sprintf("%s:%d", minioHost, minioPort.Int()),
		AccessKey: "minioadmin",
		SecretKey: "minioadmin",
		Bucket:    "test-artifacts",
		Secure:    false,
	}

	infra.Minio, err = minio.New(infra.MinioConfig.Endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(infra.MinioConfig.AccessKey, infra.MinioConfig.SecretKey, ""),
		Secure: infra.MinioConfig.Secure,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to create minio client: %w", err)
	}

	err = infra.Minio.MakeBucket(ctx, infra.MinioConfig.Bucket, minio.MakeBucketOptions{})
	if err != nil {
		return nil, fmt.Errorf("failed to create minio bucket: %w", err)
	}

	vaultContainer, err := vault.Run(ctx,
		"hashicorp/vault:1.15",
		vault.WithToken("root-token"),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to start vault: %w", err)
	}
	infra.VaultContainer = vaultContainer

	vaultHost, err := vaultContainer.Host(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to get vault host: %w", err)
	}
	vaultPort, err := vaultContainer.MappedPort(ctx, "8200")
	if err != nil {
		return nil, fmt.Errorf("failed to get vault port: %w", err)
	}

	vaultAddr := fmt.Sprintf("http://%s:%d", vaultHost, vaultPort.Int())
	infra.VaultConfig = &config.VaultConfig{
		Addr:       vaultAddr,
		Token:      "root-token",
		SecretPath: "secret/data",
	}

	vaultConfig := vaultapi.DefaultConfig()
	vaultConfig.Address = vaultAddr
	infra.Vault, err = vaultapi.NewClient(vaultConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create vault client: %w", err)
	}
	infra.Vault.SetToken("root-token")

	kvConfig := map[string]interface{}{
		"type":        "kv",
		"description": "test KV store",
		"options": map[string]interface{}{
			"version": "2",
		},
	}
	err = infra.Vault.Sys().Mount("secret", &vaultapi.MountInput{
		Type:        "kv",
		Description: "test KV store",
		Options: map[string]string{
			"version": "2",
		},
	})
	if err != nil {
		return nil, fmt.Errorf("failed to mount kv engine: %w", err)
	}

	_ = kvConfig

	return infra, nil
}

func (infra *TestInfrastructure) Cleanup(ctx context.Context) {
	timeout := 10 * time.Second

	if infra.PostgresContainer != nil {
		_ = infra.PostgresContainer.Stop(ctx, &timeout)
	}
	if infra.RedisContainer != nil {
		_ = infra.RedisContainer.Stop(ctx, &timeout)
	}
	if infra.MinioContainer != nil {
		_ = infra.MinioContainer.Stop(ctx, &timeout)
	}
	if infra.VaultContainer != nil {
		_ = infra.VaultContainer.Stop(ctx, &timeout)
	}
}

func (infra *TestInfrastructure) PutVaultSecret(ctx context.Context, path, key, value string) error {
	data := map[string]interface{}{
		"data": map[string]interface{}{
			key: value,
		},
	}
	_, err := infra.Vault.KVv2("secret").Put(ctx, path, data)
	return err
}

func (infra *TestInfrastructure) GetVaultSecret(ctx context.Context, path, key string) (string, error) {
	secret, err := infra.Vault.KVv2("secret").Get(ctx, path)
	if err != nil {
		return "", err
	}
	if secret == nil || secret.Data == nil {
		return "", fmt.Errorf("secret not found")
	}
	value, ok := secret.Data[key].(string)
	if !ok {
		return "", fmt.Errorf("secret value is not a string")
	}
	return value, nil
}
