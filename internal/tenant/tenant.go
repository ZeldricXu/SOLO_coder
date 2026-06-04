package tenant

import (
	"context"
	"encoding/json"
	"model-inference-platform/internal/pkg/config"
	"model-inference-platform/internal/pkg/database"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"
)

type Namespace struct {
	ID           string                 `json:"id"`
	Name         string                 `json:"name"`
	DisplayName  string                 `json:"display_name"`
	Description  string                 `json:"description"`
	GPUQuotaMin  float64                `json:"gpu_quota_min"`
	GPUQuotaMax  float64                `json:"gpu_quota_max"`
	CreatedAt    time.Time              `json:"created_at"`
	UpdatedAt    time.Time              `json:"updated_at"`
	Metadata     map[string]interface{} `json:"metadata"`
}

type NamespaceUsage struct {
	Namespace       string    `json:"namespace"`
	Date            string    `json:"date"`
	GPUUsageSeconds int64     `json:"gpu_usage_seconds"`
	InferenceCount  int64     `json:"inference_count"`
	CostAmount      float64   `json:"cost_amount"`
	RecordedAt      time.Time `json:"recorded_at"`
}

type ResourceAllocation struct {
	Namespace      string  `json:"namespace"`
	AllocatedGPUs  float64 `json:"allocated_gpus"`
	UsedGPUs       float64 `json:"used_gpus"`
	QueueDepth     int64   `json:"queue_depth"`
	PendingRequests int64  `json:"pending_requests"`
}

type Manager struct {
	cfg          config.TenantConfig
	db           *database.Database
	logger       *zap.Logger

	allocations   map[string]*ResourceAllocation
	allocationsMu sync.RWMutex

	gpuRatePerHour float64

	stopCh chan struct{}
	wg     sync.WaitGroup
}

func NewManager(cfg config.TenantConfig, db *database.Database, logger *zap.Logger) *Manager {
	return &Manager{
		cfg:            cfg,
		db:             db,
		logger:         logger,
		allocations:    make(map[string]*ResourceAllocation),
		gpuRatePerHour: 5.0,
		stopCh:         make(chan struct{}),
	}
}

func (m *Manager) Start(ctx context.Context) error {
	if err := m.loadNamespaces(ctx); err != nil {
		m.logger.Warn("Failed to load namespaces", zap.Error(err))
	}

	m.wg.Add(2)
	go m.usageCollector(ctx)
	go m.resourceMonitor(ctx)

	m.logger.Info("Tenant manager started")
	return nil
}

func (m *Manager) Stop() {
	close(m.stopCh)
	m.wg.Wait()
	m.logger.Info("Tenant manager stopped")
}

func (m *Manager) loadNamespaces(ctx context.Context) error {
	query := `
		SELECT id, name, display_name, description, gpu_quota_min, gpu_quota_max,
		       created_at, updated_at, metadata
		FROM namespaces
	`

	rows, err := m.db.Query(ctx, query)
	if err != nil {
		return err
	}
	defer rows.Close()

	for rows.Next() {
		ns := &Namespace{}
		var metadataJSON []byte

		err := rows.Scan(&ns.ID, &ns.Name, &ns.DisplayName, &ns.Description,
			&ns.GPUQuotaMin, &ns.GPUQuotaMax, &ns.CreatedAt, &ns.UpdatedAt, &metadataJSON)
		if err != nil {
			continue
		}

		json.Unmarshal(metadataJSON, &ns.Metadata)

		m.allocationsMu.Lock()
		m.allocations[ns.Name] = &ResourceAllocation{
			Namespace:     ns.Name,
			AllocatedGPUs: ns.GPUQuotaMin,
		}
		m.allocationsMu.Unlock()
	}

	return nil
}

func (m *Manager) CreateNamespace(ctx context.Context, name, displayName, description string,
	gpuQuotaMin, gpuQuotaMax float64, metadata map[string]interface{}) (*Namespace, error) {

	nsID := uuid.New().String()
	now := time.Now()

	if gpuQuotaMin == 0 {
		gpuQuotaMin = m.cfg.DefaultGPUMin
	}
	if gpuQuotaMax == 0 {
		gpuQuotaMax = m.cfg.DefaultGPUQuota
	}

	metadataJSON, _ := json.Marshal(metadata)

	query := `
		INSERT INTO namespaces (id, name, display_name, description, gpu_quota_min,
			gpu_quota_max, created_at, updated_at, metadata)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
		RETURNING id, name, display_name, description, gpu_quota_min,
			gpu_quota_max, created_at, updated_at
	`

	ns := &Namespace{Metadata: metadata}
	err := m.db.QueryRow(ctx, query, nsID, name, displayName, description,
		gpuQuotaMin, gpuQuotaMax, now, now, metadataJSON).Scan(
		&ns.ID, &ns.Name, &ns.DisplayName, &ns.Description,
		&ns.GPUQuotaMin, &ns.GPUQuotaMax, &ns.CreatedAt, &ns.UpdatedAt,
	)
	if err != nil {
		return nil, err
	}

	m.allocationsMu.Lock()
	m.allocations[name] = &ResourceAllocation{
		Namespace:     name,
		AllocatedGPUs: gpuQuotaMin,
	}
	m.allocationsMu.Unlock()

	return ns, nil
}

func (m *Manager) GetNamespace(ctx context.Context, name string) (*Namespace, error) {
	query := `
		SELECT id, name, display_name, description, gpu_quota_min, gpu_quota_max,
		       created_at, updated_at, metadata
		FROM namespaces WHERE name = $1
	`

	ns := &Namespace{}
	var metadataJSON []byte

	err := m.db.QueryRow(ctx, query, name).Scan(
		&ns.ID, &ns.Name, &ns.DisplayName, &ns.Description,
		&ns.GPUQuotaMin, &ns.GPUQuotaMax, &ns.CreatedAt, &ns.UpdatedAt, &metadataJSON,
	)
	if err != nil {
		return nil, err
	}

	json.Unmarshal(metadataJSON, &ns.Metadata)
	return ns, nil
}

func (m *Manager) ListNamespaces(ctx context.Context) ([]*Namespace, error) {
	query := `
		SELECT id, name, display_name, description, gpu_quota_min, gpu_quota_max,
		       created_at, updated_at, metadata
		FROM namespaces ORDER BY name
	`

	rows, err := m.db.Query(ctx, query)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var namespaces []*Namespace
	for rows.Next() {
		ns := &Namespace{}
		var metadataJSON []byte

		err := rows.Scan(&ns.ID, &ns.Name, &ns.DisplayName, &ns.Description,
			&ns.GPUQuotaMin, &ns.GPUQuotaMax, &ns.CreatedAt, &ns.UpdatedAt, &metadataJSON)
		if err != nil {
			continue
		}

		json.Unmarshal(metadataJSON, &ns.Metadata)
		namespaces = append(namespaces, ns)
	}

	return namespaces, nil
}

func (m *Manager) UpdateNamespaceQuota(ctx context.Context, name string, gpuQuotaMin, gpuQuotaMax float64) error {
	query := `
		UPDATE namespaces SET gpu_quota_min = $1, gpu_quota_max = $2, updated_at = $3
		WHERE name = $4
	`
	_, err := m.db.Exec(ctx, query, gpuQuotaMin, gpuQuotaMax, time.Now(), name)
	if err != nil {
		return err
	}

	m.allocationsMu.Lock()
	if alloc, ok := m.allocations[name]; ok {
		alloc.AllocatedGPUs = gpuQuotaMin
	}
	m.allocationsMu.Unlock()

	return nil
}

func (m *Manager) CheckQuota(namespace string, requestedGPUs float64) (bool, string) {
	m.allocationsMu.RLock()
	defer m.allocationsMu.RUnlock()

	alloc, ok := m.allocations[namespace]
	if !ok {
		return false, "namespace not found"
	}

	ns, err := m.GetNamespace(context.Background(), namespace)
	if err != nil {
		return false, "namespace not found"
	}

	if alloc.UsedGPUs+requestedGPUs > ns.GPUQuotaMax {
		return false, "GPU quota exceeded"
	}

	return true, ""
}

func (m *Manager) AllocateGPU(namespace string, amount float64) bool {
	m.allocationsMu.Lock()
	defer m.allocationsMu.Unlock()

	alloc, ok := m.allocations[namespace]
	if !ok {
		return false
	}

	ns, _ := m.GetNamespace(context.Background(), namespace)
	if ns == nil {
		return false
	}

	if alloc.UsedGPUs+amount > ns.GPUQuotaMax {
		return false
	}

	alloc.UsedGPUs += amount
	m.logger.Debug("GPU allocated",
		zap.String("namespace", namespace),
		zap.Float64("amount", amount),
		zap.Float64("total_used", alloc.UsedGPUs))
	return true
}

func (m *Manager) ReleaseGPU(namespace string, amount float64) {
	m.allocationsMu.Lock()
	defer m.allocationsMu.Unlock()

	if alloc, ok := m.allocations[namespace]; ok {
		alloc.UsedGPUs -= amount
		if alloc.UsedGPUs < 0 {
			alloc.UsedGPUs = 0
		}
		m.logger.Debug("GPU released",
			zap.String("namespace", namespace),
			zap.Float64("amount", amount),
			zap.Float64("total_used", alloc.UsedGPUs))
	}
}

func (m *Manager) GetAllocation(namespace string) *ResourceAllocation {
	m.allocationsMu.RLock()
	defer m.allocationsMu.RUnlock()

	if alloc, ok := m.allocations[namespace]; ok {
		return &ResourceAllocation{
			Namespace:      alloc.Namespace,
			AllocatedGPUs:  alloc.AllocatedGPUs,
			UsedGPUs:       alloc.UsedGPUs,
			QueueDepth:     alloc.QueueDepth,
			PendingRequests: alloc.PendingRequests,
		}
	}
	return nil
}

func (m *Manager) RecordInference(namespace string, durationMs int64) {
	gpuSeconds := float64(durationMs) / 1000.0

	m.allocationsMu.Lock()
	if alloc, ok := m.allocations[namespace]; ok {
		alloc.UsedGPUs += gpuSeconds / 3600
	}
	m.allocationsMu.Unlock()
}

func (m *Manager) usageCollector(ctx context.Context) {
	defer m.wg.Done()

	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-m.stopCh:
			return
		case <-ticker.C:
			m.collectAndPersistUsage(ctx)
		}
	}
}

func (m *Manager) collectAndPersistUsage(ctx context.Context) {
	today := time.Now().Format("2006-01-02")

	m.allocationsMu.Lock()
	allocations := make(map[string]*ResourceAllocation)
	for k, v := range m.allocations {
		allocations[k] = v
	}
	m.allocationsMu.Unlock()

	for namespace, alloc := range allocations {
		gpuSeconds := int64(alloc.UsedGPUs * 3600)
		cost := alloc.UsedGPUs * m.gpuRatePerHour

		query := `
			INSERT INTO tenant_usage (namespace, date, gpu_usage_seconds, inference_count, cost_amount)
			VALUES ($1, $2, $3, $4, $5)
			ON CONFLICT (namespace, date) DO UPDATE
			SET gpu_usage_seconds = tenant_usage.gpu_usage_seconds + $3,
			    inference_count = tenant_usage.inference_count + $4,
			    cost_amount = tenant_usage.cost_amount + $5
		`

		_, err := m.db.Exec(ctx, query, namespace, today, gpuSeconds, 0, cost)
		if err != nil {
			m.logger.Warn("Failed to persist tenant usage",
				zap.String("namespace", namespace),
				zap.Error(err))
		}
	}
}

func (m *Manager) resourceMonitor(ctx context.Context) {
	defer m.wg.Done()

	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-m.stopCh:
			return
		case <-ticker.C:
			m.adjustAllocations(ctx)
		}
	}
}

func (m *Manager) adjustAllocations(ctx context.Context) {
	m.allocationsMu.Lock()
	defer m.allocationsMu.Unlock()

	for namespace, alloc := range m.allocations {
		ns, err := m.GetNamespace(ctx, namespace)
		if err != nil {
			continue
		}

		utilization := 0.0
		if alloc.AllocatedGPUs > 0 {
			utilization = alloc.UsedGPUs / alloc.AllocatedGPUs
		}

		if utilization > 0.9 && alloc.AllocatedGPUs < ns.GPUQuotaMax {
			additional := (ns.GPUQuotaMax - alloc.AllocatedGPUs) * 0.5
			if additional > 0 {
				alloc.AllocatedGPUs += additional
				m.logger.Info("Increased GPU allocation",
					zap.String("namespace", namespace),
					zap.Float64("new_allocation", alloc.AllocatedGPUs))
			}
		} else if utilization < 0.3 && alloc.AllocatedGPUs > ns.GPUQuotaMin {
			reduction := (alloc.AllocatedGPUs - ns.GPUQuotaMin) * 0.5
			if reduction > 0 {
				alloc.AllocatedGPUs -= reduction
				m.logger.Info("Decreased GPU allocation",
					zap.String("namespace", namespace),
					zap.Float64("new_allocation", alloc.AllocatedGPUs))
			}
		}
	}
}

func (m *Manager) GetUsageReport(ctx context.Context, namespace string, startDate, endDate string) ([]*NamespaceUsage, error) {
	query := `
		SELECT namespace, date, gpu_usage_seconds, inference_count, cost_amount, created_at
		FROM tenant_usage
		WHERE namespace = $1 AND date BETWEEN $2 AND $3
		ORDER BY date DESC
	`

	rows, err := m.db.Query(ctx, query, namespace, startDate, endDate)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var usages []*NamespaceUsage
	for rows.Next() {
		u := &NamespaceUsage{}
		err := rows.Scan(&u.Namespace, &u.Date, &u.GPUUsageSeconds,
			&u.InferenceCount, &u.CostAmount, &u.RecordedAt)
		if err != nil {
			continue
		}
		usages = append(usages, u)
	}

	return usages, nil
}

func (m *Manager) GetAllAllocations() map[string]*ResourceAllocation {
	m.allocationsMu.RLock()
	defer m.allocationsMu.RUnlock()

	result := make(map[string]*ResourceAllocation)
	for k, v := range m.allocations {
		result[k] = &ResourceAllocation{
			Namespace:      v.Namespace,
			AllocatedGPUs:  v.AllocatedGPUs,
			UsedGPUs:       v.UsedGPUs,
			QueueDepth:     v.QueueDepth,
			PendingRequests: v.PendingRequests,
		}
	}
	return result
}

func (m *Manager) UpdateQueueDepth(namespace string, depth int64) {
	m.allocationsMu.Lock()
	defer m.allocationsMu.Unlock()

	if alloc, ok := m.allocations[namespace]; ok {
		alloc.QueueDepth = depth
	}
}
