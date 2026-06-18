package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/enterprise/knowledgebase/internal/config"
	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/router"
	"github.com/gin-gonic/gin"
)

func main() {
	cfg := config.Load()

	gin.SetMode(cfg.Server.Mode)
	r := gin.New()
	r.Use(gin.Logger())
	r.Use(gin.Recovery())

	container, err := router.NewRouter(cfg, r)
	if err != nil {
		log.Fatalf("Failed to setup router: %v", err)
	}

	if err := database.AutoMigrate(container.DB); err != nil {
		log.Printf("WARNING: AutoMigrate failed: %v", err)
	}

	log.Println("==================================================")
	log.Println("  Enterprise Knowledgebase System - Starting...")
	log.Printf("  Server Mode:    %s", cfg.Server.Mode)
	log.Printf("  Listening Port: :%s", cfg.Server.Port)
	log.Printf("  PostgreSQL:     %s:%s/%s", cfg.PostgreSQL.Host, cfg.PostgreSQL.Port, cfg.PostgreSQL.DBName)
	log.Printf("  Redis:          %s:%s", cfg.Redis.Host, cfg.Redis.Port)
	log.Printf("  MinIO:          %s", cfg.MinIO.Endpoint)
	log.Printf("  Bleve Index:    %s", cfg.Bleve.IndexPath)
	log.Println("==================================================")

	srv := &http.Server{
		Addr:         fmt.Sprintf(":%s", cfg.Server.Port),
		Handler:      r,
		ReadTimeout:  time.Duration(cfg.Server.ReadTimeout) * time.Second,
		WriteTimeout: time.Duration(cfg.Server.WriteTimeout) * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	go func() {
		log.Printf("Server started on %s", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	sig := <-quit
	log.Printf("Received signal: %v. Shuting down server...", sig)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("Server forced to shutdown: %v", err)
	}

	log.Println("Server exited gracefully")
	_ = container
}
