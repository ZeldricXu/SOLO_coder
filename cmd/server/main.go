package main

import (
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"GameLeaderboard/internal/api"
	"GameLeaderboard/internal/config"
	"GameLeaderboard/internal/storage"
)

func main() {
	log.Println("Starting GameLeaderboard service...")

	if err := config.InitConfig(); err != nil {
		log.Fatalf("Failed to initialize config: %v", err)
	}
	log.Println("Config initialized successfully")

	mysqlStore, err := storage.NewMySQLStore(&config.AppConfig.MySQL)
	if err != nil {
		log.Fatalf("Failed to initialize MySQL store: %v", err)
	}
	log.Println("MySQL store initialized successfully")

	redisStore, err := storage.NewRedisStore(&config.AppConfig.Redis)
	if err != nil {
		log.Fatalf("Failed to initialize Redis store: %v", err)
	}
	defer redisStore.Close()
	log.Println("Redis store initialized successfully")

	server := api.NewAPIServer(config.AppConfig, mysqlStore, redisStore)
	log.Printf("API server created, listening on port %d", config.AppConfig.Server.Port)

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)

	go func() {
		addr := fmt.Sprintf(":%d", config.AppConfig.Server.Port)
		log.Printf("Server starting on %s", addr)
		if err := server.Run(); err != nil {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()

	<-stop
	log.Println("Shutting down gracefully...")

	log.Println("GameLeaderboard service stopped")
}
