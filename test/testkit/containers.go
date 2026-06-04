package testkit

import (
	"context"
	"fmt"
	"io"
	"time"

	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"
)

type TestContainers struct {
	PgContainer testcontainers.Container
	PgHost      string
	PgPort      string
	PgUser      string
	PgPassword  string
	PgDBName    string

	RedisContainer testcontainers.Container
	RedisHost      string
	RedisPort      string
}

func SetupTestContainers(ctx context.Context) (*TestContainers, error) {
	tc := &TestContainers{
		PgUser:     "postgres",
		PgPassword: "postgres",
		PgDBName:   "task_scheduler_test",
	}

	pgReq := testcontainers.ContainerRequest{
		Image:        "postgres:15-alpine",
		ExposedPorts: []string{"5432/tcp"},
		Env: map[string]string{
			"POSTGRES_USER":     tc.PgUser,
			"POSTGRES_PASSWORD": tc.PgPassword,
			"POSTGRES_DB":       tc.PgDBName,
		},
		WaitingFor: wait.ForLog("database system is ready to accept connections").
			WithOccurrence(2).
			WithStartupTimeout(60 * time.Second),
	}

	pgC, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: pgReq,
		Started:          true,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to start postgres: %w", err)
	}
	tc.PgContainer = pgC

	pgHost, _ := pgC.Host(ctx)
	pgPort, _ := pgC.MappedPort(ctx, "5432")
	tc.PgHost = pgHost
	tc.PgPort = pgPort.Port()

	redisReq := testcontainers.ContainerRequest{
		Image:        "redis:7-alpine",
		ExposedPorts: []string{"6379/tcp"},
		WaitingFor:   wait.ForLog("Ready to accept connections").WithStartupTimeout(30 * time.Second),
	}

	redisC, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: redisReq,
		Started:          true,
	})
	if err != nil {
		pgC.Terminate(ctx)
		return nil, fmt.Errorf("failed to start redis: %w", err)
	}
	tc.RedisContainer = redisC

	redisHost, _ := redisC.Host(ctx)
	redisPort, _ := redisC.MappedPort(ctx, "6379")
	tc.RedisHost = redisHost
	tc.RedisPort = redisPort.Port()

	return tc, nil
}

func (tc *TestContainers) Terminate(ctx context.Context) {
	if tc.PgContainer != nil {
		tc.PgContainer.Terminate(ctx)
	}
	if tc.RedisContainer != nil {
		tc.RedisContainer.Terminate(ctx)
	}
}

func (tc *TestContainers) RunMigrations(ctx context.Context, db io.Writer) error {
	return nil
}

func (tc *TestContainers) PostgreSQLDSN() string {
	return fmt.Sprintf("host=%s port=%s user=%s password=%s dbname=%s sslmode=disable",
		tc.PgHost, tc.PgPort, tc.PgUser, tc.PgPassword, tc.PgDBName)
}

func (tc *TestContainers) RedisAddr() string {
	return fmt.Sprintf("%s:%s", tc.RedisHost, tc.RedisPort)
}
