package database

import (
	"fmt"
	"projectservice/internal/model"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

type Database struct {
	*gorm.DB
}

func New(host string, port int, user, password, dbname, sslmode string, poolSize int) (*Database, error) {
	dsn := fmt.Sprintf(
		"host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		host, port, user, password, dbname, sslmode,
	)

	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Info),
	})
	if err != nil {
		return nil, fmt.Errorf("failed to connect to database: %w", err)
	}

	sqlDB, err := db.DB()
	if err != nil {
		return nil, fmt.Errorf("failed to get database instance: %w", err)
	}

	sqlDB.SetMaxOpenConns(poolSize)
	sqlDB.SetMaxIdleConns(poolSize / 2)

	return &Database{DB: db}, nil
}

func (d *Database) AutoMigrate() error {
	return d.DB.AutoMigrate(
		&model.Entity{},
		&model.ConfigDefinition{},
		&model.RunInstance{},
		&model.StatSnapshot{},
		&model.SBOMDocument{},
		&model.Vulnerability{},
		&model.ProjectTemplate{},
		&model.GeneratedProject{},
		&model.Environment{},
		&model.EnvironmentUsage{},
		&model.QualityRule{},
		&model.QualityReport{},
		&model.FeatureFlag{},
		&model.UserSegment{},
		&model.SoftwareCatalog{},
		&model.ServiceDependency{},
		&model.APIContract{},
		&model.MockServer{},
		&model.DocumentIndex{},
	)
}
