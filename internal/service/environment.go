package service

import (
	"context"
	"fmt"
	"math"
	"sort"
	"sync"
	"time"

	"projectservice/internal/infrastructure/logger"
	"projectservice/internal/infrastructure/monitor"
	"projectservice/internal/model"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

type EnvironmentService struct {
	db      *gorm.DB
	logger  *logger.Logger
	metrics *monitor.Metrics

	timingMu    sync.RWMutex
	timings     map[string][]*model.TimingBreakdown
	lifecycleMu sync.RWMutex
	lifecycle   []model.EnvironmentLifecycleEvent
}

func NewEnvironmentService(db *gorm.DB, log *logger.Logger, metrics *monitor.Metrics) *EnvironmentService {
	return &EnvironmentService{
		db:        db,
		logger:    log,
		metrics:   metrics,
		timings:   make(map[string][]*model.TimingBreakdown),
		lifecycle: make([]model.EnvironmentLifecycleEvent, 0),
	}
}

func (s *EnvironmentService) CreateEnvironment(ctx context.Context, req *model.CreateEnvironmentRequest) (*model.Environment, error) {
	start := time.Now()
	s.metrics.IncInFlight()
	defer s.metrics.DecInFlight()

	defer func() {
		s.metrics.ObserveTaskDuration("environment", "create", "success", time.Since(start))
	}()

	env := &model.Environment{
		ID:            uuid.New().String(),
		Name:          req.Name,
		Type:          req.Type,
		Status:        "creating",
		Owner:         req.Owner,
		ProjectID:     req.ProjectID,
		Configuration: req.Configuration,
		CreatedAt:     time.Now(),
		UpdatedAt:     time.Now(),
	}

	if req.TTLHours > 0 {
		ttl := time.Duration(req.TTLHours) * time.Hour
		env.TTL = &ttl
		reclaimAt := time.Now().Add(ttl)
		env.AutoReclaimAt = &reclaimAt
	}

	if err := s.db.WithContext(ctx).Create(env).Error; err != nil {
		s.metrics.ObserveError("environment", "db_error")
		return nil, fmt.Errorf("failed to create environment: %w", err)
	}

	env.Status = "running"
	env.LastActiveAt = &env.CreatedAt
	if err := s.db.WithContext(ctx).Save(env).Error; err != nil {
		s.logger.Errorw("Failed to update environment status", "error", err)
	}

	s.recordUsage(ctx, env.ID, "creation", 1.0)

	return env, nil
}

func (s *EnvironmentService) GetEnvironment(ctx context.Context, envID string) (*model.Environment, error) {
	var env model.Environment
	if err := s.db.WithContext(ctx).Where("id = ?", envID).First(&env).Error; err != nil {
		return nil, fmt.Errorf("environment not found: %w", err)
	}
	return &env, nil
}

func (s *EnvironmentService) GetEnvironmentStatus(ctx context.Context, envID string) (*model.EnvironmentStatusResponse, error) {
	env, err := s.GetEnvironment(ctx, envID)
	if err != nil {
		return nil, err
	}

	return &model.EnvironmentStatusResponse{
		ID:            env.ID,
		Name:          env.Name,
		Type:          env.Type,
		Status:        env.Status,
		Owner:         env.Owner,
		AutoReclaimAt: env.AutoReclaimAt,
		CreatedAt:     env.CreatedAt,
		LastActiveAt:  env.LastActiveAt,
	}, nil
}

func (s *EnvironmentService) ListEnvironments(ctx context.Context, owner, projectID, status string, page, pageSize int) ([]model.Environment, int64, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("environment", "list", "success", time.Since(start))
	}()

	var envs []model.Environment
	var total int64

	query := s.db.WithContext(ctx).Model(&model.Environment{})

	if owner != "" {
		query = query.Where("owner = ?", owner)
	}
	if projectID != "" {
		query = query.Where("project_id = ?", projectID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&envs).Error; err != nil {
		return nil, 0, err
	}

	return envs, total, nil
}

func (s *EnvironmentService) UpdateEnvironmentStatus(ctx context.Context, envID, status string) error {
	result := s.db.WithContext(ctx).
		Model(&model.Environment{}).
		Where("id = ?", envID).
		Updates(map[string]interface{}{
			"status":     status,
			"updated_at": time.Now(),
		})

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("environment not found")
	}

	now := time.Now()
	s.db.WithContext(ctx).
		Model(&model.Environment{}).
		Where("id = ?", envID).
		Update("last_active_at", now)

	return nil
}

func (s *EnvironmentService) DeleteEnvironment(ctx context.Context, envID string) error {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("environment", "delete", "success", time.Since(start))
	}()

	if err := s.UpdateEnvironmentStatus(ctx, envID, "deleting"); err != nil {
		return err
	}

	result := s.db.WithContext(ctx).Delete(&model.Environment{}, "id = ?", envID)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("environment not found")
	}

	return nil
}

func (s *EnvironmentService) ReclaimExpiredEnvironments(ctx context.Context) ([]string, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("environment", "reclaim", "success", time.Since(start))
	}()

	var expiredEnvs []model.Environment
	now := time.Now()

	if err := s.db.WithContext(ctx).
		Where("auto_reclaim_at <= ? AND status = ?", now, "running").
		Find(&expiredEnvs).Error; err != nil {
		return nil, err
	}

	var reclaimedIDs []string
	for _, env := range expiredEnvs {
		if err := s.DeleteEnvironment(ctx, env.ID); err != nil {
			s.logger.Errorw("Failed to reclaim environment", "env_id", env.ID, "error", err)
			continue
		}
		reclaimedIDs = append(reclaimedIDs, env.ID)
		s.logger.Infow("Environment reclaimed", "env_id", env.ID, "name", env.Name)
	}

	return reclaimedIDs, nil
}

func (s *EnvironmentService) GetUsageStatistics(ctx context.Context, req *model.UsageStatisticsRequest) (*model.UsageStatisticsResponse, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("environment", "usage_stats", "success", time.Since(start))
	}()

	var records []model.EnvironmentUsage
	query := s.db.WithContext(ctx).Where("environment_id = ?", req.EnvironmentID)

	if req.ResourceType != "" {
		query = query.Where("resource_type = ?", req.ResourceType)
	}
	if req.StartTime != "" {
		if t, err := time.Parse(time.RFC3339, req.StartTime); err == nil {
			query = query.Where("recorded_at >= ?", t)
		}
	}
	if req.EndTime != "" {
		if t, err := time.Parse(time.RFC3339, req.EndTime); err == nil {
			query = query.Where("recorded_at <= ?", t)
		}
	}

	if err := query.Order("recorded_at ASC").Find(&records).Error; err != nil {
		return nil, err
	}

	var total, peak float64
	for _, r := range records {
		total += r.UsageValue
		if r.UsageValue > peak {
			peak = r.UsageValue
		}
	}

	avg := 0.0
	if len(records) > 0 {
		avg = total / float64(len(records))
	}

	response := &model.UsageStatisticsResponse{
		EnvironmentID: req.EnvironmentID,
		ResourceType:  req.ResourceType,
		Average:       avg,
		Peak:          peak,
		Records:       records,
	}

	if len(records) > 0 {
		response.StartTime = records[0].RecordedAt
		response.EndTime = records[len(records)-1].RecordedAt
	}

	return response, nil
}

func (s *EnvironmentService) recordUsage(ctx context.Context, envID, resourceType string, value float64) {
	usage := &model.EnvironmentUsage{
		ID:            uuid.New().String(),
		EnvironmentID: envID,
		ResourceType:  resourceType,
		UsageValue:    value,
		RecordedAt:    time.Now(),
	}
	if err := s.db.WithContext(ctx).Create(usage).Error; err != nil {
		s.logger.Errorw("Failed to record usage", "error", err)
	}

	s.metrics.SetEnvironmentUsage(envID, resourceType, value)
}

func (s *EnvironmentService) RecordPeriodicUsage(ctx context.Context, envID string, cpu, memory float64) {
	s.recordUsage(ctx, envID, "cpu", cpu)
	s.recordUsage(ctx, envID, "memory", memory)
}

func (s *EnvironmentService) ExtendTTL(ctx context.Context, envID string, hours int) error {
	env, err := s.GetEnvironment(ctx, envID)
	if err != nil {
		return err
	}

	if env.AutoReclaimAt == nil {
		return fmt.Errorf("environment has no TTL set")
	}

	newReclaimAt := env.AutoReclaimAt.Add(time.Duration(hours) * time.Hour)
	result := s.db.WithContext(ctx).
		Model(&model.Environment{}).
		Where("id = ?", envID).
		Update("auto_reclaim_at", newReclaimAt)

	return result.Error
}

// ===== 监控增强方法

func (s *EnvironmentService) RecordKeyPathTiming(envID, operation string, breakdown []*model.TimingBreakdown) {
	s.timingMu.Lock()
	defer s.timingMu.Unlock()

	key := fmt.Sprintf("%s:%s", envID, operation)
	s.timings[key] = breakdown
}

func (s *EnvironmentService) GetEnvironmentTiming(envID, operation string) (*model.EnvironmentTiming, error) {
	s.timingMu.RLock()
	defer s.timingMu.RUnlock()

	key := fmt.Sprintf("%s:%s", envID, operation)
	breakdown, ok := s.timings[key]
	if !ok {
		return nil, fmt.Errorf("no timing data found for env=%s op=%s", envID, operation)
	}

	var totalMs int64
	for _, b := range breakdown {
		totalMs += b.DurationMs
	}

	for _, b := range breakdown {
		if totalMs > 0 {
			b.Percent = float64(b.DurationMs) / float64(totalMs) * 100
		}
	}

	return &model.EnvironmentTiming{
		EnvID:     envID,
		Operation: operation,
		TotalMs:   totalMs,
		Breakdown: breakdown,
	}, nil
}

func (s *EnvironmentService) GetEnvironmentHealth(ctx context.Context, envID string) (*model.EnvironmentHealth, error) {
	env, err := s.GetEnvironment(ctx, envID)
	if err != nil {
		return nil, err
	}

	now := time.Now()
	var checks []model.HealthCheckItem

	healthy := env.Status == "running"
	checks = append(checks, model.HealthCheckItem{
		Name:    "status",
		Healthy: healthy,
		Detail:  fmt.Sprintf("status=%s", env.Status),
	})

	if env.AutoReclaimAt != nil {
		expiringSoon := env.AutoReclaimAt.Sub(now) < 1*time.Hour
		checks = append(checks, model.HealthCheckItem{
			Name:    "ttl",
			Healthy: !expiringSoon,
			Detail:  fmt.Sprintf("reclaim_at=%s", env.AutoReclaimAt.Format(time.RFC3339)),
		})
	} else {
		checks = append(checks, model.HealthCheckItem{
			Name:    "ttl",
			Healthy: true,
			Detail:  "no ttl set",
		})
	}

	if env.LastActiveAt != nil {
		activeWithin24h := now.Sub(*env.LastActiveAt) < 24*time.Hour
		checks = append(checks, model.HealthCheckItem{
			Name:    "activity",
			Healthy: activeWithin24h,
			Detail:  fmt.Sprintf("last_active=%s", env.LastActiveAt.Format(time.RFC3339)),
		})
	}

	uptime := int64(now.Sub(env.CreatedAt).Seconds())

	allHealthy := true
	for _, c := range checks {
		if !c.Healthy {
			allHealthy = false
			break
		}
	}

	return &model.EnvironmentHealth{
		EnvID:     env.ID,
		Name:      env.Name,
		Status:    env.Status,
		Healthy:   allHealthy,
		LastCheck: now,
		Checks:    checks,
		UptimeSec: uptime,
	}, nil
}

func (s *EnvironmentService) GetResourceUsageSummary(ctx context.Context, envID, resourceType string) (*model.ResourceUsageSummary, error) {
	var records []model.EnvironmentUsage
	query := s.db.WithContext(ctx).Where("environment_id = ?", envID)

	if resourceType != "" {
		query = query.Where("resource_type = ?", resourceType)
	}

	if err := query.Order("recorded_at ASC").Find(&records).Error; err != nil {
		return nil, err
	}

	if len(records) == 0 {
		return nil, fmt.Errorf("no usage records found")
	}

	var values []float64
	for _, r := range records {
		values = append(values, r.UsageValue)
	}

	sort.Float64s(values)

	min := values[0]
	max := values[len(values)-1]

	var sum float64
	for _, v := range values {
		sum += v
	}
	avg := sum / float64(len(values))

	var variance float64
	for _, v := range values {
		variance += (v - avg) * (v - avg)
	}
	stdDev := math.Sqrt(variance / float64(len(values)))

	lastRecord := records[len(records)-1]

	return &model.ResourceUsageSummary{
		EnvironmentID:   envID,
		ResourceType:    resourceType,
		Average:         avg,
		Peak:            max,
		Minimum:         min,
		StdDev:          stdDev,
		SampleCount:     len(records),
		LastSampleValue: lastRecord.UsageValue,
		LastSampleTs:    lastRecord.RecordedAt,
	}, nil
}

func (s *EnvironmentService) GetEnvironmentStats(ctx context.Context) (*model.EnvironmentStats, error) {
	var stats model.EnvironmentStats

	var total int64
	if err := s.db.WithContext(ctx).Model(&model.Environment{}).Count(&total).Error; err != nil {
		return nil, err
	}
	stats.TotalEnvs = int(total)

	var running int64
	if err := s.db.WithContext(ctx).Model(&model.Environment{}).Where("status = ?", "running").Count(&running).Error; err != nil {
		return nil, err
	}
	stats.RunningEnvs = int(running)

	var stopped int64
	if err := s.db.WithContext(ctx).Model(&model.Environment{}).Where("status = ?", "stopped").Count(&stopped).Error; err != nil {
		return nil, err
	}
	stats.StoppedEnvs = int(stopped)

	oneHourLater := time.Now().Add(1 * time.Hour)
	var expiring int64
	if err := s.db.WithContext(ctx).Model(&model.Environment{}).
		Where("auto_reclaim_at <= ? AND status = ?", oneHourLater, "running").
		Count(&expiring).Error; err != nil {
		return nil, err
	}
	stats.ExpiringSoon = int(expiring)

	twentyFourHoursAgo := time.Now().Add(-24 * time.Hour)
	var reclaimedCount int64
	if err := s.db.WithContext(ctx).Model(&model.Environment{}).
		Where("updated_at >= ? AND status = ?", twentyFourHoursAgo, "deleting").
		Count(&reclaimedCount).Error; err != nil {
		return nil, err
	}
	stats.Reclaimed24h = int(reclaimedCount)

	var envs []model.Environment
	if err := s.db.WithContext(ctx).Find(&envs).Error; err != nil {
		return nil, err
	}

	var totalUptime float64
	for _, e := range envs {
		if e.LastActiveAt != nil {
			totalUptime += e.LastActiveAt.Sub(e.CreatedAt).Minutes()
		} else {
			totalUptime += time.Since(e.CreatedAt).Minutes()
		}
	}
	if len(envs) > 0 {
		stats.AvgUptimeMin = totalUptime / float64(len(envs))
	}

	return &stats, nil
}

func (s *EnvironmentService) RecordLifecycleEvent(envID, eventType, detail string) {
	s.lifecycleMu.Lock()
	defer s.lifecycleMu.Unlock()

	event := model.EnvironmentLifecycleEvent{
		ID:        uuid.New().String(),
		EnvID:     envID,
		EventType: eventType,
		Detail:    detail,
		Timestamp: time.Now(),
	}
	s.lifecycle = append(s.lifecycle, event)

	if len(s.lifecycle) > 1000 {
		s.lifecycle = s.lifecycle[len(s.lifecycle)-500:]
	}

	s.logger.Infow("Environment lifecycle event",
		"env_id", envID,
		"event_type", eventType,
		"detail", detail,
	)
}

func (s *EnvironmentService) GetLifecycleEvents(envID string, limit int) []model.EnvironmentLifecycleEvent {
	s.lifecycleMu.RLock()
	defer s.lifecycleMu.RUnlock()

	var events []model.EnvironmentLifecycleEvent
	for i := len(s.lifecycle) - 1; i >= 0 && len(events) < limit; i-- {
		if envID == "" || s.lifecycle[i].EnvID == envID {
			events = append(events, s.lifecycle[i])
		}
	}
	return events
}
