package testutils

import (
	"go.uber.org/zap"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
)

func SetupTestDB(models ...interface{}) (*gorm.DB, *zap.Logger, func()) {
	logger, _ := zap.NewDevelopment()

	db, err := gorm.Open(sqlite.Open(":memory:"), &gorm.Config{})
	if err != nil {
		logger.Fatal("Failed to open in-memory database", zap.Error(err))
	}

	for _, model := range models {
		if err := db.AutoMigrate(model); err != nil {
			logger.Fatal("Failed to migrate model", zap.Error(err))
		}
	}

	cleanup := func() {
		sqlDB, _ := db.DB()
		if sqlDB != nil {
			sqlDB.Close()
		}
	}

	return db, logger, cleanup
}
