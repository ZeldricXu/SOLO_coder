package database

import (
	"context"
	"fmt"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type Config struct {
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

type ConnectionPool struct {
	db     *gorm.DB
	config *Config
	logger *zap.Logger
	mu     sync.RWMutex
}

var (
	instance *ConnectionPool
	once     sync.Once
)

func NewConnectionPool(config *Config, log *zap.Logger) (*ConnectionPool, error) {
	var err error
	once.Do(func() {
		dsn := fmt.Sprintf(
			"host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
			config.Host, config.Port, config.User, config.Password, config.DBName, config.SSLMode,
		)

		var db *gorm.DB
		db, err = gorm.Open(postgres.Open(dsn), &gorm.Config{
			Logger: logger.Default.LogMode(logger.Info),
			PrepareStmt: true,
		})
		if err != nil {
			return
		}

		sqlDB, err := db.DB()
		if err != nil {
			return
		}

		sqlDB.SetMaxOpenConns(config.MaxOpenConns)
		sqlDB.SetMaxIdleConns(config.MaxIdleConns)
		sqlDB.SetConnMaxLifetime(config.ConnMaxLifetime)
		sqlDB.SetConnMaxIdleTime(config.ConnMaxIdleTime)

		instance = &ConnectionPool{
			db:     db,
			config: config,
			logger: log,
		}
	})
	return instance, err
}

func GetDB() *gorm.DB {
	if instance == nil {
		panic("database connection pool not initialized")
	}
	return instance.db
}

func (p *ConnectionPool) GetDB() *gorm.DB {
	return p.db
}

func (p *ConnectionPool) WithTimeout(ctx context.Context, timeout time.Duration) (*gorm.DB, context.CancelFunc) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	return p.db.WithContext(ctx), cancel
}

func (p *ConnectionPool) Ping() error {
	sqlDB, err := p.db.DB()
	if err != nil {
		return err
	}
	return sqlDB.Ping()
}

func (p *ConnectionPool) Close() error {
	sqlDB, err := p.db.DB()
	if err != nil {
		return err
	}
	return sqlDB.Close()
}

func (p *ConnectionPool) Stats() map[string]interface{} {
	sqlDB, _ := p.db.DB()
	stats := sqlDB.Stats()
	return map[string]interface{}{
		"max_open_connections": stats.MaxOpenConnections,
		"open_connections":     stats.OpenConnections,
		"in_use":               stats.InUse,
		"idle":                 stats.Idle,
		"wait_count":           stats.WaitCount,
		"wait_duration":        stats.WaitDuration,
		"max_idle_closed":      stats.MaxIdleClosed,
		"max_lifetime_closed":  stats.MaxLifetimeClosed,
	}
}

func (p *ConnectionPool) AutoMigrate(models ...interface{}) error {
	return p.db.AutoMigrate(models...)
}
