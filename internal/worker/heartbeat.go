package worker

import (
	"context"
	"sync"
	"time"

	"github.com/df1-96/experiment/pkg/util"
	"go.uber.org/zap"
)

type HeartbeatSender struct {
	config         HeartbeatConfig
	workerID       string
	mu             sync.RWMutex
	running        bool
	ctx            context.Context
	cancel         context.CancelFunc
	wg             sync.WaitGroup
	connected      bool
	lastSent       time.Time
	lastAck        time.Time
	failCount      int
	loadProvider   func() LoadInfo
	statusProvider func() WorkerStatus
	heartbeatChan  chan HeartbeatResult
	commandChan    chan WorkerCommand
	sendFunc       func(ctx context.Context, workerID string, status WorkerStatus, load LoadInfo) (HeartbeatAck, error)
	reconnectFunc  func(ctx context.Context) error
}

type HeartbeatResult struct {
	WorkerID  string
	Status    WorkerStatus
	Load      LoadInfo
	Timestamp time.Time
	Ack       HeartbeatAck
	Error     error
}

type HeartbeatAck struct {
	Acknowledged    bool
	DesiredStatus   WorkerStatus
	Message         string
	NextDeadline    time.Time
}

func NewHeartbeatSender(
	config HeartbeatConfig,
	workerID string,
	loadProvider func() LoadInfo,
	statusProvider func() WorkerStatus,
	sendFunc func(ctx context.Context, workerID string, status WorkerStatus, load LoadInfo) (HeartbeatAck, error),
	reconnectFunc func(ctx context.Context) error,
) *HeartbeatSender {
	if config.Interval <= 0 {
		config.Interval = 5 * time.Second
	}
	if config.Timeout <= 0 {
		config.Timeout = 30 * time.Second
	}
	if config.MaxRetries <= 0 {
		config.MaxRetries = 3
	}
	if config.RetryInterval <= 0 {
		config.RetryInterval = 2 * time.Second
	}

	return &HeartbeatSender{
		config:         config,
		workerID:       workerID,
		loadProvider:   loadProvider,
		statusProvider: statusProvider,
		sendFunc:       sendFunc,
		reconnectFunc:  reconnectFunc,
		heartbeatChan:  make(chan HeartbeatResult, 100),
		commandChan:    make(chan WorkerCommand, 100),
	}
}

func (hs *HeartbeatSender) Start(ctx context.Context) error {
	hs.mu.Lock()
	defer hs.mu.Unlock()

	if hs.running {
		return nil
	}

	hs.ctx, hs.cancel = context.WithCancel(ctx)
	hs.running = true
	hs.connected = true
	hs.failCount = 0

	hs.wg.Add(1)
	go hs.heartbeatLoop()

	util.Info("heartbeat sender started",
		zap.String("worker_id", hs.workerID),
		zap.Duration("interval", hs.config.Interval),
		zap.Duration("timeout", hs.config.Timeout))

	return nil
}

func (hs *HeartbeatSender) Stop() error {
	hs.mu.Lock()
	defer hs.mu.Unlock()

	if !hs.running {
		return nil
	}

	hs.running = false
	hs.cancel()
	hs.wg.Wait()

	close(hs.heartbeatChan)
	close(hs.commandChan)

	util.Info("heartbeat sender stopped",
		zap.String("worker_id", hs.workerID),
		zap.Int("fail_count", hs.failCount))

	return nil
}

func (hs *HeartbeatSender) heartbeatLoop() {
	defer hs.wg.Done()

	interval := hs.config.Interval
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	if err := hs.sendHeartbeat(); err != nil {
		util.Warn("initial heartbeat failed",
			zap.String("worker_id", hs.workerID),
			zap.Error(err))
	}

	for {
		select {
		case <-hs.ctx.Done():
			return
		case <-ticker.C:
			hs.checkTimeout()

			if err := hs.sendHeartbeat(); err != nil {
				util.Warn("heartbeat failed",
					zap.String("worker_id", hs.workerID),
					zap.Int("fail_count", hs.failCount),
					zap.Error(err))

				if hs.shouldReconnect() {
					hs.tryReconnect()
				}
			}
		}
	}
}

func (hs *HeartbeatSender) sendHeartbeat() error {
	hs.mu.Lock()
	status := hs.statusProvider()
	load := hs.loadProvider()
	workerID := hs.workerID
	hs.mu.Unlock()

	ctx, cancel := context.WithTimeout(hs.ctx, hs.config.Timeout)
	defer cancel()

	result := HeartbeatResult{
		WorkerID:  workerID,
		Status:    status,
		Load:      load,
		Timestamp: time.Now(),
	}

	ack, err := hs.sendFunc(ctx, workerID, status, load)

	hs.mu.Lock()
	hs.lastSent = time.Now()
	hs.mu.Unlock()

	if err != nil {
		result.Error = err
		hs.mu.Lock()
		hs.failCount++
		hs.connected = false
		hs.mu.Unlock()

		select {
		case hs.heartbeatChan <- result:
		default:
		}

		return err
	}

	result.Ack = ack

	hs.mu.Lock()
	hs.lastAck = time.Now()
	hs.failCount = 0
	hs.connected = true
	hs.mu.Unlock()

	if ack.DesiredStatus != status && ack.DesiredStatus != WorkerStatusUnspecified {
		command := WorkerCommand{
			Type:       hs.statusToCommand(ack.DesiredStatus),
			WorkerID:   workerID,
			Message:    ack.Message,
			IssuedAt:   time.Now(),
			Parameters: make(map[string]string),
		}

		select {
		case hs.commandChan <- command:
		default:
			util.Warn("command channel full, dropping command",
				zap.String("worker_id", workerID),
				zap.String("command", ack.DesiredStatus.String()))
		}
	}

	select {
	case hs.heartbeatChan <- result:
	default:
	}

	return nil
}

func (hs *HeartbeatSender) checkTimeout() {
	hs.mu.RLock()
	lastAck := hs.lastAck
	timeout := hs.config.Timeout
	connected := hs.connected
	hs.mu.RUnlock()

	if !connected {
		return
	}

	if !lastAck.IsZero() && time.Since(lastAck) > timeout {
		util.Warn("heartbeat timeout detected",
			zap.String("worker_id", hs.workerID),
			zap.Duration("since_last_ack", time.Since(lastAck)))

		hs.mu.Lock()
		hs.connected = false
		hs.failCount++
		hs.mu.Unlock()

		hs.tryReconnect()
	}
}

func (hs *HeartbeatSender) shouldReconnect() bool {
	hs.mu.RLock()
	defer hs.mu.RUnlock()
	return hs.failCount >= hs.config.MaxRetries
}

func (hs *HeartbeatSender) tryReconnect() {
	hs.mu.RLock()
	reconnectFunc := hs.reconnectFunc
	workerID := hs.workerID
	hs.mu.RUnlock()

	if reconnectFunc == nil {
		return
	}

	util.Info("attempting to reconnect",
		zap.String("worker_id", workerID),
		zap.Int("fail_count", hs.failCount))

	for i := 0; i < hs.config.MaxRetries; i++ {
		select {
		case <-hs.ctx.Done():
			return
		case <-time.After(hs.config.RetryInterval):
			ctx, cancel := context.WithTimeout(hs.ctx, hs.config.Timeout)
			err := reconnectFunc(ctx)
			cancel()

			if err == nil {
				util.Info("reconnection successful",
					zap.String("worker_id", workerID),
					zap.Int("attempt", i+1))

				hs.mu.Lock()
				hs.connected = true
				hs.failCount = 0
				hs.mu.Unlock()
				return
			}

			util.Warn("reconnection attempt failed",
				zap.String("worker_id", workerID),
				zap.Int("attempt", i+1),
				zap.Error(err))
		}
	}

	util.Error("all reconnection attempts failed",
		zap.String("worker_id", workerID),
		zap.Int("max_retries", hs.config.MaxRetries))
}

func (hs *HeartbeatSender) statusToCommand(status WorkerStatus) CommandType {
	switch status {
	case WorkerStatusPaused:
		return CommandTypePause
	case WorkerStatusIdle, WorkerStatusBusy:
		return CommandTypeResume
	case WorkerStatusDraining:
		return CommandTypeDrain
	case WorkerStatusOffline:
		return CommandTypeShutdown
	default:
		return CommandTypeUnspecified
	}
}

func (hs *HeartbeatSender) SendNow() error {
	return hs.sendHeartbeat()
}

func (hs *HeartbeatSender) IsConnected() bool {
	hs.mu.RLock()
	defer hs.mu.RUnlock()
	return hs.connected
}

func (hs *HeartbeatSender) LastSent() time.Time {
	hs.mu.RLock()
	defer hs.mu.RUnlock()
	return hs.lastSent
}

func (hs *HeartbeatSender) LastAck() time.Time {
	hs.mu.RLock()
	defer hs.mu.RUnlock()
	return hs.lastAck
}

func (hs *HeartbeatSender) FailCount() int {
	hs.mu.RLock()
	defer hs.mu.RUnlock()
	return hs.failCount
}

func (hs *HeartbeatSender) HeartbeatChan() <-chan HeartbeatResult {
	return hs.heartbeatChan
}

func (hs *HeartbeatSender) CommandChan() <-chan WorkerCommand {
	return hs.commandChan
}

func (hs *HeartbeatSender) IsRunning() bool {
	hs.mu.RLock()
	defer hs.mu.RUnlock()
	return hs.running
}
