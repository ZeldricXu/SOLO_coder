package worker

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/df1-96/experiment/pkg/util"
	"go.uber.org/zap"
)

type Worker struct {
	config        Config
	info          WorkerInfo
	mu            sync.RWMutex
	running       bool
	ctx           context.Context
	cancel        context.CancelFunc
	wg            sync.WaitGroup
	collector     *ResourceCollector
	cache         *LocalCache
	heartbeat     *HeartbeatSender
	executor      *TaskExecutor
	taskDurations []float64
	stream        WorkerStream
	commands      chan WorkerCommand
	events        chan WorkerEvent
}

type WorkerStream interface {
	Send(ctx context.Context, req interface{}) error
	Recv(ctx context.Context) (interface{}, error)
	Close(ctx context.Context) error
}

type WorkerEventType int

const (
	WorkerEventStarted WorkerEventType = iota
	WorkerEventStopped
	WorkerEventStatusChanged
	WorkerEventTaskStarted
	WorkerEventTaskCompleted
	WorkerEventTaskFailed
	WorkerEventCommandReceived
	WorkerEventError
)

type WorkerEvent struct {
	Type      WorkerEventType
	WorkerID  string
	Timestamp time.Time
	Data      interface{}
	Error     error
}

func NewWorker(config Config) (*Worker, error) {
	if config.WorkerID == "" {
		config.WorkerID = util.GenerateIDString()
	}

	collector, err := NewResourceCollector(config.Collector)
	if err != nil {
		return nil, fmt.Errorf("failed to create resource collector: %w", err)
	}

	cache := NewLocalCache(config.Cache)

	w := &Worker{
		config:    config,
		collector: collector,
		cache:     cache,
		commands:  make(chan WorkerCommand, 100),
		events:    make(chan WorkerEvent, 100),
		taskDurations: make([]float64, 0, 100),
	}

	w.info = WorkerInfo{
		WorkerID:     config.WorkerID,
		Name:         config.Name,
		Address:      config.Address,
		Type:         config.Type,
		Status:       WorkerStatusOffline,
		Capabilities: config.Capabilities,
		Version:      config.Version,
		Zone:         config.Zone,
		RegisteredAt: time.Now(),
	}

	w.heartbeat = NewHeartbeatSender(
		config.Heartbeat,
		config.WorkerID,
		w.getLoadInfo,
		w.getStatus,
		w.sendHeartbeat,
		w.reconnect,
	)

	w.executor = NewTaskExecutor(
		config.Executor,
		config.WorkerID,
		cache,
		w.onTaskProgress,
		w.onTaskResult,
		w.fetchTask,
		w.submitResult,
	)

	return w, nil
}

func (w *Worker) Start(ctx context.Context) error {
	w.mu.Lock()
	defer w.mu.Unlock()

	if w.running {
		return nil
	}

	w.ctx, w.cancel = context.WithCancel(ctx)
	w.running = true
	w.info.Status = WorkerStatusIdle
	w.info.LastHeartbeat = time.Now()

	if err := w.collector.Start(w.ctx); err != nil {
		return fmt.Errorf("failed to start resource collector: %w", err)
	}

	if err := w.cache.Start(w.ctx); err != nil {
		return fmt.Errorf("failed to start local cache: %w", err)
	}

	if err := w.heartbeat.Start(w.ctx); err != nil {
		return fmt.Errorf("failed to start heartbeat sender: %w", err)
	}

	if err := w.executor.Start(w.ctx); err != nil {
		return fmt.Errorf("failed to start task executor: %w", err)
	}

	w.wg.Add(1)
	go w.commandHandler()

	w.wg.Add(1)
	go w.heartbeatHandler()

	w.emitEvent(WorkerEventStarted, nil)

	util.Info("worker started",
		zap.String("worker_id", w.config.WorkerID),
		zap.String("name", w.config.Name),
		zap.String("type", w.config.Type.String()))

	return nil
}

func (w *Worker) Stop() error {
	w.mu.Lock()
	defer w.mu.Unlock()

	if !w.running {
		return nil
	}

	w.running = false
	w.info.Status = WorkerStatusOffline
	w.cancel()

	if err := w.executor.Stop(); err != nil {
		util.Warn("failed to stop task executor", zap.Error(err))
	}

	if err := w.heartbeat.Stop(); err != nil {
		util.Warn("failed to stop heartbeat sender", zap.Error(err))
	}

	if err := w.cache.Stop(); err != nil {
		util.Warn("failed to stop local cache", zap.Error(err))
	}

	if err := w.collector.Stop(); err != nil {
		util.Warn("failed to stop resource collector", zap.Error(err))
	}

	w.wg.Wait()

	close(w.commands)
	close(w.events)

	w.emitEvent(WorkerEventStopped, nil)

	util.Info("worker stopped",
		zap.String("worker_id", w.config.WorkerID),
		zap.Int64("completed_tasks", w.executor.GetCompletedCount()),
		zap.Int64("failed_tasks", w.executor.GetFailedCount()))

	return nil
}

func (w *Worker) Restart(ctx context.Context) error {
	util.Info("worker restarting", zap.String("worker_id", w.config.WorkerID))

	if err := w.Stop(); err != nil {
		return fmt.Errorf("failed to stop worker: %w", err)
	}

	time.Sleep(1 * time.Second)

	if err := w.Start(ctx); err != nil {
		return fmt.Errorf("failed to start worker: %w", err)
	}

	util.Info("worker restarted successfully", zap.String("worker_id", w.config.WorkerID))
	return nil
}

func (w *Worker) commandHandler() {
	defer w.wg.Done()

	for {
		select {
		case <-w.ctx.Done():
			return
		case cmd, ok := <-w.commands:
			if !ok {
				return
			}
			w.handleCommand(cmd)
		}
	}
}

func (w *Worker) heartbeatHandler() {
	defer w.wg.Done()

	cmdChan := w.heartbeat.CommandChan()

	for {
		select {
		case <-w.ctx.Done():
			return
		case cmd, ok := <-cmdChan:
			if !ok {
				return
			}
			select {
			case w.commands <- cmd:
			default:
				util.Warn("command channel full, dropping command",
					zap.String("worker_id", w.config.WorkerID),
					zap.String("command_type", cmd.Type.String()))
			}
		}
	}
}

func (w *Worker) handleCommand(cmd WorkerCommand) {
	w.emitEvent(WorkerEventCommandReceived, cmd)

	util.Info("worker command received",
		zap.String("worker_id", w.config.WorkerID),
		zap.String("command_type", cmd.Type.String()),
		zap.String("message", cmd.Message))

	switch cmd.Type {
	case CommandTypePause:
		w.setStatus(WorkerStatusPaused)
	case CommandTypeResume:
		w.setStatus(WorkerStatusIdle)
	case CommandTypeDrain:
		w.setStatus(WorkerStatusDraining)
	case CommandTypeShutdown:
		go w.Stop()
	case CommandTypeUpdateCapabilities:
		if caps, ok := cmd.Parameters["capabilities"]; ok {
			util.Info("updating capabilities", zap.String("capabilities", caps))
		}
	}
}

func (w *Worker) setStatus(status WorkerStatus) {
	w.mu.Lock()
	oldStatus := w.info.Status
	w.info.Status = status
	w.mu.Unlock()

	if oldStatus != status {
		w.emitEvent(WorkerEventStatusChanged, map[string]interface{}{
			"old_status": oldStatus,
			"new_status": status,
		})

		util.Info("worker status changed",
			zap.String("worker_id", w.config.WorkerID),
			zap.String("old_status", oldStatus.String()),
			zap.String("new_status", status.String()))
	}
}

func (w *Worker) getStatus() WorkerStatus {
	w.mu.RLock()
	defer w.mu.RUnlock()

	if !w.running {
		return WorkerStatusOffline
	}

	return w.info.Status
}

func (w *Worker) getLoadInfo() LoadInfo {
	w.mu.RLock()
	defer w.mu.RUnlock()

	activeTasks := int32(w.executor.GetActiveTaskCount())
	pendingTasks := int32(w.executor.GetPendingTaskCount())
	completedTasks := w.executor.GetCompletedCount()
	failedTasks := w.executor.GetFailedCount()

	load := LoadInfo{
		ActiveTasks:    activeTasks,
		PendingTasks:   pendingTasks,
		CompletedTasks: int32(completedTasks),
		FailedTasks:    int32(failedTasks),
		LastUpdated:    time.Now(),
	}

	if len(w.taskDurations) > 0 {
		var sum float64
		for _, d := range w.taskDurations {
			sum += d
		}
		load.AverageTaskDuration = sum / float64(len(w.taskDurations))
	}

	load.Resources = w.collector.GetLastInfo()

	return load
}

func (w *Worker) onTaskProgress(progress TaskProgress) {
	util.Debug("task progress",
		zap.String("worker_id", w.config.WorkerID),
		zap.String("task_id", progress.TaskID),
		zap.Int64("iteration", progress.CurrentIter),
		zap.Float64("progress", progress.Progress))
}

func (w *Worker) onTaskResult(result TaskResult) {
	w.mu.Lock()
	w.taskDurations = append(w.taskDurations, float64(result.DurationMs))
	if len(w.taskDurations) > 100 {
		w.taskDurations = w.taskDurations[1:]
	}
	w.mu.Unlock()

	if result.Status == TaskStatusCompleted {
		w.emitEvent(WorkerEventTaskCompleted, result)
	} else {
		w.emitEvent(WorkerEventTaskFailed, result)
	}

	if w.getStatus() == WorkerStatusDraining && w.executor.GetActiveTaskCount() == 0 {
		w.setStatus(WorkerStatusIdle)
	}
}

func (w *Worker) sendHeartbeat(
	ctx context.Context,
	workerID string,
	status WorkerStatus,
	load LoadInfo,
) (HeartbeatAck, error) {
	w.mu.Lock()
	w.info.LastHeartbeat = time.Now()
	w.info.Load = load
	w.info.Status = status
	w.mu.Unlock()

	if w.stream != nil {
		req := map[string]interface{}{
			"type":      "heartbeat",
			"worker_id": workerID,
			"status":    status,
			"load":      load,
			"timestamp": time.Now(),
		}

		if err := w.stream.Send(ctx, req); err != nil {
			return HeartbeatAck{}, err
		}

		resp, err := w.stream.Recv(ctx)
		if err != nil {
			return HeartbeatAck{}, err
		}

		if ackMap, ok := resp.(map[string]interface{}); ok {
			ack := HeartbeatAck{
				Acknowledged: true,
				Message:      fmt.Sprintf("%v", ackMap["message"]),
			}
			return ack, nil
		}
	}

	return HeartbeatAck{
		Acknowledged: true,
		Message:      "ok",
		NextDeadline: time.Now().Add(w.config.Heartbeat.Interval * 2),
	}, nil
}

func (w *Worker) reconnect(ctx context.Context) error {
	util.Info("attempting to reconnect to scheduler",
		zap.String("worker_id", w.config.WorkerID))

	if w.stream != nil {
		if err := w.stream.Close(ctx); err != nil {
			util.Warn("failed to close existing stream", zap.Error(err))
		}
	}

	w.emitEvent(WorkerEventError, fmt.Errorf("connection lost, reconnecting"))

	return nil
}

func (w *Worker) fetchTask(ctx context.Context, workerID string) (*Task, error) {
	status := w.getStatus()
	if status == WorkerStatusPaused || status == WorkerStatusDraining || status == WorkerStatusOffline {
		return nil, nil
	}

	if w.stream != nil {
		req := map[string]interface{}{
			"type":      "fetch_task",
			"worker_id": workerID,
			"timestamp": time.Now(),
		}

		if err := w.stream.Send(ctx, req); err != nil {
			return nil, err
		}

		resp, err := w.stream.Recv(ctx)
		if err != nil {
			return nil, err
		}

		if task, ok := resp.(*Task); ok {
			w.emitEvent(WorkerEventTaskStarted, task)
			return task, nil
		}
	}

	return nil, nil
}

func (w *Worker) submitResult(ctx context.Context, result TaskResult) error {
	if w.stream != nil {
		req := map[string]interface{}{
			"type":      "submit_result",
			"worker_id": w.config.WorkerID,
			"result":    result,
			"timestamp": time.Now(),
		}

		return w.stream.Send(ctx, req)
	}

	return nil
}

func (w *Worker) SetStream(stream WorkerStream) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.stream = stream
}

func (w *Worker) GetInfo() WorkerInfo {
	w.mu.RLock()
	defer w.mu.RUnlock()
	info := w.info
	info.Load = w.getLoadInfo()
	return info
}

func (w *Worker) GetStatus() WorkerStatus {
	return w.getStatus()
}

func (w *Worker) GetLoad() LoadInfo {
	return w.getLoadInfo()
}

func (w *Worker) GetCacheStats() CacheStats {
	return w.cache.GetStats()
}

func (w *Worker) GetActiveTasks() []string {
	return w.executor.GetRunningTasks()
}

func (w *Worker) CancelTask(taskID string) error {
	return w.executor.CancelTask(taskID)
}

func (w *Worker) SubmitTask(task *Task) error {
	return w.executor.SubmitTask(task)
}

func (w *Worker) Events() <-chan WorkerEvent {
	return w.events
}

func (w *Worker) IsRunning() bool {
	w.mu.RLock()
	defer w.mu.RUnlock()
	return w.running
}

func (w *Worker) GetCollector() *ResourceCollector {
	return w.collector
}

func (w *Worker) GetCache() *LocalCache {
	return w.cache
}

func (w *Worker) GetHeartbeat() *HeartbeatSender {
	return w.heartbeat
}

func (w *Worker) GetExecutor() *TaskExecutor {
	return w.executor
}

func (w *Worker) emitEvent(eventType WorkerEventType, data interface{}) {
	event := WorkerEvent{
		Type:      eventType,
		WorkerID:  w.config.WorkerID,
		Timestamp: time.Now(),
		Data:      data,
	}

	select {
	case w.events <- event:
	default:
	}
}

func (ct CommandType) String() string {
	switch ct {
	case CommandTypePause:
		return "pause"
	case CommandTypeResume:
		return "resume"
	case CommandTypeDrain:
		return "drain"
	case CommandTypeShutdown:
		return "shutdown"
	case CommandTypeUpdateCapabilities:
		return "update_capabilities"
	default:
		return "unspecified"
	}
}
