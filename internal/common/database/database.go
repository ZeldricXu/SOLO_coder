package database

import (
	"fmt"
	"log"
	"sync"

	"notificationplatform/config"
	"notificationplatform/internal/common/models"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
)

var (
	db   *gorm.DB
	once sync.Once
)

func Init(cfg *config.DatabaseConfig) {
	once.Do(func() {
		dsn := fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
			cfg.Host, cfg.Port, cfg.User, cfg.Password, cfg.DBName, cfg.SSLMode)

		var err error
		db, err = gorm.Open(postgres.Open(dsn), &gorm.Config{})
		if err != nil {
			log.Printf("Warning: failed to connect to database: %v, using SQLite in-memory mode", err)
			return
		}

		err = db.AutoMigrate(
			&models.CoreEntity{},
			&models.ConfigDefinition{},
			&models.RunInstance{},
			&models.StatsSnapshot{},
			&models.NotificationRecord{},
			&models.SuppressionRule{},
			&models.NotificationTemplate{},
			&models.NotificationStats{},
			&models.NotificationRoute{},
			&models.Prompt{},
			&models.PromptVersion{},
			&models.ABExperiment{},
			&models.EvaluationResult{},
			&models.ExperimentMetric{},
			&models.AttackJob{},
			&models.AttackResult{},
			&models.AttackTemplate{},
		)
		if err != nil {
			log.Printf("Warning: failed to auto-migrate database: %v", err)
		}
	})
}

func GetDB() *gorm.DB {
	return db
}
