package service

import (
	"context"
	"fmt"
	"time"

	"projectservice/internal/infrastructure/logger"
	"projectservice/internal/infrastructure/monitor"
	"projectservice/internal/model"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

type CatalogService struct {
	db      *gorm.DB
	logger  *logger.Logger
	metrics *monitor.Metrics
}

func NewCatalogService(db *gorm.DB, log *logger.Logger, metrics *monitor.Metrics) *CatalogService {
	return &CatalogService{
		db:      db,
		logger:  log,
		metrics: metrics,
	}
}

func (s *CatalogService) RegisterService(ctx context.Context, req *model.RegisterServiceRequest) (*model.SoftwareCatalog, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("catalog", "register", "success", time.Since(start))
	}()

	svc := &model.SoftwareCatalog{
		ID:            uuid.New().String(),
		Name:          req.Name,
		Type:          req.Type,
		Description:   req.Description,
		Version:       req.Version,
		Owner:         req.Owner,
		Repository:    req.Repository,
		Documentation: req.Documentation,
		Tags:          req.Tags,
		Metadata:      req.Metadata,
		Status:        "active",
		CreatedAt:     time.Now(),
		UpdatedAt:     time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(svc).Error; err != nil {
		s.metrics.ObserveError("catalog", "db_error")
		return nil, fmt.Errorf("failed to register service: %w", err)
	}

	return svc, nil
}

func (s *CatalogService) GetService(ctx context.Context, serviceID string) (*model.ServiceDetail, error) {
	var svc model.SoftwareCatalog
	if err := s.db.WithContext(ctx).Where("id = ?", serviceID).First(&svc).Error; err != nil {
		return nil, fmt.Errorf("service not found: %w", err)
	}

	var dependencies []model.ServiceDependency
	if err := s.db.WithContext(ctx).
		Where("service_id = ?", serviceID).
		Find(&dependencies).Error; err != nil {
		return nil, err
	}

	var dependedBy []model.ServiceDependency
	if err := s.db.WithContext(ctx).
		Where("depend_on_id = ?", serviceID).
		Find(&dependedBy).Error; err != nil {
		return nil, err
	}

	return &model.ServiceDetail{
		SoftwareCatalog: svc,
		Dependencies:    dependencies,
		DependedBy:      dependedBy,
		TotalDependents: len(dependedBy),
	}, nil
}

func (s *CatalogService) SearchCatalog(ctx context.Context, req *model.SearchCatalogRequest) ([]model.SoftwareCatalog, int64, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("catalog", "search", "success", time.Since(start))
	}()

	var services []model.SoftwareCatalog
	var total int64

	query := s.db.WithContext(ctx).Model(&model.SoftwareCatalog{}).Where("status = ?", "active")

	if req.Query != "" {
		pattern := fmt.Sprintf("%%%s%%", req.Query)
		query = query.Where("name ILIKE ? OR description ILIKE ?", pattern, pattern)
	}
	if req.Type != "" {
		query = query.Where("type = ?", req.Type)
	}
	if len(req.Tags) > 0 {
		query = query.Where("tags @> ?", req.Tags)
	}
	if req.Owner != "" {
		query = query.Where("owner = ?", req.Owner)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (req.Page - 1) * req.PageSize
	if err := query.Offset(offset).Limit(req.PageSize).Order("created_at DESC").Find(&services).Error; err != nil {
		return nil, 0, err
	}

	return services, total, nil
}

func (s *CatalogService) UpdateService(ctx context.Context, serviceID string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	result := s.db.WithContext(ctx).
		Model(&model.SoftwareCatalog{}).
		Where("id = ?", serviceID).
		Updates(updates)

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("service not found")
	}
	return nil
}

func (s *CatalogService) DeleteService(ctx context.Context, serviceID string) error {
	result := s.db.WithContext(ctx).
		Model(&model.SoftwareCatalog{}).
		Where("id = ?", serviceID).
		Update("status", "inactive")

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("service not found")
	}
	return nil
}

func (s *CatalogService) AddDependency(ctx context.Context, dep *model.ServiceDependency) (*model.ServiceDependency, error) {
	dep.ID = uuid.New().String()
	dep.CreatedAt = time.Now()
	dep.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(dep).Error; err != nil {
		return nil, fmt.Errorf("failed to add dependency: %w", err)
	}

	return dep, nil
}

func (s *CatalogService) RemoveDependency(ctx context.Context, dependencyID string) error {
	result := s.db.WithContext(ctx).Delete(&model.ServiceDependency{}, "id = ?", dependencyID)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("dependency not found")
	}
	return nil
}

func (s *CatalogService) GetDependencyGraph(ctx context.Context, serviceID string, depth int) (*model.DependencyGraph, error) {
	svc, err := s.GetService(ctx, serviceID)
	if err != nil {
		return nil, err
	}

	graph := &model.DependencyGraph{
		ServiceID:   svc.ID,
		ServiceName: svc.Name,
	}

	visited := make(map[string]bool)
	s.buildGraph(ctx, serviceID, 0, depth, graph, visited)

	return graph, nil
}

func (s *CatalogService) buildGraph(ctx context.Context, serviceID string, currentDepth, maxDepth int, graph *model.DependencyGraph, visited map[string]bool) {
	if currentDepth >= maxDepth || visited[serviceID] {
		return
	}
	visited[serviceID] = true

	var svc model.SoftwareCatalog
	if err := s.db.WithContext(ctx).Where("id = ?", serviceID).First(&svc).Error; err != nil {
		return
	}

	graph.Nodes = append(graph.Nodes, model.DependencyNode{
		ID:    svc.ID,
		Name:  svc.Name,
		Type:  svc.Type,
		Level: currentDepth,
	})

	var dependencies []model.ServiceDependency
	if err := s.db.WithContext(ctx).
		Where("service_id = ?", serviceID).
		Find(&dependencies).Error; err != nil {
		return
	}

	for _, dep := range dependencies {
		graph.Edges = append(graph.Edges, model.DependencyEdge{
			From: serviceID,
			To:   dep.DependOnID,
			Type: dep.DependencyType,
		})
		s.buildGraph(ctx, dep.DependOnID, currentDepth+1, maxDepth, graph, visited)
	}
}

func (s *CatalogService) ListServices(ctx context.Context, serviceType string, page, pageSize int) ([]model.SoftwareCatalog, int64, error) {
	var services []model.SoftwareCatalog
	var total int64

	query := s.db.WithContext(ctx).Model(&model.SoftwareCatalog{}).Where("status = ?", "active")

	if serviceType != "" {
		query = query.Where("type = ?", serviceType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&services).Error; err != nil {
		return nil, 0, err
	}

	return services, total, nil
}
