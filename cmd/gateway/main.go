package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"go.uber.org/zap"
	"gopkg.in/yaml.v3"

	"DF1-56/internal/gateway"
	"DF1-56/internal/storage"
	"DF1-56/internal/telemetry"
)

type AppConfig struct {
	Server struct {
		Host         string `yaml:"host"`
		Port         int    `yaml:"port"`
		ReadTimeout  string `yaml:"read_timeout"`
		WriteTimeout string `yaml:"write_timeout"`
		IdleTimeout  string `yaml:"idle_timeout"`
		MaxHeaderBytes int   `yaml:"max_header_bytes"`
	} `yaml:"server"`

	Admin struct {
		Host         string `yaml:"host"`
		Port         int    `yaml:"port"`
		ReadTimeout  string `yaml:"read_timeout"`
		WriteTimeout string `yaml:"write_timeout"`
	} `yaml:"admin"`

	ETCD struct {
		Endpoints []string `yaml:"endpoints"`
		Username  string   `yaml:"username"`
		Password  string   `yaml:"password"`
	} `yaml:"etcd"`

	Redis struct {
		Address    string `yaml:"address"`
		Password   string `yaml:"password"`
		DB         int    `yaml:"db"`
		PoolSize   int    `yaml:"pool_size"`
		MaxRetries int    `yaml:"max_retries"`
		Timeout    string `yaml:"timeout"`
	} `yaml:"redis"`

	PostgreSQL struct {
		Host            string `yaml:"host"`
		Port            int    `yaml:"port"`
		User            string `yaml:"user"`
		Password        string `yaml:"password"`
		DBName          string `yaml:"dbname"`
		SSLMode         string `yaml:"sslmode"`
		MaxOpenConns    int    `yaml:"max_open_conns"`
		MaxIdleConns    int    `yaml:"max_idle_conns"`
		ConnMaxLifetime string `yaml:"conn_max_lifetime"`
		Timeout         string `yaml:"timeout"`
	} `yaml:"postgres"`

	Telemetry struct {
		ServiceName     string  `yaml:"service_name"`
		ServiceVersion  string  `yaml:"service_version"`
		OTLPEndpoint    string  `yaml:"otlp_endpoint"`
		OTLPInsecure    bool    `yaml:"otlp_insecure"`
		TraceSampleRate float64 `yaml:"trace_sample_rate"`
		MetricsEnabled  bool    `yaml:"metrics_enabled"`
		TracingEnabled  bool    `yaml:"tracing_enabled"`
		PrometheusPort  int     `yaml:"prometheus_port"`
	} `yaml:"telemetry"`

	APIKeys map[string]string `yaml:"api_keys"`

	EnabledMiddlewares []string `yaml:"enabled_middlewares"`

	Routes          map[string]RouteConfig `yaml:"routes"`
	RateLimits      map[string]RateLimitConfig `yaml:"rate_limits"`
	Auths           map[string]AuthConfig `yaml:"auths"`
	CircuitBreakers map[string]CircuitBreakerConfig `yaml:"circuit_breakers"`
	Grays           map[string]GrayConfig `yaml:"grays"`
	Mirrors         map[string]MirrorConfig `yaml:"mirrors"`
	Upstreams       map[string]UpstreamConfig `yaml:"upstreams"`
}

type RouteConfig struct {
	ID              string            `yaml:"id"`
	Path            string            `yaml:"path"`
	Method          string            `yaml:"method"`
	MatchType       string            `yaml:"match_type"`
	RegexPattern    string            `yaml:"regex_pattern,omitempty"`
	UpstreamURL     string            `yaml:"upstream_url"`
	UpstreamCluster string           `yaml:"upstream_cluster"`
	RewritePath     string            `yaml:"rewrite_path,omitempty"`
	Protocol        string            `yaml:"protocol"`
	Timeout         string            `yaml:"timeout"`
	RetryCount      int               `yaml:"retry_count"`
	Middlewares     []string          `yaml:"middlewares"`
	RateLimitPolicy string            `yaml:"rate_limit_policy,omitempty"`
	AuthPolicy      string            `yaml:"auth_policy,omitempty"`
	CircuitBreaker  string            `yaml:"circuit_breaker,omitempty"`
	GrayPolicy      string            `yaml:"gray_policy,omitempty"`
	MirrorPolicy    string            `yaml:"mirror_policy,omitempty"`
	Headers         map[string]string `yaml:"headers,omitempty"`
	Enabled         bool              `yaml:"enabled"`
}

type RateLimitConfig struct {
	ID         string                 `yaml:"id"`
	Name       string                 `yaml:"name"`
	Rules      []RateLimitRuleConfig  `yaml:"rules"`
	Algorithm  string                 `yaml:"algorithm"`
	KeyBuilder RateLimitKeyBuilderConfig `yaml:"key_builder"`
	Enabled    bool                   `yaml:"enabled"`
}

type RateLimitRuleConfig struct {
	Dimension  string `yaml:"dimension"`
	Limit       int64  `yaml:"limit"`
	Window      string `yaml:"window"`
	Burst       int64  `yaml:"burst,omitempty"`
	Capacity    int64  `yaml:"capacity,omitempty"`
	RefillRate  int64  `yaml:"refill_rate,omitempty"`
	CustomKey   string `yaml:"custom_key,omitempty"`
}

type RateLimitKeyBuilderConfig struct {
	IncludeAPI    bool     `yaml:"include_api"`
	IncludeUser   bool     `yaml:"include_user"`
	IncludeIP     bool     `yaml:"include_ip"`
	CustomHeaders []string `yaml:"custom_headers,omitempty"`
}

type AuthConfig struct {
	ID             string               `yaml:"id"`
	Name           string               `yaml:"name"`
	Strategies      []AuthStrategyConfig `yaml:"strategies"`
	AllowAnonymous bool               `yaml:"allow_anonymous"`
	TokenTTL       string               `yaml:"token_ttl"`
	Enabled        bool               `yaml:"enabled"`
}

type AuthStrategyConfig struct {
	Type     string           `yaml:"type"`
	Config   AuthStrategyConfigData `yaml:"config"`
	Optional bool             `yaml:"optional"`
	Priority int              `yaml:"priority"`
}

type AuthStrategyConfigData struct {
	JWTConfig    *JWTConfig    `yaml:"jwt_config,omitempty"`
	APIKeyConfig *APIKeyConfig `yaml:"api_key_config,omitempty"`
	OAuth2Config *OAuth2Config `yaml:"oauth2_config,omitempty"`
}

type JWTConfig struct {
	Secret         string   `yaml:"secret"`
	PublicKey      string   `yaml:"public_key,omitempty"`
	Algorithm      string   `yaml:"algorithm"`
	Issuer         string   `yaml:"issuer,omitempty"`
	Audience       []string `yaml:"audience,omitempty"`
	ClaimsRequired []string `yaml:"claims_required,omitempty"`
}

type APIKeyConfig struct {
	HeaderName string `yaml:"header_name"`
	QueryParam string `yaml:"query_param"`
}

type OAuth2Config struct {
	IntrospectionURL string            `yaml:"introspection_url"`
	ClientID         string            `yaml:"client_id"`
	ClientSecret     string            `yaml:"client_secret"`
	TokenTypeHint    string            `yaml:"token_type_hint,omitempty"`
	Headers          map[string]string `yaml:"headers,omitempty"`
}

type CircuitBreakerConfig struct {
	ID               string `yaml:"id"`
	Name             string `yaml:"name"`
	ErrorThreshold   float64 `yaml:"error_threshold"`
	RequestVolume    int64   `yaml:"request_volume"`
	SleepWindow      string  `yaml:"sleep_window"`
	HalfOpenRequests int64   `yaml:"half_open_requests"`
	SuccessThreshold int64   `yaml:"success_threshold"`
	Timeout          string  `yaml:"timeout"`
	FallbackResponse *FallbackResponseConfig `yaml:"fallback_response,omitempty"`
	Enabled          bool    `yaml:"enabled"`
}

type FallbackResponseConfig struct {
	StatusCode int               `yaml:"status_code"`
	Headers    map[string]string `yaml:"headers,omitempty"`
	Body       string          `yaml:"body"`
}

type GrayConfig struct {
	ID             string           `yaml:"id"`
	Name           string           `yaml:"name"`
	Type           string           `yaml:"type"`
	Rules          []GrayRuleConfig `yaml:"rules"`
	DefaultCluster string           `yaml:"default_cluster"`
	Enabled        bool             `yaml:"enabled"`
}

type GrayRuleConfig struct {
	TargetCluster string            `yaml:"target_cluster"`
	Percent       int               `yaml:"percent,omitempty"`
	HeaderMatch   map[string]string `yaml:"header_match,omitempty"`
	CookieMatch   map[string]string `yaml:"cookie_match,omitempty"`
	QueryMatch    map[string]string `yaml:"query_match,omitempty"`
	UserIDs       []string          `yaml:"user_ids,omitempty"`
	Priority      int               `yaml:"priority"`
}

type MirrorConfig struct {
	ID             string   `yaml:"id"`
	Name           string   `yaml:"name"`
	TargetCluster  string   `yaml:"target_cluster"`
	Percent        int      `yaml:"percent"`
	Timeout        string   `yaml:"timeout"`
	IncludeHeaders []string `yaml:"include_headers,omitempty"`
	ExcludeHeaders []string `yaml:"exclude_headers,omitempty"`
	Enabled        bool     `yaml:"enabled"`
}

type UpstreamConfig struct {
	ID           string                 `yaml:"id"`
	Name         string                 `yaml:"name"`
	Nodes        []UpstreamNodeConfig   `yaml:"nodes"`
	HealthCheck  *HealthCheckConfig    `yaml:"health_check,omitempty"`
	LoadBalancer string                 `yaml:"load_balancer"`
	Protocol     string                 `yaml:"protocol"`
}

type UpstreamNodeConfig struct {
	ID       string            `yaml:"id"`
	Address  string            `yaml:"address"`
	Weight   int               `yaml:"weight"`
	Healthy  bool              `yaml:"healthy"`
	Protocol string            `yaml:"protocol"`
	Metadata map[string]string `yaml:"metadata,omitempty"`
}

type HealthCheckConfig struct {
	Type             string   `yaml:"type"`
	Interval         string   `yaml:"interval"`
	Timeout          string   `yaml:"timeout"`
	Path             string   `yaml:"path"`
	Method           string   `yaml:"method"`
	ExpectedStatus   []int    `yaml:"expected_status"`
	FailureThreshold int      `yaml:"failure_threshold"`
	SuccessThreshold int      `yaml:"success_threshold"`
	GRPCService      string   `yaml:"grpc_service,omitempty"`
}

func main() {
	configPath := flag.String("config", "configs/config.yaml", "Path to configuration file")
	flag.Parse()

	absConfigPath, err := filepath.Abs(*configPath)
	if err != nil {
		log.Fatalf("Failed to resolve config path: %v", err)
	}

	appConfig, err := loadConfig(absConfigPath)
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	logger, err := zap.NewProduction()
	if err != nil {
		log.Fatalf("Failed to create logger: %v", err)
	}
	defer logger.Sync()

	cfg, err := buildGatewayConfig(appConfig)
	if err != nil {
		logger.Fatal("Failed to build gateway config", zap.Error(err))
	}

	gw, err := gateway.NewGateway(cfg)
	if err != nil {
		logger.Fatal("Failed to create gateway", zap.Error(err))
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		sig := <-sigCh
		logger.Info("Received signal", zap.String("signal", sig.String()))

		_, shutdownCancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer shutdownCancel()

		if err := gw.Stop(); err != nil {
			logger.Error("Gateway shutdown error", zap.Error(err))
		}

		os.Exit(0)
	}()

	if err := gw.Start(); err != nil {
		logger.Fatal("Failed to start gateway", zap.Error(err))
	}

	select {}
}

func loadConfig(path string) (*AppConfig, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	var cfg AppConfig
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("failed to parse config file: %w", err)
	}

	return &cfg, nil
}

func buildGatewayConfig(appCfg *AppConfig) (*gateway.Config, error) {
	cfg := &gateway.Config{
		ValidAPIKeys:      appCfg.APIKeys,
		EnabledMiddlewares: appCfg.EnabledMiddlewares,
	}

	cfg.Server.Host = appCfg.Server.Host
	cfg.Server.Port = appCfg.Server.Port
	if appCfg.Server.ReadTimeout != "" {
		d, err := time.ParseDuration(appCfg.Server.ReadTimeout)
		if err != nil {
			return nil, fmt.Errorf("invalid server read_timeout: %w", err)
		}
		cfg.Server.ReadTimeout = d
	}
	if appCfg.Server.WriteTimeout != "" {
		d, err := time.ParseDuration(appCfg.Server.WriteTimeout)
		if err != nil {
			return nil, fmt.Errorf("invalid server write_timeout: %w", err)
		}
		cfg.Server.WriteTimeout = d
	}
	if appCfg.Server.IdleTimeout != "" {
		d, err := time.ParseDuration(appCfg.Server.IdleTimeout)
		if err != nil {
			return nil, fmt.Errorf("invalid server idle_timeout: %w", err)
		}
		cfg.Server.IdleTimeout = d
	}
	cfg.Server.MaxHeaderBytes = appCfg.Server.MaxHeaderBytes

	cfg.Admin.Host = appCfg.Admin.Host
	cfg.Admin.Port = appCfg.Admin.Port
	if appCfg.Admin.ReadTimeout != "" {
		d, err := time.ParseDuration(appCfg.Admin.ReadTimeout)
		if err != nil {
			return nil, fmt.Errorf("invalid admin read_timeout: %w", err)
		}
		cfg.Admin.ReadTimeout = d
	}
	if appCfg.Admin.WriteTimeout != "" {
		d, err := time.ParseDuration(appCfg.Admin.WriteTimeout)
		if err != nil {
			return nil, fmt.Errorf("invalid admin write_timeout: %w", err)
		}
		cfg.Admin.WriteTimeout = d
	}

	cfg.ETCD.Endpoints = appCfg.ETCD.Endpoints
	cfg.ETCD.Username = appCfg.ETCD.Username
	cfg.ETCD.Password = appCfg.ETCD.Password

	cfg.Redis = storage.RedisConfig{
		Address:    appCfg.Redis.Address,
		Password:   appCfg.Redis.Password,
		DB:         appCfg.Redis.DB,
		PoolSize:   appCfg.Redis.PoolSize,
		MaxRetries: appCfg.Redis.MaxRetries,
	}
	if appCfg.Redis.Timeout != "" {
		d, err := time.ParseDuration(appCfg.Redis.Timeout)
		if err != nil {
			return nil, fmt.Errorf("invalid redis timeout: %w", err)
		}
		cfg.Redis.Timeout = d
	}

	cfg.PostgreSQL = storage.PostgresConfig{
		Host:            appCfg.PostgreSQL.Host,
		Port:            appCfg.PostgreSQL.Port,
		User:            appCfg.PostgreSQL.User,
		Password:        appCfg.PostgreSQL.Password,
		DBName:          appCfg.PostgreSQL.DBName,
		SSLMode:         appCfg.PostgreSQL.SSLMode,
		MaxOpenConns:    appCfg.PostgreSQL.MaxOpenConns,
		MaxIdleConns:    appCfg.PostgreSQL.MaxIdleConns,
	}
	if appCfg.PostgreSQL.ConnMaxLifetime != "" {
		d, err := time.ParseDuration(appCfg.PostgreSQL.ConnMaxLifetime)
		if err != nil {
			return nil, fmt.Errorf("invalid postgres conn_max_lifetime: %w", err)
		}
		cfg.PostgreSQL.ConnMaxLifetime = d
	}
	if appCfg.PostgreSQL.Timeout != "" {
		d, err := time.ParseDuration(appCfg.PostgreSQL.Timeout)
		if err != nil {
			return nil, fmt.Errorf("invalid postgres timeout: %w", err)
		}
		cfg.PostgreSQL.Timeout = d
	}

	cfg.Telemetry = telemetry.Config{
		ServiceName:     appCfg.Telemetry.ServiceName,
		ServiceVersion:  appCfg.Telemetry.ServiceVersion,
		OTLPEndpoint:    appCfg.Telemetry.OTLPEndpoint,
		OTLPInsecure:    appCfg.Telemetry.OTLPInsecure,
		TraceSampleRate: appCfg.Telemetry.TraceSampleRate,
		MetricsEnabled:  appCfg.Telemetry.MetricsEnabled,
		TracingEnabled:  appCfg.Telemetry.TracingEnabled,
		PrometheusPort:  appCfg.Telemetry.PrometheusPort,
	}

	return cfg, nil
}
