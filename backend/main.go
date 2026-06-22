package main

import (
	"log"
	"meeting-system/cmd/api"
	"meeting-system/internal/config"
	"meeting-system/pkg/database"
)

func main() {
	cfg := config.Load()

	if err := database.Init(cfg); err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}

	log.Println("Database connected successfully")

	server := api.NewServer(cfg)
	if err := server.Run(); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}
