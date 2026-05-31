package config

import (
	"fmt"
	"github.com/spf13/viper"
	"strings"
)

type DatabaseConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	User     string `mapstructure:"user"`
	Password string `mapstructure:"password"`
	DBName   string `mapstructure:"dbname"`
	SSLMode  string `mapstructure:"sslmode"`
}

type RedisConfig struct {
	Addr     string `mapstructure:"addr"`
	Password string `mapstructure:"password"`
	DB       int    `mapstructure:"db"`
}

type ServerConfig struct {
	Host string `mapstructure:"host"`
	Port int    `mapstructure:"port"`
}

type ChainConfig struct {
	Name      string `mapstructure:"name"`
	ChainID   int64  `mapstructure:"chain_id"`
	RPCURL    string `mapstructure:"rpc_url"`
	WSURL     string `mapstructure:"ws_url"`
	Explorer  string `mapstructure:"explorer"`
}

type AppConfig struct {
	Env      string            `mapstructure:"env"`
	Server   ServerConfig      `mapstructure:"server"`
	Database DatabaseConfig    `mapstructure:"database"`
	Redis    RedisConfig       `mapstructure:"redis"`
	Chains   []ChainConfig     `mapstructure:"chains"`
	Logger   map[string]interface{} `mapstructure:"logger"`
}

func Load(path string) (*AppConfig, error) {
	v := viper.New()
	v.SetConfigFile(path)
	v.SetConfigType("yaml")
	v.AutomaticEnv()
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))

	if err := v.ReadInConfig(); err != nil {
		return nil, fmt.Errorf("read config failed: %w", err)
	}

	var cfg AppConfig
	if err := v.Unmarshal(&cfg); err != nil {
		return nil, fmt.Errorf("unmarshal config failed: %w", err)
	}

	return &cfg, nil
}

func (c *AppConfig) DSN() string {
	return fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		c.Database.Host, c.Database.Port, c.Database.User,
		c.Database.Password, c.Database.DBName, c.Database.SSLMode)
}
