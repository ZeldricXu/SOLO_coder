package config

import (
	"fmt"
	"io/ioutil"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/joho/godotenv"
	"gopkg.in/yaml.v3"
)

type BuildInfo struct {
	Version   string
	Commit    string
	BuildTime string
	GoVersion string
}

var Info = BuildInfo{
	Version:   "dev",
	Commit:    "none",
	BuildTime: time.Now().Format(time.RFC3339),
	GoVersion: "runtime",
}

func (b BuildInfo) String() string {
	return fmt.Sprintf("version=%s commit=%s built=%s go=%s",
		b.Version, b.Commit, b.BuildTime, b.GoVersion)
}

type Config struct {
	Server   ServerConfig   `yaml:"server"`
	Mongo    MongoConfig    `yaml:"mongo"`
	Redis    RedisConfig    `yaml:"redis"`
	Game     GameConfig     `yaml:"game"`
	Match    MatchConfig    `yaml:"match"`
	Observer ObserverConfig `yaml:"observer"`
	LogLevel string         `yaml:"log_level"`
	Env      string         `yaml:"env"`
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
	DelaySec       int  `yaml:"delay_sec"`
	MaxObservers   int  `yaml:"max_observers"`
	GiftEnabled    bool `yaml:"gift_enabled"`
	DanmakuEnabled bool `yaml:"danmaku_enabled"`
}

func Load(path string) (*Config, error) {
	cfg := DefaultConfig()
	if path != "" {
		data, err := ioutil.ReadFile(path)
		if err == nil {
			if err := yaml.Unmarshal(data, cfg); err != nil {
				return nil, err
			}
		}
	}
	applyEnvOverrides(cfg)
	return cfg, nil
}

func LoadDotenv() (*Config, error) {
	_ = godotenv.Load()
	return Load(os.Getenv("CONFIG_FILE"))
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
			DelaySec:       5,
			MaxObservers:   100,
			GiftEnabled:    true,
			DanmakuEnabled: true,
		},
		LogLevel: "info",
		Env:      "development",
	}
}

func applyEnvOverrides(cfg *Config) {
	if v := os.Getenv("APP_ENV"); v != "" {
		cfg.Env = v
	}
	if v := os.Getenv("LOG_LEVEL"); v != "" {
		cfg.LogLevel = v
	}

	if v := os.Getenv("SERVER_PORT"); v != "" {
		cfg.Server.Port = envInt(v, cfg.Server.Port)
	}
	if v := os.Getenv("SERVER_HTTP_ADDR"); v != "" {
		cfg.Server.HTTPAddr = v
	}
	if v := os.Getenv("SERVER_WS_ADDR"); v != "" {
		cfg.Server.WSAddr = v
	}
	if v := os.Getenv("SERVER_READ_TIMEOUT"); v != "" {
		cfg.Server.ReadTimeout = envInt(v, cfg.Server.ReadTimeout)
	}
	if v := os.Getenv("SERVER_WRITE_TIMEOUT"); v != "" {
		cfg.Server.WriteTimeout = envInt(v, cfg.Server.WriteTimeout)
	}

	if v := os.Getenv("MONGO_URI"); v != "" {
		cfg.Mongo.URI = v
	}
	if v := os.Getenv("MONGO_DATABASE"); v != "" {
		cfg.Mongo.Database = v
	}
	if v := os.Getenv("MONGO_POOL_SIZE"); v != "" {
		cfg.Mongo.PoolSize = envInt(v, cfg.Mongo.PoolSize)
	}

	if v := os.Getenv("REDIS_ADDR"); v != "" {
		cfg.Redis.Addr = v
	}
	if v := os.Getenv("REDIS_PASSWORD"); v != "" {
		cfg.Redis.Password = v
	}
	if v := os.Getenv("REDIS_DB"); v != "" {
		cfg.Redis.DB = envInt(v, cfg.Redis.DB)
	}
	if v := os.Getenv("REDIS_POOL_SIZE"); v != "" {
		cfg.Redis.PoolSize = envInt(v, cfg.Redis.PoolSize)
	}

	if v := os.Getenv("GAME_TURN_TIMEOUT"); v != "" {
		cfg.Game.DefaultTurnTimeoutSec = envInt(v, cfg.Game.DefaultTurnTimeoutSec)
	}
	if v := os.Getenv("GAME_READY_TIMEOUT"); v != "" {
		cfg.Game.DefaultReadyTimeoutSec = envInt(v, cfg.Game.DefaultReadyTimeoutSec)
	}
	if v := os.Getenv("GAME_AUTOPLAY_DELAY"); v != "" {
		cfg.Game.AutoPlayDelayMs = envInt(v, cfg.Game.AutoPlayDelayMs)
	}
	if v := os.Getenv("GAME_TRUSTEE_DELAY"); v != "" {
		cfg.Game.TrusteeDelaySec = envInt(v, cfg.Game.TrusteeDelaySec)
	}

	if v := os.Getenv("MATCH_ELO_START"); v != "" {
		cfg.Match.EloRangeStart = envInt(v, cfg.Match.EloRangeStart)
	}
	if v := os.Getenv("MATCH_ELO_MAX"); v != "" {
		cfg.Match.EloRangeMax = envInt(v, cfg.Match.EloRangeMax)
	}
	if v := os.Getenv("MATCH_ELO_STEP"); v != "" {
		cfg.Match.EloRangeStep = envInt(v, cfg.Match.EloRangeStep)
	}
	if v := os.Getenv("MATCH_WAIT_STEP"); v != "" {
		cfg.Match.WaitStepMs = envInt(v, cfg.Match.WaitStepMs)
	}
	if v := os.Getenv("MATCH_MAX_WAIT"); v != "" {
		cfg.Match.MaxWaitMs = envInt(v, cfg.Match.MaxWaitMs)
	}
	if v := os.Getenv("MATCH_ROBOT_THRESHOLD"); v != "" {
		cfg.Match.RobotThresholdMs = envInt(v, cfg.Match.RobotThresholdMs)
	}

	if v := os.Getenv("OBSERVER_DELAY"); v != "" {
		cfg.Observer.DelaySec = envInt(v, cfg.Observer.DelaySec)
	}
	if v := os.Getenv("OBSERVER_MAX"); v != "" {
		cfg.Observer.MaxObservers = envInt(v, cfg.Observer.MaxObservers)
	}
	if v := os.Getenv("OBSERVER_GIFT_ENABLED"); v != "" {
		cfg.Observer.GiftEnabled = envBool(v)
	}
	if v := os.Getenv("OBSERVER_DANMAKU_ENABLED"); v != "" {
		cfg.Observer.DanmakuEnabled = envBool(v)
	}
}

func envInt(s string, def int) int {
	if n, err := strconv.Atoi(s); err == nil {
		return n
	}
	return def
}

func envBool(s string) bool {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "1", "true", "yes", "y", "on":
		return true
	default:
		return false
	}
}

func GetEnv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
