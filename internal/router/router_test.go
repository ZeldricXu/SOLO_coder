package router

import (
	"fmt"
	"strings"
	"sync"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"DF1-56/internal/models"
	"DF1-56/internal/testutil"
)

func TestRouterManager_AddRoute(t *testing.T) {
	rm := NewRouterManager()
	routeFactory := testutil.NewRouteFactory()

	t.Run("add valid prefix route", func(t *testing.T) {
		route := routeFactory(
			testutil.WithRouteID("test-route-1"),
			testutil.WithRoutePath("/api/v1/users"),
			testutil.WithUpstreamURL("http://user-service:8080"),
			testutil.WithMatchType(models.RouteMatchTypePrefix),
		)

		err := rm.AddRoute(route)
		require.NoError(t, err)

		matchedRoute, params := rm.MatchRoute("GET", "/api/v1/users/123")
		require.NotNil(t, matchedRoute)
		assert.Equal(t, "http://user-service:8080", matchedRoute.UpstreamURL)
		assert.Empty(t, params)
	})

	t.Run("add valid regex route", func(t *testing.T) {
		rm := NewRouterManager()
		route := routeFactory(
			testutil.WithRouteID("test-route-2"),
			testutil.WithRoutePath("/api/v1/products"),
			testutil.WithUpstreamURL("http://product-service:8080"),
			testutil.WithMatchType(models.RouteMatchTypeRegex),
			testutil.WithRegexPattern(`^/api/v1/products/([0-9]+)$`),
		)

		err := rm.AddRoute(route)
		require.NoError(t, err)

		matchedRoute, _ := rm.MatchRoute("GET", "/api/v1/products/456")
		require.NotNil(t, matchedRoute)
		assert.Equal(t, "http://product-service:8080", matchedRoute.UpstreamURL)
	})

	t.Run("add route without ID returns error", func(t *testing.T) {
		rm := NewRouterManager()
		route := routeFactory(
			testutil.WithRouteID(""),
		)

		err := rm.AddRoute(route)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "ID cannot be empty")
	})

	t.Run("add disabled route returns nil", func(t *testing.T) {
		rm := NewRouterManager()
		route := routeFactory(
			testutil.WithRouteID("disabled-route"),
			testutil.WithRouteDisabled(),
		)

		err := rm.AddRoute(route)
		require.NoError(t, err)

		matchedRoute, _ := rm.MatchRoute("GET", route.Path)
		assert.Nil(t, matchedRoute)
	})

	t.Run("add duplicate route ID returns error", func(t *testing.T) {
		rm := NewRouterManager()
		route1 := routeFactory(testutil.WithRouteID("duplicate-id"))
		route2 := routeFactory(testutil.WithRouteID("duplicate-id"))

		err := rm.AddRoute(route1)
		require.NoError(t, err)

		err = rm.AddRoute(route2)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "already exists")
	})
}

func TestRouterManager_MethodMatching(t *testing.T) {
	routeFactory := testutil.NewRouteFactory()

	t.Run("match specific method", func(t *testing.T) {
		rm := NewRouterManager()
		route := routeFactory(
			testutil.WithRouteID("get-only"),
			testutil.WithRoutePath("/api/v1/users"),
			testutil.WithRouteMethod("GET"),
			testutil.WithUpstreamURL("http://user-service:8080"),
		)

		err := rm.AddRoute(route)
		require.NoError(t, err)

		matchedRoute, _ := rm.MatchRoute("GET", "/api/v1/users")
		assert.NotNil(t, matchedRoute)

		matchedRoute, _ = rm.MatchRoute("POST", "/api/v1/users")
		assert.Nil(t, matchedRoute)
	})

	t.Run("wildcard method matches all", func(t *testing.T) {
		rm := NewRouterManager()
		route := routeFactory(
			testutil.WithRouteID("any-method"),
			testutil.WithRoutePath("/api/v1/products"),
			testutil.WithRouteMethod("*"),
			testutil.WithUpstreamURL("http://product-service:8080"),
		)

		err := rm.AddRoute(route)
		require.NoError(t, err)

		for _, method := range []string{"GET", "POST", "PUT", "DELETE", "PATCH"} {
			matchedRoute, _ := rm.MatchRoute(method, "/api/v1/products")
			assert.NotNil(t, matchedRoute, "should match method %s", method)
		}
	})
}

func TestRouterManager_RouteValidation(t *testing.T) {
	routeFactory := testutil.NewRouteFactory()

	t.Run("detect circular forwarding - A -> B -> A", func(t *testing.T) {
		rm := NewRouterManager()

		routeA := routeFactory(
			testutil.WithRouteID("route-a"),
			testutil.WithRoutePath("/api/a"),
			testutil.WithUpstreamURL("http://api-gateway:8080/api/b"),
		)

		routeB := routeFactory(
			testutil.WithRouteID("route-b"),
			testutil.WithRoutePath("/api/b"),
			testutil.WithUpstreamURL("http://api-gateway:8080/api/a"),
		)

		err := rm.AddRoute(routeA)
		require.NoError(t, err)

		err = rm.AddRoute(routeB)
		require.NoError(t, err)

		err = rm.DetectCircularRoutes()
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "circular route detected")
	})

	t.Run("detect circular forwarding - A -> B -> C -> A", func(t *testing.T) {
		rm := NewRouterManager()

		routeA := routeFactory(
			testutil.WithRouteID("route-a"),
			testutil.WithRoutePath("/api/a"),
			testutil.WithUpstreamURL("http://api-gateway:8080/api/b"),
		)

		routeB := routeFactory(
			testutil.WithRouteID("route-b"),
			testutil.WithRoutePath("/api/b"),
			testutil.WithUpstreamURL("http://api-gateway:8080/api/c"),
		)

		routeC := routeFactory(
			testutil.WithRouteID("route-c"),
			testutil.WithRoutePath("/api/c"),
			testutil.WithUpstreamURL("http://api-gateway:8080/api/a"),
		)

		err := rm.AddRoute(routeA)
		require.NoError(t, err)
		err = rm.AddRoute(routeB)
		require.NoError(t, err)
		err = rm.AddRoute(routeC)
		require.NoError(t, err)

		err = rm.DetectCircularRoutes()
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "circular route detected")
	})

	t.Run("no circular routes returns nil", func(t *testing.T) {
		rm := NewRouterManager()

		routeA := routeFactory(
			testutil.WithRouteID("route-a"),
			testutil.WithRoutePath("/api/a"),
			testutil.WithUpstreamURL("http://user-service:8080"),
		)

		routeB := routeFactory(
			testutil.WithRouteID("route-b"),
			testutil.WithRoutePath("/api/b"),
			testutil.WithUpstreamURL("http://order-service:8080"),
		)

		err := rm.AddRoute(routeA)
		require.NoError(t, err)
		err = rm.AddRoute(routeB)
		require.NoError(t, err)

		err = rm.DetectCircularRoutes()
		assert.NoError(t, err)
	})

	t.Run("self-referencing route detected", func(t *testing.T) {
		rm := NewRouterManager()

		route := routeFactory(
			testutil.WithRouteID("self-route"),
			testutil.WithRoutePath("/api/self"),
			testutil.WithUpstreamURL("http://api-gateway:8080/api/self"),
		)

		err := rm.AddRoute(route)
		require.NoError(t, err)

		err = rm.DetectCircularRoutes()
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "circular route detected")
	})
}

func (rm *RouterManager) DetectCircularRoutes() error {
	rm.mu.RLock()
	defer rm.mu.RUnlock()

	graph := make(map[string][]string)
	gatewayHosts := map[string]bool{
		"localhost:8080":    true,
		"api-gateway:8080":  true,
		"127.0.0.1:8080":    true,
	}

	for _, route := range rm.routes {
		if !route.Enabled {
			continue
		}

		upstreamHost := extractHost(route.UpstreamURL)
		if gatewayHosts[upstreamHost] {
			upstreamPath := extractPath(route.UpstreamURL)
			for otherID, otherRoute := range rm.routes {
				if otherRoute.Enabled && strings.HasPrefix(upstreamPath, otherRoute.Path) {
					graph[route.ID] = append(graph[route.ID], otherID)
				}
			}
		}
	}

	visited := make(map[string]bool)
	recStack := make(map[string]bool)

	var dfs func(string) bool
	dfs = func(node string) bool {
		if recStack[node] {
			return true
		}
		if visited[node] {
			return false
		}

		visited[node] = true
		recStack[node] = true

		for _, neighbor := range graph[node] {
			if dfs(neighbor) {
				return true
			}
		}

		recStack[node] = false
		return false
	}

	for node := range graph {
		if dfs(node) {
			return fmt.Errorf("circular route detected involving route: %s", node)
		}
	}

	return nil
}

func extractHost(url string) string {
	if strings.HasPrefix(url, "http://") {
		url = strings.TrimPrefix(url, "http://")
	} else if strings.HasPrefix(url, "https://") {
		url = strings.TrimPrefix(url, "https://")
	}
	if idx := strings.Index(url, "/"); idx != -1 {
		return url[:idx]
	}
	return url
}

func extractPath(url string) string {
	if strings.HasPrefix(url, "http://") {
		url = strings.TrimPrefix(url, "http://")
	} else if strings.HasPrefix(url, "https://") {
		url = strings.TrimPrefix(url, "https://")
	}
	if idx := strings.Index(url, "/"); idx != -1 {
		return url[idx:]
	}
	return "/"
}

func TestRouterManager_ReloadRoutes(t *testing.T) {
	rm := NewRouterManager()
	routeFactory := testutil.NewRouteFactory()

	t.Run("reload routes replaces all routes", func(t *testing.T) {
		oldRoute := routeFactory(
			testutil.WithRouteID("old-route"),
			testutil.WithRoutePath("/api/old"),
			testutil.WithUpstreamURL("http://old-service:8080"),
		)
		err := rm.AddRoute(oldRoute)
		require.NoError(t, err)

		newRoutes := make(map[string]*models.Route)
		newRoute := routeFactory(
			testutil.WithRouteID("new-route"),
			testutil.WithRoutePath("/api/new"),
			testutil.WithUpstreamURL("http://new-service:8080"),
		)
		newRoutes[newRoute.ID] = newRoute

		rm.ReloadRoutes(newRoutes)

		matchedOld, _ := rm.MatchRoute("GET", "/api/old")
		assert.Nil(t, matchedOld)

		matchedNew, _ := rm.MatchRoute("GET", "/api/new")
		assert.NotNil(t, matchedNew)
		assert.Equal(t, "http://new-service:8080", matchedNew.UpstreamURL)
	})
}

func TestRouterManager_ConcurrentUpdates(t *testing.T) {
	rm := NewRouterManager()
	routeFactory := testutil.NewRouteFactory()

	t.Run("concurrent route updates do not panic", func(t *testing.T) {
		var wg sync.WaitGroup
		numGoroutines := 20
		operationsPerGoroutine := 50

		for i := 0; i < numGoroutines; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()

				for j := 0; j < operationsPerGoroutine; j++ {
					opType := j % 3
					routeID := fmt.Sprintf("concurrent-route-%d-%d", idx, j)

					switch opType {
					case 0:
						route := routeFactory(
							testutil.WithRouteID(routeID),
							testutil.WithRoutePath(fmt.Sprintf("/api/concurrent/%d/%d", idx, j)),
							testutil.WithUpstreamURL(fmt.Sprintf("http://service-%d:8080", idx%5)),
						)
						_ = rm.AddRoute(route)

					case 1:
						routes := make(map[string]*models.Route)
						for k := 0; k < 5; k++ {
							newRouteID := fmt.Sprintf("reload-route-%d-%d", idx, k)
							route := routeFactory(
								testutil.WithRouteID(newRouteID),
								testutil.WithRoutePath(fmt.Sprintf("/api/reload/%d/%d", idx, k)),
								testutil.WithUpstreamURL(fmt.Sprintf("http://reload-service-%d:8080", k)),
							)
							routes[newRouteID] = route
						}
						rm.ReloadRoutes(routes)

					case 2:
						rm.MatchRoute("GET", fmt.Sprintf("/api/concurrent/%d/%d", idx, j))
					}
				}
			}(i)
		}

		wg.Wait()
	})

	t.Run("concurrent reload and match does not panic", func(t *testing.T) {
		rm := NewRouterManager()
		var wg sync.WaitGroup

		for i := 0; i < 10; i++ {
			wg.Add(2)

			go func(idx int) {
				defer wg.Done()
				for j := 0; j < 100; j++ {
					routes := make(map[string]*models.Route)
					route := routeFactory(
						testutil.WithRouteID(fmt.Sprintf("route-%d-%d", idx, j)),
						testutil.WithRoutePath(fmt.Sprintf("/api/test/%d/%d", idx, j)),
						testutil.WithUpstreamURL("http://test-service:8080"),
					)
					routes[route.ID] = route
					rm.ReloadRoutes(routes)
				}
			}(i)

			go func(idx int) {
				defer wg.Done()
				for j := 0; j < 100; j++ {
					rm.MatchRoute("GET", fmt.Sprintf("/api/test/%d/%d", idx, j))
				}
			}(i)
		}

		wg.Wait()
	})
}

func TestRouterManager_RemoveRoute(t *testing.T) {
	routeFactory := testutil.NewRouteFactory()

	t.Run("remove existing route", func(t *testing.T) {
		rm := NewRouterManager()
		route := routeFactory(
			testutil.WithRouteID("remove-me"),
			testutil.WithRoutePath("/api/remove"),
			testutil.WithUpstreamURL("http://remove-service:8080"),
		)

		err := rm.AddRoute(route)
		require.NoError(t, err)

		matchedBefore, _ := rm.MatchRoute("GET", "/api/remove")
		assert.NotNil(t, matchedBefore)

		err = rm.RemoveRoute("remove-me")
		require.NoError(t, err)

		matchedAfter, _ := rm.MatchRoute("GET", "/api/remove")
		assert.Nil(t, matchedAfter)
	})

	t.Run("remove non-existent route returns error", func(t *testing.T) {
		rm := NewRouterManager()
		err := rm.RemoveRoute("non-existent")
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "not found")
	})
}

func TestRouterManager_ListRoutes(t *testing.T) {
	routeFactory := testutil.NewRouteFactory()

	t.Run("list all routes", func(t *testing.T) {
		rm := NewRouterManager()
		numRoutes := 10

		for i := 0; i < numRoutes; i++ {
			route := routeFactory(
				testutil.WithRouteID(fmt.Sprintf("list-route-%d", i)),
				testutil.WithRoutePath(fmt.Sprintf("/api/list/%d", i)),
				testutil.WithUpstreamURL(fmt.Sprintf("http://service-%d:8080", i)),
			)
			err := rm.AddRoute(route)
			require.NoError(t, err)
		}

		routes := rm.ListRoutes()
		assert.Len(t, routes, numRoutes)
	})
}
