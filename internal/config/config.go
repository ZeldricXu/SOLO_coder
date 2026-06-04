package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/viper"
)

type EnvConfig struct {
	BaseURL   string            `mapstructure:"base_url"`
	Token     string            `mapstructure:"token"`
	Headers   map[string]string `mapstructure:"headers"`
	Variables map[string]string `mapstructure:"variables"`
}

type Settings struct {
	Timeout       int    `mapstructure:"timeout"`
	TLSSkipVerify bool   `mapstructure:"tls_skip_verify"`
	OutputFormat  string `mapstructure:"output_format"`
}

type Config struct {
	DefaultEnv   string              `mapstructure:"default_env"`
	Environments map[string]EnvConfig `mapstructure:"environments"`
	Variables    map[string]string    `mapstructure:"variables"`
	Settings     Settings             `mapstructure:"settings"`
}

func Load(configPath string) (*Config, error) {
	v := viper.New()

	v.SetEnvPrefix("APICALL")
	v.SetEnvKeyReplacer(strings.NewReplacer(".", "_"))
	v.AutomaticEnv()

	if configPath != "" {
		v.SetConfigFile(configPath)
	} else {
		configDir := os.Getenv("XDG_CONFIG_HOME")
		if configDir == "" {
			home, err := os.UserHomeDir()
			if err != nil {
				configDir = filepath.Join(home, ".config")
			} else {
				configDir = filepath.Join(os.Getenv("HOME"), ".config")
			}
		}
		v.SetConfigName("config")
		v.SetConfigType("yaml")
		v.AddConfigPath(filepath.Join(configDir, "htest"))
		v.AddConfigPath(".")
		v.AddConfigPath("$HOME")
	}

	v.SetDefault("default_env", "dev")
	v.SetDefault("settings.timeout", 30)
	v.SetDefault("settings.tls_skip_verify", false)
	v.SetDefault("settings.output_format", "pretty")

	v.BindEnv("default_env", "APICALL_ENV")

	if err := v.ReadInConfig(); err != nil {
		if _, ok := err.(viper.ConfigFileNotFoundError); ok {
			cfg := DefaultConfig()
			applyEnvOverrides(cfg)
			return cfg, nil
		}
		if os.IsNotExist(err) {
			cfg := DefaultConfig()
			applyEnvOverrides(cfg)
			return cfg, nil
		}
		return nil, fmt.Errorf("error reading config file: %w", err)
	}

	var cfg Config
	if err := v.Unmarshal(&cfg); err != nil {
		return nil, fmt.Errorf("error unmarshaling config: %w", err)
	}

	if cfg.Settings.Timeout == 0 {
		cfg.Settings.Timeout = 30
	}
	if cfg.Settings.OutputFormat == "" {
		cfg.Settings.OutputFormat = "pretty"
	}

	applyEnvOverrides(&cfg)

	return &cfg, nil
}

func applyEnvOverrides(cfg *Config) {
	if env := os.Getenv("APICALL_ENV"); env != "" {
		cfg.DefaultEnv = env
	}
	if env := os.Getenv("APICALL_TOKEN"); env != "" {
		if envCfg, ok := cfg.Environments[cfg.DefaultEnv]; ok {
			envCfg.Token = env
			cfg.Environments[cfg.DefaultEnv] = envCfg
		}
	}
}

func Save(cfg *Config, configPath string) error {
	v := viper.New()

	if configPath == "" {
		configPath = ".htest.yaml"
	}

	ext := filepath.Ext(configPath)
	if ext == "" {
		ext = ".yaml"
		configPath = configPath + ext
	}

	v.SetConfigFile(configPath)

	v.Set("default_env", cfg.DefaultEnv)
	v.Set("environments", cfg.Environments)
	v.Set("variables", cfg.Variables)
	v.Set("settings", cfg.Settings)

	dir := filepath.Dir(configPath)
	if dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0755); err != nil {
			return fmt.Errorf("error creating config directory: %w", err)
		}
	}

	if err := v.WriteConfigAs(configPath); err != nil {
		return fmt.Errorf("error writing config file: %w", err)
	}

	return nil
}

func DefaultConfig() *Config {
	return &Config{
		DefaultEnv: "dev",
		Settings: Settings{
			Timeout:       30,
			TLSSkipVerify: false,
			OutputFormat:  "pretty",
		},
		Environments: map[string]EnvConfig{
			"dev": {
				BaseURL:   "http://localhost:8080",
				Headers:   map[string]string{},
				Variables: map[string]string{},
			},
			"staging": {
				BaseURL:   "https://staging.api.example.com",
				Headers:   map[string]string{},
				Variables: map[string]string{},
			},
			"prod": {
				BaseURL:   "https://api.example.com",
				Headers:   map[string]string{},
				Variables: map[string]string{},
			},
		},
		Variables: map[string]string{},
	}
}
