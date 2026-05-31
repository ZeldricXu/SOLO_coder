package data

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/edgeplatform/session306/internal/model"
	"github.com/edgeplatform/session306/pkg/errors"
	"github.com/edgeplatform/session306/pkg/utils"

	"github.com/go-redis/redis/v8"
	"go.uber.org/zap"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type DatabaseConfig struct {
	Host            string
	Port            int
	User            string
	Password        string
	DBName          string
	SSLMode         string
	MaxOpenConns    int
	MaxIdleConns    int
	ConnMaxLifetime time.Duration
	ConnMaxIdleTime time.Duration
}

type RedisConfig struct {
	Host         string
	Port         int
	Password     string
	DB           int
	PoolSize     int
	MinIdleConns int
	DialTimeout  time.Duration
	ReadTimeout  time.Duration
	WriteTimeout time.Duration
}

type DataAccess struct {
	db     *gorm.DB
	rdb    *redis.Client
	logger *zap.Logger
	mu     sync.RWMutex
}

func NewDataAccess(dbConfig DatabaseConfig, redisConfig RedisConfig, log *zap.Logger) (*DataAccess, error) {
	da := &DataAccess{
		logger: log,
	}

	if err := da.initPostgres(dbConfig); err != nil {
		return nil, err
	}

	if err := da.initRedis(redisConfig); err != nil {
		return nil, err
	}

	if err := da.autoMigrate(); err != nil {
		return nil, err
	}

	return da, nil
}

func (da *DataAccess) initPostgres(cfg DatabaseConfig) error {
	dsn := fmt.Sprintf(
		"host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		cfg.Host, cfg.Port, cfg.User, cfg.Password, cfg.DBName, cfg.SSLMode,
	)

	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Info),
		PrepareStmt: true,
	})
	if err != nil {
		return errors.NewInternalError("failed to connect to database", err)
	}

	sqlDB, err := db.DB()
	if err != nil {
		return errors.NewInternalError("failed to get SQL DB", err)
	}

	maxOpen := cfg.MaxOpenConns
	if maxOpen <= 0 {
		maxOpen = 100
	}
	maxIdle := cfg.MaxIdleConns
	if maxIdle <= 0 {
		maxIdle = 20
	}
	connMaxLifetime := cfg.ConnMaxLifetime
	if connMaxLifetime <= 0 {
		connMaxLifetime = time.Hour
	}
	connMaxIdleTime := cfg.ConnMaxIdleTime
	if connMaxIdleTime <= 0 {
		connMaxIdleTime = 10 * time.Minute
	}

	sqlDB.SetMaxOpenConns(maxOpen)
	sqlDB.SetMaxIdleConns(maxIdle)
	sqlDB.SetConnMaxLifetime(connMaxLifetime)
	sqlDB.SetConnMaxIdleTime(connMaxIdleTime)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := sqlDB.PingContext(ctx); err != nil {
		return errors.NewInternalError("failed to ping database", err)
	}

	da.db = db
	da.logger.Info("PostgreSQL connection established",
		zap.Int("max_open_conns", maxOpen),
		zap.Int("max_idle_conns", maxIdle),
	)
	return nil
}

func (da *DataAccess) initRedis(cfg RedisConfig) error {
	poolSize := cfg.PoolSize
	if poolSize <= 0 {
		poolSize = 50
	}
	minIdle := cfg.MinIdleConns
	if minIdle <= 0 {
		minIdle = 10
	}

	rdb := redis.NewClient(&redis.Options{
		Addr:         fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Password:     cfg.Password,
		DB:           cfg.DB,
		PoolSize:     poolSize,
		MinIdleConns: minIdle,
		DialTimeout:  cfg.DialTimeout,
		ReadTimeout:  cfg.ReadTimeout,
		WriteTimeout: cfg.WriteTimeout,
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := rdb.Ping(ctx).Err(); err != nil {
		return errors.NewInternalError("failed to connect to Redis", err)
	}

	da.rdb = rdb
	da.logger.Info("Redis connection established",
		zap.Int("pool_size", poolSize),
		zap.Int("min_idle_conns", minIdle),
	)
	return nil
}

func (da *DataAccess) autoMigrate() error {
	err := da.db.AutoMigrate(
		&model.Entity{},
		&model.ConfigDefinition{},
		&model.RunInstance{},
		&model.MetricSnapshot{},
		&model.Device{},
		&model.Rule{},
		&model.AIModel{},
		&model.InferenceTask{},
		&model.Firmware{},
		&model.OTAJob{},
		&model.DeviceUpgrade{},
		&model.FileRecord{},
	)
	if err != nil {
		return errors.NewInternalError("failed to auto-migrate database schema", err)
	}
	da.logger.Info("Database migration completed successfully")
	return nil
}

func (da *DataAccess) DB() *gorm.DB {
	return da.db
}

func (da *DataAccess) SetDB(db *gorm.DB) {
	da.mu.Lock()
	defer da.mu.Unlock()
	da.db = db
}

func (da *DataAccess) Redis() *redis.Client {
	return da.rdb
}

func (da *DataAccess) Close() error {
	if da.db != nil {
		sqlDB, err := da.db.DB()
		if err == nil {
			sqlDB.Close()
		}
	}
	if da.rdb != nil {
		da.rdb.Close()
	}
	da.logger.Info("Data access connections closed")
	return nil
}

func (da *DataAccess) WithTransaction(ctx context.Context, fn func(tx *gorm.DB) error) error {
	return da.db.WithContext(ctx).Transaction(fn)
}

func (da *DataAccess) CacheGet(ctx context.Context, key string) (string, error) {
	if da.rdb == nil {
		return "", errors.NewInternalError("redis client not initialized", nil)
	}
	return da.rdb.Get(ctx, key).Result()
}

func (da *DataAccess) CacheSet(ctx context.Context, key string, value interface{}, expiration time.Duration) error {
	if da.rdb == nil {
		return errors.NewInternalError("redis client not initialized", nil)
	}
	return da.rdb.Set(ctx, key, value, expiration).Err()
}

func (da *DataAccess) CacheDelete(ctx context.Context, keys ...string) error {
	if da.rdb == nil {
		return errors.NewInternalError("redis client not initialized", nil)
	}
	return da.rdb.Del(ctx, keys...).Err()
}

func (da *DataAccess) CacheSetNX(ctx context.Context, key string, value interface{}, expiration time.Duration) (bool, error) {
	if da.rdb == nil {
		return false, errors.NewInternalError("redis client not initialized", nil)
	}
	return da.rdb.SetNX(ctx, key, value, expiration).Result()
}

func (da *DataAccess) OptimisticLock(ctx context.Context, key string, ttl time.Duration, fn func() error) error {
	if da.rdb == nil {
		return errors.NewInternalError("redis client not initialized", nil)
	}

	lockKey := fmt.Sprintf("lock:%s", key)
	lockValue := utils.GenerateID("lock")

	acquired, err := da.rdb.SetNX(ctx, lockKey, lockValue, ttl).Result()
	if err != nil {
		return errors.NewInternalError("failed to acquire lock", err)
	}
	if !acquired {
		return errors.NewConflictError("resource is locked by another process")
	}
	defer func() {
		script := `
			if redis.call("GET", KEYS[1]) == ARGV[1] then
				return redis.call("DEL", KEYS[1])
			else
				return 0
			end
		`
		da.rdb.Eval(ctx, script, []string{lockKey}, lockValue)
	}()

	return fn()
}

func (da *DataAccess) HealthCheck(ctx context.Context) error {
	if da.db == nil {
		return errors.NewInternalError("database not initialized", nil)
	}
	sqlDB, err := da.db.DB()
	if err != nil {
		return err
	}
	if err := sqlDB.PingContext(ctx); err != nil {
		return err
	}
	if da.rdb != nil {
		if err := da.rdb.Ping(ctx).Err(); err != nil {
			return err
		}
	}
	return nil
}
