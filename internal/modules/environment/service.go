package environment

import (
	"depguard/internal/common/utils"
	"depguard/internal/database"
	"depguard/internal/logger"
	apperrors "depguard/pkg/errors"
	"strconv"
	"sync"
	"time"

	"go.uber.org/zap"
)

type EnvironmentRepository interface {
	Create(env *Environment) error
	Update(id string, updates map[string]interface{}) error
	Delete(id string) error
	GetByID(id string) (*Environment, error)
	GetByEnvID(envID string) (*Environment, error)
	List(page, pageSize int, ownerID, status, envType string) ([]Environment, int64, error)
	GetExpiredEnvs() ([]Environment, error)
}

type UsageStatsRepository interface {
	Create(stats *UsageStats) error
	GetByEnvID(envID string, startDate, endDate string) ([]UsageStats, error)
	GetAggregatedStats(envID string) (map[string]interface{}, error)
}

type RequestRepository interface {
	Create(req *EnvironmentRequest) error
	Update(id string, updates map[string]interface{}) error
	GetByID(id string) (*EnvironmentRequest, error)
	GetByRequestID(requestID string) (*EnvironmentRequest, error)
	List(page, pageSize int, requesterID, status string) ([]EnvironmentRequest, int64, error)
}

type ConfigChangeListener func(configKey string, oldValue, newValue map[string]interface{})

type EnvironmentService struct {
	envRepo    EnvironmentRepository
	statsRepo  UsageStatsRepository
	reqRepo    RequestRepository
	configCache   sync.Map
	configMutex   sync.RWMutex
	listeners     map[string][]ConfigChangeListener
	listenerMutex sync.RWMutex
	defaultConfig map[string]interface{}
}

var (
	defaultEnvConfig = map[string]interface{}{
		"default_duration_hours":      24,
		"max_duration_hours":          720,
		"max_concurrent_envs":         10,
		"auto_recycle_enabled":        true,
		"idle_recycle_minutes":        120,
		"notify_before_recycle_min":   30,
		"default_cpu_cores":           2,
		"default_memory_gb":           4,
		"default_storage_gb":          20,
		"resource_quota_cpu_cores":    8,
		"resource_quota_memory_gb":   16,
		"resource_quota_storage_gb":  100,
	}
)

func NewEnvironmentService() *EnvironmentService {
	svc := &EnvironmentService{
		envRepo:       NewEnvironmentRepository(),
		statsRepo:     NewUsageStatsRepository(),
		reqRepo:       NewRequestRepository(),
		listeners:     make(map[string][]ConfigChangeListener),
		defaultConfig: make(map[string]interface{}),
	}

	for k, v := range defaultEnvConfig {
		svc.defaultConfig[k] = v
	}

	svc.loadAllConfigs()
	svc.AddConfigChangeListener("environment.default", svc.onDefaultConfigChanged)

	return svc
}

type envRepo struct{}

func NewEnvironmentRepository() EnvironmentRepository {
	return &envRepo{}
}

func (r *envRepo) Create(env *Environment) error {
	env.ID = utils.GenerateID("env")
	return database.DB.Create(env).Error
}

func (r *envRepo) Update(id string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	return database.DB.Model(&Environment{}).Where("id = ?", id).Updates(updates).Error
}

func (r *envRepo) Delete(id string) error {
	return database.DB.Delete(&Environment{}, "id = ?", id).Error
}

func (r *envRepo) GetByID(id string) (*Environment, error) {
	var env Environment
	err := database.DB.Where("id = ?", id).First(&env).Error
	if err != nil {
		return nil, err
	}
	return &env, nil
}

func (r *envRepo) GetByEnvID(envID string) (*Environment, error) {
	var env Environment
	err := database.DB.Where("env_id = ?", envID).First(&env).Error
	if err != nil {
		return nil, err
	}
	return &env, nil
}

func (r *envRepo) List(page, pageSize int, ownerID, status, envType string) ([]Environment, int64, error) {
	var envs []Environment
	var total int64
	query := database.DB.Model(&Environment{})

	if ownerID != "" {
		query = query.Where("owner_id = ?", ownerID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if envType != "" {
		query = query.Where("type = ?", envType)
	}

	err := query.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err = query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&envs).Error
	return envs, total, err
}

func (r *envRepo) GetExpiredEnvs() ([]Environment, error) {
	var envs []Environment
	now := time.Now()
	err := database.DB.Where("expires_at < ? AND status = ?", now, "running").Find(&envs).Error
	return envs, err
}

type usageStatsRepo struct{}

func NewUsageStatsRepository() UsageStatsRepository {
	return &usageStatsRepo{}
}

func (r *usageStatsRepo) Create(stats *UsageStats) error {
	stats.ID = utils.GenerateID("ust")
	return database.DB.Create(stats).Error
}

func (r *usageStatsRepo) GetByEnvID(envID string, startDate, endDate string) ([]UsageStats, error) {
	var stats []UsageStats
	query := database.DB.Where("env_id = ?", envID)
	if startDate != "" {
		query = query.Where("date >= ?", startDate)
	}
	if endDate != "" {
		query = query.Where("date <= ?", endDate)
	}
	err := query.Order("date DESC").Find(&stats).Error
	return stats, err
}

func (r *usageStatsRepo) GetAggregatedStats(envID string) (map[string]interface{}, error) {
	var result struct {
		AvgCPU      float64
		AvgMemory   float64
		AvgStorage  float64
		TotalUptime int64
		TotalCost   float64
	}

	err := database.DB.Model(&UsageStats{}).
		Select("AVG(cpu_usage) as avg_cpu, AVG(memory_usage) as avg_memory, AVG(storage_usage) as avg_storage, SUM(uptime_minutes) as total_uptime, SUM(cost) as total_cost").
		Where("env_id = ?", envID).
		Scan(&result).Error

	if err != nil {
		return nil, err
	}

	return map[string]interface{}{
		"avg_cpu":      result.AvgCPU,
		"avg_memory":   result.AvgMemory,
		"avg_storage":  result.AvgStorage,
		"total_uptime": result.TotalUptime,
		"total_cost":   result.TotalCost,
	}, nil
}

type requestRepo struct{}

func NewRequestRepository() RequestRepository {
	return &requestRepo{}
}

func (r *requestRepo) Create(req *EnvironmentRequest) error {
	req.ID = utils.GenerateID("ereq")
	return database.DB.Create(req).Error
}

func (r *requestRepo) Update(id string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	return database.DB.Model(&EnvironmentRequest{}).Where("id = ?", id).Updates(updates).Error
}

func (r *requestRepo) GetByID(id string) (*EnvironmentRequest, error) {
	var req EnvironmentRequest
	err := database.DB.Where("id = ?", id).First(&req).Error
	if err != nil {
		return nil, err
	}
	return &req, nil
}

func (r *requestRepo) GetByRequestID(requestID string) (*EnvironmentRequest, error) {
	var req EnvironmentRequest
	err := database.DB.Where("request_id = ?", requestID).First(&req).Error
	if err != nil {
		return nil, err
	}
	return &req, nil
}

func (r *requestRepo) List(page, pageSize int, requesterID, status string) ([]EnvironmentRequest, int64, error) {
	var reqs []EnvironmentRequest
	var total int64
	query := database.DB.Model(&EnvironmentRequest{})

	if requesterID != "" {
		query = query.Where("requester_id = ?", requesterID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	err := query.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err = query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&reqs).Error
	return reqs, total, err
}

func (s *EnvironmentService) loadAllConfigs() {
	var configs []DynamicConfig
	if err := database.DB.Where("is_active = ?", true).Find(&configs).Error; err != nil {
		logger.Log.Warn("Failed to load dynamic configs", zap.Error(err))
		return
	}

	for _, cfg := range configs {
		s.configCache.Store(cfg.ConfigKey, cfg.ConfigValue)
	}

	logger.Log.Info("Dynamic configs loaded", zap.Int("count", len(configs)))
}

func (s *EnvironmentService) GetConfig(configKey string) map[string]interface{} {
	s.configMutex.RLock()
	defer s.configMutex.RUnlock()

	if val, ok := s.configCache.Load(configKey); ok {
		return val.(map[string]interface{})
	}

	if val, ok := s.defaultConfig[configKey]; ok {
		return val.(map[string]interface{})
	}

	return nil
}

func (s *EnvironmentService) GetConfigValue(configKey string, key string) interface{} {
	cfg := s.GetConfig(configKey)
	if cfg == nil {
		return nil
	}
	return cfg[key]
}

func (s *EnvironmentService) GetConfigInt(configKey string, key string, defaultValue int) int {
	val := s.GetConfigValue(configKey, key)
	if v, ok := val.(float64); ok {
		return int(v)
	}
	if v, ok := val.(int); ok {
		return v
	}
	return defaultValue
}

func (s *EnvironmentService) GetConfigBool(configKey string, key string, defaultValue bool) bool {
	val := s.GetConfigValue(configKey, key)
	if v, ok := val.(bool); ok {
		return v
	}
	return defaultValue
}

func (s *EnvironmentService) SetConfig(configKey string, value map[string]interface{}, modifiedBy string, reason string) (*DynamicConfig, error) {
	s.configMutex.Lock()
	defer s.configMutex.Unlock()

	var existing DynamicConfig
	err := database.DB.Where("config_key = ?", configKey).First(&existing).Error

	var oldValue map[string]interface{}
	changeType := "create"
	newVersion := 1

	if err == nil {
		oldValue = existing.ConfigValue
		changeType = "update"
		newVersion = existing.Version + 1

		existing.ConfigValue = value
		existing.Version = newVersion
		existing.LastModifiedBy = modifiedBy
		existing.UpdatedAt = time.Now()

		if err := database.DB.Save(&existing).Error; err != nil {
			return nil, apperrors.Wrap(500, "failed to update config", err)
		}
	} else {
		config := &DynamicConfig{
			ConfigKey:    configKey,
			ConfigValue:  value,
			Description:  reason,
			IsActive:     true,
			Version:      newVersion,
			LastModifiedBy: modifiedBy,
		}

		if err := database.DB.Create(config).Error; err != nil {
			return nil, apperrors.Wrap(500, "failed to create config", err)
		}
		existing = *config
	}

	s.configCache.Store(configKey, value)

	changelog := &ConfigChangeLog{
		ConfigKey:    configKey,
		OldValue:     oldValue,
		NewValue:     value,
		ChangeType:   changeType,
		ChangedBy:    modifiedBy,
		ChangeReason: reason,
	}
	database.DB.Create(changelog)

	s.notifyListeners(configKey, oldValue, value)

	logger.Log.Info("Dynamic config updated",
		zap.String("key", configKey),
		zap.String("type", changeType),
		zap.Int("version", newVersion))

	return &existing, nil
}

func (s *EnvironmentService) DeleteConfig(configKey string, modifiedBy string) error {
	s.configMutex.Lock()
	defer s.configMutex.Unlock()

	var existing DynamicConfig
	if err := database.DB.Where("config_key = ?", configKey).First(&existing).Error; err != nil {
		return apperrors.ErrNotFound
	}

	if err := database.DB.Delete(&existing).Error; err != nil {
		return apperrors.Wrap(500, "failed to delete config", err)
	}

	s.configCache.Delete(configKey)

	changelog := &ConfigChangeLog{
		ConfigKey:    configKey,
		OldValue:     existing.ConfigValue,
		NewValue:     nil,
		ChangeType:   "delete",
		ChangedBy:    modifiedBy,
		ChangeReason: "Config deleted",
	}
	database.DB.Create(changelog)

	s.notifyListeners(configKey, existing.ConfigValue, nil)

	logger.Log.Info("Dynamic config deleted", zap.String("key", configKey))
	return nil
}

func (s *EnvironmentService) ListConfigs() ([]DynamicConfig, error) {
	var configs []DynamicConfig
	err := database.DB.Order("created_at DESC").Find(&configs).Error
	return configs, err
}

func (s *EnvironmentService) GetConfigChangeLogs(configKey string, limit int) ([]ConfigChangeLog, error) {
	if limit < 1 || limit > 100 {
		limit = 20
	}

	var logs []ConfigChangeLog
	query := database.DB.Model(&ConfigChangeLog{})
	if configKey != "" {
		query = query.Where("config_key = ?", configKey)
	}
	err := query.Order("created_at DESC").Limit(limit).Find(&logs).Error
	return logs, err
}

func (s *EnvironmentService) AddConfigChangeListener(configKey string, listener ConfigChangeListener) {
	s.listenerMutex.Lock()
	defer s.listenerMutex.Unlock()

	s.listeners[configKey] = append(s.listeners[configKey], listener)
	logger.Log.Debug("Config change listener added", zap.String("key", configKey))
}

func (s *EnvironmentService) RemoveConfigChangeListener(configKey string, listener ConfigChangeListener) {
	s.listenerMutex.Lock()
	defer s.listenerMutex.Unlock()

	if listeners, ok := s.listeners[configKey]; ok {
		for i, l := range listeners {
			if &l == &listener {
				s.listeners[configKey] = append(listeners[:i], listeners[i+1:]...)
				break
			}
		}
	}
}

func (s *EnvironmentService) notifyListeners(configKey string, oldValue, newValue map[string]interface{}) {
	s.listenerMutex.RLock()
	defer s.listenerMutex.RUnlock()

	if listeners, ok := s.listeners[configKey]; ok {
		for _, listener := range listeners {
			go listener(configKey, oldValue, newValue)
		}
	}

	if listeners, ok := s.listeners["*"]; ok {
		for _, listener := range listeners {
			go listener(configKey, oldValue, newValue)
		}
	}
}

func (s *EnvironmentService) onDefaultConfigChanged(configKey string, oldValue, newValue map[string]interface{}) {
	logger.Log.Info("Default environment config changed",
		zap.String("key", configKey),
		zap.Any("old", oldValue),
		zap.Any("new", newValue))
}

func (s *EnvironmentService) ReloadConfigs() (int, error) {
	s.configMutex.Lock()
	defer s.configMutex.Unlock()

	s.configCache.Range(func(key, value interface{}) bool {
		s.configCache.Delete(key)
		return true
	})

	var configs []DynamicConfig
	if err := database.DB.Where("is_active = ?", true).Find(&configs).Error; err != nil {
		return 0, err
	}

	for _, cfg := range configs {
		s.configCache.Store(cfg.ConfigKey, cfg.ConfigValue)
	}

	logger.Log.Info("Configs reloaded", zap.Int("count", len(configs)))
	return len(configs), nil
}

func (s *EnvironmentService) getEffectiveDuration(reqDuration int) int {
	maxDuration := s.GetConfigInt("environment.default", "max_duration_hours", 720)
	defaultDuration := s.GetConfigInt("environment.default", "default_duration_hours", 24)

	if reqDuration <= 0 {
		return defaultDuration
	}
	if reqDuration > maxDuration {
		return maxDuration
	}
	return reqDuration
}

func (s *EnvironmentService) CreateEnvironment(name, description, envType, ownerID, projectID string, config map[string]interface{}, durationHours int) (*Environment, error) {
	envID := utils.GenerateID("e")
	now := time.Now()

	effectiveDuration := s.getEffectiveDuration(durationHours)
	expiresAt := now.Add(time.Duration(effectiveDuration) * time.Hour)

	defaultCores := s.GetConfigInt("environment.default", "default_cpu_cores", 2)
	defaultMemory := s.GetConfigInt("environment.default", "default_memory_gb", 4)
	defaultStorage := s.GetConfigInt("environment.default", "default_storage_gb", 20)
	autoRecycle := s.GetConfigBool("environment.default", "auto_recycle_enabled", true)

	env := &Environment{
		EnvID:       envID,
		Name:        name,
		Description: description,
		Type:        envType,
		Status:      "provisioning",
		Config:      config,
		Resources: map[string]interface{}{
			"cpu":     strconv.Itoa(defaultCores) + " cores",
			"memory":  strconv.Itoa(defaultMemory) + "GB",
			"storage": strconv.Itoa(defaultStorage) + "GB",
		},
		OwnerID:     ownerID,
		ProjectID:   projectID,
		ExpiresAt:   &expiresAt,
		StartedAt:   &now,
		AutoRecycle: autoRecycle,
		AccessURL:   "http://" + envID + ".example.com",
	}

	err := s.envRepo.Create(env)
	if err != nil {
		return nil, apperrors.Wrap(500, "failed to create environment", err)
	}

	go s.provisionEnvironment(env)

	return env, nil
}

func (s *EnvironmentService) provisionEnvironment(env *Environment) {
	time.Sleep(2 * time.Second)
	_ = s.envRepo.Update(env.ID, map[string]interface{}{"status": "running"})
}

func (s *EnvironmentService) GetEnvironment(envID string) (*Environment, error) {
	env, err := s.envRepo.GetByEnvID(envID)
	if err != nil {
		return nil, apperrors.ErrNotFound
	}
	return env, nil
}

func (s *EnvironmentService) ListEnvironments(page, pageSize int, ownerID, status, envType string) ([]Environment, int64, error) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	return s.envRepo.List(page, pageSize, ownerID, status, envType)
}

func (s *EnvironmentService) StopEnvironment(envID string) error {
	env, err := s.envRepo.GetByEnvID(envID)
	if err != nil {
		return apperrors.ErrNotFound
	}

	now := time.Now()
	return s.envRepo.Update(env.ID, map[string]interface{}{
		"status":      "stopped",
		"stopped_at":  &now,
	})
}

func (s *EnvironmentService) StartEnvironment(envID string) error {
	env, err := s.envRepo.GetByEnvID(envID)
	if err != nil {
		return apperrors.ErrNotFound
	}

	now := time.Now()
	newExpiresAt := now.Add(24 * time.Hour)
	return s.envRepo.Update(env.ID, map[string]interface{}{
		"status":     "running",
		"started_at": &now,
		"expires_at": &newExpiresAt,
	})
}

func (s *EnvironmentService) DeleteEnvironment(envID string) error {
	env, err := s.envRepo.GetByEnvID(envID)
	if err != nil {
		return apperrors.ErrNotFound
	}
	return s.envRepo.Delete(env.ID)
}

func (s *EnvironmentService) ExtendEnvironment(envID string, hours int) error {
	env, err := s.envRepo.GetByEnvID(envID)
	if err != nil {
		return apperrors.ErrNotFound
	}

	newExpiresAt := env.ExpiresAt.Add(time.Duration(hours) * time.Hour)
	return s.envRepo.Update(env.ID, map[string]interface{}{
		"expires_at": newExpiresAt,
	})
}

func (s *EnvironmentService) GetUsageStats(envID, startDate, endDate string) ([]UsageStats, error) {
	return s.statsRepo.GetByEnvID(envID, startDate, endDate)
}

func (s *EnvironmentService) GetAggregatedStats(envID string) (map[string]interface{}, error) {
	return s.statsRepo.GetAggregatedStats(envID)
}

func (s *EnvironmentService) CreateRequest(requesterID, projectID, envType, reason string, config map[string]interface{}) (*EnvironmentRequest, error) {
	req := &EnvironmentRequest{
		RequestID:   utils.GenerateID("req"),
		RequesterID: requesterID,
		ProjectID:   projectID,
		EnvType:     envType,
		Config:      config,
		Reason:      reason,
		Status:      "pending",
	}

	err := s.reqRepo.Create(req)
	if err != nil {
		return nil, apperrors.Wrap(500, "failed to create request", err)
	}
	return req, nil
}

func (s *EnvironmentService) ApproveRequest(requestID, approverID string) (*Environment, error) {
	req, err := s.reqRepo.GetByRequestID(requestID)
	if err != nil {
		return nil, apperrors.ErrNotFound
	}

	now := time.Now()
	err = s.reqRepo.Update(req.ID, map[string]interface{}{
		"status":      "approved",
		"approver_id": approverID,
		"approved_at": &now,
	})
	if err != nil {
		return nil, err
	}

	return s.CreateEnvironment(
		"Env-"+requestID[:8],
		req.Reason,
		req.EnvType,
		req.RequesterID,
		req.ProjectID,
		req.Config,
		24,
	)
}

func (s *EnvironmentService) RejectRequest(requestID, approverID string) error {
	req, err := s.reqRepo.GetByRequestID(requestID)
	if err != nil {
		return apperrors.ErrNotFound
	}

	return s.reqRepo.Update(req.ID, map[string]interface{}{
		"status":      "rejected",
		"approver_id": approverID,
	})
}

func (s *EnvironmentService) ListRequests(page, pageSize int, requesterID, status string) ([]EnvironmentRequest, int64, error) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	return s.reqRepo.List(page, pageSize, requesterID, status)
}

func (s *EnvironmentService) RecycleExpiredEnvs() int {
	envs, err := s.envRepo.GetExpiredEnvs()
	if err != nil {
		return 0
	}

	count := 0
	for _, env := range envs {
		_ = s.envRepo.Update(env.ID, map[string]interface{}{
			"status": "recycled",
		})
		count++
	}
	return count
}
