package router

import (
	"sync"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"DF1-56/internal/models"
	"DF1-56/internal/testutil"
)

func TestTrieRouter_ExactMatch(t *testing.T) {
	tr := NewTrieRouter()
	routeFactory := testutil.NewRouteFactory()

	t.Run("simple exact match returns correct upstream", func(t *testing.T) {
		route := routeFactory(
			testutil.WithRoutePath("/api/v1/users"),
			testutil.WithUpstreamURL("http://user-service:8080"),
			testutil.WithMatchType(models.RouteMatchTypeExact),
		)

		err := tr.Insert("/api/v1/users", route)
		require.NoError(t, err)

		matchedRoute, params := tr.Match("/api/v1/users")
		require.NotNil(t, matchedRoute)
		assert.Equal(t, "http://user-service:8080", matchedRoute.UpstreamURL)
		assert.Empty(t, params)
	})

	t.Run("nested path exact match", func(t *testing.T) {
		tr := NewTrieRouter()
		route := routeFactory(
			testutil.WithRoutePath("/api/v1/users/orders/items"),
			testutil.WithUpstreamURL("http://order-service:8080"),
			testutil.WithMatchType(models.RouteMatchTypeExact),
		)

		err := tr.Insert("/api/v1/users/orders/items", route)
		require.NoError(t, err)

		matchedRoute, params := tr.Match("/api/v1/users/orders/items")
		require.NotNil(t, matchedRoute)
		assert.Equal(t, "http://order-service:8080", matchedRoute.UpstreamURL)
		assert.Empty(t, params)
	})

	t.Run("exact match with trailing slash", func(t *testing.T) {
		tr := NewTrieRouter()
		route := routeFactory(
			testutil.WithRoutePath("/api/v1/users"),
			testutil.WithUpstreamURL("http://user-service:8080"),
		)

		err := tr.Insert("/api/v1/users", route)
		require.NoError(t, err)

		matchedRoute, _ := tr.Match("/api/v1/users/")
		assert.Nil(t, matchedRoute)
	})
}

func TestTrieRouter_WildcardMatch(t *testing.T) {
	tr := NewTrieRouter()
	routeFactory := testutil.NewRouteFactory()

	t.Run("single level wildcard match", func(t *testing.T) {
		route := routeFactory(
			testutil.WithRoutePath("/api/v1/*"),
			testutil.WithUpstreamURL("http://catchall-service:8080"),
		)

		err := tr.Insert("/api/v1/*", route)
		require.NoError(t, err)

		matchedRoute, params := tr.Match("/api/v1/anything/here")
		require.NotNil(t, matchedRoute)
		assert.Equal(t, "http://catchall-service:8080", matchedRoute.UpstreamURL)
		assert.Empty(t, params)
	})

	t.Run("wildcard at root", func(t *testing.T) {
		tr := NewTrieRouter()
		route := routeFactory(
			testutil.WithRoutePath("/*"),
			testutil.WithUpstreamURL("http://default-service:8080"),
		)

		err := tr.Insert("/*", route)
		require.NoError(t, err)

		matchedRoute, _ := tr.Match("/any/path/here")
		require.NotNil(t, matchedRoute)
		assert.Equal(t, "http://default-service:8080", matchedRoute.UpstreamURL)
	})

	t.Run("exact match takes precedence over wildcard", func(t *testing.T) {
		tr := NewTrieRouter()
		wildcardRoute := routeFactory(
			testutil.WithRoutePath("/api/v1/*"),
			testutil.WithUpstreamURL("http://wildcard:8080"),
		)
		exactRoute := routeFactory(
			testutil.WithRoutePath("/api/v1/users"),
			testutil.WithUpstreamURL("http://exact:8080"),
		)

		err := tr.Insert("/api/v1/*", wildcardRoute)
		require.NoError(t, err)
		err = tr.Insert("/api/v1/users", exactRoute)
		require.NoError(t, err)

		matchedRoute, _ := tr.Match("/api/v1/users")
		require.NotNil(t, matchedRoute)
		assert.Equal(t, "http://exact:8080", matchedRoute.UpstreamURL)
	})
}

func TestTrieRouter_PathParameter(t *testing.T) {
	tr := NewTrieRouter()
	routeFactory := testutil.NewRouteFactory()

	t.Run("single path parameter extraction", func(t *testing.T) {
		route := routeFactory(
			testutil.WithRoutePath("/api/v1/users/:id"),
			testutil.WithUpstreamURL("http://user-service:8080"),
		)

		err := tr.Insert("/api/v1/users/:id", route)
		require.NoError(t, err)

		matchedRoute, params := tr.Match("/api/v1/users/123")
		require.NotNil(t, matchedRoute)
		assert.Equal(t, "http://user-service:8080", matchedRoute.UpstreamURL)
		assert.Equal(t, "123", params["id"])
	})

	t.Run("multiple path parameters", func(t *testing.T) {
		tr := NewTrieRouter()
		route := routeFactory(
			testutil.WithRoutePath("/api/v1/users/:userId/orders/:orderId"),
			testutil.WithUpstreamURL("http://order-service:8080"),
		)

		err := tr.Insert("/api/v1/users/:userId/orders/:orderId", route)
		require.NoError(t, err)

		matchedRoute, params := tr.Match("/api/v1/users/456/orders/789")
		require.NotNil(t, matchedRoute)
		assert.Equal(t, "http://order-service:8080", matchedRoute.UpstreamURL)
		assert.Equal(t, "456", params["userId"])
		assert.Equal(t, "789", params["orderId"])
	})

	t.Run("parameter with wildcard", func(t *testing.T) {
		tr := NewTrieRouter()
		paramRoute := routeFactory(
			testutil.WithRoutePath("/api/v1/users/:id"),
			testutil.WithUpstreamURL("http://param-service:8080"),
		)
		wildcardRoute := routeFactory(
			testutil.WithRoutePath("/api/v1/*"),
			testutil.WithUpstreamURL("http://wildcard:8080"),
		)

		err := tr.Insert("/api/v1/users/:id", paramRoute)
		require.NoError(t, err)
		err = tr.Insert("/api/v1/*", wildcardRoute)
		require.NoError(t, err)

		matchedRoute, params := tr.Match("/api/v1/users/123")
		require.NotNil(t, matchedRoute)
		assert.Equal(t, "http://param-service:8080", matchedRoute.UpstreamURL)
		assert.Equal(t, "123", params["id"])
	})
}

func TestTrieRouter_InsertValidation(t *testing.T) {
	tr := NewTrieRouter()
	routeFactory := testutil.NewRouteFactory()

	t.Run("empty path returns error", func(t *testing.T) {
		route := routeFactory()
		err := tr.Insert("", route)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "cannot be empty")
	})

	t.Run("path without leading slash returns error", func(t *testing.T) {
		route := routeFactory()
		err := tr.Insert("api/v1/users", route)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "must start with /")
	})

	t.Run("nil route returns error", func(t *testing.T) {
		err := tr.Insert("/api/v1/users", nil)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "cannot be nil")
	})

	t.Run("duplicate route returns error", func(t *testing.T) {
		tr := NewTrieRouter()
		route1 := routeFactory(testutil.WithRoutePath("/api/v1/users"))
		route2 := routeFactory(testutil.WithRoutePath("/api/v1/users"))

		err := tr.Insert("/api/v1/users", route1)
		require.NoError(t, err)

		err = tr.Insert("/api/v1/users", route2)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "already exists")
	})

	t.Run("conflicting parameter names returns error", func(t *testing.T) {
		tr := NewTrieRouter()
		route1 := routeFactory(testutil.WithRoutePath("/api/v1/users/:id"))
		route2 := routeFactory(testutil.WithRoutePath("/api/v1/users/:userId"))

		err := tr.Insert("/api/v1/users/:id", route1)
		require.NoError(t, err)

		err = tr.Insert("/api/v1/users/:userId", route2)
		assert.Error(t, err)
		assert.Contains(t, err.Error(), "parameter name conflict")
	})
}

func TestTrieRouter_MatchEdgeCases(t *testing.T) {
	tr := NewTrieRouter()
	routeFactory := testutil.NewRouteFactory()

	t.Run("match empty path returns nil", func(t *testing.T) {
		route, params := tr.Match("")
		assert.Nil(t, route)
		assert.Nil(t, params)
	})

	t.Run("match root path", func(t *testing.T) {
		route := routeFactory(
			testutil.WithRoutePath("/"),
			testutil.WithUpstreamURL("http://root-service:8080"),
		)

		err := tr.Insert("/", route)
		require.NoError(t, err)

		matchedRoute, _ := tr.Match("/")
		require.NotNil(t, matchedRoute)
		assert.Equal(t, "http://root-service:8080", matchedRoute.UpstreamURL)
	})

	t.Run("no match returns nil", func(t *testing.T) {
		tr := NewTrieRouter()
		route := routeFactory(testutil.WithRoutePath("/api/v1/users"))

		err := tr.Insert("/api/v1/users", route)
		require.NoError(t, err)

		matchedRoute, params := tr.Match("/api/v2/products")
		assert.Nil(t, matchedRoute)
		assert.Nil(t, params)
	})
}

func TestTrieRouter_ConcurrentAccess(t *testing.T) {
	tr := NewTrieRouter()
	routeFactory := testutil.NewRouteFactory()

	t.Run("concurrent insert and match does not panic", func(t *testing.T) {
		var wg sync.WaitGroup
		numOperations := 100

		for i := 0; i < numOperations; i++ {
			wg.Add(2)

			go func(idx int) {
				defer wg.Done()
				route := routeFactory(
					testutil.WithRouteID(string(rune('A'+idx%26))),
					testutil.WithRoutePath("/api/v1/resource/" + string(rune('A'+idx%26))),
					testutil.WithUpstreamURL("http://service-" + string(rune('A'+idx%26)) + ":8080"),
				)
				_ = tr.Insert("/api/v1/resource/"+string(rune('A'+idx%26)), route)
			}(i)

			go func(idx int) {
				defer wg.Done()
				tr.Match("/api/v1/resource/" + string(rune('A'+idx%26)))
			}(i)
		}

		wg.Wait()
	})

	t.Run("concurrent match operations do not race", func(t *testing.T) {
		tr := NewTrieRouter()
		route := routeFactory(
			testutil.WithRoutePath("/api/v1/users/:id"),
			testutil.WithUpstreamURL("http://user-service:8080"),
		)
		err := tr.Insert("/api/v1/users/:id", route)
		require.NoError(t, err)

		var wg sync.WaitGroup
		numGoroutines := 50

		for i := 0; i < numGoroutines; i++ {
			wg.Add(1)
			go func(idx int) {
				defer wg.Done()
				for j := 0; j < 100; j++ {
					matchedRoute, params := tr.Match("/api/v1/users/" + string(rune('0'+idx%10)))
					assert.NotNil(t, matchedRoute)
					assert.NotEmpty(t, params["id"])
				}
			}(i)
		}

		wg.Wait()
	})
}
