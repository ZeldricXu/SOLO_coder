package models

import (
	"context"
	"net/http"
	"time"
)

type ContextKey string

const (
	ContextKeyRoute       ContextKey = "route"
	ContextKeyPathParams  ContextKey = "path_params"
	ContextKeyUserID      ContextKey = "user_id"
	ContextKeyAPIKey      ContextKey = "api_key"
	ContextKeyClaims      ContextKey = "claims"
	ContextKeyRateLimit     ContextKey = "rate_limit"
	ContextKeyCircuitBroken ContextKey = "circuit_broken"
	ContextKeyRequestID     ContextKey = "request_id"
	ContextKeyTraceID       ContextKey = "trace_id"
	ContextKeyGrayVersion   ContextKey = "gray_version"
)

type Route struct {
	ID              string            `json:"id"`
	Path            string            `json:"path"`
	Method          string            `json:"method"`
	MatchType       RouteMatchType    `json:"match_type"`
	RegexPattern    string            `json:"regex_pattern,omitempty"`
	UpstreamURL     string            `json:"upstream_url"`
	UpstreamCluster string            `json:"upstream_cluster"`
	RewritePath     string            `json:"rewrite_path,omitempty"`
	Protocol        ProtocolType      `json:"protocol"`
	Timeout         time.Duration     `json:"timeout"`
	RetryCount      int               `json:"retry_count"`
	Middlewares     []string          `json:"middlewares"`
	RateLimitPolicy string            `json:"rate_limit_policy,omitempty"`
	AuthPolicy      string            `json:"auth_policy,omitempty"`
	CircuitBreaker  string            `json:"circuit_breaker,omitempty"`
	GrayPolicy      string            `json:"gray_policy,omitempty"`
	MirrorPolicy    string            `json:"mirror_policy,omitempty"`
	Headers         map[string]string `json:"headers,omitempty"`
	Enabled         bool              `json:"enabled"`
	CreatedAt       time.Time         `json:"created_at"`
	UpdatedAt       time.Time         `json:"updated_at"`
}

type RouteMatchType string

const (
	RouteMatchTypePrefix RouteMatchType = "prefix"
	RouteMatchTypeRegex  RouteMatchType = "regex"
	RouteMatchTypeExact  RouteMatchType = "exact"
)

type ProtocolType string

const (
	ProtocolHTTP  ProtocolType = "http"
	ProtocolGRPC  ProtocolType = "grpc"
	ProtocolHTTP2 ProtocolType = "http2"
)

type RateLimitPolicy struct {
	ID         string              `json:"id"`
	Name       string              `json:"name"`
	Rules      []RateLimitRule     `json:"rules"`
	Algorithm  RateLimitAlgorithm  `json:"algorithm"`
	KeyBuilder RateLimitKeyBuilder `json:"key_builder"`
	Adaptive   *AdaptiveRateLimit  `json:"adaptive,omitempty"`
	Enabled    bool                `json:"enabled"`
}

type AdaptiveRateLimit struct {
	Enabled            bool          `json:"enabled"`
	MinLimit           int64         `json:"min_limit"`
	BaselineRTp99      time.Duration `json:"baseline_rt_p99"`
	RTThreshold        float64       `json:"rt_threshold"`
	ErrorRateThreshold float64       `json:"error_rate_threshold"`
	RTScaleDownFactor  float64       `json:"rt_scale_down_factor"`
	ErrorScaleDownFactor float64     `json:"error_scale_down_factor"`
	ScaleUpFactor      float64       `json:"scale_up_factor"`
	AdjustInterval     time.Duration `json:"adjust_interval"`
	WindowSize         time.Duration `json:"window_size"`
}

type UpstreamMetrics struct {
	RouteID     string
	RTp99       time.Duration
	RTp90       time.Duration
	RTp50       time.Duration
	ErrorRate   float64
	TotalCount  int64
	ErrorCount  int64
	WindowStart time.Time
	WindowEnd   time.Time
}

type RateLimitAlgorithm string

const (
	AlgorithmTokenBucket  RateLimitAlgorithm = "token_bucket"
	AlgorithmSlidingWindow RateLimitAlgorithm = "sliding_window"
	AlgorithmConcurrency  RateLimitAlgorithm = "concurrency"
	AlgorithmMixed        RateLimitAlgorithm = "mixed"
)

type RateLimitRule struct {
	Dimension   RateLimitDimension `json:"dimension"`
	Limit       int64              `json:"limit"`
	Window      time.Duration      `json:"window"`
	Burst       int64              `json:"burst,omitempty"`
	Capacity    int64              `json:"capacity,omitempty"`
	RefillRate  int64              `json:"refill_rate,omitempty"`
	CustomKey   string             `json:"custom_key,omitempty"`
}

type RateLimitDimension string

const (
	DimensionAPI     RateLimitDimension = "api"
	DimensionUser    RateLimitDimension = "user"
	DimensionIP      RateLimitDimension = "ip"
	DimensionCustom  RateLimitDimension = "custom"
)

type RateLimitKeyBuilder struct {
	IncludeAPI    bool     `json:"include_api"`
	IncludeUser   bool     `json:"include_user"`
	IncludeIP     bool     `json:"include_ip"`
	CustomHeaders []string `json:"custom_headers,omitempty"`
}

type RateLimitResult struct {
	Allowed    bool          `json:"allowed"`
	Limit      int64         `json:"limit"`
	Remaining  int64         `json:"remaining"`
	ResetAfter time.Duration `json:"reset_after"`
	Reason     string        `json:"reason,omitempty"`
}

type AuthPolicy struct {
	ID              string         `json:"id"`
	Name            string         `json:"name"`
	Strategies      []AuthStrategy `json:"strategies"`
	AllowAnonymous  bool           `json:"allow_anonymous"`
	TokenTTL        time.Duration  `json:"token_ttl"`
	Enabled         bool           `json:"enabled"`
}

type AuthStrategy struct {
	Type     AuthType     `json:"type"`
	Config   AuthConfig   `json:"config"`
	Optional bool         `json:"optional"`
	Priority int          `json:"priority"`
}

type AuthType string

const (
	AuthTypeJWT       AuthType = "jwt"
	AuthTypeAPIKey    AuthType = "api_key"
	AuthTypeOAuth2    AuthType = "oauth2"
	AuthTypeCustom    AuthType = "custom"
)

type AuthConfig struct {
	JWTConfig        *JWTConfig              `json:"jwt_config,omitempty"`
	APIKeyConfig     *APIKeyConfig           `json:"api_key_config,omitempty"`
	OAuth2Config     *OAuth2Config           `json:"oauth2_config,omitempty"`
	CustomProvider   *CustomProviderConfig   `json:"custom_provider,omitempty"`
}

type CustomProviderConfig struct {
	Name       string                 `json:"name"`
	Type       string                 `json:"type"`
	Config     map[string]interface{} `json:"config"`
	PluginPath string                 `json:"plugin_path,omitempty"`
}

type JWTConfig struct {
	Secret         string        `json:"secret"`
	PublicKey      string        `json:"public_key,omitempty"`
	Algorithm      string        `json:"algorithm"`
	Issuer         string        `json:"issuer,omitempty"`
	Audience       []string      `json:"audience,omitempty"`
	ClaimsRequired []string      `json:"claims_required,omitempty"`
}

type APIKeyConfig struct {
	HeaderName string `json:"header_name"`
	QueryParam string `json:"query_param"`
}

type OAuth2Config struct {
	IntrospectionURL string            `json:"introspection_url"`
	ClientID         string            `json:"client_id"`
	ClientSecret     string            `json:"client_secret"`
	TokenTypeHint    string            `json:"token_type_hint,omitempty"`
	Headers          map[string]string `json:"headers,omitempty"`
}

type CircuitBreakerPolicy struct {
	ID               string        `json:"id"`
	Name             string        `json:"name"`
	ErrorThreshold   float64       `json:"error_threshold"`
	RequestVolume    int64         `json:"request_volume"`
	SleepWindow      time.Duration `json:"sleep_window"`
	HalfOpenRequests int64         `json:"half_open_requests"`
	SuccessThreshold int64         `json:"success_threshold"`
	Timeout          time.Duration `json:"timeout"`
	FallbackResponse *FallbackResp `json:"fallback_response,omitempty"`
	Enabled          bool          `json:"enabled"`
}

type FallbackResp struct {
	StatusCode int               `json:"status_code"`
	Headers    map[string]string `json:"headers,omitempty"`
	Body       string            `json:"body"`
}

type CircuitBreakerState string

const (
	StateClosed     CircuitBreakerState = "closed"
	StateOpen       CircuitBreakerState = "open"
	StateHalfOpen   CircuitBreakerState = "half_open"
	StateDisabled   CircuitBreakerState = "disabled"
)

type HealthCheckConfig struct {
	Type            HealthCheckType `json:"type"`
	Interval        time.Duration   `json:"interval"`
	Timeout         time.Duration   `json:"timeout"`
	Path            string          `json:"path"`
	Method          string          `json:"method"`
	ExpectedStatus  []int           `json:"expected_status"`
	FailureThreshold int            `json:"failure_threshold"`
	SuccessThreshold int            `json:"success_threshold"`
	GRPCService     string          `json:"grpc_service,omitempty"`
}

type HealthCheckType string

const (
	HealthCheckHTTP HealthCheckType = "http"
	HealthCheckTCP  HealthCheckType = "tcp"
	HealthCheckGRPC HealthCheckType = "grpc"
)

type UpstreamNode struct {
	ID              string        `json:"id"`
	Address         string        `json:"address"`
	Weight          int           `json:"weight"`
	Healthy         bool          `json:"healthy"`
	LastCheck       time.Time     `json:"last_check"`
	FailCount       int           `json:"fail_count"`
	SuccessCount    int           `json:"success_count"`
	Protocol        ProtocolType  `json:"protocol"`
	Metadata        map[string]string `json:"metadata,omitempty"`
}

type UpstreamCluster struct {
	ID           string            `json:"id"`
	Name         string            `json:"name"`
	Nodes        []*UpstreamNode   `json:"nodes"`
	HealthCheck  *HealthCheckConfig `json:"health_check,omitempty"`
	LoadBalancer LoadBalanceType   `json:"load_balancer"`
	Protocol     ProtocolType      `json:"protocol"`
}

type LoadBalanceType string

const (
	LoadBalancerRoundRobin LoadBalanceType = "round_robin"
	LoadBalancerWeighted   LoadBalanceType = "weighted"
	LoadBalancerIPHash     LoadBalanceType = "ip_hash"
	LoadBalancerRandom     LoadBalanceType = "random"
	LoadBalancerLeastConn  LoadBalanceType = "least_conn"
)

type GrayPolicy struct {
	ID           string            `json:"id"`
	Name         string            `json:"name"`
	Type         GrayType          `json:"type"`
	Rules        []GrayRule        `json:"rules"`
	DefaultCluster string           `json:"default_cluster"`
	Enabled      bool              `json:"enabled"`
}

type GrayType string

const (
	GrayTypePercent  GrayType = "percent"
	GrayTypeHeader   GrayType = "header"
	GrayTypeCookie   GrayType = "cookie"
	GrayTypeQuery    GrayType = "query"
	GrayTypeUserID   GrayType = "user_id"
)

type GrayRule struct {
	TargetCluster string            `json:"target_cluster"`
	Percent       int               `json:"percent,omitempty"`
	HeaderMatch   map[string]string `json:"header_match,omitempty"`
	CookieMatch   map[string]string `json:"cookie_match,omitempty"`
	QueryMatch    map[string]string `json:"query_match,omitempty"`
	UserIDs       []string          `json:"user_ids,omitempty"`
	Priority      int               `json:"priority"`
}

type MirrorPolicy struct {
	ID             string        `json:"id"`
	Name           string        `json:"name"`
	TargetCluster  string        `json:"target_cluster"`
	Percent        int           `json:"percent"`
	Timeout        time.Duration `json:"timeout"`
	IncludeHeaders []string      `json:"include_headers,omitempty"`
	ExcludeHeaders []string      `json:"exclude_headers,omitempty"`
	Enabled        bool          `json:"enabled"`
}

type AuditLog struct {
	ID         string    `json:"id"`
	Timestamp  time.Time `json:"timestamp"`
	RequestID  string    `json:"request_id"`
	TraceID    string    `json:"trace_id"`
	UserID     string    `json:"user_id,omitempty"`
	APIKey     string    `json:"api_key,omitempty"`
	ClientIP   string    `json:"client_ip"`
	Method     string    `json:"method"`
	Path       string    `json:"path"`
	RouteID    string    `json:"route_id"`
	Upstream   string    `json:"upstream"`
	StatusCode int       `json:"status_code"`
	Duration   int64     `json:"duration_ms"`
	Error      string    `json:"error,omitempty"`
	RateLimited bool     `json:"rate_limited"`
	CircuitBroken bool   `json:"circuit_broken"`
	GrayVersion string   `json:"gray_version,omitempty"`
}

type GatewayContext struct {
	context.Context
	Request     *http.Request
	Response    http.ResponseWriter
	Route       *Route
	PathParams  map[string]string
	RequestID   string
	TraceID     string
	UserID      string
	ClientIP    string
	StartTime   time.Time
	Attributes  map[string]interface{}
}

func NewGatewayContext(w http.ResponseWriter, r *http.Request) *GatewayContext {
	return &GatewayContext{
		Context:    r.Context(),
		Request:    r,
		Response:   w,
		PathParams: make(map[string]string),
		StartTime:  time.Now(),
		Attributes: make(map[string]interface{}),
	}
}

func (g *GatewayContext) Set(key string, value interface{}) {
	g.Attributes[key] = value
}

func (g *GatewayContext) Get(key string) (interface{}, bool) {
	v, ok := g.Attributes[key]
	return v, ok
}

type Middleware interface {
	Name() string
	Handle(ctx *GatewayContext, next HandlerFunc) error
}

type HandlerFunc func(ctx *GatewayContext) error

type MiddlewareChain struct {
	middlewares []Middleware
}

func NewMiddlewareChain(middlewares ...Middleware) *MiddlewareChain {
	return &MiddlewareChain{middlewares: middlewares}
}

func (c *MiddlewareChain) Use(m Middleware) {
	c.middlewares = append(c.middlewares, m)
}

func (c *MiddlewareChain) Then(handler HandlerFunc) HandlerFunc {
	for i := len(c.middlewares) - 1; i >= 0; i-- {
		m := c.middlewares[i]
		next := handler
		handler = func(m Middleware, next HandlerFunc) HandlerFunc {
			return func(ctx *GatewayContext) error {
				return m.Handle(ctx, next)
			}
		}(m, next)
	}
	return handler
}
