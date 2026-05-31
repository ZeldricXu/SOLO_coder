package catalog

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"techplatform/internal/dao"
	"techplatform/pkg/common"
	"techplatform/pkg/common/logger"
	"techplatform/pkg/common/utils"
	"techplatform/pkg/models"

	"gorm.io/gorm"
)

type ServiceType string

const (
	TypeService ServiceType = "service"
	TypeLibrary ServiceType = "library"
	TypeTool    ServiceType = "tool"
	TypeDatabase ServiceType = "database"
)

type ServiceStatus string

const (
	StatusActive    ServiceStatus = "active"
	StatusDeprecated ServiceStatus = "deprecated"
	StatusBeta      ServiceStatus = "beta"
	StatusArchived  ServiceStatus = "archived"
)

type HealthStatus string

const (
	Healthy   HealthStatus = "healthy"
	Degraded  HealthStatus = "degraded"
	Unhealthy HealthStatus = "unhealthy"
	Unknown   HealthStatus = "unknown"
)

type Service struct {
	models.BaseModel
	Name          string            `json:"name" gorm:"uniqueIndex;size:255"`
	Namespace     string            `json:"namespace" gorm:"index;size:100"`
	Type          ServiceType       `json:"type" gorm:"index;size:50"`
	Status        ServiceStatus     `json:"status" gorm:"index;size:50"`
	Health        HealthStatus      `json:"health" gorm:"size:50"`
	Description   string            `json:"description"`
	Version       string            `json:"version"`
	Language      string            `json:"language" gorm:"size:50"`
	Tags          string            `json:"tags"`
	Owner         string            `json:"owner" gorm:"index;size:100"`
	Team          string            `json:"team" gorm:"index;size:100"`
	RepositoryURL string            `json:"repository_url"`
	HomepageURL   string            `json:"homepage_url"`
	DocumentationURL string         `json:"documentation_url"`
	SwaggerURL    string            `json:"swagger_url"`
	CIURL         string            `json:"ci_url"`
	Metadata      string            `json:"metadata"`
	ContactInfo   string            `json:"contact_info"`
	SLALevel      string            `json:"sla_level" gorm:"size:50"`
	DataSensitivity string          `json:"data_sensitivity" gorm:"size:50"`
}

type Dependency struct {
	models.BaseModel
	ServiceID       string `json:"service_id" gorm:"index;size:36"`
	DependencyID    string `json:"dependency_id" gorm:"index;size:36"`
	DependencyName  string `json:"dependency_name"`
	VersionRange    string `json:"version_range"`
	CurrentVersion  string `json:"current_version"`
	Type            string `json:"type" gorm:"size:50"`
	Optional        bool   `json:"optional"`
	Critical        bool   `json:"critical"`
	Description     string `json:"description"`
}

type ServiceMetadata struct {
	ID          string            `json:"id"`
	Name        string            `json:"name"`
	Namespace   string            `json:"namespace"`
	Type        ServiceType       `json:"type"`
	Status      ServiceStatus     `json:"status"`
	Health      HealthStatus      `json:"health"`
	Description string            `json:"description"`
	Version     string            `json:"version"`
	Language    string            `json:"language"`
	Tags        []string          `json:"tags"`
	Owner       string            `json:"owner"`
	Team        string            `json:"team"`
	CreatedAt   time.Time         `json:"created_at"`
	UpdatedAt   time.Time         `json:"updated_at"`
}

type DependencyGraph struct {
	Nodes []GraphNode `json:"nodes"`
	Edges []GraphEdge `json:"edges"`
}

type GraphNode struct {
	ID    string      `json:"id"`
	Name  string      `json:"name"`
	Type  ServiceType `json:"type"`
	Group string      `json:"group"`
}

type GraphEdge struct {
	Source string `json:"source"`
	Target string `json:"target"`
	Type   string `json:"type"`
}

type ServiceQuery struct {
	Query     string        `json:"query"`
	Type      ServiceType   `json:"type"`
	Status    ServiceStatus `json:"status"`
	Health    HealthStatus  `json:"health"`
	Namespace string        `json:"namespace"`
	Owner     string        `json:"owner"`
	Team      string        `json:"team"`
	Tags      []string      `json:"tags"`
	Language  string        `json:"language"`
	Page      int           `json:"page"`
	PageSize  int           `json:"page_size"`
}

type CatalogManager struct {
	mu             sync.RWMutex
	db             *dao.DAO
	serviceIndex   map[string]map[string][]string
	tagIndex       map[string][]string
	ownerIndex     map[string][]string
}

func NewCatalogManager(db *dao.DAO) *CatalogManager {
	cm := &CatalogManager{
		db:             db,
		serviceIndex:   make(map[string]map[string][]string),
		tagIndex:       make(map[string][]string),
		ownerIndex:     make(map[string][]string),
	}
	db.AutoMigrate(&Service{}, &Dependency{})
	cm.rebuildIndexes()
	logger.Info("Catalog manager initialized")
	return cm
}

func (cm *CatalogManager) RegisterService(service *Service) (*Service, error) {
	if service.Name == "" {
		return nil, fmt.Errorf("%w: service name required", common.ErrInvalidInput)
	}
	if service.Owner == "" {
		return nil, fmt.Errorf("%w: service owner required", common.ErrInvalidInput)
	}

	cm.mu.Lock()
	defer cm.mu.Unlock()

	var existing Service
	result := cm.db.DB().Where("name = ? AND namespace = ?", service.Name, service.Namespace).First(&existing)
	if result.Error == nil {
		return nil, fmt.Errorf("%w: service already exists", common.ErrAlreadyExists)
	}
	if !errors.Is(result.Error, gorm.ErrRecordNotFound) {
		return nil, result.Error
	}

	service.ID = utils.GenerateUUID()
	service.Status = StatusActive
	service.Health = Unknown

	if err := cm.db.DB().Create(service).Error; err != nil {
		return nil, err
	}

	cm.indexService(service)
	cm.invalidateCache(service)
	logger.Info("Service registered: %s/%s", service.Namespace, service.Name)
	return service, nil
}

func (cm *CatalogManager) GetService(idOrName string) (*Service, error) {
	var service Service
	cacheKey := fmt.Sprintf("service:%s", idOrName)

	err := cm.db.GetWithCache(context.Background(), cacheKey, &service, func() (interface{}, error) {
		query := cm.db.DB().Model(&Service{})
		if utils.ContainsString([]string{idOrName}, idOrName) && len(idOrName) == 36 {
			query = query.Where("id = ?", idOrName)
		} else {
			parts := strings.SplitN(idOrName, "/", 2)
			if len(parts) == 2 {
				query = query.Where("name = ? AND namespace = ?", parts[1], parts[0])
			} else {
				query = query.Where("name = ?", idOrName)
			}
		}
		if err := query.First(&service).Error; err != nil {
			if errors.Is(err, gorm.ErrRecordNotFound) {
				return nil, common.ErrNotFound
			}
			return nil, err
		}
		return service, nil
	})

	if err != nil {
		return nil, err
	}

	return &service, nil
}

func (cm *CatalogManager) UpdateService(id string, updates map[string]interface{}) (*Service, error) {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	var service Service
	if err := cm.db.DB().First(&service, "id = ?", id).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, common.ErrNotFound
		}
		return nil, err
	}

	if err := cm.db.DB().Model(&service).Updates(updates).Error; err != nil {
		return nil, err
	}

	cm.db.DB().First(&service, "id = ?", id)
	cm.reindexService(&service)
	cm.invalidateCache(&service)

	logger.Info("Service updated: %s", service.Name)
	return &service, nil
}

func (cm *CatalogManager) DeleteService(id string) error {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	var service Service
	if err := cm.db.DB().First(&service, "id = ?", id).Error; err != nil {
		return err
	}

	err := cm.db.DB().Transaction(func(tx *gorm.DB) error {
		if err := tx.Where("service_id = ? OR dependency_id = ?", id, id).Delete(&Dependency{}).Error; err != nil {
			return err
		}
		if err := tx.Delete(&service).Error; err != nil {
			return err
		}
		return nil
	})

	if err != nil {
		return err
	}

	cm.removeFromIndex(&service)
	cm.invalidateCache(&service)
	logger.Info("Service deleted: %s", service.Name)
	return nil
}

func (cm *CatalogManager) SearchServices(query ServiceQuery) (*models.PageResult, error) {
	query.Page, query.PageSize = normalizePagination(query.Page, query.PageSize)

	cm.mu.RLock()
	defer cm.mu.RUnlock()

	dbQuery := cm.db.DB().Model(&Service{})

	if query.Query != "" {
		keyword := "%" + strings.ToLower(query.Query) + "%"
		dbQuery = dbQuery.Where(
			"LOWER(name) LIKE ? OR LOWER(description) LIKE ? OR LOWER(tags) LIKE ?",
			keyword, keyword, keyword,
		)
	}
	if query.Type != "" {
		dbQuery = dbQuery.Where("type = ?", query.Type)
	}
	if query.Status != "" {
		dbQuery = dbQuery.Where("status = ?", query.Status)
	}
	if query.Health != "" {
		dbQuery = dbQuery.Where("health = ?", query.Health)
	}
	if query.Namespace != "" {
		dbQuery = dbQuery.Where("namespace = ?", query.Namespace)
	}
	if query.Owner != "" {
		dbQuery = dbQuery.Where("owner = ?", query.Owner)
	}
	if query.Team != "" {
		dbQuery = dbQuery.Where("team = ?", query.Team)
	}
	if query.Language != "" {
		dbQuery = dbQuery.Where("language = ?", query.Language)
	}
	if len(query.Tags) > 0 {
		for _, tag := range query.Tags {
			dbQuery = dbQuery.Where("tags LIKE ?", "%"+tag+"%")
		}
	}

	var total int64
	dbQuery.Count(&total)

	var services []Service
	offset := (query.Page - 1) * query.PageSize
	if err := dbQuery.Offset(offset).Limit(query.PageSize).Order("created_at DESC").Find(&services).Error; err != nil {
		return nil, err
	}

	return &models.PageResult{
		Total:    total,
		Page:     query.Page,
		PageSize: query.PageSize,
		Items:    services,
	}, nil
}

func (cm *CatalogManager) AddDependency(serviceID, dependencyID, versionRange string, optional, critical bool) (*Dependency, error) {
	if serviceID == dependencyID {
		return nil, fmt.Errorf("%w: cannot depend on self", common.ErrInvalidInput)
	}

	_, err := cm.GetService(serviceID)
	if err != nil {
		return nil, fmt.Errorf("source service: %w", err)
	}
	dep, err := cm.GetService(dependencyID)
	if err != nil {
		return nil, fmt.Errorf("dependency service: %w", err)
	}

	var existing Dependency
	result := cm.db.DB().Where("service_id = ? AND dependency_id = ?", serviceID, dependencyID).First(&existing)
	if result.Error == nil {
		return nil, fmt.Errorf("%w: dependency already exists", common.ErrAlreadyExists)
	}

	dependency := &Dependency{
		BaseModel: models.BaseModel{
			ID: utils.GenerateUUID(),
		},
		ServiceID:      serviceID,
		DependencyID:   dependencyID,
		DependencyName: dep.Name,
		VersionRange:   versionRange,
		CurrentVersion: dep.Version,
		Type:           "runtime",
		Optional:       optional,
		Critical:       critical,
	}

	if err := cm.db.DB().Create(dependency).Error; err != nil {
		return nil, err
	}

	cm.db.InvalidateCache(context.Background(), fmt.Sprintf("deps:%s", serviceID))
	logger.Info("Dependency added: %s -> %s", serviceID, dependencyID)
	return dependency, nil
}

func (cm *CatalogManager) GetDependencies(serviceID string) ([]Dependency, error) {
	var deps []Dependency
	cacheKey := fmt.Sprintf("deps:%s", serviceID)

	err := cm.db.GetWithCache(context.Background(), cacheKey, &deps, func() (interface{}, error) {
		if err := cm.db.DB().Where("service_id = ?", serviceID).Find(&deps).Error; err != nil {
			return nil, err
		}
		return deps, nil
	})

	if err != nil {
		return nil, err
	}
	return deps, nil
}

func (cm *CatalogManager) GetDependents(serviceID string) ([]Dependency, error) {
	var deps []Dependency
	if err := cm.db.DB().Where("dependency_id = ?", serviceID).Find(&deps).Error; err != nil {
		return nil, err
	}
	return deps, nil
}

func (cm *CatalogManager) GetDependencyGraph(serviceID string, depth int) (*DependencyGraph, error) {
	if depth <= 0 {
		depth = 3
	}
	if depth > 10 {
		depth = 10
	}

	visited := make(map[string]bool)
	nodes := make([]GraphNode, 0)
	edges := make([]GraphEdge, 0)

	var traverse func(id string, currentDepth int)
	traverse = func(id string, currentDepth int) {
		if currentDepth > depth || visited[id] {
			return
		}
		visited[id] = true

		svc, err := cm.GetService(id)
		if err != nil {
			return
		}

		nodes = append(nodes, GraphNode{
			ID:    svc.ID,
			Name:  svc.Name,
			Type:  svc.Type,
			Group: svc.Namespace,
		})

		deps, _ := cm.GetDependencies(id)
		for _, dep := range deps {
			edges = append(edges, GraphEdge{
				Source: id,
				Target: dep.DependencyID,
				Type:   dep.Type,
			})
			traverse(dep.DependencyID, currentDepth+1)
		}

		dependents, _ := cm.GetDependents(id)
		for _, dep := range dependents {
			edges = append(edges, GraphEdge{
				Source: dep.ServiceID,
				Target: id,
				Type:   "dependent",
			})
			traverse(dep.ServiceID, currentDepth+1)
		}
	}

	traverse(serviceID, 0)

	nodeMap := make(map[string]bool)
	uniqueNodes := make([]GraphNode, 0)
	for _, node := range nodes {
		if !nodeMap[node.ID] {
			nodeMap[node.ID] = true
			uniqueNodes = append(uniqueNodes, node)
		}
	}

	return &DependencyGraph{
		Nodes: uniqueNodes,
		Edges: edges,
	}, nil
}

func (cm *CatalogManager) GetAllDependencies() ([]Dependency, error) {
	var deps []Dependency
	if err := cm.db.DB().Find(&deps).Error; err != nil {
		return nil, err
	}
	return deps, nil
}

func (cm *CatalogManager) RemoveDependency(serviceID, dependencyID string) error {
	result := cm.db.DB().Where("service_id = ? AND dependency_id = ?", serviceID, dependencyID).Delete(&Dependency{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return common.ErrNotFound
	}
	cm.db.InvalidateCache(context.Background(), fmt.Sprintf("deps:%s", serviceID))
	logger.Info("Dependency removed: %s -> %s", serviceID, dependencyID)
	return nil
}

func (cm *CatalogManager) UpdateHealth(serviceID string, health HealthStatus) error {
	return cm.UpdateServiceField(serviceID, "health", health)
}

func (cm *CatalogManager) UpdateServiceField(serviceID, field string, value interface{}) error {
	result := cm.db.DB().Model(&Service{}).Where("id = ?", serviceID).Update(field, value)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return common.ErrNotFound
	}

	svc, _ := cm.GetService(serviceID)
	if svc != nil {
		cm.invalidateCache(svc)
	}
	return nil
}

func (cm *CatalogManager) ListAll(page, pageSize int, namespace string) (*models.PageResult, error) {
	page, pageSize = normalizePagination(page, pageSize)

	var services []Service
	var total int64

	query := cm.db.DB().Model(&Service{})
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&services).Error; err != nil {
		return nil, err
	}

	return &models.PageResult{
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Items:    services,
	}, nil
}

func (cm *CatalogManager) GetStats() map[string]interface{} {
	var totalServices int64
	var activeServices int64
	var deprecatedServices int64
	var totalDeps int64

	cm.db.DB().Model(&Service{}).Count(&totalServices)
	cm.db.DB().Model(&Service{}).Where("status = ?", StatusActive).Count(&activeServices)
	cm.db.DB().Model(&Service{}).Where("status = ?", StatusDeprecated).Count(&deprecatedServices)
	cm.db.DB().Model(&Dependency{}).Count(&totalDeps)

	typeByType := make(map[string]int64)
	rows, _ := cm.db.DB().Model(&Service{}).Select("type, COUNT(*) as count").Group("type").Rows()
	for rows.Next() {
		var t string
		var count int64
		rows.Scan(&t, &count)
		typeByType[t] = count
	}
	rows.Close()

	byNamespace := make(map[string]int64)
	rows, _ = cm.db.DB().Model(&Service{}).Select("namespace, COUNT(*) as count").Group("namespace").Rows()
	for rows.Next() {
		var ns string
		var count int64
		rows.Scan(&ns, &count)
		byNamespace[ns] = count
	}
	rows.Close()

	byTeam := make(map[string]int64)
	rows, _ = cm.db.DB().Model(&Service{}).Select("team, COUNT(*) as count").Group("team").Rows()
	for rows.Next() {
		var team string
		var count int64
		rows.Scan(&team, &count)
		byTeam[team] = count
	}
	rows.Close()

	byHealth := make(map[string]int64)
	rows, _ = cm.db.DB().Model(&Service{}).Select("health, COUNT(*) as count").Group("health").Rows()
	for rows.Next() {
		var health string
		var count int64
		rows.Scan(&health, &count)
		byHealth[health] = count
	}
	rows.Close()

	return map[string]interface{}{
		"total_services":     totalServices,
		"active_services":    activeServices,
		"deprecated_services": deprecatedServices,
		"total_dependencies": totalDeps,
		"by_type":            typeByType,
		"by_namespace":       byNamespace,
		"by_team":            byTeam,
		"by_health":          byHealth,
		"indexed_services":   len(cm.serviceIndex),
	}
}

func (cm *CatalogManager) BatchRegister(services []*Service) ([]*Service, error) {
	registered := make([]*Service, 0, len(services))
	for _, svc := range services {
		reg, err := cm.RegisterService(svc)
		if err != nil {
			logger.Warn("Failed to register service %s: %v", svc.Name, err)
			continue
		}
		registered = append(registered, reg)
	}
	return registered, nil
}

func (cm *CatalogManager) GetTopDependencies(limit int) []map[string]interface{} {
	type Result struct {
		DependencyID   string
		DependencyName string
		Count          int
	}
	var results []Result

	cm.db.DB().Model(&Dependency{}).
		Select("dependency_id, dependency_name, COUNT(*) as count").
		Group("dependency_id, dependency_name").
		Order("count DESC").
		Limit(limit).
		Scan(&results)

	topDeps := make([]map[string]interface{}, len(results))
	for i, r := range results {
		topDeps[i] = map[string]interface{}{
			"id":    r.DependencyID,
			"name":  r.DependencyName,
			"count": r.Count,
		}
	}
	return topDeps
}

func (cm *CatalogManager) GetServicesByOwner(owner string) ([]Service, error) {
	var services []Service
	if err := cm.db.DB().Where("owner = ?", owner).Find(&services).Error; err != nil {
		return nil, err
	}
	return services, nil
}

func (cm *CatalogManager) GetServicesByTeam(team string) ([]Service, error) {
	var services []Service
	if err := cm.db.DB().Where("team = ?", team).Find(&services).Error; err != nil {
		return nil, err
	}
	return services, nil
}

func (cm *CatalogManager) indexService(service *Service) {
	words := tokenizeService(service)
	for _, word := range words {
		if _, exists := cm.serviceIndex[word]; !exists {
			cm.serviceIndex[word] = make(map[string][]string)
		}
		if !utils.ContainsString(cm.serviceIndex[word][service.ID], service.ID) {
			cm.serviceIndex[word][service.ID] = append(cm.serviceIndex[word][service.ID], service.ID)
		}
	}

	tags := strings.Split(service.Tags, ",")
	for _, tag := range tags {
		tag = strings.TrimSpace(tag)
		if tag != "" {
			cm.tagIndex[tag] = append(cm.tagIndex[tag], service.ID)
		}
	}

	if service.Owner != "" {
		cm.ownerIndex[service.Owner] = append(cm.ownerIndex[service.Owner], service.ID)
	}
}

func (cm *CatalogManager) removeFromIndex(service *Service) {
	words := tokenizeService(service)
	for _, word := range words {
		if _, exists := cm.serviceIndex[word]; exists {
			delete(cm.serviceIndex[word], service.ID)
			if len(cm.serviceIndex[word]) == 0 {
				delete(cm.serviceIndex, word)
			}
		}
	}

	tags := strings.Split(service.Tags, ",")
	for _, tag := range tags {
		tag = strings.TrimSpace(tag)
		if tag != "" {
			ids := cm.tagIndex[tag]
			for i, id := range ids {
				if id == service.ID {
					cm.tagIndex[tag] = append(ids[:i], ids[i+1:]...)
					break
				}
			}
		}
	}

	if service.Owner != "" {
		ids := cm.ownerIndex[service.Owner]
		for i, id := range ids {
			if id == service.ID {
				cm.ownerIndex[service.Owner] = append(ids[:i], ids[i+1:]...)
				break
			}
		}
	}
}

func (cm *CatalogManager) reindexService(service *Service) {
	cm.removeFromIndex(service)
	cm.indexService(service)
}

func (cm *CatalogManager) rebuildIndexes() {
	var services []Service
	cm.db.DB().Find(&services)
	for i := range services {
		cm.indexService(&services[i])
	}
	logger.Info("Catalog indexes rebuilt with %d services", len(services))
}

func (cm *CatalogManager) invalidateCache(service *Service) {
	keys := []string{
		fmt.Sprintf("service:%s", service.ID),
		fmt.Sprintf("service:%s", service.Name),
		fmt.Sprintf("service:%s/%s", service.Namespace, service.Name),
		fmt.Sprintf("deps:%s", service.ID),
	}
	cm.db.InvalidateCache(context.Background(), keys...)
}

func tokenizeService(service *Service) []string {
	text := strings.ToLower(service.Name + " " + service.Description + " " + service.Tags + " " +
		service.Namespace + " " + service.Owner + " " + service.Team + " " + service.Language)
	return strings.Fields(text)
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

func (s *Service) GetTagsArray() []string {
	if s.Tags == "" {
		return []string{}
	}
	tags := strings.Split(s.Tags, ",")
	for i := range tags {
		tags[i] = strings.TrimSpace(tags[i])
	}
	return tags
}

func (s *Service) SetTagsArray(tags []string) {
	s.Tags = strings.Join(tags, ",")
}

func (s *Service) GetMetadataMap() map[string]interface{} {
	if s.Metadata == "" {
		return make(map[string]interface{})
	}
	var meta map[string]interface{}
	json.Unmarshal([]byte(s.Metadata), &meta)
	return meta
}

func (s *Service) SetMetadataMap(meta map[string]interface{}) {
	b, _ := json.Marshal(meta)
	s.Metadata = string(b)
}

func (cm *CatalogManager) SortServices(services []Service, sortBy string, ascending bool) []Service {
	sort.Slice(services, func(i, j int) bool {
		switch sortBy {
		case "name":
			if ascending {
				return services[i].Name < services[j].Name
			}
			return services[i].Name > services[j].Name
		case "created":
			if ascending {
				return services[i].CreatedAt.Before(services[j].CreatedAt)
			}
			return services[i].CreatedAt.After(services[j].CreatedAt)
		case "updated":
			if ascending {
				return services[i].UpdatedAt.Before(services[j].UpdatedAt)
			}
			return services[i].UpdatedAt.After(services[j].UpdatedAt)
		default:
			return services[i].Name < services[j].Name
		}
	})
	return services
}
