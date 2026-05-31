package config

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
	"time"

	"session130/internal/logger"
	"session130/pkg/models"
)

type OperationType string

const (
	OpCreate   OperationType = "create"
	OpUpdate   OperationType = "update"
	OpRollback OperationType = "rollback"
	OpDelete   OperationType = "delete"
)

type OperationStatus string

const (
	StatusPending   OperationStatus = "pending"
	StatusRunning   OperationStatus = "running"
	StatusCompleted OperationStatus = "completed"
	StatusFailed    OperationStatus = "failed"
)

type AsyncOperation struct {
	ID            string
	Type          OperationType
	Namespace     string
	Parameters    map[string]interface{}
	TargetVersion int
	Status        OperationStatus
	Result        *models.Config
	Error         error
	CreatedAt     time.Time
	CompletedAt   *time.Time
	ready         chan struct{}
}

func (op *AsyncOperation) Done() <-chan struct{} {
	return op.ready
}

func (op *AsyncOperation) Wait() (*models.Config, error) {
	<-op.ready
	return op.Result, op.Error
}

type task struct {
	op *AsyncOperation
	fn func() (*models.Config, error)
}

type ConfigStore interface {
	Get(namespace string) (*models.Config, bool)
	Set(namespace string, cfg *models.Config)
	Delete(namespace string)
	ListNamespaces() []string
}

type VersionStore interface {
	AddVersion(namespace string, cfg *models.Config)
	GetVersions(namespace string) ([]*models.Config, bool)
	GetByVersion(namespace string, version int) (*models.Config, bool)
	Delete(namespace string)
}

type ConfigManager interface {
	CreateConfig(namespace string, parameters map[string]interface{}) (*models.Config, error)
	CreateConfigAsync(namespace string, parameters map[string]interface{}) *AsyncOperation
	UpdateConfig(namespace string, parameters map[string]interface{}) (*models.Config, error)
	UpdateConfigAsync(namespace string, parameters map[string]interface{}) *AsyncOperation
	GetConfig(namespace string) (*models.Config, error)
	GetConfigByVersion(namespace string, version int) (*models.Config, error)
	Rollback(namespace string, targetVersion int) (*models.Config, error)
	RollbackAsync(namespace string, targetVersion int) *AsyncOperation
	GetVersionHistory(namespace string) ([]*models.Config, error)
	ListNamespaces() []string
	DeleteConfig(namespace string) error
	DeleteConfigAsync(namespace string) *AsyncOperation
	Subscribe(listener func(*models.Config))
	GetOperation(opID string) (*AsyncOperation, error)
	ListOperations(status OperationStatus) []*AsyncOperation
	GetWorkerStats() map[string]interface{}
	Shutdown()
}

type memoryConfigStore struct {
	mu      sync.RWMutex
	configs map[string]*models.Config
}

func newMemoryConfigStore() *memoryConfigStore {
	return &memoryConfigStore{
		configs: make(map[string]*models.Config),
	}
}

func (s *memoryConfigStore) Get(namespace string) (*models.Config, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	cfg, exists := s.configs[namespace]
	return cfg, exists
}

func (s *memoryConfigStore) Set(namespace string, cfg *models.Config) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.configs[namespace] = cfg
}

func (s *memoryConfigStore) Delete(namespace string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.configs, namespace)
}

func (s *memoryConfigStore) ListNamespaces() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	namespaces := make([]string, 0, len(s.configs))
	for ns := range s.configs {
		namespaces = append(namespaces, ns)
	}
	return namespaces
}

type memoryVersionStore struct {
	mu            sync.RWMutex
	versionList   map[string][]*models.Config
	versionIndex  map[string]map[int]*models.Config
}

func newMemoryVersionStore() *memoryVersionStore {
	return &memoryVersionStore{
		versionList:  make(map[string][]*models.Config),
		versionIndex: make(map[string]map[int]*models.Config),
	}
}

func (s *memoryVersionStore) AddVersion(namespace string, cfg *models.Config) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.versionList[namespace] = append(s.versionList[namespace], cfg)

	if _, exists := s.versionIndex[namespace]; !exists {
		s.versionIndex[namespace] = make(map[int]*models.Config)
	}
	s.versionIndex[namespace][cfg.Version] = cfg
}

func (s *memoryVersionStore) GetVersions(namespace string) ([]*models.Config, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	versions, exists := s.versionList[namespace]
	return versions, exists
}

func (s *memoryVersionStore) GetByVersion(namespace string, version int) (*models.Config, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	index, exists := s.versionIndex[namespace]
	if !exists {
		return nil, false
	}
	cfg, exists := index[version]
	return cfg, exists
}

func (s *memoryVersionStore) Delete(namespace string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.versionList, namespace)
	delete(s.versionIndex, namespace)
}

type Manager struct {
	mu             sync.RWMutex
	configStore    ConfigStore
	versionStore   VersionStore
	listeners      []func(*models.Config)
	taskQueue      chan *task
	workerCount    int
	activeWorkers  int
	operations     map[string]*AsyncOperation
	opMu           sync.RWMutex
	stopChan       chan struct{}
	wg             sync.WaitGroup
	autoScale      bool
	minWorkers     int
	maxWorkers     int
}

var (
	instance *Manager
	once     sync.Once
)

func NewManager() *Manager {
	return NewManagerWithWorkers(1, 32, true)
}

func NewManagerWithWorkers(minWorkers, maxWorkers int, autoScale bool) *Manager {
	initialWorkers := minWorkers
	if initialWorkers < 1 {
		initialWorkers = 1
	}
	m := &Manager{
		configStore:   newMemoryConfigStore(),
		versionStore:  newMemoryVersionStore(),
		taskQueue:     make(chan *task, 10000),
		workerCount:   initialWorkers,
		minWorkers:    minWorkers,
		maxWorkers:    maxWorkers,
		operations:    make(map[string]*AsyncOperation),
		stopChan:      make(chan struct{}),
		autoScale:     autoScale,
	}
	m.startWorkers(initialWorkers)
	if autoScale {
		go m.autoScaler()
	}
	return m
}

func GetManager() *Manager {
	once.Do(func() {
		instance = NewManager()
	})
	return instance
}

func (m *Manager) startWorkers(count int) {
	for i := 0; i < count; i++ {
		m.wg.Add(1)
		m.activeWorkers++
		go m.worker()
	}
}

func (m *Manager) stopWorkers(count int) {
	for i := 0; i < count && m.activeWorkers > m.minWorkers; i++ {
		m.stopChan <- struct{}{}
		m.activeWorkers--
	}
}

func (m *Manager) worker() {
	defer m.wg.Done()
	for {
		select {
		case task := <-m.taskQueue:
			m.executeTask(task)
		case <-m.stopChan:
			return
		}
	}
}

func (m *Manager) executeTask(t *task) {
	t.op.Status = StatusRunning
	result, err := t.fn()
	t.op.Result = result
	t.op.Error = err
	if err != nil {
		t.op.Status = StatusFailed
	} else {
		t.op.Status = StatusCompleted
	}
	now := time.Now()
	t.op.CompletedAt = &now
	close(t.op.ready)

	m.opMu.Lock()
	delete(m.operations, t.op.ID)
	m.opMu.Unlock()

	if result != nil && err == nil {
		m.notifyListeners(result)
	}
}

func (m *Manager) autoScaler() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		queueLen := len(m.taskQueue)
		desiredWorkers := m.workerCount

		if queueLen > 100 && m.workerCount < m.maxWorkers {
			desiredWorkers = m.workerCount + 2
			if desiredWorkers > m.maxWorkers {
				desiredWorkers = m.maxWorkers
			}
			m.startWorkers(desiredWorkers - m.workerCount)
			m.workerCount = desiredWorkers
			logger.Info("", "scaling up config workers", map[string]interface{}{
				"queue_length": queueLen,
				"workers":      m.workerCount,
			})
		} else if queueLen < 10 && m.workerCount > m.minWorkers {
			toStop := (m.workerCount - m.minWorkers) / 2
			if toStop > 0 {
				m.stopWorkers(toStop)
				m.workerCount -= toStop
				logger.Info("", "scaling down config workers", map[string]interface{}{
					"queue_length": queueLen,
					"workers":      m.workerCount,
				})
			}
		}
	}
}

func (m *Manager) submitAsync(opType OperationType, namespace string, params map[string]interface{}, targetVersion int, fn func() (*models.Config, error)) *AsyncOperation {
	op := &AsyncOperation{
		ID:            generateOpID(),
		Type:          opType,
		Namespace:     namespace,
		Parameters:    params,
		TargetVersion: targetVersion,
		Status:        StatusPending,
		CreatedAt:     time.Now(),
		ready:         make(chan struct{}),
	}

	m.opMu.Lock()
	m.operations[op.ID] = op
	m.opMu.Unlock()

	m.taskQueue <- &task{op: op, fn: fn}

	logger.Debug("", "async operation submitted", map[string]interface{}{
		"op_id":     op.ID,
		"op_type":   op.Type,
		"namespace": namespace,
	})

	return op
}

func (m *Manager) GetOperation(opID string) (*AsyncOperation, error) {
	m.opMu.RLock()
	defer m.opMu.RUnlock()

	op, exists := m.operations[opID]
	if !exists {
		return nil, fmt.Errorf("operation %s not found", opID)
	}
	return op, nil
}

func (m *Manager) ListOperations(status OperationStatus) []*AsyncOperation {
	m.opMu.RLock()
	defer m.opMu.RUnlock()

	ops := make([]*AsyncOperation, 0, len(m.operations))
	for _, op := range m.operations {
		if status == "" || op.Status == status {
			ops = append(ops, op)
		}
	}
	return ops
}

func (m *Manager) GetWorkerStats() map[string]interface{} {
	m.opMu.RLock()
	defer m.opMu.RUnlock()

	return map[string]interface{}{
		"active_workers": m.activeWorkers,
		"queue_length":   len(m.taskQueue),
		"pending_ops":    len(m.operations),
		"min_workers":    m.minWorkers,
		"max_workers":    m.maxWorkers,
		"auto_scale":     m.autoScale,
	}
}

func generateConfigID(namespace string, version int) string {
	h := sha256.New()
	h.Write([]byte(fmt.Sprintf("%s:%d:%d", namespace, version, time.Now().UnixNano())))
	return "cfg_" + hex.EncodeToString(h.Sum(nil))[:8]
}

func generateOpID() string {
	h := sha256.New()
	h.Write([]byte(fmt.Sprintf("op:%d", time.Now().UnixNano())))
	return "op_" + hex.EncodeToString(h.Sum(nil))[:12]
}

func (m *Manager) getNextVersion(namespace string) int {
	versions, exists := m.versionStore.GetVersions(namespace)
	if !exists || len(versions) == 0 {
		return 1
	}
	return versions[len(versions)-1].Version + 1
}

func (m *Manager) createConfigRecord(namespace string, parameters map[string]interface{}, version int) *models.Config {
	cfg := &models.Config{
		ConfigID:   generateConfigID(namespace, version),
		Namespace:  namespace,
		Version:    version,
		Parameters: parameters,
		Enabled:    true,
		AppliedAt:  time.Now(),
	}

	m.configStore.Set(namespace, cfg)
	m.versionStore.AddVersion(namespace, cfg)

	logger.Info("", "config created", map[string]interface{}{
		"config_id": cfg.ConfigID,
		"namespace": namespace,
		"version":   version,
	})

	return cfg
}

func (m *Manager) CreateConfig(namespace string, parameters map[string]interface{}) (*models.Config, error) {
	if namespace == "" {
		return nil, errors.New("namespace is required")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	return m.createConfigInternal(namespace, parameters)
}

func (m *Manager) CreateConfigAsync(namespace string, parameters map[string]interface{}) *AsyncOperation {
	return m.submitAsync(OpCreate, namespace, parameters, 0, func() (*models.Config, error) {
		m.mu.Lock()
		defer m.mu.Unlock()
		return m.createConfigInternal(namespace, parameters)
	})
}

func (m *Manager) createConfigInternal(namespace string, parameters map[string]interface{}) (*models.Config, error) {
	if namespace == "" {
		return nil, errors.New("namespace is required")
	}

	newVersion := m.getNextVersion(namespace)
	return m.createConfigRecord(namespace, parameters, newVersion), nil
}

func (m *Manager) UpdateConfig(namespace string, parameters map[string]interface{}) (*models.Config, error) {
	if namespace == "" {
		return nil, errors.New("namespace is required")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	return m.updateConfigInternal(namespace, parameters)
}

func (m *Manager) UpdateConfigAsync(namespace string, parameters map[string]interface{}) *AsyncOperation {
	return m.submitAsync(OpUpdate, namespace, parameters, 0, func() (*models.Config, error) {
		m.mu.Lock()
		defer m.mu.Unlock()
		return m.updateConfigInternal(namespace, parameters)
	})
}

func (m *Manager) updateConfigInternal(namespace string, parameters map[string]interface{}) (*models.Config, error) {
	if namespace == "" {
		return nil, errors.New("namespace is required")
	}

	if _, exists := m.configStore.Get(namespace); !exists {
		return nil, fmt.Errorf("config for namespace %s not found", namespace)
	}

	newVersion := m.getNextVersion(namespace)
	return m.createConfigRecord(namespace, parameters, newVersion), nil
}

func (m *Manager) GetConfig(namespace string) (*models.Config, error) {
	cfg, exists := m.configStore.Get(namespace)
	if !exists {
		return nil, fmt.Errorf("config for namespace %s not found", namespace)
	}
	return cfg, nil
}

func (m *Manager) GetConfigByVersion(namespace string, version int) (*models.Config, error) {
	cfg, exists := m.versionStore.GetByVersion(namespace, version)
	if !exists {
		if _, nsExists := m.versionStore.GetVersions(namespace); !nsExists {
			return nil, fmt.Errorf("config for namespace %s not found", namespace)
		}
		return nil, fmt.Errorf("config version %d not found for namespace %s", version, namespace)
	}
	return cfg, nil
}

func (m *Manager) Rollback(namespace string, targetVersion int) (*models.Config, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	return m.rollbackInternal(namespace, targetVersion)
}

func (m *Manager) RollbackAsync(namespace string, targetVersion int) *AsyncOperation {
	return m.submitAsync(OpRollback, namespace, nil, targetVersion, func() (*models.Config, error) {
		m.mu.Lock()
		defer m.mu.Unlock()
		return m.rollbackInternal(namespace, targetVersion)
	})
}

func (m *Manager) rollbackInternal(namespace string, targetVersion int) (*models.Config, error) {
	targetCfg, exists := m.versionStore.GetByVersion(namespace, targetVersion)
	if !exists {
		if _, nsExists := m.versionStore.GetVersions(namespace); !nsExists {
			return nil, fmt.Errorf("config for namespace %s not found", namespace)
		}
		return nil, fmt.Errorf("target version %d not found", targetVersion)
	}

	newVersion := m.getNextVersion(namespace)
	rollbackCfg := m.createConfigRecord(namespace, cloneMap(targetCfg.Parameters), newVersion)

	logger.Info("", "config rolled back", map[string]interface{}{
		"config_id":       rollbackCfg.ConfigID,
		"namespace":       namespace,
		"target_version":  targetVersion,
		"current_version": newVersion,
	})

	return rollbackCfg, nil
}

func (m *Manager) GetVersionHistory(namespace string) ([]*models.Config, error) {
	versions, exists := m.versionStore.GetVersions(namespace)
	if !exists {
		return nil, fmt.Errorf("config for namespace %s not found", namespace)
	}

	result := make([]*models.Config, len(versions))
	copy(result, versions)
	return result, nil
}

func (m *Manager) ListNamespaces() []string {
	return m.configStore.ListNamespaces()
}

func (m *Manager) DeleteConfig(namespace string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	return m.deleteConfigInternal(namespace)
}

func (m *Manager) DeleteConfigAsync(namespace string) *AsyncOperation {
	return m.submitAsync(OpDelete, namespace, nil, 0, func() (*models.Config, error) {
		m.mu.Lock()
		defer m.mu.Unlock()
		err := m.deleteConfigInternal(namespace)
		return nil, err
	})
}

func (m *Manager) deleteConfigInternal(namespace string) error {
	if _, exists := m.configStore.Get(namespace); !exists {
		return fmt.Errorf("config for namespace %s not found", namespace)
	}

	m.configStore.Delete(namespace)
	m.versionStore.Delete(namespace)

	logger.Info("", "config deleted", map[string]interface{}{
		"namespace": namespace,
	})

	return nil
}

func (m *Manager) Subscribe(listener func(*models.Config)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.listeners = append(m.listeners, listener)
}

func (m *Manager) notifyListeners(cfg *models.Config) {
	for _, listener := range m.listeners {
		go listener(cfg)
	}
}

func (m *Manager) Shutdown() {
	close(m.taskQueue)
	m.wg.Wait()
	logger.Info("", "config manager shutdown complete", nil)
}

func cloneMap(src map[string]interface{}) map[string]interface{} {
	dst := make(map[string]interface{})
	for k, v := range src {
		dst[k] = v
	}
	return dst
}
