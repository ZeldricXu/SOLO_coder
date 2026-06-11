package router

import (
	"fmt"
	"strings"
	"sync"

	"DF1-56/internal/models"
)

type RouterManager struct {
	trieRouter    *TrieRouter
	radixRouter   *MethodRadixRouter
	regexRouter   *RegexRouter
	routes        map[string]*models.Route
	mu            sync.RWMutex
}

func NewRouterManager() *RouterManager {
	return &RouterManager{
		trieRouter:  NewTrieRouter(),
		radixRouter: NewMethodRadixRouter(),
		regexRouter: NewRegexRouter(),
		routes:      make(map[string]*models.Route),
	}
}

func (m *RouterManager) AddRoute(route *models.Route) error {
	if route == nil {
		return fmt.Errorf("route cannot be nil")
	}
	if route.ID == "" {
		return fmt.Errorf("route ID cannot be empty")
	}
	if route.Path == "" {
		return fmt.Errorf("route path cannot be empty")
	}
	if !route.Enabled {
		return nil
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.routes[route.ID]; exists {
		return fmt.Errorf("route with ID %s already exists", route.ID)
	}

	switch route.MatchType {
	case models.RouteMatchTypePrefix:
		prefixPath := route.Path
		if !strings.HasSuffix(prefixPath, "/*") {
			prefixPath = strings.TrimRight(prefixPath, "/") + "/*"
		}
		if err := m.trieRouter.Insert(prefixPath, route); err != nil {
			return fmt.Errorf("failed to insert into trie router: %w", err)
		}
		method := route.Method
		if method == "" {
			method = "*"
		}
		if err := m.radixRouter.Insert(method, prefixPath, route); err != nil {
			return fmt.Errorf("failed to insert into radix router: %w", err)
		}
	case models.RouteMatchTypeExact:
		if err := m.trieRouter.Insert(route.Path, route); err != nil {
			return fmt.Errorf("failed to insert into trie router: %w", err)
		}
		method := route.Method
		if method == "" {
			method = "*"
		}
		if err := m.radixRouter.Insert(method, route.Path, route); err != nil {
			return fmt.Errorf("failed to insert into radix router: %w", err)
		}
	case models.RouteMatchTypeRegex:
		if route.RegexPattern == "" {
			return fmt.Errorf("regex pattern cannot be empty for regex match type")
		}
		if err := m.regexRouter.Insert(route.Path, route.RegexPattern, route); err != nil {
			return fmt.Errorf("failed to insert into regex router: %w", err)
		}
	default:
		return fmt.Errorf("unsupported match type: %s", route.MatchType)
	}

	m.routes[route.ID] = route
	return nil
}

func (m *RouterManager) UpdateRoute(route *models.Route) error {
	if route == nil {
		return fmt.Errorf("route cannot be nil")
	}
	if route.ID == "" {
		return fmt.Errorf("route ID cannot be empty")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.routes[route.ID]; !exists {
		return fmt.Errorf("route with ID %s not found", route.ID)
	}

	if err := m.rebuild(); err != nil {
		return fmt.Errorf("failed to rebuild router: %w", err)
	}

	return nil
}

func (m *RouterManager) RemoveRoute(routeID string) error {
	if routeID == "" {
		return fmt.Errorf("route ID cannot be empty")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.routes[routeID]; !exists {
		return fmt.Errorf("route with ID %s not found", routeID)
	}

	delete(m.routes, routeID)

	if err := m.rebuild(); err != nil {
		return fmt.Errorf("failed to rebuild router: %w", err)
	}

	return nil
}

func (m *RouterManager) GetRoute(routeID string) (*models.Route, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	route, exists := m.routes[routeID]
	return route, exists
}

func (m *RouterManager) ListRoutes() []*models.Route {
	m.mu.RLock()
	defer m.mu.RUnlock()

	routes := make([]*models.Route, 0, len(m.routes))
	for _, route := range m.routes {
		routes = append(routes, route)
	}
	return routes
}

func (m *RouterManager) MatchRoute(method, path string) (*models.Route, map[string]string) {
	if method == "" || path == "" {
		return nil, nil
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	if route, params := m.radixRouter.Match(method, path); route != nil {
		return route, params
	}

	if route, params := m.trieRouter.Match(path); route != nil {
		if route.Method == "" || strings.EqualFold(route.Method, method) || route.Method == "*" {
			return route, params
		}
	}

	if route, params := m.regexRouter.Match(path); route != nil {
		if route.Method == "" || strings.EqualFold(route.Method, method) || route.Method == "*" {
			return route, params
		}
	}

	return nil, nil
}

func (m *RouterManager) ReloadRoutes(routes map[string]*models.Route) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.routes = routes
	_ = m.rebuild()
}

func (m *RouterManager) rebuild() error {
	m.trieRouter = NewTrieRouter()
	m.radixRouter = NewMethodRadixRouter()
	m.regexRouter = NewRegexRouter()

	for _, route := range m.routes {
		if !route.Enabled {
			continue
		}

		switch route.MatchType {
		case models.RouteMatchTypePrefix:
			prefixPath := route.Path
			if !strings.HasSuffix(prefixPath, "/*") {
				prefixPath = strings.TrimRight(prefixPath, "/") + "/*"
			}
			if err := m.trieRouter.Insert(prefixPath, route); err != nil {
				return fmt.Errorf("failed to rebuild trie router for route %s: %w", route.ID, err)
			}
			method := route.Method
			if method == "" {
				method = "*"
			}
			if err := m.radixRouter.Insert(method, prefixPath, route); err != nil {
				return fmt.Errorf("failed to rebuild radix router for route %s: %w", route.ID, err)
			}
		case models.RouteMatchTypeExact:
			if err := m.trieRouter.Insert(route.Path, route); err != nil {
				return fmt.Errorf("failed to rebuild trie router for route %s: %w", route.ID, err)
			}
			method := route.Method
			if method == "" {
				method = "*"
			}
			if err := m.radixRouter.Insert(method, route.Path, route); err != nil {
				return fmt.Errorf("failed to rebuild radix router for route %s: %w", route.ID, err)
			}
		case models.RouteMatchTypeRegex:
			if route.RegexPattern == "" {
				return fmt.Errorf("regex pattern cannot be empty for route %s", route.ID)
			}
			if err := m.regexRouter.Insert(route.Path, route.RegexPattern, route); err != nil {
				return fmt.Errorf("failed to rebuild regex router for route %s: %w", route.ID, err)
			}
		}
	}

	return nil
}
