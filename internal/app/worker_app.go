package app

import (
	"context"
	"fmt"
	"os"
	"runtime"
	"sync"

	"go.uber.org/zap"

	"github.com/df1-96/experiment/internal/config"
	"github.com/df1-96/experiment/internal/worker"
	"github.com/df1-96/experiment/pkg/grpcapi/client"
	"github.com/df1-96/experiment/pkg/util"
)

type WorkerApp struct {
	cfg        *config.Config
	worker     *worker.Worker
	grpcClient *client.GRPCClient
	logger     *zap.Logger
	mu         sync.Mutex
	running    bool
}

func NewWorkerApp(cfg *config.Config) (*WorkerApp, error) {
	if cfg == nil {
		return nil, fmt.Errorf("config is required")
	}

	logger := util.With(zap.String("component", "worker-app"))

	return &WorkerApp{
		cfg:    cfg,
		logger: logger,
	}, nil
}

func (app *WorkerApp) Start(ctx context.Context) error {
	app.mu.Lock()
	if app.running {
		app.mu.Unlock()
		return fmt.Errorf("worker app is already running")
	}
	app.running = true
	app.mu.Unlock()

	var err error

	app.logger.Info("Initializing gRPC client connection to scheduler")
	schedulerAddr := fmt.Sprintf("localhost:%d", app.cfg.Server.GRPCPort)
	if envAddr := os.Getenv("SCHEDULER_ADDR"); envAddr != "" {
		schedulerAddr = envAddr
	}

	grpcConfig := client.DefaultGRPCClientConfig()
	grpcConfig.Address = schedulerAddr
	grpcConfig.Block = true

	app.grpcClient = client.NewGRPCClient(grpcConfig, app.logger)
	if err := app.grpcClient.Connect(ctx); err != nil {
		return fmt.Errorf("failed to connect to scheduler: %w", err)
	}

	app.logger.Info("Initializing worker")
	workerCfg := app.buildWorkerConfig()
	app.worker, err = worker.NewWorker(workerCfg)
	if err != nil {
		return fmt.Errorf("failed to create worker: %w", err)
	}

	app.setupWorkerStream()

	if err := app.worker.Start(ctx); err != nil {
		return fmt.Errorf("failed to start worker: %w", err)
	}

	app.logger.Info("Worker app started successfully",
		zap.String("worker_id", workerCfg.WorkerID),
		zap.String("name", workerCfg.Name),
		zap.String("scheduler", schedulerAddr),
	)
	return nil
}

func (app *WorkerApp) buildWorkerConfig() worker.Config {
	workerID := fmt.Sprintf("%d", app.cfg.Worker.WorkerID)
	if app.cfg.Worker.WorkerID == 0 {
		workerID = util.GenerateIDString()
	}

	hostname, _ := os.Hostname()
	workerName := fmt.Sprintf("worker-%s", workerID)
	if hostname != "" {
		workerName = fmt.Sprintf("%s-%s", hostname, workerID)
	}

	workerType := worker.WorkerTypeCPU
	if runtime.GOARCH == "arm64" {
		workerType = worker.WorkerTypeHybrid
	}

	return worker.Config{
		WorkerID: workerID,
		Name:     workerName,
		Address:  getOutboundIP(),
		Type:     workerType,
		Version:  "1.0.0",
		Zone:     getZone(),
		Tags: map[string]string{
			"os":      runtime.GOOS,
			"arch":    runtime.GOARCH,
			"cpus":    fmt.Sprintf("%d", runtime.NumCPU()),
		},
		Heartbeat: worker.HeartbeatConfig{
			Interval:      app.cfg.Worker.HeartbeatInterval,
			Timeout:       app.cfg.Worker.HeartbeatInterval * 3,
			MaxRetries:    3,
			RetryInterval: app.cfg.Worker.HeartbeatInterval / 2,
		},
		Cache: worker.CacheConfig{
			MaxSize:         app.cfg.Worker.CacheSize,
			TTL:             3600 * 1000000000,
			PersistPath:     "",
			PersistInterval: 300 * 1000000000,
		},
		Collector: worker.CollectorConfig{
			Interval:    5 * 1000000000,
			CPUInterval: 1 * 1000000000,
		},
		Executor: worker.ExecutorConfig{
			MaxParallelTasks: int32(app.cfg.Worker.ConcurrentTasks),
			ProgressInterval: 10 * 1000000000,
			TaskTimeout:      3600 * 1000000000,
		},
		Capabilities: worker.WorkerCapabilities{
			SupportedFunctions: []string{"optimize", "evaluate", "gradient"},
			SupportedFrameworks: []string{"numpy", "gonum"},
			MaxParallelTasks:   int32(app.cfg.Worker.ConcurrentTasks),
			MaxMemoryGB:        float64(getMemoryGB()),
			PerformanceScore:   calculatePerformanceScore(),
			Tags: map[string]string{
				"compute": "cpu",
			},
		},
	}
}

func (app *WorkerApp) setupWorkerStream() {
	stream := &workerGRPCStream{
		client: app.grpcClient,
		logger: app.logger,
	}
	app.worker.SetStream(stream)
}

func (app *WorkerApp) Stop() error {
	app.mu.Lock()
	if !app.running {
		app.mu.Unlock()
		return nil
	}
	app.running = false
	app.mu.Unlock()

	app.logger.Info("Stopping worker app")

	if app.worker != nil {
		if err := app.worker.Stop(); err != nil {
			app.logger.Warn("Failed to stop worker gracefully", zap.Error(err))
		}
	}

	if app.grpcClient != nil {
		if err := app.grpcClient.Close(); err != nil {
			app.logger.Warn("Failed to close gRPC client connection", zap.Error(err))
		}
	}

	if err := util.Sync(); err != nil {
		app.logger.Warn("Failed to sync logger", zap.Error(err))
	}

	app.logger.Info("Worker app stopped")
	return nil
}

func (app *WorkerApp) GetWorker() *worker.Worker {
	return app.worker
}

func (app *WorkerApp) GetGRPCClient() *client.GRPCClient {
	return app.grpcClient
}

type workerGRPCStream struct {
	client *client.GRPCClient
	logger *zap.Logger
}

func (s *workerGRPCStream) Send(ctx context.Context, req interface{}) error {
	s.logger.Debug("Sending request via gRPC stream", zap.Any("request", req))
	return nil
}

func (s *workerGRPCStream) Recv(ctx context.Context) (interface{}, error) {
	return nil, nil
}

func (s *workerGRPCStream) Close(ctx context.Context) error {
	return nil
}

func getOutboundIP() string {
	return "127.0.0.1"
}

func getZone() string {
	if zone := os.Getenv("ZONE"); zone != "" {
		return zone
	}
	return "default"
}

func getMemoryGB() int {
	return 8
}

func calculatePerformanceScore() float64 {
	return float64(runtime.NumCPU()) * 100.0
}
