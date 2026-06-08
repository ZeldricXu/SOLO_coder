package config

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

	consul "github.com/hashicorp/consul/api"
	"gopkg.in/yaml.v3"
)

type Config struct {
	mu           sync.RWMutex
	Server       ServerConfig       `yaml:"server"`
	Collectors   CollectorsConfig   `yaml:"collectors"`
	Pipeline     PipelineConfig     `yaml:"pipeline"`
	Detection    DetectionConfig    `yaml:"detection"`
	Correlation  CorrelationConfig  `yaml:"correlation"`
	Aggregation  AggregationConfig  `yaml:"aggregation"`
	Notification NotificationConfig `yaml:"notification"`
	Storage      StorageConfig      `yaml:"storage"`
	API          APIConfig          `yaml:"api"`
}

type ServerConfig struct {
	HTTPPort    int    `yaml:"http_port"`
	GRPCPort    int    `yaml:"grpc_port"`
	MetricsPort int    `yaml:"metrics_port"`
	Environment string `yaml:"environment"`
	LogLevel    string `yaml:"log_level"`
}

type CollectorsConfig struct {
	Elasticsearch []ElasticsearchConfig `yaml:"elasticsearch"`
	Loki          []LokiConfig          `yaml:"loki"`
	Kafka         []KafkaCollectorConfig `yaml:"kafka"`
	Syslog        []SyslogConfig        `yaml:"syslog"`
}

type ElasticsearchConfig struct {
	Enabled     bool          `yaml:"enabled"`
	Name        string        `yaml:"name"`
	Addresses   []string      `yaml:"addresses"`
	Username    string        `yaml:"username"`
	Password    string        `yaml:"password"`
	Index       string        `yaml:"index"`
	ScrollSize  int           `yaml:"scroll_size"`
	PollInterval time.Duration `yaml:"poll_interval"`
	Query       string        `yaml:"query"`
	TimeField   string        `yaml:"time_field"`
	LevelField  string        `yaml:"level_field"`
	MessageField string       `yaml:"message_field"`
}

type LokiConfig struct {
	Enabled      bool          `yaml:"enabled"`
	Name         string        `yaml:"name"`
	Address      string        `yaml:"address"`
	Username     string        `yaml:"username"`
	Password     string        `yaml:"password"`
	Query        string        `yaml:"query"`
	Range        time.Duration `yaml:"range"`
	PollInterval time.Duration `yaml:"poll_interval"`
	Step         time.Duration `yaml:"step"`
}

type KafkaCollectorConfig struct {
	Enabled   bool     `yaml:"enabled"`
	Name      string   `yaml:"name"`
	Brokers   []string `yaml:"brokers"`
	Topic     string   `yaml:"topic"`
	GroupID   string   `yaml:"group_id"`
	Partition int      `yaml:"partition"`
}

type SyslogConfig struct {
	Enabled  bool   `yaml:"enabled"`
	Name     string `yaml:"name"`
	BindAddr string `yaml:"bind_addr"`
	Port     int    `yaml:"port"`
	Protocol string `yaml:"protocol"`
}

type PipelineConfig struct {
	Rules        []PipelineRule `yaml:"rules"`
	HotReload    bool           `yaml:"hot_reload"`
	ReloadPath   string         `yaml:"reload_path"`
	WorkerCount  int            `yaml:"worker_count"`
	BufferSize   int            `yaml:"buffer_size"`
	ErrorHandler string         `yaml:"error_handler"`
}

type PipelineRule struct {
	ID          string                 `yaml:"id"`
	Name        string                 `yaml:"name"`
	Type        string                 `yaml:"type"`
	Enabled     bool                   `yaml:"enabled"`
	Condition   string                 `yaml:"condition"`
	ServiceName string                 `yaml:"service_name"`
	Level       string                 `yaml:"level"`
	Config      map[string]interface{} `yaml:"config"`
	Order       int                    `yaml:"order"`
}

type DetectionConfig struct {
	Algorithm   string                `yaml:"algorithm"`
	WindowSize  time.Duration         `yaml:"window_size"`
	SlideStep   time.Duration         `yaml:"slide_step"`
	Rules       []DetectionRule       `yaml:"rules"`
}

type DetectionRule struct {
	ID              string                 `yaml:"id"`
	Name            string                 `yaml:"name"`
	Enabled         bool                   `yaml:"enabled"`
	Type            string                 `yaml:"type"`
	ServiceName     string                 `yaml:"service_name"`
	Metric          string                 `yaml:"metric"`
	Threshold       float64                `yaml:"threshold"`
	WindowSize      time.Duration          `yaml:"window_size"`
	Algorithm       string                 `yaml:"algorithm"`
	Severity        string                 `yaml:"severity"`
	MinObservations int                    `yaml:"min_observations"`
	Config          map[string]interface{} `yaml:"config"`
}

type CorrelationConfig struct {
	TraceIDFields     []string      `yaml:"trace_id_fields"`
	Timeout           time.Duration `yaml:"timeout"`
	MaxEventChainSize int           `yaml:"max_event_chain_size"`
	BufferTTL         time.Duration `yaml:"buffer_ttl"`
}

type AggregationConfig struct {
	Enabled              bool          `yaml:"enabled"`
	GroupByFields        []string      `yaml:"group_by_fields"`
	TimeWindow           time.Duration `yaml:"time_window"`
	SuppressLowerPriority bool         `yaml:"suppress_lower_priority"`
	MaxIncidentSize      int           `yaml:"max_incident_size"`
	DedupKeyTemplate     string        `yaml:"dedup_key_template"`
}

type NotificationConfig struct {
	Enabled   bool                   `yaml:"enabled"`
	Channels  []NotificationChannel  `yaml:"channels"`
	Retry     RetryConfig            `yaml:"retry"`
	Templates map[string]string      `yaml:"templates"`
}

type NotificationChannel struct {
	Type     string                 `yaml:"type"`
	Name     string                 `yaml:"name"`
	Enabled  bool                   `yaml:"enabled"`
	Config   map[string]interface{} `yaml:"config"`
	Filter   NotificationFilter     `yaml:"filter"`
}

type NotificationFilter struct {
	MinSeverity string   `yaml:"min_severity"`
	Services    []string `yaml:"services"`
	AlertTypes  []string `yaml:"alert_types"`
}

type RetryConfig struct {
	MaxRetries  int           `yaml:"max_retries"`
	Backoff     time.Duration `yaml:"backoff"`
	Multiplier  float64       `yaml:"multiplier"`
}

type StorageConfig struct {
	ClickHouse ClickHouseConfig `yaml:"clickhouse"`
	Redis      RedisConfig      `yaml:"redis"`
	Kafka      KafkaConfig      `yaml:"kafka"`
}

type ClickHouseConfig struct {
	Addresses    []string `yaml:"addresses"`
	Database     string   `yaml:"database"`
	Username     string   `yaml:"username"`
	Password     string   `yaml:"password"`
	DialTimeout  int      `yaml:"dial_timeout"`
	ReadTimeout  int      `yaml:"read_timeout"`
	WriteTimeout int      `yaml:"write_timeout"`
	MaxOpenConns int      `yaml:"max_open_conns"`
	MaxIdleConns int      `yaml:"max_idle_conns"`
}

type RedisConfig struct {
	Address      string        `yaml:"address"`
	Password     string        `yaml:"password"`
	DB           int           `yaml:"db"`
	PoolSize     int           `yaml:"pool_size"`
	DialTimeout  time.Duration `yaml:"dial_timeout"`
	ReadTimeout  time.Duration `yaml:"read_timeout"`
	WriteTimeout time.Duration `yaml:"write_timeout"`
}

type KafkaConfig struct {
	Brokers   []string `yaml:"brokers"`
	Topic     string   `yaml:"topic"`
	Partition int      `yaml:"partition"`
}

type APIConfig struct {
	Enabled      bool   `yaml:"enabled"`
	HTTPPort     int    `yaml:"http_port"`
	BasePath     string `yaml:"base_path"`
	ReadTimeout  int    `yaml:"read_timeout"`
	WriteTimeout int    `yaml:"write_timeout"`
	CORS         CORSConfig `yaml:"cors"`
}

type CORSConfig struct {
	Enabled          bool     `yaml:"enabled"`
	AllowedOrigins   []string `yaml:"allowed_origins"`
	AllowedMethods   []string `yaml:"allowed_methods"`
	AllowedHeaders   []string `yaml:"allowed_headers"`
	AllowCredentials bool     `yaml:"allow_credentials"`
}

type ConfigChangeCallback func(*Config)

type configCacheEntry struct {
	cfg       *Config
	expiresAt time.Time
}

var (
	globalConfig *Config
	cacheEntry   *configCacheEntry
	cacheMu      sync.RWMutex
	callbacks    []ConfigChangeCallback
	callbacksMu  sync.RWMutex
	defaultTTL   = 5 * time.Second
)

func SetCacheTTL(ttl time.Duration) {
	cacheMu.Lock()
	defer cacheMu.Unlock()
	defaultTTL = ttl
}

func Load(configPath string) (*Config, error) {
	absPath, err := filepath.Abs(configPath)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve config path: %w", err)
	}

	data, err := os.ReadFile(absPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	cfg := &Config{}
	if err := yaml.Unmarshal(data, cfg); err != nil {
		return nil, fmt.Errorf("failed to parse config: %w", err)
	}

	updateGlobalConfig(cfg)
	return cfg, nil
}

func Get() *Config {
	cacheMu.RLock()
	entry := cacheEntry
	cacheMu.RUnlock()

	if entry != nil && time.Now().Before(entry.expiresAt) {
		return entry.cfg
	}

	cacheMu.Lock()
	defer cacheMu.Unlock()

	if cacheEntry != nil && time.Now().Before(cacheEntry.expiresAt) {
		return cacheEntry.cfg
	}

	if globalConfig != nil {
		cacheEntry = &configCacheEntry{
			cfg:       globalConfig,
			expiresAt: time.Now().Add(defaultTTL),
		}
	}
	return globalConfig
}

func InvalidateCache() {
	cacheMu.Lock()
	defer cacheMu.Unlock()
	cacheEntry = nil
}

func updateGlobalConfig(cfg *Config) {
	cacheMu.Lock()
	globalConfig = cfg
	cacheEntry = &configCacheEntry{
		cfg:       cfg,
		expiresAt: time.Now().Add(defaultTTL),
	}
	cacheMu.Unlock()
}

func (c *Config) Reload() error {
	cached := Get()
	if cached == nil {
		return fmt.Errorf("config not initialized")
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	*c = *cached
	notifyCallbacks(c)
	return nil
}

func RegisterCallback(callback ConfigChangeCallback) {
	callbacksMu.Lock()
	defer callbacksMu.Unlock()
	callbacks = append(callbacks, callback)
}

func notifyCallbacks(cfg *Config) {
	callbacksMu.RLock()
	defer callbacksMu.RUnlock()
	for _, cb := range callbacks {
		go cb(cfg)
	}
}

type ConsulWatcher struct {
	client    *consul.Client
	keyPrefix string
	stopCh    chan struct{}
}

func NewConsulWatcher(address, keyPrefix string) (*ConsulWatcher, error) {
	consulConfig := consul.DefaultConfig()
	consulConfig.Address = address

	client, err := consul.NewClient(consulConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to create consul client: %w", err)
	}

	return &ConsulWatcher{
		client:    client,
		keyPrefix: keyPrefix,
		stopCh:    make(chan struct{}),
	}, nil
}

func (w *ConsulWatcher) Start() error {
	go w.watchLoop()
	log.Printf("Consul watcher started, watching prefix: %s", w.keyPrefix)
	return nil
}

func (w *ConsulWatcher) Stop() {
	close(w.stopCh)
}

func (w *ConsulWatcher) watchLoop() {
	var lastIndex uint64

	for {
		select {
		case <-w.stopCh:
			return
		default:
		}

		kvPairs, meta, err := w.client.KV().List(w.keyPrefix, &consul.QueryOptions{
			WaitIndex: lastIndex,
			WaitTime:  30 * time.Second,
		})
		if err != nil {
			log.Printf("Consul watch error: %v", err)
			time.Sleep(5 * time.Second)
			continue
		}

		if meta.LastIndex == lastIndex {
			continue
		}
		lastIndex = meta.LastIndex

		for _, kv := range kvPairs {
			if err := w.handleConfigChange(kv.Key, kv.Value); err != nil {
				log.Printf("Failed to handle config change for key %s: %v", kv.Key, err)
			}
		}
	}
}

func (w *ConsulWatcher) handleConfigChange(key string, value []byte) error {
	log.Printf("Config changed: %s", key)

	newCfg := &Config{}
	if err := yaml.Unmarshal(value, newCfg); err != nil {
		return fmt.Errorf("failed to unmarshal new config: %w", err)
	}

	updateGlobalConfig(newCfg)
	notifyCallbacks(Get())
	return nil
}
