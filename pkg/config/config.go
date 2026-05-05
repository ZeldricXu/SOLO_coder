package config

import (
	"os"
	"strconv"
	"time"
)

type Config struct {
	Server    ServerConfig
	MongoDB   MongoDBConfig
	Redis     RedisConfig
	JWT       JWTConfig
	WebSocket WebSocketConfig
	Game      GameConfig
}

type ServerConfig struct {
	Port string
	Mode string
}

type MongoDBConfig struct {
	URI        string
	Database   string
	Collection string
}

type RedisConfig struct {
	Addr     string
	Password string
	DB       int
}

type JWTConfig struct {
	Secret     string
	ExpireTime time.Duration
}

type WebSocketConfig struct {
	MaxMessageSize int64
	PingInterval   time.Duration
	PongWait       time.Duration
	WriteWait      time.Duration
}

type GameConfig struct {
	DefaultMapID  string
	StartPosition Position
	MaxHP         int
	BaseAttack    int
	BaseDefense   int
	MaxDistance   float64
}

type Position struct {
	X float64
	Y float64
}

func Load() *Config {
	return &Config{
		Server: ServerConfig{
			Port: getEnv("SERVER_PORT", "8080"),
			Mode: getEnv("SERVER_MODE", "development"),
		},
		MongoDB: MongoDBConfig{
			URI:        getEnv("MONGODB_URI", "mongodb://localhost:27017"),
			Database:   getEnv("MONGODB_DATABASE", "pixelrealm"),
			Collection: getEnv("MONGODB_COLLECTION", "players"),
		},
		Redis: RedisConfig{
			Addr:     getEnv("REDIS_ADDR", "localhost:6379"),
			Password: getEnv("REDIS_PASSWORD", ""),
			DB:       getEnvInt("REDIS_DB", 0),
		},
		JWT: JWTConfig{
			Secret:     getEnv("JWT_SECRET", "pixelrealm_secret_key_2026"),
			ExpireTime: time.Duration(getEnvInt("JWT_EXPIRE_HOURS", 24)) * time.Hour,
		},
		WebSocket: WebSocketConfig{
			MaxMessageSize: 4096,
			PingInterval:   30 * time.Second,
			PongWait:       60 * time.Second,
			WriteWait:      10 * time.Second,
		},
		Game: GameConfig{
			DefaultMapID: "forest_01",
			StartPosition: Position{
				X: 100.0,
				Y: 100.0,
			},
			MaxHP:       150,
			BaseAttack:  25,
			BaseDefense: 10,
			MaxDistance: 200.0,
		},
	}
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value := os.Getenv(key); value != "" {
		if intValue, err := strconv.Atoi(value); err == nil {
			return intValue
		}
	}
	return defaultValue
}
