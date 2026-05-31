package apigateway

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httputil"
	"net/url"
	"sync"
	"time"

	"github.com/parking-platform/platform/pkg/models"
	"github.com/parking-platform/platform/pkg/utils"
)

type APIGateway struct {
	mu      sync.RWMutex
	routes  map[string]*models.RouteRule
	proxies map[string]*httputil.ReverseProxy
}

func NewAPIGateway() *APIGateway {
	return &APIGateway{
		routes:  make(map[string]*models.RouteRule),
		proxies: make(map[string]*httputil.ReverseProxy),
	}
}

func (g *APIGateway) AddRoute(path, method, backend, protocol, rewritePath string, timeout int) *models.RouteRule {
	g.mu.Lock()
	defer g.mu.Unlock()
	rule := &models.RouteRule{
		ID:          utils.GenerateID("route"),
		Path:        path,
		Method:      method,
		Backend:     backend,
		Protocol:    protocol,
		RewritePath: rewritePath,
		Timeout:     timeout,
	}
	g.routes[rule.ID] = rule

	if _, exists := g.proxies[backend]; !exists {
		backendURL, _ := url.Parse(backend)
		proxy := httputil.NewSingleHostReverseProxy(backendURL)
		g.proxies[backend] = proxy
	}

	return rule
}

func (g *APIGateway) ListRoutes() []*models.RouteRule {
	g.mu.RLock()
	defer g.mu.RUnlock()
	result := make([]*models.RouteRule, 0, len(g.routes))
	for _, r := range g.routes {
		result = append(result, r)
	}
	return result
}

func (g *APIGateway) RemoveRoute(id string) {
	g.mu.Lock()
	defer g.mu.Unlock()
	delete(g.routes, id)
}

func (g *APIGateway) MatchRoute(path, method string) (*models.RouteRule, bool) {
	g.mu.RLock()
	defer g.mu.RUnlock()
	for _, r := range g.routes {
		if r.Path == path && (r.Method == "*" || r.Method == method) {
			return r, true
		}
	}
	return nil, false
}

func (g *APIGateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	rule, matched := g.MatchRoute(r.URL.Path, r.Method)
	if !matched {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}

	if rule.Timeout > 0 {
		ctx, cancel := context.WithTimeout(r.Context(), time.Duration(rule.Timeout)*time.Second)
		defer cancel()
		r = r.WithContext(ctx)
	}

	if rule.RewritePath != "" {
		r.URL.Path = rule.RewritePath
	}

	if proxy, ok := g.proxies[rule.Backend]; ok {
		proxy.ServeHTTP(w, r)
		return
	}

	body, _ := io.ReadAll(r.Body)
	r.Body.Close()
	r.Body = io.NopCloser(bytes.NewReader(body))

	w.WriteHeader(http.StatusOK)
	w.Write([]byte("forwarded to " + rule.Backend))
}

func (g *APIGateway) ConvertProtocol(r *http.Request, toProtocol string) *http.Request {
	if toProtocol == "http" && r.TLS != nil {
		r.TLS = nil
		r.URL.Scheme = "http"
	} else if toProtocol == "https" {
		r.URL.Scheme = "https"
	}
	return r
}

func (g *APIGateway) Handler() http.Handler {
	return http.HandlerFunc(g.ServeHTTP)
}


