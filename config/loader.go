package config

import (
	"encoding/json"
	"log"
	"os"
	"strings"
)

func LoadFromFile(filename string) (*Config, error) {
	data, err := os.ReadFile(filename)
	if err != nil {
		return nil, err
	}

	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}

	applyDefaults(&cfg)
	return &cfg, nil
}

func applyDefaults(cfg *Config) {
	defaults := Load()

	if cfg.Server.Address == "" {
		cfg.Server.Address = defaults.Server.Address
	}
	if cfg.Server.Port == 0 {
		cfg.Server.Port = defaults.Server.Port
	}

	if cfg.Session.DefaultTTL == 0 {
		cfg.Session.DefaultTTL = defaults.Session.DefaultTTL
	}
	if cfg.Session.ExtendTTL == 0 {
		cfg.Session.ExtendTTL = defaults.Session.ExtendTTL
	}

	if cfg.Password.MinLength == 0 {
		cfg.Password.MinLength = defaults.Password.MinLength
	}
	if cfg.Password.Cost == 0 {
		cfg.Password.Cost = defaults.Password.Cost
	}

	if cfg.MFA.CodeLength == 0 {
		cfg.MFA.CodeLength = defaults.MFA.CodeLength
	}
	if cfg.MFA.ExpireDuration == 0 {
		cfg.MFA.ExpireDuration = defaults.MFA.ExpireDuration
	}

	if cfg.Redis.Address == "" {
		cfg.Redis.Address = defaults.Redis.Address
	}
	if cfg.Redis.AuditQueue == "" {
		cfg.Redis.AuditQueue = defaults.Redis.AuditQueue
	}
	if cfg.Redis.MaxRetries == 0 {
		cfg.Redis.MaxRetries = defaults.Redis.MaxRetries
	}

	if cfg.Permissions.ConfigFile == "" {
		cfg.Permissions.ConfigFile = defaults.Permissions.ConfigFile
	}
}

func LoadWithFallback() *Config {
	configFiles := []string{
		"config.json",
		"configs/config.json",
		"/etc/accessguard/config.json",
	}

	for _, file := range configFiles {
		if _, err := os.Stat(file); err == nil {
			cfg, err := LoadFromFile(file)
			if err == nil {
				log.Printf("Loaded configuration from %s", file)
				return cfg
			}
			log.Printf("Warning: Failed to load config from %s: %v", file, err)
		}
	}

	log.Println("Using default configuration")
	return Load()
}

func LoadPermissionsFromFile(filename string) (map[string]PermissionDef, error) {
	data, err := os.ReadFile(filename)
	if err != nil {
		if os.IsNotExist(err) {
			return make(map[string]PermissionDef), nil
		}
		return nil, err
	}

	var perms map[string]PermissionDef
	if err := json.Unmarshal(data, &perms); err != nil {
		return nil, err
	}

	return perms, nil
}

func GetEnvConfig() *Config {
	cfg := Load()

	if addr := os.Getenv("AG_SERVER_ADDRESS"); addr != "" {
		cfg.Server.Address = addr
	}
	if port := os.Getenv("AG_SERVER_PORT"); port != "" {
		if p, err := strconvInt(port); err == nil {
			cfg.Server.Port = p
		}
	}

	if redisAddr := os.Getenv("AG_REDIS_ADDRESS"); redisAddr != "" {
		cfg.Redis.Address = redisAddr
	}
	if redisPass := os.Getenv("AG_REDIS_PASSWORD"); redisPass != "" {
		cfg.Redis.Password = redisPass
	}
	if redisDB := os.Getenv("AG_REDIS_DB"); redisDB != "" {
		if db, err := strconvInt(redisDB); err == nil {
			cfg.Redis.DB = db
		}
	}
	if enabled := os.Getenv("AG_REDIS_ENABLED"); enabled != "" {
		cfg.Redis.Enabled = strings.EqualFold(enabled, "true") || enabled == "1"
	}

	if permFile := os.Getenv("AG_PERMISSIONS_FILE"); permFile != "" {
		cfg.Permissions.ConfigFile = permFile
	}

	return cfg
}

func strconvInt(s string) (int, error) {
	var result int
	neg := false
	if len(s) > 0 && s[0] == '-' {
		neg = true
		s = s[1:]
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, nil
		}
		result = result*10 + int(c-'0')
	}
	if neg {
		result = -result
	}
	return result, nil
}
