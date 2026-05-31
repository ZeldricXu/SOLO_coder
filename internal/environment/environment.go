package environment

import (
	"fmt"
	"github.com/solocoder/tasktracker/internal/logger"
	"sync"
	"time"
)

type EnvironmentStatus string

const (
	StatusCreating   EnvironmentStatus = "creating"
	StatusRunning    EnvironmentStatus = "running"
	StatusStopped    EnvironmentStatus = "stopped"
	StatusDestroying EnvironmentStatus = "destroying"
	StatusDestroyed  EnvironmentStatus = "destroyed"
	StatusFailed     EnvironmentStatus = "failed"
)

type EnvironmentType string

const (
	TypePreview EnvironmentType = "preview"
	TypeTest    EnvironmentType = "test"
	TypeStaging EnvironmentType = "staging"
)

type Environment struct {
	ID          string            `json:"id"`
	Name        string            `json:"name"`
	Type        EnvironmentType   `json:"type"`
	Status      EnvironmentStatus `json:"status"`
	Owner       string            `json:"owner"`
	ProjectID   string            `json:"project_id"`
	Branch      string            `json:"branch"`
	Config      map[string]interface{} `json:"config"`
	Resources   map[string]string `json:"resources"`
	URLs        []string          `json:"urls"`
	CreatedAt   time.Time         `json:"created_at"`
	StartedAt   *time.Time        `json:"started_at"`
	ExpiresAt   time.Time         `json:"expires_at"`
	DestroyedAt *time.Time        `json:"destroyed_at"`
	AutoDestroy bool              `json:"auto_destroy"`
	Metadata    map[string]string `json:"metadata"`
}

type UsageSnapshot struct {
	Timestamp     time.Time         `json:"timestamp"`
	ActiveEnvs    int               `json:"active_envs"`
	TotalUsageHrs float64           `json:"total_usage_hours"`
	ByType        map[EnvironmentType]int `json:"by_type"`
	ByOwner       map[string]int    `json:"by_owner"`
}

type EnvironmentManager struct {
	mu            sync.RWMutex
	environments  map[string]*Environment
	usageHistory  []UsageSnapshot
	maxEnvs       int
	defaultTTL    time.Duration
	reclaimTicker *time.Ticker
	stopReclaim   chan struct{}
}

type Config struct {
	MaxEnvironments int           `json:"max_environments"`
	DefaultTTL      time.Duration `json:"default_ttl"`
	ReclaimInterval time.Duration `json:"reclaim_interval"`
}

func NewEnvironmentManager(cfg Config) *EnvironmentManager {
	if cfg.MaxEnvironments <= 0 {
		cfg.MaxEnvironments = 10
	}
	if cfg.DefaultTTL <= 0 {
		cfg.DefaultTTL = 24 * time.Hour
	}
	if cfg.ReclaimInterval <= 0 {
		cfg.ReclaimInterval = 1 * time.Hour
	}

	em := &EnvironmentManager{
		environments: make(map[string]*Environment),
		usageHistory: make([]UsageSnapshot, 0),
		maxEnvs:      cfg.MaxEnvironments,
		defaultTTL:   cfg.DefaultTTL,
		stopReclaim:  make(chan struct{}),
	}

	em.startAutoReclaim(cfg.ReclaimInterval)
	return em
}

func (em *EnvironmentManager) Create(env *Environment) (*Environment, error) {
	em.mu.Lock()
	defer em.mu.Unlock()

	activeCount := 0
	for _, e := range em.environments {
		if e.Status == StatusRunning || e.Status == StatusCreating {
			activeCount++
		}
	}

	if activeCount >= em.maxEnvs {
		return nil, fmt.Errorf("maximum active environments reached: %d", em.maxEnvs)
	}

	env.ID = fmt.Sprintf("env_%d", time.Now().UnixNano())
	env.Status = StatusCreating
	env.CreatedAt = time.Now()
	env.ExpiresAt = time.Now().Add(em.defaultTTL)
	env.AutoDestroy = true

	if env.Metadata == nil {
		env.Metadata = make(map[string]string)
	}
	if env.Config == nil {
		env.Config = make(map[string]interface{})
	}
	if env.Resources == nil {
		env.Resources = make(map[string]string)
	}

	em.environments[env.ID] = env

	logger.Info("Environment created",
		logger.String("env_id", env.ID),
		logger.String("name", env.Name),
		logger.String("owner", env.Owner),
	)

	go em.provisionEnvironment(env.ID)

	return env, nil
}

func (em *EnvironmentManager) provisionEnvironment(envID string) {
	time.Sleep(2 * time.Second)

	em.mu.Lock()
	defer em.mu.Unlock()

	env, ok := em.environments[envID]
	if !ok {
		return
	}

	env.Status = StatusRunning
	now := time.Now()
	env.StartedAt = &now
	env.URLs = []string{
		fmt.Sprintf("https://%s.preview.example.com", env.Name),
	}
	env.Resources["cpu"] = "2"
	env.Resources["memory"] = "4Gi"
	env.Resources["storage"] = "10Gi"

	logger.Info("Environment provisioned", logger.String("env_id", envID))
}

func (em *EnvironmentManager) Get(envID string) (*Environment, error) {
	em.mu.RLock()
	defer em.mu.RUnlock()

	env, ok := em.environments[envID]
	if !ok {
		return nil, fmt.Errorf("environment not found: %s", envID)
	}
	return env, nil
}

func (em *EnvironmentManager) List(owner string, envType EnvironmentType) []*Environment {
	em.mu.RLock()
	defer em.mu.RUnlock()

	result := make([]*Environment, 0)
	for _, env := range em.environments {
		if owner != "" && env.Owner != owner {
			continue
		}
		if envType != "" && env.Type != envType {
			continue
		}
		result = append(result, env)
	}
	return result
}

func (em *EnvironmentManager) Stop(envID string) error {
	em.mu.Lock()
	defer em.mu.Unlock()

	env, ok := em.environments[envID]
	if !ok {
		return fmt.Errorf("environment not found: %s", envID)
	}

	if env.Status != StatusRunning {
		return fmt.Errorf("environment not running: %s", env.Status)
	}

	env.Status = StatusStopped
	logger.Info("Environment stopped", logger.String("env_id", envID))
	return nil
}

func (em *EnvironmentManager) Start(envID string) error {
	em.mu.Lock()
	defer em.mu.Unlock()

	env, ok := em.environments[envID]
	if !ok {
		return fmt.Errorf("environment not found: %s", envID)
	}

	if env.Status != StatusStopped {
		return fmt.Errorf("environment not stopped: %s", env.Status)
	}

	env.Status = StatusRunning
	now := time.Now()
	env.StartedAt = &now
	logger.Info("Environment started", logger.String("env_id", envID))
	return nil
}

func (em *EnvironmentManager) Destroy(envID string) error {
	em.mu.Lock()
	defer em.mu.Unlock()

	env, ok := em.environments[envID]
	if !ok {
		return fmt.Errorf("environment not found: %s", envID)
	}

	env.Status = StatusDestroying

	go func(id string) {
		time.Sleep(1 * time.Second)
		em.mu.Lock()
		defer em.mu.Unlock()

		if e, exists := em.environments[id]; exists {
			now := time.Now()
			e.Status = StatusDestroyed
			e.DestroyedAt = &now
			logger.Info("Environment destroyed", logger.String("env_id", id))
		}
	}(envID)

	return nil
}

func (em *EnvironmentManager) ExtendTTL(envID string, duration time.Duration) error {
	em.mu.Lock()
	defer em.mu.Unlock()

	env, ok := em.environments[envID]
	if !ok {
		return fmt.Errorf("environment not found: %s", envID)
	}

	env.ExpiresAt = env.ExpiresAt.Add(duration)
	logger.Info("Environment TTL extended",
		logger.String("env_id", envID),
		logger.String("extended_by", duration.String()),
	)
	return nil
}

func (em *EnvironmentManager) startAutoReclaim(interval time.Duration) {
	em.reclaimTicker = time.NewTicker(interval)

	go func() {
		for {
			select {
			case <-em.reclaimTicker.C:
				em.reclaimExpired()
			case <-em.stopReclaim:
				em.reclaimTicker.Stop()
				return
			}
		}
	}()
}

func (em *EnvironmentManager) reclaimExpired() {
	em.mu.Lock()
	defer em.mu.Unlock()

	now := time.Now()
	reclaimed := 0

	for id, env := range em.environments {
		if env.Status == StatusRunning && env.AutoDestroy && env.ExpiresAt.Before(now) {
			env.Status = StatusDestroying
			reclaimed++

			go func(envID string) {
				time.Sleep(500 * time.Millisecond)
				em.mu.Lock()
				defer em.mu.Unlock()

				if e, exists := em.environments[envID]; exists {
					destroyTime := time.Now()
					e.Status = StatusDestroyed
					e.DestroyedAt = &destroyTime
					logger.Info("Environment auto-reclaimed", logger.String("env_id", envID))
				}
			}(id)
		}
	}

	if reclaimed > 0 {
		logger.Info("Auto-reclaim completed", logger.Int("reclaimed_count", reclaimed))
	}
}

func (em *EnvironmentManager) GetUsageStats() *UsageSnapshot {
	em.mu.RLock()
	defer em.mu.RUnlock()

	snapshot := &UsageSnapshot{
		Timestamp: time.Now(),
		ByType:    make(map[EnvironmentType]int),
		ByOwner:   make(map[string]int),
	}

	var totalUsage float64

	for _, env := range em.environments {
		if env.Status == StatusRunning || env.Status == StatusStopped {
			snapshot.ActiveEnvs++
			snapshot.ByType[env.Type]++
			snapshot.ByOwner[env.Owner]++

			if env.StartedAt != nil {
				duration := time.Since(*env.StartedAt)
				totalUsage += duration.Hours()
			}
		}
	}

	snapshot.TotalUsageHrs = totalUsage

	em.usageHistory = append(em.usageHistory, *snapshot)
	if len(em.usageHistory) > 100 {
		em.usageHistory = em.usageHistory[1:]
	}

	return snapshot
}

func (em *EnvironmentManager) GetUsageHistory() []UsageSnapshot {
	em.mu.RLock()
	defer em.mu.RUnlock()

	history := make([]UsageSnapshot, len(em.usageHistory))
	copy(history, em.usageHistory)
	return history
}

func (em *EnvironmentManager) StopAutoReclaim() {
	close(em.stopReclaim)
}

func (em *EnvironmentManager) GetQuotaUsage() map[string]interface{} {
	em.mu.RLock()
	defer em.mu.RUnlock()

	active := 0
	for _, env := range em.environments {
		if env.Status == StatusRunning || env.Status == StatusCreating {
			active++
		}
	}

	return map[string]interface{}{
		"max_environments":  em.maxEnvs,
		"active_environments": active,
		"available_slots":  em.maxEnvs - active,
		"total_created":    len(em.environments),
	}
}
