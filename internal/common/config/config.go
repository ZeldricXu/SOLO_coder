package config

import (
	"fmt"
	"strings"

	"github.com/spf13/viper"
)

type Config struct {
	Server       ServerConfig       `mapstructure:"server"`
	Database     DatabaseConfig     `mapstructure:"database"`
	Redis        RedisConfig        `mapstructure:"redis"`
	Chains       map[string]ChainConfig `mapstructure:"chains"`
	IPFS         IPFSConfig         `mapstructure:"ipfs"`
	HDWallet     HDWalletConfig     `mapstructure:"hdwallet"`
	GasEstimator GasEstimatorConfig `mapstructure:"gas_estimator"`
	EventListener EventListenerConfig `mapstructure:"event_listener"`
	Indexer      IndexerConfig      `mapstructure:"indexer"`
	ZKP          ZKPConfig          `mapstructure:"zkp"`
	Metrics      MetricsConfig      `mapstructure:"metrics"`
}

type MetricsConfig struct {
	Enabled bool   `mapstructure:"enabled"`
	Port    int    `mapstructure:"port"`
	Path    string `mapstructure:"path"`
}

type ServerConfig struct {
	Port int    `mapstructure:"port"`
	Mode string `mapstructure:"mode"`
}

type DatabaseConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	User     string `mapstructure:"user"`
	Password string `mapstructure:"password"`
	DBName   string `mapstructure:"dbname"`
	SSLMode  string `mapstructure:"sslmode"`
}

type RedisConfig struct {
	Host     string `mapstructure:"host"`
	Port     int    `mapstructure:"port"`
	Password string `mapstructure:"password"`
	DB       int    `mapstructure:"db"`
}

type ChainConfig struct {
	RPCURL  string `mapstructure:"rpc_url"`
	ChainID uint64 `mapstructure:"chain_id"`
}

type IPFSConfig struct {
	APIURL     string `mapstructure:"api_url"`
	GatewayURL string `mapstructure:"gateway_url"`
}

type HDWalletConfig struct {
	Mnemonic             string `mapstructure:"mnemonic"`
	Password             string `mapstructure:"password"`
	DefaultDerivationPath string `mapstructure:"default_derivation_path"`
}

type GasEstimatorConfig struct {
	HistoryBlocks int     `mapstructure:"history_blocks"`
	Percentile    float64 `mapstructure:"percentile"`
	CacheTTL      int     `mapstructure:"cache_ttl"`
}

type EventListenerConfig struct {
	SyncInterval    int `mapstructure:"sync_interval"`
	MaxBlocksPerSync int `mapstructure:"max_blocks_per_sync"`
	RetryCount      int `mapstructure:"retry_count"`
}

type IndexerConfig struct {
	StartBlock       uint64 `mapstructure:"start_block"`
	BatchSize        int    `mapstructure:"batch_size"`
	ConcurrentWorkers int   `mapstructure:"concurrent_workers"`
}

type ZKPConfig struct {
	VerificationKeyPath string `mapstructure:"verification_key_path"`
	CircuitPath         string `mapstructure:"circuit_path"`
}

var AppConfig *Config

func Load(configPath string) error {
	v := viper.New()
	v.SetConfigFile(configPath)
	v.SetConfigType("yaml")
	v.SetEnvPrefix("BLOCKCHAIN_MW")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()

	if err := v.ReadInConfig(); err != nil {
		return fmt.Errorf("failed to read config file: %w", err)
	}

	AppConfig = &Config{}
	if err := v.Unmarshal(AppConfig); err != nil {
		return fmt.Errorf("failed to unmarshal config: %w", err)
	}

	return nil
}

func (d DatabaseConfig) DSN() string {
	return fmt.Sprintf("host=%s port=%d user=%s password=%s dbname=%s sslmode=%s",
		d.Host, d.Port, d.User, d.Password, d.DBName, d.SSLMode)
}

func (r RedisConfig) Addr() string {
	return fmt.Sprintf("%s:%d", r.Host, r.Port)
}
