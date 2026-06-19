package testutil

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/blevesearch/bleve/v2"
	"github.com/enterprise/knowledgebase/internal/config"
	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/search"
	goredis "github.com/redis/go-redis/v9"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
	"github.com/testcontainers/testcontainers-go"
	tcminio "github.com/testcontainers/testcontainers-go/modules/minio"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	tcredis "github.com/testcontainers/testcontainers-go/modules/redis"
	"github.com/testcontainers/testcontainers-go/wait"
	gormpg "gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type TestInfrastructure struct {
	PostgresContainer  *tcpostgres.PostgresContainer
	RedisContainer     *tcredis.RedisContainer
	MinioContainer     *tcminio.MinioContainer
	DB                 *gorm.DB
	RedisClient        *goredis.Client
	MinioClient        *minio.Client
	BlevePath          string
	BleveIndex         bleve.Index
	Config             *config.Config
	Ctx                context.Context
	Cancel             context.CancelFunc
	postgresConnection string
	redisURI           string
	minioEndpoint      string
}

func NewTestInfrastructure() (*TestInfrastructure, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)

	infra := &TestInfrastructure{
		Ctx:    ctx,
		Cancel: cancel,
	}

	if err := infra.startPostgres(); err != nil {
		cancel()
		return nil, fmt.Errorf("start postgres: %w", err)
	}

	if err := infra.startRedis(); err != nil {
		cancel()
		return nil, fmt.Errorf("start redis: %w", err)
	}

	if err := infra.startMinio(); err != nil {
		cancel()
		return nil, fmt.Errorf("start minio: %w", err)
	}

	if err := infra.initBleve(); err != nil {
		cancel()
		return nil, fmt.Errorf("init bleve: %w", err)
	}

	if err := infra.migrateDB(); err != nil {
		cancel()
		return nil, fmt.Errorf("migrate db: %w", err)
	}

	return infra, nil
}

func (ti *TestInfrastructure) startPostgres() error {
	pgContainer, err := tcpostgres.Run(ti.Ctx,
		"postgres:16-alpine",
		tcpostgres.WithDatabase("test_kb"),
		tcpostgres.WithUsername("test"),
		tcpostgres.WithPassword("test"),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").
				WithOccurrence(2).
				WithStartupTimeout(30*time.Second),
		),
	)
	if err != nil {
		return err
	}
	ti.PostgresContainer = pgContainer

	connStr, err := pgContainer.ConnectionString(ti.Ctx, "sslmode=disable")
	if err != nil {
		return err
	}
	ti.postgresConnection = connStr

	db, err := gorm.Open(gormpg.Open(connStr), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Warn),
	})
	if err != nil {
		return err
	}
	ti.DB = db

	sqlDB, err := db.DB()
	if err != nil {
		return err
	}
	sqlDB.SetMaxOpenConns(20)
	sqlDB.SetMaxIdleConns(5)

	return nil
}

func (ti *TestInfrastructure) startRedis() error {
	redisContainer, err := tcredis.Run(ti.Ctx,
		"redis:7-alpine",
		testcontainers.WithWaitStrategy(
			wait.ForLog("Ready to accept connections").
				WithStartupTimeout(30*time.Second),
		),
	)
	if err != nil {
		return err
	}
	ti.RedisContainer = redisContainer

	uri, err := redisContainer.ConnectionString(ti.Ctx)
	if err != nil {
		return err
	}
	ti.redisURI = uri

	opts, err := goredis.ParseURL(uri)
	if err != nil {
		return err
	}
	ti.RedisClient = goredis.NewClient(opts)

	ctx, cancel := context.WithTimeout(ti.Ctx, 5*time.Second)
	defer cancel()
	if err := ti.RedisClient.Ping(ctx).Err(); err != nil {
		return err
	}

	return nil
}

func (ti *TestInfrastructure) startMinio() error {
	minioContainer, err := tcminio.Run(ti.Ctx,
		"minio/minio:latest",
		tcminio.WithUsername("minioadmin"),
		tcminio.WithPassword("minioadmin"),
		testcontainers.WithWaitStrategy(
			wait.ForHTTP("/minio/health/ready").
				WithPort("9000/tcp").
				WithStartupTimeout(30*time.Second),
		),
	)
	if err != nil {
		return err
	}
	ti.MinioContainer = minioContainer

	endpoint, err := minioContainer.ConnectionString(ti.Ctx)
	if err != nil {
		return err
	}
	ti.minioEndpoint = endpoint

	mc, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4("minioadmin", "minioadmin", ""),
		Secure: false,
	})
	if err != nil {
		return err
	}
	ti.MinioClient = mc

	bucketName := "test-knowledgebase"
	ctx, cancel := context.WithTimeout(ti.Ctx, 10*time.Second)
	defer cancel()

	exists, err := mc.BucketExists(ctx, bucketName)
	if err != nil {
		return err
	}
	if !exists {
		if err := mc.MakeBucket(ctx, bucketName, minio.MakeBucketOptions{}); err != nil {
			return err
		}
	}

	return nil
}

func (ti *TestInfrastructure) initBleve() error {
	tmpDir, err := os.MkdirTemp("", "bleve-test-*")
	if err != nil {
		return err
	}
	ti.BlevePath = tmpDir

	_ = search.RegisterCustomAnalyzers()
	mapping := search.BuildDocumentMapping()
	idxPath := filepath.Join(tmpDir, "test-index")

	idx, err := bleve.New(idxPath, mapping)
	if err != nil {
		return err
	}
	ti.BleveIndex = idx

	ti.Config = &config.Config{
		Bleve: config.BleveConfig{IndexPath: tmpDir},
	}

	return nil
}

func (ti *TestInfrastructure) migrateDB() error {
	models := []interface{}{
		&model.Tenant{},
		&model.Quota{},
		&model.Theme{},
		&model.Space{},
		&model.User{},
		&model.UserGroup{},
		&model.UserGroupMember{},
		&model.Department{},
		&model.Directory{},
		&model.Document{},
		&model.DocumentVersion{},

		&model.Attachment{},
		&model.Permission{},
		&model.ApiToken{},
		&model.I18nDoc{},
		&model.TranslationMemory{},
	}
	for _, m := range models {
		if err := ti.DB.AutoMigrate(m); err != nil {
			return fmt.Errorf("migrate %T: %w", m, err)
		}
	}
	return nil
}

func (ti *TestInfrastructure) Cleanup() {
	ti.Cancel()

	if ti.BleveIndex != nil {
		_ = ti.BleveIndex.Close()
	}
	if ti.BlevePath != "" {
		_ = os.RemoveAll(ti.BlevePath)
	}

	if ti.RedisClient != nil {
		_ = ti.RedisClient.Close()
	}

	ctx := context.Background()
	if ti.PostgresContainer != nil {
		_ = ti.PostgresContainer.Terminate(ctx)
	}
	if ti.RedisContainer != nil {
		_ = ti.RedisContainer.Terminate(ctx)
	}
	if ti.MinioContainer != nil {
		_ = ti.MinioContainer.Terminate(ctx)
	}
}

func (ti *TestInfrastructure) ClearDB() error {
	tables := []string{
		"translation_memories",
		"i18n_docs",
		"api_tokens",
		"permissions",
		"attachments",
		"document_templates",
		"document_versions",
		"documents",
		"directories",
		"departments",
		"user_group_members",
		"user_groups",
		"users",
		"spaces",
		"themes",
		"quotas",
		"tenants",
	}
	for _, t := range tables {
		if err := ti.DB.Exec(fmt.Sprintf("TRUNCATE TABLE %s CASCADE", t)).Error; err != nil {
			return err
		}
	}
	return nil
}

func (ti *TestInfrastructure) ClearRedis() error {
	ctx, cancel := context.WithTimeout(ti.Ctx, 5*time.Second)
	defer cancel()
	return ti.RedisClient.FlushAll(ctx).Err()
}

func (ti *TestInfrastructure) TenantContext(tenantID string) context.Context {
	return database.WithTenant(ti.Ctx, tenantID)
}
