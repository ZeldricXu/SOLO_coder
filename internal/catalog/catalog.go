package catalog

import (
	"encoding/json"
	"errors"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/solocoder/session138/pkg/cache"
	"github.com/solocoder/session138/pkg/database"
	"github.com/solocoder/session138/pkg/metrics"
	"github.com/solocoder/session138/pkg/models"
	"github.com/solocoder/session138/pkg/utils"
	"go.uber.org/zap"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	ModuleName          = "catalog"
	CachePrefixL1       = "catalog:l1:"
	CachePrefixL2       = "catalog:l2:"
	CacheTTL            = 5 * time.Minute
	CacheGraceTTL       = 30 * time.Second
	MaxL1CacheSize      = 1000
	SearchCacheKey      = "search"
	DependencyGraphKey  = "dependency_graph"
	CacheWarmupBatchSize = 100
)

type RegisterServiceRequest struct {
	Name         string            `json:"name" binding:"required"`
	Description  string            `json:"description"`
	Type         string            `json:"type" binding:"required"`
	Version      string            `json:"version" binding:"required"`
	Owner        string            `json:"owner" binding:"required"`
	Labels       map[string]string `json:"labels"`
	Endpoints    []string          `json:"endpoints"`
	Dependencies []string          `json:"dependencies"`
}

type SearchServiceRequest struct {
	Query    string            `json:"query"`
	Type     string            `json:"type"`
	Labels   map[string]string `json:"labels"`
	Page     int               `json:"page"`
	PageSize int               `json:"page_size"`
}

type ServiceWithDependencies struct {
	models.Service
	Dependents   []string `json:"dependents"`
	Dependencies []string `json:"dependencies"`
}

type CacheEntry struct {
	Data       interface{}
	ExpiresAt  time.Time
	GraceUntil time.Time
}

type MultiLevelCache struct {
	l1Cache      map[string]*CacheEntry
	l1Mutex      sync.RWMutex
	l1MaxSize    int
	warmupDone   bool
	warmupMutex  sync.Mutex
	invalidCh    chan string
	stopCh       chan struct{}
}

var cacheManager = &MultiLevelCache{
	l1Cache:    make(map[string]*CacheEntry),
	l1MaxSize:  MaxL1CacheSize,
	invalidCh:  make(chan string, 100),
	stopCh:     make(chan struct{}),
}

func init() {
	go cacheManager.startInvalidationWorker()
}

func (mc *MultiLevelCache) startInvalidationWorker() {
	for {
		select {
		case key := <-mc.invalidCh:
			mc.l1Mutex.Lock()
			delete(mc.l1Cache, key)
			mc.l1Mutex.Unlock()

			if cache.Client != nil {
				cache.Delete(CachePrefixL2 + key)
			}
		case <-mc.stopCh:
			return
		}
	}
}

func (mc *MultiLevelCache) getL1(key string) (*CacheEntry, bool) {
	mc.l1Mutex.RLock()
	defer mc.l1Mutex.RUnlock()

	entry, exists := mc.l1Cache[key]
	if !exists {
		return nil, false
	}

	if time.Now().After(entry.ExpiresAt) {
		if time.Now().Before(entry.GraceUntil) {
			return entry, true
		}
		return nil, false
	}

	return entry, true
}

func (mc *MultiLevelCache) setL1(key string, data interface{}) {
	mc.l1Mutex.Lock()
	defer mc.l1Mutex.Unlock()

	if len(mc.l1Cache) >= mc.l1MaxSize {
		var oldestKey string
		var oldestTime time.Time
		for k, v := range mc.l1Cache {
			if oldestTime.IsZero() || v.ExpiresAt.Before(oldestTime) {
				oldestKey = k
				oldestTime = v.ExpiresAt
			}
		}
		delete(mc.l1Cache, oldestKey)
	}

	mc.l1Cache[key] = &CacheEntry{
		Data:       data,
		ExpiresAt:  time.Now().Add(CacheTTL),
		GraceUntil: time.Now().Add(CacheTTL + CacheGraceTTL),
	}

	metrics.SetCacheSize(ModuleName, "l1", len(mc.l1Cache))
}

func (mc *MultiLevelCache) invalidate(key string) {
	select {
	case mc.invalidCh <- key:
	default:
	}
}

func (mc *MultiLevelCache) invalidatePattern(pattern string) {
	mc.l1Mutex.Lock()
	defer mc.l1Mutex.Unlock()

	for k := range mc.l1Cache {
		if strings.Contains(k, pattern) {
			delete(mc.l1Cache, k)
		}
	}

	metrics.SetCacheSize(ModuleName, "l1", len(mc.l1Cache))
}

func (mc *MultiLevelCache) Get(key string) (interface{}, bool) {
	if entry, ok := mc.getL1(key); ok {
		metrics.RecordCacheHit(ModuleName, "l1", key)
		return entry.Data, true
	}

	if cache.Client != nil {
		data, err := cache.Get(CachePrefixL2 + key)
		if err == nil && data != "" {
			var result interface{}
			if err := json.Unmarshal([]byte(data), &result); err == nil {
				mc.setL1(key, result)
				metrics.RecordCacheHit(ModuleName, "l2", key)
				return result, true
			}
		}
	}

	metrics.RecordCacheMiss(ModuleName, "l1", key)
	if cache.Client != nil {
		metrics.RecordCacheMiss(ModuleName, "l2", key)
	}

	return nil, false
}

func (mc *MultiLevelCache) Set(key string, data interface{}) {
	mc.setL1(key, data)

	if cache.Client != nil {
		jsonData, err := json.Marshal(data)
		if err == nil {
			cache.Set(CachePrefixL2+key, string(jsonData), CacheTTL)
		}
	}
}

func (mc *MultiLevelCache) Invalidate(key string) {
	mc.invalidate(key)
}

func (mc *MultiLevelCache) Warmup() error {
	mc.warmupMutex.Lock()
	defer mc.warmupMutex.Unlock()

	if mc.warmupDone {
		return nil
	}

	timer := metrics.NewTimer(ModuleName, "cache_warmup")
	defer timer.ObserveSuccess()

	var services []models.Service
	var offset int

	for {
		var batch []models.Service
		if err := database.DB.Offset(offset).Limit(CacheWarmupBatchSize).Find(&batch).Error; err != nil {
			return err
		}

		if len(batch) == 0 {
			break
		}

		for _, svc := range batch {
			mc.setL1("service:"+svc.ID, svc)
		}

		services = append(services, batch...)
		offset += CacheWarmupBatchSize

		if len(batch) < CacheWarmupBatchSize {
			break
		}
	}

	if err := warmupDependencyGraph(); err != nil {
		zap.L().Warn("Failed to warmup dependency graph cache", zap.Error(err))
	}

	mc.warmupDone = true
	zap.L().Info("Catalog cache warmup completed", zap.Int("services_count", len(services)))
	return nil
}

func warmupDependencyGraph() error {
	var services []models.Service
	if err := database.DB.Find(&services).Error; err != nil {
		return err
	}

	nodes := make([]gin.H, 0, len(services))
	edges := make([]gin.H, 0)

	serviceMap := make(map[string]bool)
	for _, s := range services {
		serviceMap[s.ID] = true
		nodes = append(nodes, gin.H{
			"id":    s.ID,
			"label": s.Name,
			"type":  s.Type,
		})
	}

	for _, s := range services {
		for _, depID := range s.Dependencies {
			if serviceMap[depID] {
				edges = append(edges, gin.H{
					"from": s.ID,
					"to":   depID,
				})
			}
		}
	}

	cacheManager.Set(DependencyGraphKey, gin.H{
		"nodes": nodes,
		"edges": edges,
	})

	return nil
}

func RegisterService(c *gin.Context) {
	timer := metrics.NewTimer(ModuleName, "register_service")
	var req RegisterServiceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "invalid_request")
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	service := models.Service{
		ID:           utils.GenerateID("svc"),
		Name:         req.Name,
		Description:  req.Description,
		Type:         req.Type,
		Version:      req.Version,
		Owner:        req.Owner,
		Labels:       req.Labels,
		Endpoints:    req.Endpoints,
		Dependencies: req.Dependencies,
		CreatedAt:    utils.Now(),
		UpdatedAt:    utils.Now(),
	}

	if err := database.DB.Create(&service).Error; err != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "db_create_failed")
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "注册服务失败", "error": err.Error()})
		return
	}

	cacheManager.Invalidate(SearchCacheKey)
	cacheManager.Invalidate(DependencyGraphKey)

	timer.ObserveSuccess()
	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": service})
}

func GetService(c *gin.Context) {
	timer := metrics.NewTimer(ModuleName, "get_service")
	id := c.Param("id")
	cacheKey := "service:" + id

	if cached, ok := cacheManager.Get(cacheKey); ok {
		timer.ObserveSuccess()
		c.JSON(http.StatusOK, gin.H{"code": 200, "data": cached})
		return
	}

	var service models.Service
	if err := database.DB.First(&service, "id = ?", id).Error; err != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "service_not_found")
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "服务不存在"})
		return
	}

	cacheManager.Set(cacheKey, service)
	timer.ObserveSuccess()
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": service})
}

func SearchServices(c *gin.Context) {
	timer := metrics.NewTimer(ModuleName, "search_services")
	var req SearchServiceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		req.Query = c.Query("q")
		req.Type = c.Query("type")
		req.Page = 1
		req.PageSize = 20
	}

	cacheKey := fmt.Sprintf("search:%s:%s:%d:%d", req.Query, req.Type, req.Page, req.PageSize)
	if cached, ok := cacheManager.Get(cacheKey); ok {
		timer.ObserveSuccess()
		c.JSON(http.StatusOK, gin.H{"code": 200, "data": cached})
		return
	}

	query := database.DB.Model(&models.Service{})

	if req.Query != "" {
		query = query.Where("name ILIKE ? OR description ILIKE ?", "%"+req.Query+"%", "%"+req.Query+"%")
	}

	if req.Type != "" {
		query = query.Where("type = ?", req.Type)
	}

	var services []models.Service
	var total int64

	query.Count(&total)

	offset := (req.Page - 1) * req.PageSize
	if err := query.Offset(offset).Limit(req.PageSize).Find(&services).Error; err != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "search_failed")
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "查询失败", "error": err.Error()})
		return
	}

	result := gin.H{
		"items": services,
		"total": total,
		"page":  req.Page,
		"size":  req.PageSize,
	}

	cacheManager.Set(cacheKey, result)
	timer.ObserveSuccess()
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": result})
}

func GetServiceDependencies(c *gin.Context) {
	timer := metrics.NewTimer(ModuleName, "get_dependencies")
	id := c.Param("id")
	cacheKey := "deps:" + id

	if cached, ok := cacheManager.Get(cacheKey); ok {
		timer.ObserveSuccess()
		c.JSON(http.StatusOK, gin.H{"code": 200, "data": cached})
		return
	}

	var service models.Service
	if err := database.DB.First(&service, "id = ?", id).Error; err != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "service_not_found")
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "服务不存在"})
		return
	}

	var dependencies []models.Service
	if len(service.Dependencies) > 0 {
		database.DB.Where("id IN ?", service.Dependencies).Find(&dependencies)
	}

	var dependents []models.Service
	database.DB.Where("dependencies::jsonb ?| array[?]", id).Find(&dependents)

	dependentIDs := make([]string, len(dependents))
	for i, d := range dependents {
		dependentIDs[i] = d.ID
	}

	result := ServiceWithDependencies{
		Service:      service,
		Dependencies: service.Dependencies,
		Dependents:   dependentIDs,
	}

	cacheManager.Set(cacheKey, result)
	timer.ObserveSuccess()
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": result})
}

func UpdateService(c *gin.Context) {
	timer := metrics.NewTimer(ModuleName, "update_service")
	id := c.Param("id")

	var service models.Service
	if err := database.DB.First(&service, "id = ?", id).Error; err != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "service_not_found")
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "服务不存在"})
		return
	}

	var req RegisterServiceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "invalid_request")
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	service.Name = req.Name
	service.Description = req.Description
	service.Type = req.Type
	service.Version = req.Version
	service.Owner = req.Owner
	service.Labels = req.Labels
	service.Endpoints = req.Endpoints
	service.Dependencies = req.Dependencies
	service.UpdatedAt = utils.Now()

	if err := database.DB.Save(&service).Error; err != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "db_update_failed")
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "更新失败", "error": err.Error()})
		return
	}

	cacheManager.Invalidate("service:" + id)
	cacheManager.Invalidate("deps:" + id)
	cacheManager.invalidatePattern("search:")
	cacheManager.Invalidate(DependencyGraphKey)

	timer.ObserveSuccess()
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": service})
}

func DeleteService(c *gin.Context) {
	timer := metrics.NewTimer(ModuleName, "delete_service")
	id := c.Param("id")

	result := database.DB.Delete(&models.Service{}, "id = ?", id)
	if result.Error != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "db_delete_failed")
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "删除失败", "error": result.Error.Error()})
		return
	}

	if result.RowsAffected == 0 {
		timer.ObserveError()
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "服务不存在"})
		return
	}

	cacheManager.Invalidate("service:" + id)
	cacheManager.Invalidate("deps:" + id)
	cacheManager.invalidatePattern("search:")
	cacheManager.Invalidate(DependencyGraphKey)

	timer.ObserveSuccess()
	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "删除成功"})
}

func GetDependencyGraph(c *gin.Context) {
	timer := metrics.NewTimer(ModuleName, "get_dependency_graph")

	if cached, ok := cacheManager.Get(DependencyGraphKey); ok {
		timer.ObserveSuccess()
		c.JSON(http.StatusOK, gin.H{"code": 200, "data": cached})
		return
	}

	var services []models.Service
	if err := database.DB.Find(&services).Error; err != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "graph_query_failed")
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "查询失败", "error": err.Error()})
		return
	}

	nodes := make([]gin.H, 0, len(services))
	edges := make([]gin.H, 0)

	serviceMap := make(map[string]bool)
	for _, s := range services {
		serviceMap[s.ID] = true
		nodes = append(nodes, gin.H{
			"id":    s.ID,
			"label": s.Name,
			"type":  s.Type,
		})
	}

	for _, s := range services {
		for _, depID := range s.Dependencies {
			if serviceMap[depID] {
				edges = append(edges, gin.H{
					"from": s.ID,
					"to":   depID,
				})
			}
		}
	}

	result := gin.H{
		"nodes": nodes,
		"edges": edges,
	}

	cacheManager.Set(DependencyGraphKey, result)
	timer.ObserveSuccess()
	c.JSON(http.StatusOK, gin.H{"code": 200, "data": result})
}

func GetCacheStats(c *gin.Context) {
	cacheManager.l1Mutex.RLock()
	l1Size := len(cacheManager.l1Cache)
	warmupDone := cacheManager.warmupDone
	cacheManager.l1Mutex.RUnlock()

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"l1_cache_size":     l1Size,
			"l1_max_size":       MaxL1CacheSize,
			"warmup_completed":  warmupDone,
			"cache_ttl_seconds": CacheTTL.Seconds(),
		},
	})
}

func WarmupCache(c *gin.Context) {
	if err := cacheManager.Warmup(); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "缓存预热失败", "error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "缓存预热完成"})
}

func InvalidateCache(c *gin.Context) {
	key := c.Query("key")
	if key != "" {
		cacheManager.Invalidate(key)
	} else {
		cacheManager.invalidatePattern("")
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "缓存已失效"})
}

func RegisterRoutes(r *gin.RouterGroup) {
	catalog := r.Group("/catalog")
	{
		catalog.POST("/services", RegisterService)
		catalog.GET("/services/:id", GetService)
		catalog.POST("/services/search", SearchServices)
		catalog.GET("/services/:id/dependencies", GetServiceDependencies)
		catalog.PUT("/services/:id", UpdateService)
		catalog.DELETE("/services/:id", DeleteService)
		catalog.GET("/dependency-graph", GetDependencyGraph)
		catalog.GET("/cache/stats", GetCacheStats)
		catalog.POST("/cache/warmup", WarmupCache)
		catalog.POST("/cache/invalidate", InvalidateCache)
	}
}
