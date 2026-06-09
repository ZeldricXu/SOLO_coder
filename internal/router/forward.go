package router

import (
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"DF1-56/internal/models"
)

type Forwarder struct {
	client *http.Client
}

func NewForwarder() *Forwarder {
	return &Forwarder{
		client: &http.Client{
			Timeout: 30 * time.Second,
			Transport: &http.Transport{
				MaxIdleConns:        100,
				MaxIdleConnsPerHost: 100,
				IdleConnTimeout:     90 * time.Second,
				DisableCompression:  false,
			},
		},
	}
}

func (f *Forwarder) Forward(ctx *models.GatewayContext, targetURL string) error {
	if ctx == nil {
		return fmt.Errorf("gateway context cannot be nil")
	}
	if targetURL == "" {
		return fmt.Errorf("target URL cannot be empty")
	}

	rewrittenPath := f.rewritePath(ctx)

	target, err := url.Parse(targetURL)
	if err != nil {
		return fmt.Errorf("invalid target URL: %w", err)
	}

	target.Path = rewrittenPath
	target.RawQuery = ctx.Request.URL.RawQuery

	req, err := f.buildRequest(ctx, target)
	if err != nil {
		return fmt.Errorf("failed to build request: %w", err)
	}

	if err := f.setHeaders(ctx, req); err != nil {
		return fmt.Errorf("failed to set headers: %w", err)
	}

	timeout := f.getTimeout(ctx)
	f.client.Timeout = timeout

	resp, err := f.client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to forward request: %w", err)
	}
	defer resp.Body.Close()

	if err := f.copyResponse(ctx, resp); err != nil {
		return fmt.Errorf("failed to copy response: %w", err)
	}

	return nil
}

func (f *Forwarder) rewritePath(ctx *models.GatewayContext) string {
	if ctx.Route == nil || ctx.Route.RewritePath == "" {
		return ctx.Request.URL.Path
	}

	rewritePath := ctx.Route.RewritePath

	for key, value := range ctx.PathParams {
		placeholder := fmt.Sprintf(":%s", key)
		rewritePath = strings.ReplaceAll(rewritePath, placeholder, value)
	}

	if strings.Contains(rewritePath, "$1") && len(ctx.PathParams) > 0 {
		idx := 0
		for _, value := range ctx.PathParams {
			placeholder := fmt.Sprintf("$%d", idx+1)
			rewritePath = strings.Replace(rewritePath, placeholder, value, 1)
			idx++
		}
	}

	return rewritePath
}

func (f *Forwarder) buildRequest(ctx *models.GatewayContext, target *url.URL) (*http.Request, error) {
	var body io.ReadCloser
	if ctx.Request.Body != nil {
		body = ctx.Request.Body
	}

	req, err := http.NewRequestWithContext(ctx, ctx.Request.Method, target.String(), body)
	if err != nil {
		return nil, err
	}

	req.Host = target.Host

	return req, nil
}

func (f *Forwarder) setHeaders(ctx *models.GatewayContext, req *http.Request) error {
	for key, values := range ctx.Request.Header {
		if isHopHeader(key) {
			continue
		}
		for _, value := range values {
			req.Header.Add(key, value)
		}
	}

	if ctx.RequestID != "" {
		req.Header.Set("X-Request-ID", ctx.RequestID)
	}
	if ctx.TraceID != "" {
		req.Header.Set("X-Trace-ID", ctx.TraceID)
	}
	if ctx.ClientIP != "" {
		req.Header.Set("X-Forwarded-For", ctx.ClientIP)
	}
	req.Header.Set("X-Forwarded-Proto", getProtocol(ctx.Request))

	if ctx.Route != nil && ctx.Route.Headers != nil {
		for key, value := range ctx.Route.Headers {
			req.Header.Set(key, value)
		}
	}

	return nil
}

func (f *Forwarder) copyResponse(ctx *models.GatewayContext, resp *http.Response) error {
	for key, values := range resp.Header {
		if isHopHeader(key) {
			continue
		}
		for _, value := range values {
			ctx.Response.Header().Add(key, value)
		}
	}

	ctx.Response.WriteHeader(resp.StatusCode)

	if resp.Body != nil {
		_, err := io.Copy(ctx.Response, resp.Body)
		if err != nil && err != io.EOF {
			return fmt.Errorf("failed to copy response body: %w", err)
		}
	}

	return nil
}

func (f *Forwarder) getTimeout(ctx *models.GatewayContext) time.Duration {
	if ctx.Route != nil && ctx.Route.Timeout > 0 {
		return ctx.Route.Timeout
	}
	return 30 * time.Second
}

func (f *Forwarder) ForwardWithRetry(ctx *models.GatewayContext, targetURL string) error {
	if ctx == nil {
		return fmt.Errorf("gateway context cannot be nil")
	}

	retryCount := 0
	if ctx.Route != nil && ctx.Route.RetryCount > 0 {
		retryCount = ctx.Route.RetryCount
	}

	var lastErr error
	for i := 0; i <= retryCount; i++ {
		if err := f.Forward(ctx, targetURL); err != nil {
			lastErr = err
			if i < retryCount {
				time.Sleep(time.Duration(100*(i+1)) * time.Millisecond)
				continue
			}
			return err
		}
		return nil
	}

	return lastErr
}

func isHopHeader(header string) bool {
	hopHeaders := map[string]bool{
		"Connection":          true,
		"Keep-Alive":          true,
		"Proxy-Authenticate":  true,
		"Proxy-Authorization": true,
		"TE":                  true,
		"Trailers":            true,
		"Transfer-Encoding":   true,
		"Upgrade":             true,
	}
	return hopHeaders[header]
}

func getProtocol(r *http.Request) string {
	if r.TLS != nil {
		return "https"
	}
	return "http"
}

func (f *Forwarder) ForwardWithLoadBalancer(ctx *models.GatewayContext, cluster *models.UpstreamCluster, lb *LoadBalancer) error {
	if ctx == nil {
		return fmt.Errorf("gateway context cannot be nil")
	}
	if cluster == nil {
		return fmt.Errorf("upstream cluster cannot be nil")
	}
	if lb == nil {
		return fmt.Errorf("load balancer cannot be nil")
	}

	node := lb.SelectNode(cluster, ctx.ClientIP)
	if node == nil {
		return fmt.Errorf("no healthy nodes available in cluster: %s", cluster.ID)
	}

	targetURL := f.buildTargetURL(node, ctx.Request.URL.Path)

	return f.ForwardWithRetry(ctx, targetURL)
}

func (f *Forwarder) buildTargetURL(node *models.UpstreamNode, path string) string {
	scheme := "http"
	if node.Protocol == models.ProtocolGRPC || node.Protocol == models.ProtocolHTTP2 {
		scheme = "https"
	}

	port := ""
	if !strings.Contains(node.Address, ":") {
		port = ":80"
		if scheme == "https" {
			port = ":443"
		}
	}

	return fmt.Sprintf("%s://%s%s%s", scheme, node.Address, port, path)
}

func (f *Forwarder) SendErrorResponse(ctx *models.GatewayContext, statusCode int, message string) error {
	if ctx == nil {
		return fmt.Errorf("gateway context cannot be nil")
	}

	ctx.Response.Header().Set("Content-Type", "application/json")
	ctx.Response.WriteHeader(statusCode)

	body := fmt.Sprintf(`{"error": %s, "code": %d, "request_id": %s}`,
		strconv.Quote(message), statusCode, strconv.Quote(ctx.RequestID))

	_, err := ctx.Response.Write([]byte(body))
	return err
}
