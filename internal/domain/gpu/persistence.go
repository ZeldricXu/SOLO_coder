package gpu

import (
	"context"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
)

type TaskState string

const (
	TaskStateQueued    TaskState = "queued"
	TaskStateRunning   TaskState = "running"
	TaskStateCompleted TaskState = "completed"
	TaskStateFailed    TaskState = "failed"
	TaskStatePreempted TaskState = "preempted"
	TaskStateCancelled TaskState = "cancelled"
)

type TaskSnapshot struct {
	TaskID      string                 `json:"task_id"`
	Name        string                 `json:"name"`
	Priority    TaskPriority           `json:"priority"`
	VRAMRequired uint64                `json:"vram_required_mb"`
	State       TaskState              `json:"state"`
	ResourceID  string                 `json:"resource_id,omitempty"`
	Payload     interface{}            `json:"payload"`
	Result      interface{}            `json:"result,omitempty"`
	Error       string                 `json:"error,omitempty"`
	SubmittedAt time.Time              `json:"submitted_at"`
	StartedAt   *time.Time             `json:"started_at,omitempty"`
	CompletedAt *time.Time             `json:"completed_at,omitempty"`
	Preemptible bool                   `json:"preemptible"`
	SnapshotAt  time.Time              `json:"snapshot_at"`
}

type TaskPersistenceStore interface {
	Save(ctx context.Context, task *GPUTask, state TaskState) error
	BatchSave(ctx context.Context, tasks []*GPUTask, state TaskState) error
	LoadAll(ctx context.Context) ([]*TaskSnapshot, error)
	LoadByState(ctx context.Context, state TaskState) ([]*TaskSnapshot, error)
	LoadByID(ctx context.Context, taskID string) (*TaskSnapshot, error)
	Delete(ctx context.Context, taskID string) error
	Clear(ctx context.Context) error
	Close() error
}

type FilePersistenceStore struct {
	basePath string
	mu       sync.RWMutex
}

func NewFilePersistenceStore(basePath string) (*FilePersistenceStore, error) {
	if err := os.MkdirAll(basePath, 0755); err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal,
			"failed to create persistence directory")
	}

	return &FilePersistenceStore{
		basePath: basePath,
	}, nil
}

func (s *FilePersistenceStore) taskFilePath(taskID string) string {
	return filepath.Join(s.basePath, fmt.Sprintf("%s.json", taskID))
}

func (s *FilePersistenceStore) Save(ctx context.Context, task *GPUTask, state TaskState) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	snapshot := &TaskSnapshot{
		TaskID:       task.ID,
		Name:         task.Name,
		Priority:     task.Priority,
		VRAMRequired: task.VRAMRequired,
		State:        state,
		ResourceID:   task.ResourceID,
		Payload:      task.Payload,
		Result:       task.Result,
		Error:        task.Error,
		SubmittedAt:  task.SubmittedAt,
		StartedAt:    task.StartedAt,
		CompletedAt:  task.CompletedAt,
		Preemptible:  task.Preemptible,
		SnapshotAt:   time.Now(),
	}

	data, err := json.MarshalIndent(snapshot, "", "  ")
	if err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal,
			"failed to marshal task snapshot")
	}

	if err := ioutil.WriteFile(s.taskFilePath(task.ID), data, 0644); err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal,
			"failed to write task snapshot")
	}

	return nil
}

func (s *FilePersistenceStore) BatchSave(ctx context.Context, tasks []*GPUTask, state TaskState) error {
	for _, task := range tasks {
		if err := s.Save(ctx, task, state); err != nil {
			return err
		}
	}
	return nil
}

func (s *FilePersistenceStore) LoadAll(ctx context.Context) ([]*TaskSnapshot, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	files, err := ioutil.ReadDir(s.basePath)
	if err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal,
			"failed to read persistence directory")
	}

	var snapshots []*TaskSnapshot
	for _, file := range files {
		if file.IsDir() || filepath.Ext(file.Name()) != ".json" {
			continue
		}

		data, err := ioutil.ReadFile(filepath.Join(s.basePath, file.Name()))
		if err != nil {
			continue
		}

		var snapshot TaskSnapshot
		if err := json.Unmarshal(data, &snapshot); err != nil {
			continue
		}

		snapshots = append(snapshots, &snapshot)
	}

	return snapshots, nil
}

func (s *FilePersistenceStore) LoadByState(ctx context.Context, state TaskState) ([]*TaskSnapshot, error) {
	all, err := s.LoadAll(ctx)
	if err != nil {
		return nil, err
	}

	var filtered []*TaskSnapshot
	for _, s := range all {
		if s.State == state {
			filtered = append(filtered, s)
		}
	}

	return filtered, nil
}

func (s *FilePersistenceStore) LoadByID(ctx context.Context, taskID string) (*TaskSnapshot, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	data, err := ioutil.ReadFile(s.taskFilePath(taskID))
	if err != nil {
		if os.IsNotExist(err) {
			return nil, errors.New(errors.ErrCodeNotFound, "task snapshot not found")
		}
		return nil, errors.Wrap(err, errors.ErrCodeInternal,
			"failed to read task snapshot")
	}

	var snapshot TaskSnapshot
	if err := json.Unmarshal(data, &snapshot); err != nil {
		return nil, errors.Wrap(err, errors.ErrCodeInternal,
			"failed to unmarshal task snapshot")
	}

	return &snapshot, nil
}

func (s *FilePersistenceStore) Delete(ctx context.Context, taskID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := os.Remove(s.taskFilePath(taskID)); err != nil && !os.IsNotExist(err) {
		return errors.Wrap(err, errors.ErrCodeInternal,
			"failed to delete task snapshot")
	}

	return nil
}

func (s *FilePersistenceStore) Clear(ctx context.Context) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	files, err := ioutil.ReadDir(s.basePath)
	if err != nil {
		return errors.Wrap(err, errors.ErrCodeInternal,
			"failed to read persistence directory")
	}

	for _, file := range files {
		if !file.IsDir() && filepath.Ext(file.Name()) == ".json" {
			os.Remove(filepath.Join(s.basePath, file.Name()))
		}
	}

	return nil
}

func (s *FilePersistenceStore) Close() error {
	return nil
}

type PersistenceScheduler struct {
	*GPUSchedulerImpl
	store       TaskPersistenceStore
	autoSave    bool
	autoSaveCh  chan struct{}
	stopCh      chan struct{}
	logger      domain.Logger
}

func NewPersistenceScheduler(
	resourceManager *GPUResourceManagerImpl,
	preemptionEnabled bool,
	workerCount int,
	store TaskPersistenceStore,
	autoSave bool,
	logger domain.Logger,
) *PersistenceScheduler {
	base := NewGPUScheduler(resourceManager, preemptionEnabled, workerCount, logger)

	ps := &PersistenceScheduler{
		GPUSchedulerImpl: base,
		store:            store,
		autoSave:         autoSave,
		autoSaveCh:       make(chan struct{}, 1),
		stopCh:           make(chan struct{}),
		logger:           logger,
	}

	if autoSave {
		go ps.autoSaveLoop()
	}

	return ps
}

func (ps *PersistenceScheduler) SubmitTask(ctx context.Context, task *GPUTask) (*GPUTask, error) {
	created, err := ps.GPUSchedulerImpl.SubmitTask(ctx, task)
	if err != nil {
		return nil, err
	}

	if ps.store != nil {
		if err := ps.store.Save(ctx, created, TaskStateQueued); err != nil {
			ps.logger.Warn("Failed to persist task",
				domain.String("task_id", created.ID),
				domain.Error(err),
			)
		}
	}

	ps.triggerAutoSave()
	return created, nil
}

func (ps *PersistenceScheduler) CancelTask(ctx context.Context, taskID string) error {
	if err := ps.GPUSchedulerImpl.CancelTask(ctx, taskID); err != nil {
		return err
	}

	if ps.store != nil {
		task, _ := ps.GPUSchedulerImpl.GetTaskStatus(ctx, taskID)
		if task != nil {
			ps.store.Save(ctx, task, TaskStateCancelled)
		}
	}

	return nil
}

func (ps *PersistenceScheduler) PreemptTasks(ctx context.Context, minVRAM uint64) ([]*GPUTask, error) {
	preempted, err := ps.GPUSchedulerImpl.PreemptTasks(ctx, minVRAM)
	if err != nil {
		return nil, err
	}

	if ps.store != nil {
		for _, task := range preempted {
			ps.store.Save(ctx, task, TaskStatePreempted)
		}
	}

	return preempted, nil
}

func (ps *PersistenceScheduler) Recover(ctx context.Context) (int, error) {
	if ps.store == nil {
		return 0, nil
	}

	snapshots, err := ps.store.LoadAll(ctx)
	if err != nil {
		return 0, err
	}

	recoveredCount := 0
	for _, snap := range snapshots {
		task := &GPUTask{
			ID:           snap.TaskID,
			Name:         snap.Name,
			Priority:     snap.Priority,
			VRAMRequired: snap.VRAMRequired,
			Status:       domain.StatusPending,
			Payload:      snap.Payload,
			Preemptible:  snap.Preemptible,
			SubmittedAt:  snap.SubmittedAt,
			StartedAt:    snap.StartedAt,
			CompletedAt:  snap.CompletedAt,
			Error:        snap.Error,
			Result:       snap.Result,
		}

		switch snap.State {
		case TaskStateQueued:
			ps.mu.Lock()
			heap.Push(&ps.taskQueue, task)
			ps.mu.Unlock()
			recoveredCount++
			ps.logger.Info("Recovered queued task",
				domain.String("task_id", task.ID),
			)

		case TaskStateRunning:
			ps.mu.Lock()
			task.Status = domain.StatusRunning
			ps.runningTasks[task.ID] = task
			ps.mu.Unlock()
			recoveredCount++
			ps.logger.Info("Recovered running task",
				domain.String("task_id", task.ID),
			)

		case TaskStateCompleted, TaskStateFailed, TaskStatePreempted, TaskStateCancelled:
			ps.mu.Lock()
			ps.completedTasks[task.ID] = task
			ps.mu.Unlock()
			ps.logger.Debug("Recovered completed task",
				domain.String("task_id", task.ID),
				domain.String("state", string(snap.State)),
			)
		}
	}

	ps.logger.Info("Task recovery complete",
		domain.Int("recovered_count", recoveredCount),
		domain.Int("total_snapshots", len(snapshots)),
	)

	go ps.trySchedule()
	return recoveredCount, nil
}

func (ps *PersistenceScheduler) triggerAutoSave() {
	if !ps.autoSave {
		return
	}

	select {
	case ps.autoSaveCh <- struct{}{}:
	default:
	}
}

func (ps *PersistenceScheduler) autoSaveLoop() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			ps.saveAllState()
		case <-ps.autoSaveCh:
			ps.saveAllState()
		case <-ps.stopCh:
			ps.saveAllState()
			return
		}
	}
}

func (ps *PersistenceScheduler) saveAllState() {
	if ps.store == nil {
		return
	}

	ctx := context.Background()

	ps.mu.RLock()
	defer ps.mu.RUnlock()

	for _, task := range ps.taskQueue {
		ps.store.Save(ctx, task, TaskStateQueued)
	}

	for _, task := range ps.runningTasks {
		ps.store.Save(ctx, task, TaskStateRunning)
	}

	for _, task := range ps.completedTasks {
		state := TaskStateCompleted
		if task.Error != "" {
			state = TaskStateFailed
		}
		ps.store.Save(ctx, task, state)
	}

	ps.logger.Debug("Auto-saved task state")
}

func (ps *PersistenceScheduler) Shutdown(ctx context.Context) error {
	close(ps.stopCh)
	if ps.store != nil {
		ps.saveAllState()
		ps.store.Close()
	}
	return ps.GPUSchedulerImpl.Shutdown(ctx)
}

func taskStatusToState(status domain.ResourceStatus) TaskState {
	switch status {
	case domain.StatusPending:
		return TaskStateQueued
	case domain.StatusRunning:
		return TaskStateRunning
	case domain.StatusCompleted:
		return TaskStateCompleted
	case domain.StatusFailed:
		return TaskStateFailed
	case domain.StatusPreempted:
		return TaskStatePreempted
	default:
		return TaskStateQueued
	}
}
