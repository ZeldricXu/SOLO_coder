package storage

import (
	"fmt"
	"time"

	"github.com/solocoder/cloudci/internal/config"
	applogger "github.com/solocoder/cloudci/internal/logger"
	"go.uber.org/zap"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	gormlogger "gorm.io/gorm/logger"
)

var db *gorm.DB

func InitPostgres(cfg *config.DatabaseConfig) error {
	dsn := cfg.DSN()
	applogger.Info("connecting to postgres",
		zap.String("host", cfg.Host),
		zap.Int("port", cfg.Port),
		zap.String("db", cfg.Name),
	)

	newLogger := gormlogger.New(
		&zapWriter{applogger.L()},
		gormlogger.Config{
			SlowThreshold:             200 * time.Millisecond,
			LogLevel:                  gormlogger.Warn,
			IgnoreRecordNotFoundError: true,
			Colorful:                  false,
		},
	)

	gormDB, err := gorm.Open(postgres.Open(dsn), &gorm.Config{
		Logger: newLogger,
	})
	if err != nil {
		return fmt.Errorf("failed to connect postgres: %w", err)
	}

	sqlDB, err := gormDB.DB()
	if err != nil {
		return fmt.Errorf("failed to get sql db: %w", err)
	}

	sqlDB.SetMaxOpenConns(cfg.MaxOpenConns)
	sqlDB.SetMaxIdleConns(cfg.MaxIdleConns)
	sqlDB.SetConnMaxLifetime(time.Hour)
	sqlDB.SetConnMaxIdleTime(10 * time.Minute)

	if err := sqlDB.Ping(); err != nil {
		return fmt.Errorf("failed to ping postgres: %w", err)
	}

	db = gormDB
	applogger.Info("postgres connected successfully")
	return nil
}

func GetDB() *gorm.DB {
	if db == nil {
		applogger.Fatal("postgres not initialized")
	}
	return db
}

func ClosePostgres() error {
	if db != nil {
		sqlDB, err := db.DB()
		if err != nil {
			return err
		}
		return sqlDB.Close()
	}
	return nil
}

type zapWriter struct {
	log *zap.Logger
}

func (w *zapWriter) Printf(format string, args ...interface{}) {
	w.log.Debug(fmt.Sprintf(format, args...))
}
