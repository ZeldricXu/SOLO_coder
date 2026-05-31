package database

import (
	"fmt"
	"github.com/solocoder/session138/pkg/config"
	"github.com/solocoder/session138/pkg/models"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

var DB *gorm.DB

func Init(cfg *config.DatabaseConfig) error {
	dsn := fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		cfg.Host, cfg.Port, cfg.User, cfg.Password, cfg.DBName, cfg.SSLMode)

	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		return err
	}

	DB = db

	return DB.AutoMigrate(
		&models.Entity{},
		&models.ConfigDefinition{},
		&models.RunInstance{},
		&models.Snapshot{},
		&models.Service{},
		&models.LogLevelConfig{},
		&models.Environment{},
		&models.FeatureFlag{},
		&models.Vulnerability{},
	)
}
