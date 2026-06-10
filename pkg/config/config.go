package config

import (
	"io/ioutil"
	"os"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Server   ServerConfig   `yaml:"server"`
	Mongo    MongoConfig    `yaml:"mongo"`
	Redis    RedisConfig    `yaml:"redis"`
	Game     GameConfig     `yaml:"game"`
	Match    MatchConfig    `yaml:"match"`
	Observer ObserverConfig `yaml:"observer"`
}

type ServerConfig struct {
	Port         int    `yaml:"port"`
	HTTPAddr     string `yaml:"http_addr"`
	WSAddr       string `yaml:"ws_addr"`
	ReadTimeout  int    `yaml:"read_timeout"`
	WriteTimeout int    `yaml:"write_timeout"`
}

type MongoConfig struct {
	URI      string `yaml:"uri"`
	Database string `yaml:"database"`
	PoolSize int    `yaml:"pool_size"`
}

type RedisConfig struct {
	Addr     string `yaml:"addr"`
	Password string `yaml:"password"`
	DB       int    `yaml:"db"`
	PoolSize int    `yaml:"pool_size"`
}

type GameConfig struct {
	DefaultTurnTimeoutSec  int `yaml:"default_turn_timeout_sec"`
	DefaultReadyTimeoutSec int `yaml:"default_ready_timeout_sec"`
	AutoPlayDelayMs        int `yaml:"auto_play_delay_ms"`
	TrusteeDelaySec        int `yaml:"trustee_delay_sec"`
}

type MatchConfig struct {
	EloRangeStart    int `yaml:"elo_range_start"`
	EloRangeMax      int `yaml:"elo_range_max"`
	EloRangeStep     int `yaml:"elo_range_step"`
	WaitStepMs       int `yaml:"wait_step_ms"`
	MaxWaitMs        int `yaml:"max_wait_ms"`
	RobotThresholdMs int `yaml:"robot_threshold_ms"`
}

type ObserverConfig struct {
	DelaySec      int `yaml:"delay_sec"`
	MaxObservers  int `yaml:"max_observers"`
	GiftEnabled   bool `yaml:"gift_enabled"`
	DanmakuEnabled bool `yaml:"danmaku_enabled"`
}

func Load(path string) (*Config, error) {
	data, err := ioutil.ReadFile(path)
	if err != nil {
		return DefaultConfig(), nil
	}
	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	return &cfg, nil
}

func DefaultConfig() *Config {
	return &Config{
		Server: ServerConfig{
			Port:         8080,
			HTTPAddr:     ":8080",
			WSAddr:       ":8081",
			ReadTimeout:  30,
			WriteTimeout: 30,
		},
		Mongo: MongoConfig{
			URI:      "mongodb://localhost:27017",
			Database: "gameroom",
			PoolSize: 100,
		},
		Redis: RedisConfig{
			Addr:     "localhost:6379",
			Password: "",
			DB:       0,
			PoolSize: 100,
		},
		Game: GameConfig{
			DefaultTurnTimeoutSec:  15,
			DefaultReadyTimeoutSec: 60,
			AutoPlayDelayMs:        500,
			TrusteeDelaySec:        30,
		},
		Match: MatchConfig{
			EloRangeStart:    50,
			EloRangeMax:      500,
			EloRangeStep:     50,
			WaitStepMs:       3000,
			MaxWaitMs:        60000,
			RobotThresholdMs: 20000,
		},
		Observer: ObserverConfig{
			DelaySec:        5,
			MaxObservers:    100,
			GiftEnabled:     true,
			DanmakuEnabled:  true,
		},
	}
}

func GetEnv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
