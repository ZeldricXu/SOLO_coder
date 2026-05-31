package config

import (
	"encoding/json"
	"os"
	"sync"
)

type ServerConfig struct {
	Host string `json:"host"`
	Port int    `json:"port"`
}

type DatabaseConfig struct {
	DSN string `json:"dsn"`
}

type RedisConfig struct {
	Addr     string `json:"addr"`
	Password string `json:"password"`
	DB       int    `json:"db"`
}

type LoggerConfig struct {
	Level       string   `json:"level"`
	Encoding    string   `json:"encoding"`
	OutputPaths []string `json:"output_paths"`
}

type RateLimitConfig struct {
	MaxConcurrent int `json:"max_concurrent"`
	QueueSize     int `json:"queue_size"`
}

type APIConfig struct {
	Timeout   int               `json:"timeout"`
	Retries   int               `json:"retries"`
	Routes    []RouteConfig     `json:"routes"`
	RateLimit RateLimitConfig   `json:"rate_limit"`
}

type RouteConfig struct {
	Path        string            `json:"path"`
	Method      string            `json:"method"`
	Protocol    string            `json:"protocol"`
	Target      string            `json:"target"`
	Transform   string            `json:"transform"`
	AuthRequired bool             `json:"auth_required"`
}

type TEEConfig struct {
	EnclaveType   string `json:"enclave_type"`
	AttestationURL string `json:"attestation_url"`
}

type MPCConfig struct {
	Protocol     string `json:"protocol"`
	ParticipantCount int `json:"participant_count"`
	Timeout      int    `json:"timeout"`
}

type FederatedConfig struct {
	MaxEpochs   int     `json:"max_epochs"`
	LearningRate float64 `json:"learning_rate"`
	Aggregation string  `json:"aggregation"`
}

type PrivacyConfig struct {
	Epsilon      float64 `json:"epsilon"`
	Delta        float64 `json:"delta"`
	Sensitivity  float64 `json:"sensitivity"`
	Mechanism    string  `json:"mechanism"`
}

type AppConfig struct {
	Server      ServerConfig      `json:"server"`
	Database    DatabaseConfig    `json:"database"`
	Redis       RedisConfig       `json:"redis"`
	Logger      LoggerConfig      `json:"logger"`
	API         APIConfig         `json:"api"`
	TEE         TEEConfig         `json:"tee"`
	MPC         MPCConfig         `json:"mpc"`
	Federated   FederatedConfig   `json:"federated"`
	Privacy     PrivacyConfig     `json:"privacy"`
}

var (
	instance *AppConfig
	once     sync.Once
)

func Load(path string) (*AppConfig, error) {
	var err error
	once.Do(func() {
		data, readErr := os.ReadFile(path)
		if readErr != nil {
			err = readErr
			return
		}
		instance = &AppConfig{}
		if parseErr := json.Unmarshal(data, instance); parseErr != nil {
			err = parseErr
			return
		}
	})
	return instance, err
}

func Get() *AppConfig {
	if instance == nil {
		return Default()
	}
	return instance
}

func Default() *AppConfig {
	return &AppConfig{
		Server: ServerConfig{
			Host: "0.0.0.0",
			Port: 8080,
		},
		Database: DatabaseConfig{
			DSN: "postgres://user:pass@localhost:5432/db?sslmode=disable",
		},
		Redis: RedisConfig{
			Addr: "localhost:6379",
			DB:   0,
		},
		Logger: LoggerConfig{
			Level:       "info",
			Encoding:    "json",
			OutputPaths: []string{"stdout"},
		},
		API: APIConfig{
			Timeout: 30,
			Retries: 3,
			RateLimit: RateLimitConfig{
				MaxConcurrent: 1000,
				QueueSize:     5000,
			},
		},
		TEE: TEEConfig{
			EnclaveType: "sgx",
		},
		MPC: MPCConfig{
			Protocol:         "spdz",
			ParticipantCount: 3,
			Timeout:          300,
		},
		Federated: FederatedConfig{
			MaxEpochs:   100,
			LearningRate: 0.01,
			Aggregation: "fedavg",
		},
		Privacy: PrivacyConfig{
			Epsilon:     1.0,
			Delta:       1e-5,
			Sensitivity: 1.0,
			Mechanism:   "laplace",
		},
	}
}
