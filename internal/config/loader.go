package config

import (
	"fmt"
	"os"
	"path/filepath"

	"dbmigrator/pkg/models"
	"gopkg.in/yaml.v3"
)

const DefaultConfigFile = "dbmigrator.yaml"

func LoadConfig(configPath string) (*models.Config, error) {
	if configPath == "" {
		configPath = findConfigFile()
	}

	if configPath == "" {
		return nil, fmt.Errorf("config file not found. Please create dbmigrator.yaml")
	}

	data, err := os.ReadFile(configPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	var config models.Config
	if err := yaml.Unmarshal(data, &config); err != nil {
		return nil, fmt.Errorf("failed to parse config file: %w", err)
	}

	if err := validateConfig(&config); err != nil {
		return nil, err
	}

	return &config, nil
}

func findConfigFile() string {
	cwd, err := os.Getwd()
	if err != nil {
		return ""
	}

	configPath := filepath.Join(cwd, DefaultConfigFile)
	if _, err := os.Stat(configPath); err == nil {
		return configPath
	}

	return ""
}

func validateConfig(config *models.Config) error {
	if config.Environments == nil || len(config.Environments) == 0 {
		return fmt.Errorf("no environments defined in config")
	}

	for name, env := range config.Environments {
		if err := validateDatabaseConfig(name, &env.Database); err != nil {
			return err
		}
	}

	if config.Migrations.Directory == "" {
		config.Migrations.Directory = "migrations"
	}

	if config.Migrations.Table == "" {
		config.Migrations.Table = "schema_migrations"
	}

	if config.Migrations.LogTable == "" {
		config.Migrations.LogTable = "migration_logs"
	}

	return nil
}

func validateDatabaseConfig(envName string, db *models.DatabaseConfig) error {
	if db.Driver == "" {
		return fmt.Errorf("environment '%s': driver is required", envName)
	}

	if db.Driver != "mysql" && db.Driver != "postgres" && db.Driver != "postgresql" {
		return fmt.Errorf("environment '%s': unsupported driver '%s', supported: mysql, postgres", envName, db.Driver)
	}

	if db.Host == "" {
		return fmt.Errorf("environment '%s': host is required", envName)
	}

	if db.Port <= 0 {
		if db.Driver == "mysql" {
			db.Port = 3306
		} else {
			db.Port = 5432
		}
	}

	if db.User == "" {
		return fmt.Errorf("environment '%s': user is required", envName)
	}

	if db.DBName == "" {
		return fmt.Errorf("environment '%s': dbname is required", envName)
	}

	return nil
}

func GetEnvironmentConfig(config *models.Config, envName string) (*models.EnvironmentConfig, error) {
	if config.Environments == nil {
		return nil, fmt.Errorf("no environments configured")
	}

	env, exists := config.Environments[envName]
	if !exists {
		return nil, fmt.Errorf("environment '%s' not found in config", envName)
	}

	return &env, nil
}
