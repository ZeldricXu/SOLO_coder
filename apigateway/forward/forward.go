package forward

import (
	"apigateway/auth"
	"apigateway/circuitbreaker"
	"apigateway/loadbalancer"
	"apigateway/logger"
	"apigateway/metrics"
	"apigateway/models"
	"apigateway/ratelimit"
	"apigateway/retry"
	"apigateway/router"
	"bytes"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"
)

type ForwardConfig struct {
	Timeout        time.Duration
	MaxIdleConns   int
	IdleConnTimeout time.Duration
}

type Forwarder struct {
	routerManager  *router.RouterManager
	rateLimiter    *ratelimit.RateLimiter
	loadBalancer   *loadbalancer.LoadBalancer
	circuitBreaker *circuitbreaker.CircuitBreaker
	metricsCollector *metrics.MetricsCollector
	logger         *logger.RequestLogger
	authManager    *auth.AuthManager
	retryer        *retry.Retryer
	httpClient     *http.Client
	config         ForwardConfig
}

func NewForwarder(
	routerManager *router.RouterManager,
	rateLimiter *ratelimit.RateLimiter,
	loadBalancer *loadbalancer.LoadBalancer,
	circuitBreaker *circuitbreaker.CircuitBreaker,
	metricsCollector *metrics.MetricsCollector,
	logger *logger.RequestLogger,
	authManager *auth.AuthManager,
) *Forwarder {
	return NewForwarderWithConfig(
		routerManager, rateLimiter, loadBalancer, circuitBreaker,
		metricsCollector, logger, authManager,
		ForwardConfig{
			Timeout:         30 * time.Second,
			MaxIdleConns:    100,
			IdleConnTimeout: 90 * time.Second,
		},
	)
}

func NewForwarderWithConfig(
	routerManager *router.RouterManager,
	rateLimiter *ratelimit.RateLimiter,
	loadBalancer *loadbalancer.LoadBalancer,
	circuitBreaker *circuitbreaker.CircuitBreaker,
	metricsCollector *metrics.MetricsCollector,
	logger *logger.RequestLogger,
	authManager *auth.AuthManager,
	config ForwardConfig,
) *Forwarder {
	httpClient := &http.Client{
		Timeout: config.Timeout,
		Transport: &http.Transport{
			MaxIdleConns:        config.MaxIdleConns,
			IdleConnTimeout:     config.IdleConnTimeout,
			MaxIdleConnsPerHost: config.MaxIdleConns,
			DialContext: (&net.Dialer{
				Timeout:   30 * time.Second,
				KeepAlive: 30 * time.Second,
			}).DialContext,
		},
	}

	return &Forwarder{
		routerManager:  routerManager,
		rateLimiter:    rateLimiter,
		loadBalancer:   loadBalancer,
		circuitBreaker: circuitBreaker,
		metricsCollector: metricsCollector,
		logger:         logger,
		authManager:    authManager,
		retryer:        retry.NewRetryer(),
		httpClient:     httpClient,
		config:         config,
	}
}

func (f *Forwarder) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	startTime := time.Now()
	requestID := uuid.New().String()

	requestLog := &models.RequestLog{
		RequestID:     requestID,
		ClientIP:      getClientIP(r),
		RequestMethod: r.Method,
		RequestPath:   r.URL.Path,
		RequestTime:   startTime,
	}

	defer func() {
		requestLog.Latency = time.Since(startTime).Milliseconds()
		f.logger.LogRequest(requestLog)
	}()

	r.Header.Set("X-Request-ID", requestID)

	matchedRoute, err := f.routerManager.MatchRoute(r.URL.Path)
	if err != nil {
		requestLog.ResponseStatus = http.StatusNotFound
		f.writeErrorResponse(w, http.StatusNotFound, "Route not found")
		return
	}

	requestLog.RouteID = matchedRoute.RouteID

	if matchedRoute.AuthRequired {
		authenticated, _, authErr := f.authManager.Authenticate(r)
		if authErr != nil || !authenticated {
			requestLog.ResponseStatus = http.StatusUnauthorized
			f.metricsCollector.RecordFailure(matchedRoute.RouteID, time.Since(startTime).Milliseconds())
			f.writeErrorResponse(w, http.StatusUnauthorized, "Authentication required")
			return
		}
	}

	allowed, rateErr := f.rateLimiter.CheckRateLimit(matchedRoute.RouteID, &matchedRoute.RateLimit, "token_bucket")
	if rateErr != nil || !allowed {
		requestLog.ResponseStatus = http.StatusTooManyRequests
		f.metricsCollector.RecordFailure(matchedRoute.RouteID, time.Since(startTime).Milliseconds())
		f.writeErrorResponse(w, http.StatusTooManyRequests, "Rate limit exceeded")
		return
	}

	targetService := matchedRoute.TargetService
	instance, lbErr := f.loadBalancer.SelectInstance(targetService, "")
	if lbErr != nil {
		requestLog.ResponseStatus = http.StatusServiceUnavailable
		f.metricsCollector.RecordFailure(matchedRoute.RouteID, time.Since(startTime).Milliseconds())
		requestLog.Error = lbErr.Error()
		f.writeErrorResponse(w, http.StatusServiceUnavailable, "No available instances")
		return
	}

	requestLog.TargetAddress = instance.Address

	allowed, cbStatus := f.circuitBreaker.AllowRequest(targetService)
	if !allowed {
		requestLog.ResponseStatus = http.StatusServiceUnavailable
		f.metricsCollector.RecordFailure(matchedRoute.RouteID, time.Since(startTime).Milliseconds())
		requestLog.Error = fmt.Sprintf("Circuit breaker %s", cbStatus)
		f.writeErrorResponse(w, http.StatusServiceUnavailable, "Service unavailable (circuit breaker open)")
		return
	}

	var response *http.Response
	var retryCount int
	var forwardErr error

	timeout := matchedRoute.ForwardConfig.Timeout
	if timeout <= 0 {
		timeout = 3000
	}

	retryConfig := retry.RetryConfig{
		MaxRetries:   matchedRoute.ForwardConfig.RetryCount,
		InitialDelay: 100 * time.Millisecond,
		MaxDelay:     2 * time.Second,
		BackoffFactor: 2.0,
	}

	targetURL := fmt.Sprintf("http://%s%s", instance.Address, r.URL.Path)
	if r.URL.RawQuery != "" {
		targetURL = targetURL + "?" + r.URL.RawQuery
	}

	response, retryCount, forwardErr = f.retryer.DoWithConfig(retryConfig, func() (*http.Response, error) {
		return f.forwardRequest(r, targetURL, time.Duration(timeout)*time.Millisecond)
	})

	requestLog.RetryCount = retryCount - 1

	if forwardErr != nil {
		requestLog.ResponseStatus = http.StatusBadGateway
		requestLog.Error = forwardErr.Error()
		f.metricsCollector.RecordFailure(matchedRoute.RouteID, time.Since(startTime).Milliseconds())
		f.circuitBreaker.OnFailure(targetService)
		f.writeErrorResponse(w, http.StatusBadGateway, forwardErr.Error())
		return
	}

	defer response.Body.Close()

	f.copyResponseHeaders(w, response)
	w.WriteHeader(response.StatusCode)

	body, readErr := io.ReadAll(response.Body)
	if readErr == nil {
		w.Write(body)
	}

	requestLog.ResponseStatus = response.StatusCode

	if response.StatusCode >= 200 && response.StatusCode < 400 {
		f.metricsCollector.RecordSuccess(matchedRoute.RouteID, time.Since(startTime).Milliseconds())
		f.circuitBreaker.OnSuccess(targetService)
	} else {
		f.metricsCollector.RecordFailure(matchedRoute.RouteID, time.Since(startTime).Milliseconds())
		f.circuitBreaker.OnFailure(targetService)
	}
}

func (f *Forwarder) forwardRequest(r *http.Request, targetURL string, timeout time.Duration) (*http.Response, error) {
	target, parseErr := url.Parse(targetURL)
	if parseErr != nil {
		return nil, parseErr
	}

	proxy := httputil.NewSingleHostReverseProxy(target)

	originalDirector := proxy.Director
	proxy.Director = func(req *http.Request) {
		originalDirector(req)
		f.copyRequestHeaders(req, r)
		req.Host = target.Host
	}

	var bodyBytes []byte
	if r.Body != nil {
		bodyBytes, _ = io.ReadAll(r.Body)
		r.Body.Close()
		r.Body = io.NopCloser(bytes.NewBuffer(bodyBytes))
	}

	req, err := http.NewRequest(r.Method, targetURL, bytes.NewBuffer(bodyBytes))
	if err != nil {
		return nil, err
	}

	f.copyRequestHeaders(req, r)
	req.Host = target.Host

	client := &http.Client{
		Timeout: timeout,
	}

	return client.Do(req)
}

func (f *Forwarder) copyRequestHeaders(dst, src *http.Request) {
	hopByHopHeaders := []string{
		"Connection",
		"Keep-Alive",
		"Proxy-Authenticate",
		"Proxy-Authorization",
		"TE",
		"Trailers",
		"Transfer-Encoding",
		"Upgrade",
	}

	headers := make(map[string][]string)
	for k, v := range src.Header {
		headers[k] = v
	}

	for _, h := range hopByHopHeaders {
		delete(headers, h)
	}

	for k, v := range headers {
		dst.Header[k] = v
	}

	if clientIP := getClientIP(src); clientIP != "" {
		if existing := dst.Header.Get("X-Forwarded-For"); existing != "" {
			dst.Header.Set("X-Forwarded-For", existing+", "+clientIP)
		} else {
			dst.Header.Set("X-Forwarded-For", clientIP)
		}
	}
}

func (f *Forwarder) copyResponseHeaders(w http.ResponseWriter, resp *http.Response) {
	hopByHopHeaders := []string{
		"Connection",
		"Keep-Alive",
		"Proxy-Authenticate",
		"Proxy-Authorization",
		"TE",
		"Trailers",
		"Transfer-Encoding",
		"Upgrade",
	}

	for k, v := range resp.Header {
		isHopByHop := false
		for _, h := range hopByHopHeaders {
			if strings.EqualFold(k, h) {
				isHopByHop = true
				break
			}
		}
		if !isHopByHop {
			w.Header()[k] = v
		}
	}
}

func (f *Forwarder) writeErrorResponse(w http.ResponseWriter, statusCode int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	response := models.APIResponse{
		Code: statusCode,
		Msg:  message,
	}
	jsonBytes, _ := jsonMarshal(response)
	w.Write(jsonBytes)
}

func jsonMarshal(v interface{}) ([]byte, error) {
	var buf bytes.Buffer
	enc := func() ([]byte, error) {
		switch val := v.(type) {
		case models.APIResponse:
			if val.Data == nil {
				return []byte(fmt.Sprintf(`{"code":%d,"msg":"%s"}`, val.Code, val.Msg)), nil
			}
			dataBytes, _ := jsonMarshal(val.Data)
			return []byte(fmt.Sprintf(`{"code":%d,"data":%s,"msg":"%s"}`, val.Code, string(dataBytes), val.Msg)), nil
		}
		return []byte("{}"), nil
	}
	_ = buf
	return enc()
}

func getClientIP(r *http.Request) string {
	xff := r.Header.Get("X-Forwarded-For")
	if xff != "" {
		parts := strings.Split(xff, ",")
		if len(parts) > 0 {
			return strings.TrimSpace(parts[0])
		}
	}

	xri := r.Header.Get("X-Real-IP")
	if xri != "" {
		return xri
	}

	ip, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return ip
}
