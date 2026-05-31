package environments

import (
	"context"
	"depguard/database"
	"depguard/events"
	"depguard/logger"
	"depguard/utils"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"time"
)

type Service struct {
	db *gorm.DB
}

func NewService() *Service {
	return &Service{db: database.Get()}
}

func (s *Service) CreateRequest(ctx context.Context, req *CreateEnvRequest, ownerID string) (*EnvironmentRequest, error) {
	resources := ResourceSpec{
		CPU:     "1",
		Memory:  "2Gi",
		Storage: "10Gi",
		Replicas: 1,
	}
	if req.Resources != nil {
		resources = *req.Resources
	}

	ttl := req.TTLSeconds
	if ttl <= 0 {
		ttl = 3600 * 24
	}

	envReq := &EnvironmentRequest{
		ID:         utils.GenerateID("envreq"),
		Name:       req.Name,
		Type:       req.Type,
		OwnerID:    ownerID,
		ProjectID:  req.ProjectID,
		Status:     "pending",
		Config:     req.Config,
		Resources:  resources,
		TTLSeconds: ttl,
		Reason:     req.Reason,
		CreatedAt:  time.Now(),
		UpdatedAt:  time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(envReq).Error; err != nil {
		return nil, err
	}

	events.Get().Publish(ctx, events.Event{
		Type: "env.request.created",
		Payload: map[string]interface{}{
			"request_id": envReq.ID,
			"owner_id":   ownerID,
			"type":       req.Type,
		},
		TraceID: getTraceID(ctx),
	})

	return envReq, nil
}

func (s *Service) ListRequests(ctx context.Context, ownerID string, status string, page, size int) ([]EnvironmentRequest, int64, error) {
	if page < 0 {
		page = 0
	}
	if size <= 0 || size > 100 {
		size = 20
	}

	var requests []EnvironmentRequest
	var total int64

	q := s.db.WithContext(ctx).Model(&EnvironmentRequest{})
	if ownerID != "" {
		q = q.Where("owner_id = ?", ownerID)
	}
	if status != "" {
		q = q.Where("status = ?", status)
	}

	q.Count(&total)

	if err := q.Order("created_at DESC").
		Offset(page * size).
		Limit(size).
		Find(&requests).Error; err != nil {
		return nil, 0, err
	}

	return requests, total, nil
}

func (s *Service) ApproveRequest(ctx context.Context, requestID, approverID string) (*Environment, error) {
	var req EnvironmentRequest
	if err := s.db.WithContext(ctx).First(&req, "id = ?", requestID).Error; err != nil {
		return nil, err
	}

	if req.Status != "pending" {
		return nil, nil
	}

	now := time.Now()
	req.Status = "approved"
	req.ApproverID = &approverID
	req.ApprovedAt = &now
	req.UpdatedAt = now

	if err := s.db.WithContext(ctx).Save(&req).Error; err != nil {
		return nil, err
	}

	env, err := s.createEnvironment(ctx, &req)
	if err != nil {
		return nil, err
	}

	req.EnvironmentID = &env.ID
	s.db.Save(&req)

	events.Get().Publish(ctx, events.Event{
		Type: "env.created",
		Payload: map[string]interface{}{
			"env_id":   env.ID,
			"owner_id": env.OwnerID,
		},
		TraceID: getTraceID(ctx),
	})

	return env, nil
}

func (s *Service) createEnvironment(ctx context.Context, req *EnvironmentRequest) (*Environment, error) {
	now := time.Now()
	var expiresAt *time.Time
	if req.TTLSeconds > 0 {
		t := now.Add(time.Duration(req.TTLSeconds) * time.Second)
		expiresAt = &t
	}

	env := &Environment{
		ID:          utils.GenerateID("env"),
		Name:        req.Name,
		Type:        req.Type,
		Status:      "provisioning",
		OwnerID:     req.OwnerID,
		ProjectID:   req.ProjectID,
		Config:      req.Config,
		Endpoints:   map[string]string{},
		Resources:   req.Resources,
		TTLSeconds:  req.TTLSeconds,
		ExpiresAt:   expiresAt,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	if err := s.db.WithContext(ctx).Create(env).Error; err != nil {
		return nil, err
	}

	go s.provisionEnvironment(env)

	return env, nil
}

func (s *Service) provisionEnvironment(env *Environment) {
	logger.Get().Info("provisioning environment",
		zap.String("env_id", env.ID),
		zap.String("name", env.Name),
	)

	time.Sleep(2 * time.Second)

	now := time.Now()
	env.Status = "running"
	env.StartedAt = &now
	env.Endpoints = map[string]string{
		"web":     "http://" + env.ID + ".example.com",
		"api":     "http://api." + env.ID + ".example.com",
		"metrics": "http://metrics." + env.ID + ".example.com",
	}
	env.UpdatedAt = now

	s.db.Save(env)

	logger.Get().Info("environment provisioned", zap.String("env_id", env.ID))

	events.Get().Publish(context.Background(), events.Event{
		Type: "env.provisioned",
		Payload: map[string]interface{}{
			"env_id": env.ID,
		},
		TraceID: "",
	})
}

func (s *Service) ListEnvironments(ctx context.Context, ownerID, projectID, status string, page, size int) ([]Environment, int64, error) {
	if page < 0 {
		page = 0
	}
	if size <= 0 || size > 100 {
		size = 20
	}

	var envs []Environment
	var total int64

	q := s.db.WithContext(ctx).Model(&Environment{})
	if ownerID != "" {
		q = q.Where("owner_id = ?", ownerID)
	}
	if projectID != "" {
		q = q.Where("project_id = ?", projectID)
	}
	if status != "" {
		q = q.Where("status = ?", status)
	}

	q.Count(&total)

	if err := q.Order("created_at DESC").
		Offset(page * size).
		Limit(size).
		Find(&envs).Error; err != nil {
		return nil, 0, err
	}

	return envs, total, nil
}

func (s *Service) GetEnvironment(ctx context.Context, id string) (*Environment, error) {
	var env Environment
	if err := s.db.WithContext(ctx).First(&env, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &env, nil
}

func (s *Service) StopEnvironment(ctx context.Context, id string) error {
	var env Environment
	if err := s.db.WithContext(ctx).First(&env, "id = ?", id).Error; err != nil {
		return err
	}

	if env.Status == "running" {
		now := time.Now()
		env.Status = "stopped"
		env.StoppedAt = &now
		env.UpdatedAt = now
		s.db.Save(&env)

		s.recordUsage(&env)
	}

	return nil
}

func (s *Service) DeleteEnvironment(ctx context.Context, id string) error {
	var env Environment
	if err := s.db.WithContext(ctx).First(&env, "id = ?", id).Error; err != nil {
		return err
	}

	if env.Status == "running" {
		s.recordUsage(&env)
	}

	return s.db.WithContext(ctx).Delete(&env).Error
}

func (s *Service) recordUsage(env *Environment) {
	var start time.Time
	if env.StartedAt != nil {
		start = *env.StartedAt
	} else {
		start = env.CreatedAt
	}

	end := time.Now()
	duration := end.Sub(start)

	record := &UsageRecord{
		ID:            utils.GenerateID("usage"),
		EnvironmentID: env.ID,
		OwnerID:       env.OwnerID,
		ProjectID:     env.ProjectID,
		CPUSeconds:    duration.Seconds() * float64(env.Resources.Replicas),
		MemoryMBHours: duration.Hours() * 2048,
		StartTime:     start,
		EndTime:       end,
	}

	if err := s.db.Create(record).Error; err != nil {
		logger.Get().Warn("failed to record usage", zap.Error(err))
	}
}

func (s *Service) CleanupExpired(ctx context.Context) {
	now := time.Now()
	var expired []Environment
	s.db.Where("status = ? AND expires_at IS NOT NULL AND expires_at < ?", "running", now).Find(&expired)

	for _, env := range expired {
		logger.Get().Info("cleaning up expired environment",
			zap.String("env_id", env.ID),
			zap.Time("expired_at", *env.ExpiresAt),
		)
		s.recordUsage(&env)
		s.db.Delete(&env)
	}
}

func (s *Service) GetUsageStats(ctx context.Context, start, end time.Time) (*DailyStats, error) {
	var totalCPU float64
	var totalMemory float64

	s.db.Model(&UsageRecord{}).
		Where("start_time >= ? AND end_time <= ?", start, end).
		Select("COALESCE(SUM(cpu_seconds), 0)", "COALESCE(SUM(memory_mb_hours), 0)").
		Row().Scan(&totalCPU, &totalMemory)

	var active int64
	s.db.Model(&Environment{}).Where("status = ?", "running").Count(&active)

	var totalReqs int64
	s.db.Model(&EnvironmentRequest{}).
		Where("created_at >= ? AND created_at <= ?", start, end).
		Count(&totalReqs)

	var approvedReqs int64
	s.db.Model(&EnvironmentRequest{}).
		Where("status = ? AND approved_at >= ? AND approved_at <= ?", "approved", start, end).
		Count(&approvedReqs)

	return &DailyStats{
		ID:               utils.GenerateID("stat"),
		Date:             start,
		TotalRequests:    int(totalReqs),
		ApprovedRequests: int(approvedReqs),
		ActiveEnvs:       int(active),
		TotalCPUHours:    totalCPU / 3600,
		TotalMemoryGBHours: totalMemory / 1024,
	}, nil
}

func getTraceID(ctx context.Context) string {
	if v := ctx.Value("trace_id"); v != nil {
		return v.(string)
	}
	return ""
}
