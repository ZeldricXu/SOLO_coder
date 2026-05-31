package sidecar

import (
	"errors"
	"sync"
	"time"

	"github.com/enterprise/config-platform/pkg/utils"
)

type ResourceLimits struct {
	CPULimit    string `json:"cpu_limit"`
	MemoryLimit string `json:"memory_limit"`
	CPURequest  string `json:"cpu_request"`
	MemoryRequest string `json:"memory_request"`
}

type InjectionPolicy struct {
	Enabled       bool              `json:"enabled"`
	Namespaces    []string          `json:"namespaces"`
	LabelSelector map[string]string `json:"label_selector"`
	Strategy      string            `json:"strategy"`
}

type SidecarConfig struct {
	ConfigID       string                 `json:"config_id"`
	Version        int                    `json:"version"`
	Image          string                 `json:"image"`
	Resources      ResourceLimits         `json:"resources"`
	EnvVars        map[string]string      `json:"env_vars"`
	Mounts         []MountPoint           `json:"mounts"`
	InjectionPolicy InjectionPolicy       `json:"injection_policy"`
	HotReload      bool                   `json:"hot_reload"`
	CreatedAt      time.Time              `json:"created_at"`
	UpdatedAt      time.Time              `json:"updated_at"`
}

type MountPoint struct {
	Name      string `json:"name"`
	MountPath string `json:"mount_path"`
	ReadOnly  bool   `json:"read_only"`
}

type SidecarInstance struct {
	InstanceID string       `json:"instance_id"`
	ConfigID   string       `json:"config_id"`
	PodName    string       `json:"pod_name"`
	Namespace  string       `json:"namespace"`
	Status     string       `json:"status"`
	Config     SidecarConfig `json:"config"`
	InjectedAt time.Time    `json:"injected_at"`
}

type HotUpdateRequest struct {
	ConfigID string                 `json:"config_id"`
	Updates  map[string]interface{} `json:"updates"`
}

type Manager struct {
	configs    map[string]*SidecarConfig
	instances  map[string]*SidecarInstance
	callbacks  map[string][]func(*SidecarConfig)
	mu         sync.RWMutex
}

var (
	instance *Manager
	once     sync.Once
)

func GetManager() *Manager {
	once.Do(func() {
		instance = &Manager{
			configs:   make(map[string]*SidecarConfig),
			instances: make(map[string]*SidecarInstance),
			callbacks: make(map[string][]func(*SidecarConfig)),
		}
	})
	return instance
}

func (m *Manager) CreateConfig(config *SidecarConfig) (*SidecarConfig, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	config.ConfigID = utils.GenerateID("sc")
	config.Version = 1
	config.CreatedAt = time.Now().UTC()
	config.UpdatedAt = time.Now().UTC()

	if config.Resources.CPULimit == "" {
		config.Resources.CPULimit = "500m"
	}
	if config.Resources.MemoryLimit == "" {
		config.Resources.MemoryLimit = "256Mi"
	}

	m.configs[config.ConfigID] = config
	return config, nil
}

func (m *Manager) GetConfig(configID string) (*SidecarConfig, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	cfg, exists := m.configs[configID]
	if !exists {
		return nil, errors.New("config not found")
	}
	return cfg, nil
}

func (m *Manager) UpdateConfig(configID string, updates map[string]interface{}) (*SidecarConfig, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	cfg, exists := m.configs[configID]
	if !exists {
		return nil, errors.New("config not found")
	}

	if image, ok := updates["image"].(string); ok {
		cfg.Image = image
	}
	if env, ok := updates["env_vars"].(map[string]string); ok {
		cfg.EnvVars = env
	}
	if resources, ok := updates["resources"].(ResourceLimits); ok {
		cfg.Resources = resources
	}

	cfg.Version++
	cfg.UpdatedAt = time.Now().UTC()

	if cfg.HotReload {
		go m.notifyCallbacks(cfg)
	}

	return cfg, nil
}

func (m *Manager) DeleteConfig(configID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.configs[configID]; !exists {
		return errors.New("config not found")
	}
	delete(m.configs, configID)
	return nil
}

func (m *Manager) ListConfigs() []*SidecarConfig {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*SidecarConfig, 0, len(m.configs))
	for _, cfg := range m.configs {
		result = append(result, cfg)
	}
	return result
}

func (m *Manager) InjectSidecar(podName, namespace, configID string, labels map[string]string) (*SidecarInstance, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	cfg, exists := m.configs[configID]
	if !exists {
		return nil, errors.New("config not found")
	}

	if !cfg.InjectionPolicy.Enabled {
		return nil, errors.New("injection not enabled for this config")
	}

	if len(cfg.InjectionPolicy.Namespaces) > 0 {
		nsMatch := false
		for _, ns := range cfg.InjectionPolicy.Namespaces {
			if ns == namespace {
				nsMatch = true
				break
			}
		}
		if !nsMatch {
			return nil, errors.New("namespace not allowed for injection")
		}
	}

	for k, v := range cfg.InjectionPolicy.LabelSelector {
		if labels[k] != v {
			return nil, errors.New("label selector mismatch")
		}
	}

	instance := &SidecarInstance{
		InstanceID: utils.GenerateID("si"),
		ConfigID:   configID,
		PodName:    podName,
		Namespace:  namespace,
		Status:     "injecting",
		Config:     *cfg,
		InjectedAt: time.Now().UTC(),
	}

	m.instances[instance.InstanceID] = instance

	go func() {
		time.Sleep(100 * time.Millisecond)
		m.mu.Lock()
		instance.Status = "running"
		m.mu.Unlock()
	}()

	return instance, nil
}

func (m *Manager) GetInstance(instanceID string) (*SidecarInstance, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	inst, exists := m.instances[instanceID]
	if !exists {
		return nil, errors.New("instance not found")
	}
	return inst, nil
}

func (m *Manager) ListInstances() []*SidecarInstance {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*SidecarInstance, 0, len(m.instances))
	for _, inst := range m.instances {
		result = append(result, inst)
	}
	return result
}

func (m *Manager) RemoveSidecar(instanceID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	inst, exists := m.instances[instanceID]
	if !exists {
		return errors.New("instance not found")
	}

	inst.Status = "removing"

	go func() {
		time.Sleep(100 * time.Millisecond)
		m.mu.Lock()
		delete(m.instances, instanceID)
		m.mu.Unlock()
	}()

	return nil
}

func (m *Manager) RegisterHotReloadCallback(configID string, callback func(*SidecarConfig)) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.callbacks[configID] = append(m.callbacks[configID], callback)
}

func (m *Manager) notifyCallbacks(cfg *SidecarConfig) {
	m.mu.RLock()
	callbacks := m.callbacks[cfg.ConfigID]
	m.mu.RUnlock()

	for _, cb := range callbacks {
		cb(cfg)
	}
}

func (m *Manager) HotUpdate(configID string, updates map[string]interface{}) error {
	m.mu.RLock()
	cfg, exists := m.configs[configID]
	m.mu.RUnlock()

	if !exists {
		return errors.New("config not found")
	}

	if !cfg.HotReload {
		return errors.New("hot reload not enabled for this config")
	}

	_, err := m.UpdateConfig(configID, updates)
	return err
}

func (m *Manager) SetInjectionPolicy(configID string, policy InjectionPolicy) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	cfg, exists := m.configs[configID]
	if !exists {
		return errors.New("config not found")
	}

	cfg.InjectionPolicy = policy
	cfg.UpdatedAt = time.Now().UTC()
	return nil
}

func (m *Manager) SetResourceLimits(configID string, limits ResourceLimits) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	cfg, exists := m.configs[configID]
	if !exists {
		return errors.New("config not found")
	}

	cfg.Resources = limits
	cfg.UpdatedAt = time.Now().UTC()
	return nil
}
