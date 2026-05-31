package database

import (
	"fmt"
	"sync"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"

	"edgescheduler/internal/common/config"
	"edgescheduler/internal/common/logger"
)

var (
	db   *gorm.DB
	once sync.Once
)

func Init(cfg *config.DatabaseConfig) error {
	var err error
	once.Do(func() {
		dsn := fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
			cfg.Host, cfg.Port, cfg.User, cfg.Password, cfg.DBName, cfg.SSLMode)

		newLogger := logger.New(
			logger.WriterFunc(func(s string, i ...interface{}) {
				logger.Infof(s, i...)
			}),
			logger.Config{
				LogLevel: logger.Info,
			},
		)

		db, err = gorm.Open(postgres.Open(dsn), &gorm.Config{
			Logger: newLogger,
		})

		if err == nil {
			sqlDB, _ := db.DB()
			sqlDB.SetMaxIdleConns(10)
			sqlDB.SetMaxOpenConns(100)
		}
	})

	return err
}

func GetDB() *gorm.DB {
	if db == nil {
		logger.Fatal("Database not initialized")
	}
	return db
}

func AutoMigrate(models ...interface{}) error {
	return GetDB().AutoMigrate(models...)
}
