package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/server"
)

func main() {
	var configPath string
	var vaultPath string

	flag.StringVar(&configPath, "config", "", "配置文件路径")
	flag.StringVar(&vaultPath, "vault", "", "知识库目录路径")
	flag.Parse()

	cfg, err := config.Load(configPath)
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	if vaultPath != "" {
		cfg.VaultPath = vaultPath
	}

	log.Printf("Knowledge Base starting...")
	log.Printf("Vault path: %s", cfg.VaultPath)
	log.Printf("Database path: %s", cfg.DBPath)

	srv, err := server.New(cfg)
	if err != nil {
		log.Fatalf("Failed to create server: %v", err)
	}

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		if err := srv.Start(); err != nil {
			log.Fatalf("Server error: %v", err)
		}
	}()

	<-quit
	log.Println("Shutting down...")
	srv.Stop()
	log.Println("Goodbye!")
}
