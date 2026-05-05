package config

import (
	"github.com/spf13/viper"
)

type Config struct {
	MongoDB MongoDBConfig
	Redis   RedisConfig
	Server  ServerConfig
}

type MongoDBConfig struct {
	URI        string
	Database   string
	Collection struct {
		Posts         string
		Feeds         string
		Interactions  string
		Comments      string
		Relations     string
		AuditRecords  string
		Notifications string
	}
}

type RedisConfig struct {
	Addr     string
	Password string
	DB       int
}

type ServerConfig struct {
	Port string
}

func Load() (*Config, error) {
	viper.SetDefault("MONGODB_URI", "mongodb://localhost:27017")
	viper.SetDefault("MONGODB_DATABASE", "socialfeed")
	viper.SetDefault("MONGODB_COLLECTION_POSTS", "posts")
	viper.SetDefault("MONGODB_COLLECTION_FEEDS", "feeds")
	viper.SetDefault("MONGODB_COLLECTION_INTERACTIONS", "interactions")
	viper.SetDefault("MONGODB_COLLECTION_COMMENTS", "comments")
	viper.SetDefault("MONGODB_COLLECTION_RELATIONS", "relations")
	viper.SetDefault("MONGODB_COLLECTION_AUDITRECORDS", "audit_records")
	viper.SetDefault("MONGODB_COLLECTION_NOTIFICATIONS", "notifications")

	viper.SetDefault("REDIS_ADDR", "localhost:6379")
	viper.SetDefault("REDIS_PASSWORD", "")
	viper.SetDefault("REDIS_DB", 0)

	viper.SetDefault("SERVER_PORT", "8080")

	viper.AutomaticEnv()

	config := &Config{
		MongoDB: MongoDBConfig{
			URI:      viper.GetString("MONGODB_URI"),
			Database: viper.GetString("MONGODB_DATABASE"),
		},
		Redis: RedisConfig{
			Addr:     viper.GetString("REDIS_ADDR"),
			Password: viper.GetString("REDIS_PASSWORD"),
			DB:       viper.GetInt("REDIS_DB"),
		},
		Server: ServerConfig{
			Port: viper.GetString("SERVER_PORT"),
		},
	}

	config.MongoDB.Collection.Posts = viper.GetString("MONGODB_COLLECTION_POSTS")
	config.MongoDB.Collection.Feeds = viper.GetString("MONGODB_COLLECTION_FEEDS")
	config.MongoDB.Collection.Interactions = viper.GetString("MONGODB_COLLECTION_INTERACTIONS")
	config.MongoDB.Collection.Comments = viper.GetString("MONGODB_COLLECTION_COMMENTS")
	config.MongoDB.Collection.Relations = viper.GetString("MONGODB_COLLECTION_RELATIONS")
	config.MongoDB.Collection.AuditRecords = viper.GetString("MONGODB_COLLECTION_AUDITRECORDS")
	config.MongoDB.Collection.Notifications = viper.GetString("MONGODB_COLLECTION_NOTIFICATIONS")

	return config, nil
}
