package softwarecatalog

import (
	"context"
	"depguard/database"
	"depguard/utils"
	"gorm.io/gorm"
	"strings"
	"time"
)

type Service struct {
	db *gorm.DB
}

func NewService() *Service {
	return &Service{db: database.Get()}
}

func (s *Service) RegisterService(ctx context.Context, svc *Service) (*Service, error) {
	svc.ID = utils.GenerateID("svc")
	svc.CreatedAt = time.Now()
	svc.UpdatedAt = time.Now()
	if svc.Status == "" {
		svc.Status = "active"
	}

	if err := s.db.WithContext(ctx).Create(svc).Error; err != nil {
		return nil, err
	}
	return svc, nil
}

func (s *Service) ListServices(ctx context.Context, q *SearchQuery) ([]Service, int64, error) {
	if q.Page < 0 {
		q.Page = 0
	}
	if q.Size <= 0 || q.Size > 100 {
		q.Size = 20
	}

	var services []Service
	var total int64

	query := s.db.WithContext(ctx).Model(&Service{})

	if q.Type != "" {
		query = query.Where("type = ?", q.Type)
	}
	if q.Language != "" {
		query = query.Where("language = ?", q.Language)
	}
	if q.Status != "" {
		query = query.Where("status = ?", q.Status)
	}
	if q.OwnerTeam != "" {
		query = query.Where("owner_team = ?", q.OwnerTeam)
	}
	if q.Query != "" {
		pattern := "%" + q.Query + "%"
		query = query.Where("name ILIKE ? OR description ILIKE ?", pattern, pattern)
	}

	query.Count(&total)

	if err := query.Order("created_at DESC").
		Offset(q.Page * q.Size).
		Limit(q.Size).
		Find(&services).Error; err != nil {
		return nil, 0, err
	}

	return services, total, nil
}

func (s *Service) GetService(ctx context.Context, id string) (*Service, error) {
	var svc Service
	if err := s.db.WithContext(ctx).First(&svc, "id = ? OR name = ?", id, id).Error; err != nil {
		return nil, err
	}
	return &svc, nil
}

func (s *Service) UpdateService(ctx context.Context, id string, svc *Service) (*Service, error) {
	svc.UpdatedAt = time.Now()
	if err := s.db.WithContext(ctx).Where("id = ?", id).Save(svc).Error; err != nil {
		return nil, err
	}
	return svc, nil
}

func (s *Service) DeleteService(ctx context.Context, id string) error {
	return s.db.WithContext(ctx).Delete(&Service{}, "id = ?", id).Error
}

func (s *Service) RegisterLibrary(ctx context.Context, lib *Library) (*Library, error) {
	lib.ID = utils.GenerateID("lib")
	lib.CreatedAt = time.Now()
	lib.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(lib).Error; err != nil {
		return nil, err
	}
	return lib, nil
}

func (s *Service) ListLibraries(ctx context.Context, q *SearchQuery) ([]Library, int64, error) {
	if q.Page < 0 {
		q.Page = 0
	}
	if q.Size <= 0 || q.Size > 100 {
		q.Size = 20
	}

	var libraries []Library
	var total int64

	query := s.db.WithContext(ctx).Model(&Library{})

	if q.Language != "" {
		query = query.Where("language = ?", q.Language)
	}
	if q.Query != "" {
		pattern := "%" + q.Query + "%"
		query = query.Where("name ILIKE ? OR description ILIKE ?", pattern, pattern)
	}

	query.Count(&total)

	if err := query.Order("name, created_at DESC").
		Offset(q.Page * q.Size).
		Limit(q.Size).
		Find(&libraries).Error; err != nil {
		return nil, 0, err
	}

	return libraries, total, nil
}

func (s *Service) GetLibrary(ctx context.Context, id string) (*Library, error) {
	var lib Library
	if err := s.db.WithContext(ctx).First(&lib, "id = ? OR name = ?", id, id).Error; err != nil {
		return nil, err
	}
	return &lib, nil
}

func (s *Service) AddDependency(ctx context.Context, fromID, fromType, toID, toType, version, scope string) (*Dependency, error) {
	dep := &Dependency{
		ID:                utils.GenerateID("dep"),
		FromID:            fromID,
		FromType:          fromType,
		ToID:              toID,
		ToType:            toType,
		VersionConstraint: version,
		Scope:             scope,
		CreatedAt:         time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(dep).Error; err != nil {
		return nil, err
	}
	return dep, nil
}

func (s *Service) GetDependencies(ctx context.Context, id string) (*DependencyGraph, error) {
	graph := &DependencyGraph{
		Nodes: []DependencyNode{},
		Edges: []DependencyEdge{},
	}

	visited := make(map[string]bool)
	s.buildDependencyGraph(ctx, id, graph, visited, 0)

	return graph, nil
}

func (s *Service) buildDependencyGraph(ctx context.Context, id string, graph *DependencyGraph, visited map[string]bool, depth int) {
	if depth > 5 {
		return
	}
	if visited[id] {
		return
	}
	visited[id] = true

	var svc Service
	if err := s.db.WithContext(ctx).First(&svc, "id = ?", id).Error; err == nil {
		graph.Nodes = append(graph.Nodes, DependencyNode{
			ID:       svc.ID,
			Name:     svc.Name,
			Type:     "service",
			Language: svc.Language,
		})
	}

	var lib Library
	if err := s.db.WithContext(ctx).First(&lib, "id = ?", id).Error; err == nil {
		graph.Nodes = append(graph.Nodes, DependencyNode{
			ID:       lib.ID,
			Name:     lib.Name,
			Type:     "library",
			Version:  lib.Version,
			Language: lib.Language,
		})
	}

	var deps []Dependency
	s.db.WithContext(ctx).Where("from_id = ?", id).Find(&deps)

	for _, dep := range deps {
		graph.Edges = append(graph.Edges, DependencyEdge{
			From:    dep.FromID,
			To:      dep.ToID,
			Version: dep.VersionConstraint,
			Scope:   dep.Scope,
		})
		s.buildDependencyGraph(ctx, dep.ToID, graph, visited, depth+1)
	}
}

func (s *Service) GetDependents(ctx context.Context, id string) ([]DependencyNode, error) {
	var deps []Dependency
	if err := s.db.WithContext(ctx).Where("to_id = ?", id).Find(&deps).Error; err != nil {
		return nil, err
	}

	var nodes []DependencyNode
	for _, dep := range deps {
		if dep.FromType == "service" {
			var svc Service
			if err := s.db.WithContext(ctx).First(&svc, "id = ?", dep.FromID).Error; err == nil {
				nodes = append(nodes, DependencyNode{
					ID:       svc.ID,
					Name:     svc.Name,
					Type:     "service",
					Language: svc.Language,
				})
			}
		} else {
			var lib Library
			if err := s.db.WithContext(ctx).First(&lib, "id = ?", dep.FromID).Error; err == nil {
				nodes = append(nodes, DependencyNode{
					ID:       lib.ID,
					Name:     lib.Name,
					Type:     "library",
					Version:  lib.Version,
				})
			}
		}
	}

	return nodes, nil
}

func (s *Service) AddServiceVersion(ctx context.Context, ver *ServiceVersion) (*ServiceVersion, error) {
	ver.ID = utils.GenerateID("ver")
	ver.CreatedAt = time.Now()
	ver.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(ver).Error; err != nil {
		return nil, err
	}
	return ver, nil
}

func (s *Service) ListServiceVersions(ctx context.Context, serviceID string) ([]ServiceVersion, error) {
	var versions []ServiceVersion
	if err := s.db.WithContext(ctx).
		Where("service_id = ?", serviceID).
		Order("created_at DESC").
		Find(&versions).Error; err != nil {
		return nil, err
	}
	return versions, nil
}

func (s *Service) UpdateHealth(ctx context.Context, health *ServiceHealth) error {
	health.ID = utils.GenerateID("health")
	health.UpdatedAt = time.Now()

	var existing ServiceHealth
	if err := s.db.WithContext(ctx).Where("service_id = ?", health.ServiceID).First(&existing).Error; err == nil {
		health.ID = existing.ID
		return s.db.WithContext(ctx).Save(health).Error
	}

	return s.db.WithContext(ctx).Create(health).Error
}

func (s *Service) GetHealth(ctx context.Context, serviceID string) (*ServiceHealth, error) {
	var health ServiceHealth
	if err := s.db.WithContext(ctx).Where("service_id = ?", serviceID).First(&health).Error; err != nil {
		return nil, err
	}
	return &health, nil
}

func (s *Service) Search(ctx context.Context, query string) ([]interface{}, error) {
	var results []interface{}
	pattern := "%" + strings.ToLower(query) + "%"

	var services []Service
	s.db.WithContext(ctx).
		Where("LOWER(name) LIKE ? OR LOWER(description) LIKE ?", pattern, pattern).
		Limit(20).
		Find(&services)

	for _, svc := range services {
		results = append(results, map[string]interface{}{
			"type": "service",
			"id":   svc.ID,
			"name": svc.Name,
		})
	}

	var libraries []Library
	s.db.WithContext(ctx).
		Where("LOWER(name) LIKE ? OR LOWER(description) LIKE ?", pattern, pattern).
		Limit(20).
		Find(&libraries)

	for _, lib := range libraries {
		results = append(results, map[string]interface{}{
			"type":    "library",
			"id":      lib.ID,
			"name":    lib.Name,
			"version": lib.Version,
		})
	}

	return results, nil
}
