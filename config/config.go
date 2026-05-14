package config

import "time"

type Config struct {
	Server   ServerConfig   `json:"server"`
	Session  SessionConfig  `json:"session"`
	Password PasswordConfig `json:"password"`
	MFA      MFAConfig      `json:"mfa"`
	Redis    RedisConfig    `json:"redis"`
	Permissions PermissionsConfig `json:"permissions"`
}

type ServerConfig struct {
	Address string `json:"address"`
	Port    int    `json:"port"`
}

type SessionConfig struct {
	DefaultTTL time.Duration `json:"default_ttl"`
	ExtendTTL  time.Duration `json:"extend_ttl"`
}

type PasswordConfig struct {
	MinLength int `json:"min_length"`
	Cost      int `json:"cost"`
}

type MFAConfig struct {
	Enabled        bool          `json:"enabled"`
	CodeLength     int           `json:"code_length"`
	ExpireDuration time.Duration `json:"expire_duration"`
}

type RedisConfig struct {
	Address     string        `json:"address"`
	Password    string        `json:"password"`
	DB          int           `json:"db"`
	MaxRetries  int           `json:"max_retries"`
	AuditQueue  string        `json:"audit_queue"`
	Enabled     bool          `json:"enabled"`
}

type PermissionsConfig struct {
	ConfigFile string            `json:"config_file"`
	Permissions map[string]PermissionDef `json:"permissions"`
}

type PermissionDef struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Category    string `json:"category"`
}

func Load() *Config {
	return &Config{
		Server: ServerConfig{
			Address: "0.0.0.0",
			Port:    8080,
		},
		Session: SessionConfig{
			DefaultTTL: 2 * time.Hour,
			ExtendTTL:  30 * time.Minute,
		},
		Password: PasswordConfig{
			MinLength: 8,
			Cost:      12,
		},
		MFA: MFAConfig{
			Enabled:        true,
			CodeLength:     6,
			ExpireDuration: 5 * time.Minute,
		},
		Redis: RedisConfig{
			Address:    "localhost:6379",
			Password:   "",
			DB:         0,
			MaxRetries: 3,
			AuditQueue: "audit_queue",
			Enabled:    false,
		},
		Permissions: PermissionsConfig{
			ConfigFile:  "permissions.json",
			Permissions: make(map[string]PermissionDef),
		},
	}
}
