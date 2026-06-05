package config

import (
	"fmt"
	"os"

	"gopkg.in/yaml.v3"
)

type ServerConfig struct {
	Host         string `yaml:"host"`
	Port         int    `yaml:"port"`
	ReadTimeout  int    `yaml:"read_timeout"`
	WriteTimeout int    `yaml:"write_timeout"`
}

type DatabaseConfig struct {
	Host     string `yaml:"host"`
	Port     int    `yaml:"port"`
	User     string `yaml:"user"`
	Password string `yaml:"password"`
	DBName   string `yaml:"dbname"`
	SSLMode  string `yaml:"sslmode"`
}

type RedisConfig struct {
	Host     string `yaml:"host"`
	Port     int    `yaml:"port"`
	Password string `yaml:"password"`
	DB       int    `yaml:"db"`
	CacheTTL int    `yaml:"cache_ttl"`
}

type StorageConfig struct {
	DataDir   string `yaml:"data_dir"`
	TileDir   string `yaml:"tile_dir"`
	UploadDir string `yaml:"upload_dir"`
}

type OctreeConfig struct {
	MaxDepth         int `yaml:"max_depth"`
	MinPointsPerNode int `yaml:"min_points_per_node"`
	MaxPointsPerNode int `yaml:"max_points_per_node"`
	LODLevels        int `yaml:"lod_levels"`
}

type CollaborationConfig struct {
	PingInterval          int    `yaml:"ping_interval"`
	MaxConnectionsPerRoom int    `yaml:"max_connections_per_room"`
	ConflictResolution    string `yaml:"conflict_resolution"`
}

type Config struct {
	Server         ServerConfig         `yaml:"server"`
	Database       DatabaseConfig       `yaml:"database"`
	Redis          RedisConfig          `yaml:"redis"`
	Storage        StorageConfig        `yaml:"storage"`
	Octree         OctreeConfig         `yaml:"octree"`
	Collaboration  CollaborationConfig  `yaml:"collaboration"`
}

var AppConfig *Config

func Load(configPath string) error {
	data, err := os.ReadFile(configPath)
	if err != nil {
		return fmt.Errorf("failed to read config file: %w", err)
	}

	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return fmt.Errorf("failed to parse config file: %w", err)
	}

	AppConfig = &cfg

	if err := os.MkdirAll(cfg.Storage.DataDir, 0755); err != nil {
		return fmt.Errorf("failed to create data directory: %w", err)
	}
	if err := os.MkdirAll(cfg.Storage.TileDir, 0755); err != nil {
		return fmt.Errorf("failed to create tile directory: %w", err)
	}
	if err := os.MkdirAll(cfg.Storage.UploadDir, 0755); err != nil {
		return fmt.Errorf("failed to create upload directory: %w", err)
	}

	return nil
}

func (c *DatabaseConfig) DSN() string {
	return fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		c.Host, c.Port, c.User, c.Password, c.DBName, c.SSLMode)
}

func (c *RedisConfig) Addr() string {
	return fmt.Sprintf("%s:%d", c.Host, c.Port)
}

func (c *ServerConfig) Addr() string {
	return fmt.Sprintf("%s:%d", c.Host, c.Port)
}
