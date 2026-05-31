package database

import (
	"context"
	"fmt"
	"log"
	"time"

	"loglevelplatform/internal/common/config"
	"loglevelplatform/internal/common/models"

	"github.com/go-redis/redis/v8"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

var (
	db          *gorm.DB
	redisClient *redis.Client
)

func Init(cfg *config.DatabaseConfig) error {
	dsn := fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		cfg.Host, cfg.Port, cfg.User, cfg.Password, cfg.DBName, cfg.SSLMode)

	var err error
	db, err = gorm.Open(postgres.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Info),
	})
	if err != nil {
		return fmt.Errorf("failed to connect database: %w", err)
	}

	sqlDB, err := db.DB()
	if err != nil {
		return fmt.Errorf("failed to get sql DB: %w", err)
	}

	sqlDB.SetMaxOpenConns(cfg.PoolSize)
	sqlDB.SetMaxIdleConns(cfg.PoolSize / 2)
	sqlDB.SetConnMaxLifetime(time.Hour)

	if err := autoMigrate(); err != nil {
		log.Printf("Warning: auto migration failed: %v", err)
	}

	return nil
}

func autoMigrate() error {
	return db.AutoMigrate(
		&models.CoreEntity{},
		&models.ConfigDefinition{},
		&models.RunInstance{},
		&models.StatsSnapshot{},
		&models.LogLevelConfig{},
		&models.NotificationRecord{},
		&models.ScheduledTask{},
		&models.TaskExecution{},
		&models.AnomalyDetectionResult{},
		&models.CacheEntry{},
	)
}

func GetDB() *gorm.DB {
	return db
}

func WithTransaction(fn func(tx *gorm.DB) error) error {
	return db.Transaction(fn)
}

func InitRedis(cfg *config.RedisConfig) error {
	redisClient = redis.NewClient(&redis.Options{
		Addr:     fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Password: cfg.Password,
		DB:       cfg.DB,
		PoolSize: cfg.PoolSize,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := redisClient.Ping(ctx).Err(); err != nil {
		return fmt.Errorf("failed to connect redis: %w", err)
	}

	return nil
}

func GetRedis() *redis.Client {
	return redisClient
}

type OptimisticLock struct {
	Version int `gorm:"column:version;default:0"`
}

func (o *OptimisticLock) Increment() {
	o.Version++
}
