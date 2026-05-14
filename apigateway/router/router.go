package router

import (
	"apigateway/models"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

type RouterManager struct {
	routes     map[string]*models.Route
	routesByID map[string]*models.Route
	routesMu   sync.RWMutex
}

func NewRouterManager() *RouterManager {
	return &RouterManager{
		routes:     make(map[string]*models.Route),
		routesByID: make(map[string]*models.Route),
	}
}

func (rm *RouterManager) CreateRoute(req *models.CreateRouteRequest) (*models.Route, error) {
	rm.routesMu.Lock()
	defer rm.routesMu.Unlock()

	if req.RoutePattern == "" {
		return nil, fmt.Errorf("route pattern is required")
	}
	if req.TargetService == "" {
		return nil, fmt.Errorf("target service is required")
	}

	for pattern := range rm.routes {
		if pattern == req.RoutePattern {
			return nil, fmt.Errorf("route pattern already exists")
		}
	}

	now := time.Now()
	routeID := "route_" + strings.ReplaceAll(strings.Trim(req.RoutePattern, "/"), "/", "_")

	route := &models.Route{
		RouteID:         routeID,
		RoutePattern:    req.RoutePattern,
		TargetService:   req.TargetService,
		TargetInstances: req.TargetInstances,
		ForwardConfig: models.ForwardConfig{
			Timeout:    3000,
			RetryCount: 2,
		},
		AuthRequired: false,
		RateLimit: models.RateLimitConfig{
			QPS:         100,
			Burst:       20,
			Algorithm:   models.AlgorithmTokenBucket,
			WindowSize:  1,
			Distributed: false,
		},
		Enabled:   true,
		Group:     req.Group,
		CreatedAt: now,
		UpdatedAt: now,
	}

	if req.ForwardConfig != nil {
		route.ForwardConfig = *req.ForwardConfig
	}
	if req.AuthRequired != nil {
		route.AuthRequired = *req.AuthRequired
	}
	if req.RateLimit != nil {
		route.RateLimit = *req.RateLimit
	}

	rm.routes[req.RoutePattern] = route
	rm.routesByID[routeID] = route

	return route, nil
}

func (rm *RouterManager) GetRoute(routeID string) (*models.Route, error) {
	rm.routesMu.RLock()
	defer rm.routesMu.RUnlock()

	route, exists := rm.routesByID[routeID]
	if !exists {
		return nil, fmt.Errorf("route not found")
	}
	return route, nil
}

func (rm *RouterManager) ListRoutes() []*models.Route {
	rm.routesMu.RLock()
	defer rm.routesMu.RUnlock()

	routes := make([]*models.Route, 0, len(rm.routesByID))
	for _, route := range rm.routesByID {
		routes = append(routes, route)
	}
	return routes
}

func (rm *RouterManager) DeleteRoute(routeID string) error {
	rm.routesMu.Lock()
	defer rm.routesMu.Unlock()

	route, exists := rm.routesByID[routeID]
	if !exists {
		return fmt.Errorf("route not found")
	}

	delete(rm.routes, route.RoutePattern)
	delete(rm.routesByID, routeID)
	return nil
}

func (rm *RouterManager) UpdateRoute(routeID string, req *models.CreateRouteRequest) (*models.Route, error) {
	rm.routesMu.Lock()
	defer rm.routesMu.Unlock()

	route, exists := rm.routesByID[routeID]
	if !exists {
		return nil, fmt.Errorf("route not found")
	}

	if req.RoutePattern != "" && req.RoutePattern != route.RoutePattern {
		if _, exists := rm.routes[req.RoutePattern]; exists {
			return nil, fmt.Errorf("route pattern already exists")
		}
		delete(rm.routes, route.RoutePattern)
		route.RoutePattern = req.RoutePattern
	}

	if req.TargetService != "" {
		route.TargetService = req.TargetService
	}
	if req.TargetInstances != nil {
		route.TargetInstances = req.TargetInstances
	}
	if req.ForwardConfig != nil {
		route.ForwardConfig = *req.ForwardConfig
	}
	if req.AuthRequired != nil {
		route.AuthRequired = *req.AuthRequired
	}
	if req.RateLimit != nil {
		route.RateLimit = *req.RateLimit
	}
	if req.Group != "" {
		route.Group = req.Group
	}

	route.UpdatedAt = time.Now()
	rm.routes[route.RoutePattern] = route

	return route, nil
}

func (rm *RouterManager) MatchRoute(path string) (*models.Route, error) {
	rm.routesMu.RLock()
	defer rm.routesMu.RUnlock()

	var matchedRoute *models.Route
	var longestMatchLen int

	for pattern, route := range rm.routes {
		if !route.Enabled {
			continue
		}

		matchLen, matched := matchPattern(pattern, path)
		if matched && matchLen > longestMatchLen {
			matchedRoute = route
			longestMatchLen = matchLen
		}
	}

	if matchedRoute == nil {
		return nil, fmt.Errorf("route not found for path: %s", path)
	}

	return matchedRoute, nil
}

func (rm *RouterManager) ListRoutesByGroup(group string) []*models.Route {
	rm.routesMu.RLock()
	defer rm.routesMu.RUnlock()

	routes := make([]*models.Route, 0)
	for _, route := range rm.routesByID {
		if route.Group == group {
			routes = append(routes, route)
		}
	}
	return routes
}

func matchPattern(pattern, path string) (int, bool) {
	patternParts := strings.Split(strings.Trim(pattern, "/"), "/")
	pathParts := strings.Split(strings.Trim(path, "/"), "/")

	if strings.HasSuffix(pattern, "*") {
		basePattern := strings.TrimSuffix(pattern, "*")
		if strings.HasPrefix(path, basePattern) {
			return len(basePattern), true
		}
	}

	if len(patternParts) != len(pathParts) {
		return 0, false
	}

	matchCount := 0
	for i, part := range patternParts {
		if part == ":param" || strings.HasPrefix(part, ":") {
			matchCount += 1
		} else if part == pathParts[i] {
			matchCount += len(part)
		} else {
			return 0, false
		}
	}

	return matchCount, true
}

func GenerateID() string {
	return uuid.New().String()
}

var PatternMatch = regexp.MustCompile
