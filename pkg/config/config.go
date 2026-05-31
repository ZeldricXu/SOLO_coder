package config

import (
	"encoding/json"
	"os"
	"sync"
)

type Config struct {
	Server    ServerConfig    `json:"server"`
	Chains    []ChainConfig   `json:"chains"`
	Database  DatabaseConfig  `json:"database"`
	Redis     RedisConfig     `json:"redis"`
	Storage   StorageConfig   `json:"storage"`
	Multisig  MultisigConfig  `json:"multisig"`
	Logger    LoggerConfig    `json:"logger"`
}

type ServerConfig struct {
	Port         int    `json:"port"`
	ReadTimeout  int    `json:"read_timeout"`
	WriteTimeout int    `json:"write_timeout"`
	Mode         string `json:"mode"`
}

type ChainConfig struct {
	Name         string   `json:"name"`
	ChainID      int64    `json:"chain_id"`
	RPCURLs      []string `json:"rpc_urls"`
	WSURL        string   `json:"ws_url,omitempty"`
	ExplorerURL  string   `json:"explorer_url,omitempty"`
	NativeToken  string   `json:"native_token"`
	Confirmations int    `json:"confirmations"`
}

type DatabaseConfig struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	User     string `json:"user"`
	Password string `json:"password"`
	DBName   string `json:"dbname"`
	SSLMode  string `json:"ssl_mode"`
}

type RedisConfig struct {
	Address  string `json:"address"`
	Password string `json:"password"`
	DB       int    `json:"db"`
}

type StorageConfig struct {
	IPFSEndpoints []string `json:"ipfs_endpoints"`
	ArweaveNode   string   `json:"arweave_node,omitempty"`
	Timeout       int      `json:"timeout"`
}

type MultisigConfig struct {
	Threshold     int      `json:"threshold"`
	TotalSigners  int      `json:"total_signers"`
	Signers       []string `json:"signers"`
}

type LoggerConfig struct {
	Level  string `json:"level"`
	Format string `json:"format"`
}

var (
	instance *Config
	once     sync.Once
)

func Load(configPath string) (*Config, error) {
	var err error
	once.Do(func() {
		data, readErr := os.ReadFile(configPath)
		if readErr != nil {
			err = readErr
			return
		}
		
		cfg := &Config{
			Server: ServerConfig{
				Port:         8080,
				ReadTimeout:  30,
				WriteTimeout: 30,
				Mode:         "release",
			},
			Logger: LoggerConfig{
				Level:  "info",
				Format: "json",
			},
		}
		
		if parseErr := json.Unmarshal(data, cfg); parseErr != nil {
			err = parseErr
			return
		}
		
		instance = cfg
	})
	
	return instance, err
}

func Get() *Config {
	if instance == nil {
		return &Config{}
	}
	return instance
}
