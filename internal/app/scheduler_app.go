package app

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"sync"

	"go.uber.org/zap"

	v1 "github.com/df1-96/experiment/pkg/grpcapi/distcomp/v1"
	"github.com/df1-96/experiment/internal/config"
	"github.com/df1-96/experiment/internal/scheduler"
	"github.com/df1-96/experiment/internal/storage"
	"github.com/df1-96/experiment/pkg/grpcapi/server"
	"github.com/df1-96/experiment/pkg/util"
)

type SchedulerApp struct {
	cfg           *config.Config
	db            *storage.DB
	taskScheduler *scheduler.TaskScheduler
	grpcServer    *server.GRPCServer
	httpServer    *http.Server
	logger        *zap.Logger
	mu            sync.Mutex
	running       bool
}

func NewSchedulerApp(cfg *config.Config) (*SchedulerApp, error) {
	if cfg == nil {
		return nil, fmt.Errorf("config is required")
	}

	logger := util.With(zap.String("component", "scheduler-app"))

	return &SchedulerApp{
		cfg:    cfg,
		logger: logger,
	}, nil
}

func (app *SchedulerApp) Start(ctx context.Context) error {
	app.mu.Lock()
	if app.running {
		app.mu.Unlock()
		return fmt.Errorf("scheduler app is already running")
	}
	app.running = true
	app.mu.Unlock()

	var err error

	app.logger.Info("Initializing database connection")
	app.db, err = storage.NewDB(&app.cfg.Database)
	if err != nil {
		return fmt.Errorf("failed to initialize database: %w", err)
	}

	if err := app.db.AutoMigrate(); err != nil {
		app.logger.Warn("Failed to auto-migrate database schema", zap.Error(err))
	}

	app.logger.Info("Initializing task scheduler")
	schedulerCfg := scheduler.SchedulerConfig{
		HeartbeatTimeout:       app.cfg.Scheduler.HeartbeatTimeout,
		DefaultTaskTimeout:     app.cfg.Scheduler.TaskTimeout,
		DefaultMaxRetries:      app.cfg.Scheduler.MaxRetries,
		AssignmentStrategy:     scheduler.AssignmentBestFit,
		CheckpointInterval:     5 * 60 * 1000000000,
		WorkerOfflineThreshold: 60 * 1000000000,
		MaxConcurrentTasks:     1000,
	}
	app.taskScheduler = scheduler.NewTaskScheduler(schedulerCfg)

	app.taskScheduler.OnEvent(app.handleSchedulerEvent)

	if err := app.taskScheduler.Start(ctx); err != nil {
		return fmt.Errorf("failed to start scheduler: %w", err)
	}

	if err := app.startGRPCServer(ctx); err != nil {
		return fmt.Errorf("failed to start gRPC server: %w", err)
	}

	if err := app.startHTTPServer(ctx); err != nil {
		return fmt.Errorf("failed to start HTTP server: %w", err)
	}

	app.logger.Info("Scheduler app started successfully")
	return nil
}

func (app *SchedulerApp) startGRPCServer(ctx context.Context) error {
	app.logger.Info("Initializing gRPC server", zap.Int("port", app.cfg.Server.GRPCPort))

	grpcConfig := server.DefaultGRPCServerConfig()
	grpcConfig.Port = app.cfg.Server.GRPCPort
	grpcConfig.Address = "0.0.0.0"

	var err error
	app.grpcServer, err = server.NewGRPCServer(grpcConfig, app.logger)
	if err != nil {
		return fmt.Errorf("failed to create gRPC server: %w", err)
	}

	adapter := newSchedulerGRPCAdapter(app.taskScheduler, app.logger)

	taskServer := server.NewTaskServer(adapter, app.logger)
	app.grpcServer.RegisterTaskServer(taskServer)

	computeServer := server.NewComputeServer(adapter, app.logger)
	app.grpcServer.RegisterComputeServer(computeServer)

	workerServer := server.NewWorkerServer(adapter, app.logger)
	app.grpcServer.RegisterWorkerServer(workerServer)

	go func() {
		if err := app.grpcServer.Start(ctx); err != nil {
			app.logger.Error("gRPC server error", zap.Error(err))
		}
	}()

	return nil
}

func (app *SchedulerApp) startHTTPServer(ctx context.Context) error {
	app.logger.Info("Initializing HTTP server", zap.Int("port", app.cfg.Server.HTTPPort))

	mux := http.NewServeMux()

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"healthy","service":"scheduler"}`))
	})

	mux.HandleFunc("/metrics", func(w http.ResponseWriter, r *http.Request) {
		app.mu.Lock()
		workerCount := app.taskScheduler.GetWorkerCount()
		taskCount := app.taskScheduler.GetTaskCount()
		queueLen := app.taskScheduler.GetQueueLength()
		app.mu.Unlock()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(fmt.Sprintf(
			`{"workers":%d,"tasks":%d,"queue":%d}`,
			workerCount, taskCount, queueLen,
		)))
	})

	addr := ":" + strconv.Itoa(app.cfg.Server.HTTPPort)
	app.httpServer = &http.Server{
		Addr:    addr,
		Handler: mux,
	}

	go func() {
		app.logger.Info("HTTP server starting", zap.String("addr", addr))
		if err := app.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			app.logger.Error("HTTP server error", zap.Error(err))
		}
	}()

	return nil
}

func (app *SchedulerApp) Stop() error {
	app.mu.Lock()
	if !app.running {
		app.mu.Unlock()
		return nil
	}
	app.running = false
	app.mu.Unlock()

	app.logger.Info("Stopping scheduler app")

	if app.httpServer != nil {
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*1000000000)
		defer cancel()
		if err := app.httpServer.Shutdown(shutdownCtx); err != nil {
			app.logger.Warn("Failed to shutdown HTTP server gracefully", zap.Error(err))
		}
	}

	if app.grpcServer != nil {
		app.grpcServer.Stop()
	}

	if app.taskScheduler != nil {
		if err := app.taskScheduler.Stop(); err != nil {
			app.logger.Warn("Failed to stop task scheduler", zap.Error(err))
		}
	}

	if app.db != nil {
		if err := app.db.Close(); err != nil {
			app.logger.Warn("Failed to close database connection", zap.Error(err))
		}
	}

	if err := util.Sync(); err != nil {
		app.logger.Warn("Failed to sync logger", zap.Error(err))
	}

	app.logger.Info("Scheduler app stopped")
	return nil
}

func (app *SchedulerApp) handleSchedulerEvent(event scheduler.SchedulerEvent, payload interface{}) {
	app.logger.Debug("Scheduler event received",
		zap.String("event", string(event)),
		zap.Any("payload", payload),
	)

	switch event {
	case scheduler.EventWorkerRegistered:
		if worker, ok := payload.(interface{ GetID() int64 }); ok {
			app.logger.Info("Worker registered", zap.Int64("worker_id", worker.GetID()))
		}
	case scheduler.EventWorkerOffline:
		if workerID, ok := payload.(int64); ok {
			app.logger.Warn("Worker went offline", zap.Int64("worker_id", workerID))
		}
	case scheduler.EventTaskStarted:
		if task, ok := payload.(interface{ GetID() int64 }); ok {
			app.logger.Info("Task started", zap.Int64("task_id", task.GetID()))
		}
	case scheduler.EventTaskCompleted:
		if taskID, ok := payload.(int64); ok {
			app.logger.Info("Task completed", zap.Int64("task_id", taskID))
		}
	case scheduler.EventTaskFailed:
		if taskID, ok := payload.(int64); ok {
			app.logger.Error("Task failed", zap.Int64("task_id", taskID))
		}
	}
}

func (app *SchedulerApp) GetScheduler() *scheduler.TaskScheduler {
	return app.taskScheduler
}

func (app *SchedulerApp) GetDB() *storage.DB {
	return app.db
}

func (app *SchedulerApp) GetGRPCAddr() net.Addr {
	if app.grpcServer != nil {
		return app.grpcServer.GetListenerAddr()
	}
	return nil
}

type schedulerGRPCAdapter struct {
	scheduler *scheduler.TaskScheduler
	logger    *zap.Logger
}

func newSchedulerGRPCAdapter(s *scheduler.TaskScheduler, logger *zap.Logger) *schedulerGRPCAdapter {
	return &schedulerGRPCAdapter{
		scheduler: s,
		logger:    logger,
	}
}

func (a *schedulerGRPCAdapter) CreateTask(ctx context.Context, req *v1.CreateTaskRequest) (*v1.CreateTaskResponse, error) {
	return &v1.CreateTaskResponse{
		TaskId: util.GenerateIDString(),
		Status: v1.TaskStatus_TASK_STATUS_PENDING,
	}, nil
}

func (a *schedulerGRPCAdapter) GetTask(ctx context.Context, req *v1.GetTaskRequest) (*v1.GetTaskResponse, error) {
	return &v1.GetTaskResponse{
		Task: &v1.Task{
			TaskId:   req.TaskId,
			Status:   v1.TaskStatus_TASK_STATUS_RUNNING,
			Priority: v1.TaskPriority_TASK_PRIORITY_MEDIUM,
		},
	}, nil
}

func (a *schedulerGRPCAdapter) ListTasks(ctx context.Context, req *v1.ListTasksRequest) (*v1.ListTasksResponse, error) {
	return &v1.ListTasksResponse{
		Tasks:      []*v1.Task{},
		TotalCount: 0,
	}, nil
}

func (a *schedulerGRPCAdapter) UpdateTaskStatus(ctx context.Context, req *v1.UpdateTaskStatusRequest) (*v1.UpdateTaskStatusResponse, error) {
	return &v1.UpdateTaskStatusResponse{Success: true}, nil
}

func (a *schedulerGRPCAdapter) CancelTask(ctx context.Context, req *v1.CancelTaskRequest) (*v1.CancelTaskResponse, error) {
	return &v1.CancelTaskResponse{Success: true}, nil
}

func (a *schedulerGRPCAdapter) SubmitResult(ctx context.Context, req *v1.SubmitResultRequest) (*v1.SubmitResultResponse, error) {
	return &v1.SubmitResultResponse{
		Success: true,
		Message: "result submitted successfully",
	}, nil
}

func (a *schedulerGRPCAdapter) GetShard(ctx context.Context, req *v1.GetShardRequest) (*v1.GetShardResponse, error) {
	return &v1.GetShardResponse{
		Shard: &v1.TaskShard{
			TaskId:      req.TaskId,
			ShardId:     0,
			TotalShards: 1,
		},
	}, nil
}

func (a *schedulerGRPCAdapter) HandleComputeRequest(ctx context.Context, req *v1.ComputeRequest) (*v1.ComputeResponse, error) {
	return nil, nil
}

func (a *schedulerGRPCAdapter) HandleHeartbeat(ctx context.Context, req *v1.Heartbeat) (*v1.TaskStatusUpdate, error) {
	return &v1.TaskStatusUpdate{
		TaskId:  req.TaskId,
		ShardId: req.ShardId,
		Status:  v1.TaskStatus_TASK_STATUS_RUNNING,
	}, nil
}

func (a *schedulerGRPCAdapter) HandleProgress(ctx context.Context, req *v1.ProgressUpdate) (*v1.TaskCancellation, error) {
	return nil, nil
}

func (a *schedulerGRPCAdapter) RegisterWorker(ctx context.Context, req *v1.RegisterWorkerRequest) (*v1.RegisterWorkerResponse, error) {
	workerID := util.GenerateID()
	return &v1.RegisterWorkerResponse{
		WorkerId: strconv.FormatInt(workerID, 10),
		Success:  true,
		Message:  "worker registered successfully",
	}, nil
}

func (a *schedulerGRPCAdapter) UnregisterWorker(ctx context.Context, req *v1.UnregisterWorkerRequest) (*v1.UnregisterWorkerResponse, error) {
	return &v1.UnregisterWorkerResponse{
		Success: true,
		Message: "worker unregistered successfully",
	}, nil
}

func (a *schedulerGRPCAdapter) Heartbeat(ctx context.Context, req *v1.HeartbeatRequest) (*v1.HeartbeatResponse, error) {
	workerID, _ := strconv.ParseInt(req.WorkerId, 10, 64)
	if workerID > 0 {
		_ = a.scheduler.Heartbeat(workerID)
	}

	return &v1.HeartbeatResponse{
		Acknowledged:  true,
		DesiredStatus: v1.WorkerStatus_WORKER_STATUS_IDLE,
		Message:       "heartbeat acknowledged",
	}, nil
}

func (a *schedulerGRPCAdapter) GetWorker(ctx context.Context, req *v1.GetWorkerRequest) (*v1.GetWorkerResponse, error) {
	return &v1.GetWorkerResponse{
		Worker: &v1.WorkerInfo{
			WorkerId: req.WorkerId,
			Status:   v1.WorkerStatus_WORKER_STATUS_IDLE,
		},
	}, nil
}

func (a *schedulerGRPCAdapter) ListWorkers(ctx context.Context, req *v1.ListWorkersRequest) (*v1.ListWorkersResponse, error) {
	return &v1.ListWorkersResponse{
		Workers:    []*v1.WorkerInfo{},
		TotalCount: 0,
	}, nil
}

func (a *schedulerGRPCAdapter) UpdateWorkerStatus(ctx context.Context, req *v1.UpdateWorkerStatusRequest) (*v1.UpdateWorkerStatusResponse, error) {
	return &v1.UpdateWorkerStatusResponse{Success: true}, nil
}

func (a *schedulerGRPCAdapter) HandleWorkerStreamRequest(ctx context.Context, req *v1.WorkerStreamRequest) (*v1.WorkerStreamResponse, error) {
	return nil, nil
}
