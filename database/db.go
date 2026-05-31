package database

import (
	"depguard/config"
	"fmt"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"sync"
)

var (
	db   *gorm.DB
	once sync.Once
)

func Get() *gorm.DB {
	once.Do(func() {
		cfg := config.Get().Database
		dsn := fmt.Sprintf(
			"host=%s user=%s password=%s dbname=%s port=%s sslmode=%s",
			cfg.Host, cfg.User, cfg.Password, cfg.DBName, cfg.Port, cfg.SSLMode,
		)
		var err error
		db, err = gorm.Open(postgres.Open(dsn), &gorm.Config{
			Logger: logger.Default.LogMode(logger.Info),
		})
		if err != nil {
			panic("failed to connect database: " + err.Error())
		}
	})
	return db
}

func AutoMigrate(models ...interface{}) {
	if db == nil {
		Get()
	}
	_ = db.AutoMigrate(models...)
}
