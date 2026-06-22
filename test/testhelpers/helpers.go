package testhelpers

import (
	"context"
	"fmt"
	"math"
	"math/rand"
	"sync/atomic"
	"testing"
	"time"

	"github.com/df1-96/experiment/internal/models"
	"github.com/df1-96/experiment/pkg/util"
	"github.com/stretchr/testify/assert"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

var testIDCounter int64

func SetupTestLogger() *zap.Logger {
	config := zap.NewDevelopmentConfig()
	config.EncoderConfig.EncodeLevel = zapcore.CapitalColorLevelEncoder
	config.OutputPaths = []string{"stdout"}
	config.Level = zap.NewAtomicLevelAt(zapcore.WarnLevel)
	logger, _ := config.Build()
	return logger
}

func NewTestID() int64 {
	return atomic.AddInt64(&testIDCounter, 1)
}

func GenerateParamCombinations(count int) []models.Params {
	result := make([]models.Params, count)
	r := rand.New(rand.NewSource(42))

	for i := 0; i < count; i++ {
		params := make(models.Params)
		params["learning_rate"] = 0.001 + r.Float64()*0.099
		params["batch_size"] = 32 + r.Intn(224)
		params["hidden_units"] = 64 + r.Intn(448)
		params["dropout"] = r.Float64() * 0.5
		params["optimizer"] = []string{"adam", "sgd", "rmsprop"}[r.Intn(3)]
		params["activation"] = []string{"relu", "tanh", "sigmoid"}[r.Intn(3)]
		result[i] = params
	}
	return result
}

func AssertApproxEqual(t *testing.T, expected, actual, tolerance float64) {
	t.Helper()
	diff := math.Abs(expected - actual)
	assert.True(t, diff <= tolerance,
		"expected %v, actual %v, diff %v exceeds tolerance %v",
		expected, actual, diff, tolerance)
}

func RunWithTimeout(t *testing.T, duration time.Duration, fn func()) {
	t.Helper()
	done := make(chan struct{})
	go func() {
		defer close(done)
		fn()
	}()

	select {
	case <-done:
	case <-time.After(duration):
		t.Fatalf("test timed out after %v", duration)
	}
}

func RunWithTimeoutCtx(t *testing.T, duration time.Duration, fn func(ctx context.Context)) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), duration)
	defer cancel()

	done := make(chan struct{})
	go func() {
		defer close(done)
		fn(ctx)
	}()

	select {
	case <-done:
	case <-ctx.Done():
		t.Fatalf("test timed out after %v", duration)
	}
}

func NewTestTask(id int64, priority int) *models.Task {
	if id <= 0 {
		id = util.GenerateID()
	}
	return &models.Task{
		ID:           id,
		ExperimentID: util.GenerateID(),
		Name:         fmt.Sprintf("test-task-%d", id),
		Status:       models.TaskStatusPending,
		Priority:     priority,
		MaxRetries:   3,
		TimeoutSeconds: 300,
		Params:       models.Params{"test": true},
	}
}

func NewTestWorker(id int64, cores int, memoryGB int) *models.Worker {
	if id <= 0 {
		id = util.GenerateID()
	}
	now := time.Now()
	return &models.Worker{
		ID:              id,
		Name:            fmt.Sprintf("test-worker-%d", id),
		Status:          models.WorkerStatusIdle,
		Host:            "127.0.0.1",
		Port:            8080 + int(id%1000),
		CPUCores:        cores,
		MemoryGB:        memoryGB,
		LastHeartbeatAt: &now,
		HeartbeatCount:  1,
	}
}
