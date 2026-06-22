package testutil

import (
	"context"
	"fmt"
	"meeting-system/internal/config"
	"meeting-system/internal/model"
	"meeting-system/pkg/database"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"
	gormpostgres "gorm.io/driver/postgres"
	"gorm.io/gorm"
)

type TestDB struct {
	container *tcpostgres.PostgresContainer
	cfg       *config.Config
	DB        *gorm.DB
	cleanup   func()
}

func SetupTestDB(t *testing.T) *TestDB {
	t.Helper()
	ctx := context.Background()

	pgContainer, err := tcpostgres.Run(ctx,
		"postgres:15-alpine",
		tcpostgres.WithDatabase("test_meeting"),
		tcpostgres.WithUsername("test"),
		tcpostgres.WithPassword("test"),
		testcontainers.WithWaitStrategy(
			wait.ForLog("database system is ready to accept connections").
				WithOccurrence(2).
				WithStartupTimeout(30*time.Second),
		),
	)
	require.NoError(t, err, "Failed to start postgres container")

	host, err := pgContainer.Host(ctx)
	require.NoError(t, err)

	port, err := pgContainer.MappedPort(ctx, "5432")
	require.NoError(t, err)

	dsn := fmt.Sprintf("host=%s port=%s user=test password=test dbname=test_meeting sslmode=disable",
		host, port.Port())

	db, err := gorm.Open(gormpostgres.Open(dsn), &gorm.Config{})
	require.NoError(t, err, "Failed to connect to test db")

	err = db.AutoMigrate(
		&model.User{},
		&model.Room{},
		&model.Booking{},
		&model.MeetingDoc{},
		&model.Todo{},
		&model.CheckIn{},
		&model.Notification{},
		&model.NotificationPreference{},
		&model.QRCodeToken{},
	)
	require.NoError(t, err, "Failed to run migrations")

	cfg := &config.Config{
		DBHost:     host,
		DBPort:     port.Port(),
		DBUser:     "test",
		DBPassword: "test",
		DBName:     "test_meeting",
		JWTSecret:  "test-secret-key-for-testing",
		ServerPort: "0",
	}

	database.DB = db

	return &TestDB{
		container: pgContainer,
		cfg:       cfg,
		DB:        db,
		cleanup: func() {
			testcontainers.CleanupContainer(t, pgContainer)
		},
	}
}

func (tdb *TestDB) Cleanup(t *testing.T) {
	t.Helper()
	if tdb.cleanup != nil {
		tdb.cleanup()
	}
}

func (tdb *TestDB) GetConfig() *config.Config {
	return tdb.cfg
}

func (tdb *TestDB) TruncateTables(t *testing.T) {
	t.Helper()
	tables := []string{
		"qr_code_tokens",
		"notification_preferences",
		"notifications",
		"check_ins",
		"todos",
		"meeting_docs",
		"bookings",
		"rooms",
		"users",
	}
	for _, table := range tables {
		err := tdb.DB.Exec(fmt.Sprintf("TRUNCATE TABLE %s RESTART IDENTITY CASCADE", table)).Error
		require.NoError(t, err, "Failed to truncate table %s", table)
	}
}
