package environment

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"sync"
	"time"

	"techplatform/internal/dao"
	"techplatform/pkg/common"
	"techplatform/pkg/common/logger"
	"techplatform/pkg/common/utils"
	"techplatform/pkg/models"

	"gorm.io/gorm"
)

type EnvStatus string

const (
	StatusCreating EnvStatus = "creating"
	StatusRunning  EnvStatus = "running"
	StatusStopping EnvStatus = "stopping"
	StatusStopped  EnvStatus = "stopped"
	StatusFailed   EnvStatus = "failed"
	StatusExpired  EnvStatus = "expired"
	StatusRecycling EnvStatus = "recycling"
)

type EnvType string

const (
	TypeDocker EnvType = "docker"
	TypeK8s    EnvType = "kubernetes"
	TypeVM     EnvType = "vm"
	TypeCloud  EnvType = "cloud"
)

type Environment struct {
	models.BaseModel
	Name          string    `json:"name" gorm:"index;size:100"`
	Description   string    `json:"description"`
	Type          EnvType   `json:"type" gorm:"index;size:50"`
	Status        EnvStatus `json:"status" gorm:"index;size:50"`
	OwnerID       string    `json:"owner_id" gorm:"index;size:36"`
	OwnerName     string    `json:"owner_name"`
	ProjectID     string    `json:"project_id" gorm:"index;size:36"`
	Branch        string    `json:"branch"`
	CommitSHA     string    `json:"commit_sha"`
	ImageTag      string    `json:"image_tag"`
	Config        string    `json:"config"`
	URL           string    `json:"url"`
	Endpoints     string    `json:"endpoints"`
	Resources     string    `json:"resources"`
	TTLMinutes    int       `json:"ttl_minutes"`
	ExpiresAt     *time.Time `json:"expires_at"`
	StartedAt     *time.Time `json:"started_at"`
	StoppedAt     *time.Time `json:"stopped_at"`
	AutoRecycle   bool      `json:"auto_recycle"`
	MaxDuration   int       `json:"max_duration_minutes"`
	CPUUsage      float64   `json:"cpu_usage"`
	MemoryUsage   int64     `json:"memory_usage"`
	DiskUsage     int64     `json:"disk_usage"`
	NetworkIn     int64     `json:"network_in"`
	NetworkOut    int64     `json:"network_out"`
	Cost          float64   `json:"cost"`
	LastResult    string    `json:"last_result"`
}

type EnvironmentConfig struct {
	EnvVars        map[string]string `json:"env_vars"`
	Ports          []int             `json:"ports"`
	Volumes        []string          `json:"volumes"`
	Command        string            `json:"command"`
	Args           []string          `json:"args"`
	HealthCheck    HealthCheckConfig `json:"health_check"`
	ResourceLimit  ResourceLimit     `json:"resource_limit"`
}

type HealthCheckConfig struct {
	Type        string `json:"type"`
	Path        string `json:"path"`
	Port        int    `json:"port"`
	Interval    int    `json:"interval"`
	Timeout     int    `json:"timeout"`
	Retries     int    `json:"retries"`
}

type ResourceLimit struct {
	CPU    float64 `json:"cpu"`
	Memory int64   `json:"memory"`
	Disk   int64   `json:"disk"`
}

type UsageStats struct {
	TotalCreated     int64   `json:"total_created"`
	TotalRunning     int64   `json:"total_running"`
	TotalStopped     int64   `json:"total_stopped"`
	TotalFailed      int64   `json:"total_failed"`
	TotalCost        float64 `json:"total_cost"`
	AvgDuration      int64   `json:"avg_duration_minutes"`
	TodayCreated     int64   `json:"today_created"`
	WeekCreated      int64   `json:"week_created"`
	MonthCreated     int64   `json:"month_created"`
}

type EnvironmentRequest struct {
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	Type        EnvType                `json:"type"`
	ProjectID   string                 `json:"project_id"`
	Branch      string                 `json:"branch"`
	CommitSHA   string                 `json:"commit_sha"`
	ImageTag    string                 `json:"image_tag"`
	Config      *EnvironmentConfig     `json:"config"`
	TTLMinutes  int                    `json:"ttl_minutes"`
	AutoRecycle bool                   `json:"auto_recycle"`
}

type EnvironmentProvider interface {
	Name() string
	Provision(ctx context.Context, env *Environment, config *EnvironmentConfig) error
	Start(ctx context.Context, env *Environment) error
	Stop(ctx context.Context, env *Environment) error
	Destroy(ctx context.Context, env *Environment) error
	GetStatus(ctx context.Context, env *Environment) (EnvStatus, error)
	GetUsage(ctx context.Context, env *Environment) (*ResourceUsage, error)
}

type ResourceUsage struct {
	CPUUsage    float64 `json:"cpu_usage"`
	MemoryUsage int64   `json:"memory_usage"`
	DiskUsage   int64   `json:"disk_usage"`
	NetworkIn   int64   `json:"network_in"`
	NetworkOut  int64   `json:"network_out"`
}

type DockerProvider struct{}

func (d *DockerProvider) Name() string { return "docker" }

func (d *DockerProvider) Provision(ctx context.Context, env *Environment, config *EnvironmentConfig) error {
	logger.Info("[Docker] Provisioning environment: %s", env.Name)
	time.Sleep(2 * time.Second)
	env.URL = fmt.Sprintf("http://%s.example.com:8080", env.Name)
	endpoints := map[string]string{
		"web":     fmt.Sprintf("http://%s.example.com:8080", env.Name),
		"api":     fmt.Sprintf("http://%s.example.com:8080/api", env.Name),
		"admin":   fmt.Sprintf("http://%s.example.com:8080/admin", env.Name),
		"metrics": fmt.Sprintf("http://%s.example.com:9090", env.Name),
	}
	b, _ := json.Marshal(endpoints)
	env.Endpoints = string(b)
	return nil
}

func (d *DockerProvider) Start(ctx context.Context, env *Environment) error {
	logger.Info("[Docker] Starting environment: %s", env.Name)
	time.Sleep(1 * time.Second)
	return nil
}

func (d *DockerProvider) Stop(ctx context.Context, env *Environment) error {
	logger.Info("[Docker] Stopping environment: %s", env.Name)
	time.Sleep(1 * time.Second)
	return nil
}

func (d *DockerProvider) Destroy(ctx context.Context, env *Environment) error {
	logger.Info("[Docker] Destroying environment: %s", env.Name)
	time.Sleep(1 * time.Second)
	return nil
}

func (d *DockerProvider) GetStatus(ctx context.Context, env *Environment) (EnvStatus, error) {
	return env.Status, nil
}

func (d *DockerProvider) GetUsage(ctx context.Context, env *Environment) (*ResourceUsage, error) {
	return &ResourceUsage{
		CPUUsage:    15.5 + float64(int(time.Now().UnixNano())%300)/10,
		MemoryUsage: 512*1024*1024 + int64(time.Now().UnixNano()%(1024*1024*512)),
		DiskUsage:   2*1024*1024*1024 + int64(time.Now().UnixNano()%(1024*1024*1024)),
		NetworkIn:   int64(time.Now().UnixNano() % 1000000),
		NetworkOut:  int64(time.Now().UnixNano() % 500000),
	}, nil
}

type KubernetesProvider struct{}

func (k *KubernetesProvider) Name() string { return "kubernetes" }

func (k *KubernetesProvider) Provision(ctx context.Context, env *Environment, config *EnvironmentConfig) error {
	logger.Info("[K8s] Provisioning environment: %s", env.Name)
	time.Sleep(3 * time.Second)
	env.URL = fmt.Sprintf("https://%s.k8s.example.com", env.Name)
	return nil
}

func (k *KubernetesProvider) Start(ctx context.Context, env *Environment) error {
	logger.Info("[K8s] Starting environment: %s", env.Name)
	return nil
}

func (k *KubernetesProvider) Stop(ctx context.Context, env *Environment) error {
	logger.Info("[K8s] Stopping environment: %s", env.Name)
	return nil
}

func (k *KubernetesProvider) Destroy(ctx context.Context, env *Environment) error {
	logger.Info("[K8s] Destroying environment: %s", env.Name)
	return nil
}

func (k *KubernetesProvider) GetStatus(ctx context.Context, env *Environment) (EnvStatus, error) {
	return env.Status, nil
}

func (k *KubernetesProvider) GetUsage(ctx context.Context, env *Environment) (*ResourceUsage, error) {
	return &ResourceUsage{
		CPUUsage:    25.0,
		MemoryUsage: 1024 * 1024 * 1024,
		DiskUsage:   10 * 1024 * 1024 * 1024,
	}, nil
}

type EnvironmentManager struct {
	mu              sync.RWMutex
	db              *dao.DAO
	providers       map[string]EnvironmentProvider
	defaultTTL      time.Duration
	maxEnvironments int
	resourceLimit   ResourceLimit
	recycleChan     chan string
	stopChan        chan struct{}
}

type ManagerConfig struct {
	DefaultTTL      time.Duration
	MaxEnvironments int
	ResourceLimit   ResourceLimit
}

func NewEnvironmentManager(db *dao.DAO, config ManagerConfig) *EnvironmentManager {
	if config.DefaultTTL <= 0 {
		config.DefaultTTL = 24 * time.Hour
	}
	if config.MaxEnvironments <= 0 {
		config.MaxEnvironments = 10
	}
	if config.ResourceLimit.CPU <= 0 {
		config.ResourceLimit.CPU = 2.0
	}
	if config.ResourceLimit.Memory <= 0 {
		config.ResourceLimit.Memory = 2048
	}

	em := &EnvironmentManager{
		db:              db,
		providers:       make(map[string]EnvironmentProvider),
		defaultTTL:      config.DefaultTTL,
		maxEnvironments: config.MaxEnvironments,
		resourceLimit:   config.ResourceLimit,
		recycleChan:     make(chan string, 100),
		stopChan:        make(chan struct{}),
	}

	em.registerProvider(&DockerProvider{})
	em.registerProvider(&KubernetesProvider{})
	db.AutoMigrate(&Environment{})

	go em.startRecycler()
	go em.startUsageCollector()

	logger.Info("Environment manager initialized, max environments: %d, default TTL: %v",
		config.MaxEnvironments, config.DefaultTTL)
	return em
}

func (em *EnvironmentManager) registerProvider(provider EnvironmentProvider) {
	em.providers[provider.Name()] = provider
}

func (em *EnvironmentManager) Create(ctx context.Context, req EnvironmentRequest, ownerID, ownerName string) (*Environment, error) {
	if req.Name == "" {
		return nil, fmt.Errorf("%w: environment name required", common.ErrInvalidInput)
	}
	if req.ProjectID == "" {
		return nil, fmt.Errorf("%w: project ID required", common.ErrInvalidInput)
	}

	em.mu.RLock()
	var runningCount int64
	em.db.DB().Model(&Environment{}).Where("status IN ?", []EnvStatus{StatusCreating, StatusRunning}).Count(&runningCount)
	if int(runningCount) >= em.maxEnvironments {
		em.mu.RUnlock()
		return nil, fmt.Errorf("%w: maximum number of running environments reached (%d)", common.ErrInvalidInput, em.maxEnvironments)
	}

	var existing Environment
	result := em.db.DB().Where("name = ? AND status IN ?", req.Name, []EnvStatus{StatusCreating, StatusRunning}).First(&existing)
	if result.Error == nil {
		em.mu.RUnlock()
		return nil, fmt.Errorf("%w: environment with name '%s' already exists and is active", common.ErrAlreadyExists, req.Name)
	}
	em.mu.RUnlock()

	var existing2 Environment
	result = em.db.DB().Where("project_id = ? AND branch = ? AND status IN ?", req.ProjectID, req.Branch,
		[]EnvStatus{StatusCreating, StatusRunning}).First(&existing2)
	if result.Error == nil {
		em.mu.RUnlock()
		return nil, fmt.Errorf("%w: environment for project '%s' branch '%s' already exists (ID: %s)",
			common.ErrAlreadyExists, req.ProjectID, req.Branch, existing2.ID)
	}
	em.mu.RUnlock()

	configJSON := "{}"
	if req.Config != nil {
		b, _ := json.Marshal(req.Config)
		configJSON = string(b)
	}

	resB, _ := json.Marshal(em.resourceLimit)

	ttlMinutes := req.TTLMinutes
	if ttlMinutes <= 0 {
		ttlMinutes = int(em.defaultTTL.Minutes())
	}

	now := time.Now()
	expiresAt := now.Add(time.Duration(ttlMinutes) * time.Minute)

	env := &Environment{
		BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
		Name:        req.Name,
		Description: req.Description,
		Type:        req.Type,
		Status:      StatusCreating,
		OwnerID:     ownerID,
		OwnerName:   ownerName,
		ProjectID:   req.ProjectID,
		Branch:      req.Branch,
		CommitSHA:   req.CommitSHA,
		ImageTag:    req.ImageTag,
		Config:      configJSON,
		Resources:   string(resB),
		TTLMinutes:  ttlMinutes,
		ExpiresAt:   &expiresAt,
		AutoRecycle: req.AutoRecycle,
		MaxDuration: ttlMinutes,
	}

	if err := em.db.DB().Create(env).Error; err != nil {
		return nil, err
	}

	go em.provisionEnvironment(env, req.Config)

	logger.Info("Environment created: %s (owner: %s, project: %s, type: %s)",
		env.Name, ownerName, req.ProjectID, req.Type)
	return env, nil
}

func (em *EnvironmentManager) provisionEnvironment(env *Environment, config *EnvironmentConfig) {
	ctx := context.Background()

	if config == nil {
		config = &EnvironmentConfig{}
		if env.Config != "" {
			json.Unmarshal([]byte(env.Config), config)
		}
	}

	provider, exists := em.providers[string(env.Type)]
	if !exists {
		provider = em.providers["docker"]
	}

	now := time.Now()
	env.StartedAt = &now

	if err := provider.Provision(ctx, env, config); err != nil {
		env.Status = StatusFailed
		env.LastResult = err.Error()
		em.db.DB().Save(env)
		logger.Error("Failed to provision environment %s: %v", env.Name, err)
		return
	}

	if err := provider.Start(ctx, env); err != nil {
		env.Status = StatusFailed
		env.LastResult = err.Error()
		em.db.DB().Save(env)
		logger.Error("Failed to start environment %s: %v", env.Name, err)
		return
	}

	env.Status = StatusRunning
	em.db.DB().Save(env)
	em.invalidateCache(env)

	logger.Info("Environment provisioned successfully: %s, URL: %s", env.Name, env.URL)
}

func (em *EnvironmentManager) Get(id string) (*Environment, error) {
	var env Environment
	cacheKey := fmt.Sprintf("env:%s", id)

	err := em.db.GetWithCache(context.Background(), cacheKey, &env, func() (interface{}, error) {
		if err := em.db.DB().First(&env, "id = ?", id).Error; err != nil {
			if errors.Is(err, gorm.ErrRecordNotFound) {
				return nil, common.ErrNotFound
			}
			return nil, err
		}
		return env, nil
	})

	if err != nil {
		return nil, err
	}

	return &env, nil
}

func (em *EnvironmentManager) List(page, pageSize int, ownerID, projectID, status string) (*models.PageResult, error) {
	page, pageSize = normalizePagination(page, pageSize)

	var envs []Environment
	var total int64

	query := em.db.DB().Model(&Environment{})
	if ownerID != "" {
		query = query.Where("owner_id = ?", ownerID)
	}
	if projectID != "" {
		query = query.Where("project_id = ?", projectID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&envs).Error; err != nil {
		return nil, err
	}

	return &models.PageResult{
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Items:    envs,
	}, nil
}

func (em *EnvironmentManager) Stop(id string) error {
	env, err := em.Get(id)
	if err != nil {
		return err
	}

	if env.Status != StatusRunning {
		return fmt.Errorf("%w: environment is not running", common.ErrInvalidInput)
	}

	env.Status = StatusStopping
	em.db.DB().Save(env)

	go func() {
		ctx := context.Background()
		provider := em.getProvider(env)
		if err := provider.Stop(ctx, env); err != nil {
			logger.Error("Failed to stop environment %s: %v", env.Name, err)
			return
		}

		now := time.Now()
		env.Status = StatusStopped
		env.StoppedAt = &now
		em.db.DB().Save(env)
		em.invalidateCache(env)
		logger.Info("Environment stopped: %s", env.Name)
	}()

	return nil
}

func (em *EnvironmentManager) Start(id string) error {
	env, err := em.Get(id)
	if err != nil {
		return err
	}

	if env.Status != StatusStopped && env.Status != StatusExpired {
		return fmt.Errorf("%w: environment cannot be started from status: %s", common.ErrInvalidInput, env.Status)
	}

	env.Status = StatusCreating
	em.db.DB().Save(env)

	go func() {
		ctx := context.Background()
		provider := em.getProvider(env)

		if err := provider.Start(ctx, env); err != nil {
			env.Status = StatusFailed
			em.db.DB().Save(env)
			logger.Error("Failed to start environment %s: %v", env.Name, err)
			return
		}

		now := time.Now()
		expiresAt := now.Add(time.Duration(env.TTLMinutes) * time.Minute)
		env.Status = StatusRunning
		env.StartedAt = &now
		env.ExpiresAt = &expiresAt
		em.db.DB().Save(env)
		em.invalidateCache(env)
		logger.Info("Environment started: %s", env.Name)
	}()

	return nil
}

func (em *EnvironmentManager) Destroy(id string) error {
	env, err := em.Get(id)
	if err != nil {
		return err
	}

	env.Status = StatusRecycling
	em.db.DB().Save(env)

	go func() {
		ctx := context.Background()
		provider := em.getProvider(env)

		if err := provider.Destroy(ctx, env); err != nil {
			logger.Error("Failed to destroy environment %s: %v", env.Name, err)
		}

		em.db.DB().Delete(env)
		em.invalidateCache(env)
		logger.Info("Environment destroyed: %s", env.Name)
	}()

	return nil
}

func (em *EnvironmentManager) ExtendTTL(id string, minutes int) error {
	env, err := em.Get(id)
	if err != nil {
		return err
	}

	if env.Status != StatusRunning {
		return fmt.Errorf("%w: can only extend TTL of running environments", common.ErrInvalidInput)
	}

	newExpiry := env.ExpiresAt.Add(time.Duration(minutes) * time.Minute)
	env.ExpiresAt = &newExpiry
	env.TTLMinutes += minutes
	env.MaxDuration += minutes

	if err := em.db.DB().Save(env).Error; err != nil {
		return err
	}

	em.invalidateCache(env)
	logger.Info("Environment TTL extended: %s, new expiry: %v", env.Name, newExpiry)
	return nil
}

func (em *EnvironmentManager) getProvider(env *Environment) EnvironmentProvider {
	if provider, exists := em.providers[string(env.Type)]; exists {
		return provider
	}
	return em.providers["docker"]
}

func (em *EnvironmentManager) invalidateCache(env *Environment) {
	keys := []string{
		fmt.Sprintf("env:%s", env.ID),
		fmt.Sprintf("env:%s", env.Name),
	}
	em.db.InvalidateCache(context.Background(), keys...)
}

func (em *EnvironmentManager) startRecycler() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-em.stopChan:
			return
		case <-ticker.C:
			em.recycleExpired()
		case envID := <-em.recycleChan:
			em.Destroy(envID)
		}
	}
}

func (em *EnvironmentManager) recycleExpired() {
	em.mu.Lock()
	defer em.mu.Unlock()

	now := time.Now()
	var expiredEnvs []Environment

	em.db.DB().Where("status = ? AND expires_at <= ? AND auto_recycle = ?",
		StatusRunning, now, true).Find(&expiredEnvs)

	for _, env := range expiredEnvs {
		logger.Info("Environment expired, scheduling recycle: %s (owner: %s)", env.Name, env.OwnerName)
		env.Status = StatusExpired
		em.db.DB().Save(&env)

		go em.Destroy(env.ID)
	}

	var longRunning []Environment
	maxDuration := 24 * time.Hour
	em.db.DB().Where("status = ? AND started_at <= ?",
		StatusRunning, now.Add(-maxDuration)).Find(&longRunning)

	for _, env := range longRunning {
		logger.Warn("Environment running too long: %s (started: %v)", env.Name, env.StartedAt)
	}
}

func (em *EnvironmentManager) startUsageCollector() {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-em.stopChan:
			return
		case <-ticker.C:
			em.collectUsage()
		}
	}
}

func (em *EnvironmentManager) collectUsage() {
	em.mu.RLock()
	defer em.mu.RUnlock()

	var runningEnvs []Environment
	em.db.DB().Where("status = ?", StatusRunning).Find(&runningEnvs)

	for _, env := range runningEnvs {
		provider := em.getProvider(&env)
		usage, err := provider.GetUsage(context.Background(), &env)
		if err != nil {
			logger.Warn("Failed to get usage for %s: %v", env.Name, err)
			continue
		}

		env.CPUUsage = usage.CPUUsage
		env.MemoryUsage = usage.MemoryUsage
		env.DiskUsage = usage.DiskUsage
		env.NetworkIn = usage.NetworkIn
		env.NetworkOut = usage.NetworkOut
		env.Cost = env.calculateCost()

		em.db.DB().Save(&env)
	}
}

func (env *Environment) calculateCost() float64 {
	if env.StartedAt == nil {
		return 0
	}
	duration := time.Since(*env.StartedAt).Hours()
	rate := 0.05
	return float64(int64(duration * rate * 100)) / 100
}

func (em *EnvironmentManager) GetStats() *UsageStats {
	var stats UsageStats

	em.db.DB().Model(&Environment{}).Count(&stats.TotalCreated)
	em.db.DB().Model(&Environment{}).Where("status = ?", StatusRunning).Count(&stats.TotalRunning)
	em.db.DB().Model(&Environment{}).Where("status = ?", StatusStopped).Count(&stats.TotalStopped)
	em.db.DB().Model(&Environment{}).Where("status = ?", StatusFailed).Count(&stats.TotalFailed)
	em.db.DB().Model(&Environment{}).Select("COALESCE(SUM(cost), 0").Scan(&stats.TotalCost)

	rows, _ := em.db.DB().Model(&Environment{}).
		Where("stopped_at IS NOT NULL").
		Select("AVG(strftime('%s', stopped_at) - strftime('%s', started_at)) / 60").Rows()
	if rows.Next() {
		rows.Scan(&stats.AvgDuration)
	}
	rows.Close()

	today := time.Now().Truncate(24 * time.Hour)
	em.db.DB().Model(&Environment{}).Where("created_at >= ?", today).Count(&stats.TodayCreated)

	weekAgo := time.Now().AddDate(0, 0, -7)
	em.db.DB().Model(&Environment{}).Where("created_at >= ?", weekAgo).Count(&stats.WeekCreated)

	monthAgo := time.Now().AddDate(0, 0, -30)
	em.db.DB().Model(&Environment{}).Where("created_at >= ?", monthAgo).Count(&stats.MonthCreated)

	return &stats
}

func (em *EnvironmentManager) GetUsageByUser(limit int) []map[string]interface{} {
	type Result struct {
		OwnerID   string
		OwnerName string
		Count     int
		TotalCost float64
	}
	var results []Result

	em.db.DB().Model(&Environment{}).
		Select("owner_id, owner_name, COUNT(*) as count, COALESCE(SUM(cost), 0) as total_cost").
		Group("owner_id, owner_name").
		Order("count DESC").
		Limit(limit).
		Scan(&results)

	userStats := make([]map[string]interface{}, len(results))
	for i, r := range results {
		userStats[i] = map[string]interface{}{
			"user_id":    r.OwnerID,
			"user_name":  r.OwnerName,
			"envs_count": r.Count,
			"total_cost": r.TotalCost,
		}
	}
	return userStats
}

func (em *EnvironmentManager) GetUsageByProject(limit int) []map[string]interface{} {
	type Result struct {
		ProjectID string
		Count     int
		TotalCost float64
	}
	var results []Result

	em.db.DB().Model(&Environment{}).
		Select("project_id, COUNT(*) as count, COALESCE(SUM(cost), 0) as total_cost").
		Group("project_id").
		Order("count DESC").
		Limit(limit).
		Scan(&results)

	projStats := make([]map[string]interface{}, len(results))
	for i, r := range results {
		projStats[i] = map[string]interface{}{
			"project_id": r.ProjectID,
			"envs_count": r.Count,
			"total_cost": r.TotalCost,
		}
	}
	return projStats
}

func (em *EnvironmentManager) StopAll() {
	em.mu.RLock()
	defer em.mu.RUnlock()

	var running []Environment
	em.db.DB().Where("status = ?", StatusRunning).Find(&running)

	for _, env := range running {
		logger.Info("Stopping environment: %s", env.Name)
		em.Stop(env.ID)
	}

	close(em.stopChan)
}

func (em *EnvironmentManager) GetConfig() *EnvironmentConfig {
	return &EnvironmentConfig{
		ResourceLimit: em.resourceLimit,
	}
}

func (em *EnvironmentManager) GetEndpoints(env *Environment) map[string]string {
	var endpoints map[string]string
	if env.Endpoints != "" {
		json.Unmarshal([]byte(env.Endpoints), &endpoints)
	}
	return endpoints
}

func (em *EnvironmentManager) ValidateRequest(req *EnvironmentRequest) error {
	if req.Name == "" {
		return fmt.Errorf("%w: name is required", common.ErrInvalidInput)
	}
	if len(req.Name) > 50 {
		return fmt.Errorf("%w: name is too long (max 50 chars)", common.ErrInvalidInput)
	}
	if !isValidName(req.Name) {
		return fmt.Errorf("%w: name can only contain lowercase letters, numbers and hyphens", common.ErrInvalidInput)
	}
	if req.TTLMinutes > 0 && req.TTLMinutes > 10080 {
		return fmt.Errorf("%w: maximum TTL is 7 days (10080 minutes)", common.ErrInvalidInput)
	}
	return nil
}

func isValidName(name string) bool {
	for _, c := range name {
		if !((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
			return false
		}
	}
	return true
}

func normalizePagination(page, pageSize int) (int, int) {
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}
	if pageSize > 100 {
		pageSize = 100
	}
	return page, pageSize
}

func (em *EnvironmentManager) GetRunningEnvsByOwner(ownerID string) []Environment {
	var envs []Environment
	em.db.DB().Where("owner_id = ? AND status = ?", ownerID, StatusRunning).Find(&envs)
	return envs
}

func (em *EnvironmentManager) SortEnvironments(envs []Environment, sortBy string, ascending bool) []Environment {
	sort.Slice(envs, func(i, j int) bool {
		switch sortBy {
		case "name":
			if ascending {
				return envs[i].Name < envs[j].Name
			}
			return envs[i].Name > envs[j].Name
		case "created":
			if ascending {
				return envs[i].CreatedAt.Before(envs[j].CreatedAt)
			}
			return envs[i].CreatedAt.After(envs[j].CreatedAt)
		case "expires":
			if envs[i].ExpiresAt == nil {
				return true
			}
			if envs[j].ExpiresAt == nil {
				return false
			}
			if ascending {
				return envs[i].ExpiresAt.Before(*envs[j].ExpiresAt)
			}
			return envs[i].ExpiresAt.After(*envs[j].ExpiresAt)
		case "cost":
			if ascending {
				return envs[i].Cost < envs[j].Cost
			}
			return envs[i].Cost > envs[j].Cost
		default:
			return envs[i].CreatedAt.After(envs[j].CreatedAt)
		}
	})
	return envs
}
