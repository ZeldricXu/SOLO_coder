package main

import (
	"fmt"
	"log"
	"os"
	"path/filepath"

	"pointcloud-platform/config"
	"pointcloud-platform/internal/annotation"
	"pointcloud-platform/internal/asset"
	"pointcloud-platform/internal/cache"
	"pointcloud-platform/internal/collaboration"
	"pointcloud-platform/internal/database"
	"pointcloud-platform/internal/octree"
	"pointcloud-platform/internal/parser"
	"pointcloud-platform/internal/renderer"
	"pointcloud-platform/internal/tile"

	"github.com/gin-contrib/cors"
	"github.com/gin-gonic/gin"
)

func main() {
	configPath := "config/config.yaml"
	if len(os.Args) > 1 {
		configPath = os.Args[1]
	}
	if err := config.Load(configPath); err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}
	cfg := config.AppConfig

	if err := ensureDirectories(cfg); err != nil {
		log.Fatalf("Failed to ensure directories: %v", err)
	}

	if err := database.Init(&cfg.Database); err != nil {
		log.Fatalf("Failed to initialize database: %v", err)
	}
	defer database.DB.Close()

	if err := cache.Init(&cfg.Redis); err != nil {
		log.Fatalf("Failed to initialize cache: %v", err)
	}
	defer cache.Client.Close()

	parseService := parser.NewParseService(4)
	octreeService := octree.NewOctreeService(&cfg.Octree, &cfg.Storage)
	tileService := tile.NewTileService(cfg, octreeService)
	renderService := renderer.NewRenderService()
	annotationService := annotation.NewAnnotationService()
	collabService := collaboration.NewCollaborationService(&cfg.Collaboration)
	assetService := asset.NewAssetService(&cfg.Storage, parseService)

	r := gin.Default()

	r.Use(cors.New(cors.Config{
		AllowAllOrigins:  true,
		AllowMethods:     []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"},
		AllowHeaders:     []string{"Origin", "Content-Type", "Accept", "Range"},
		ExposeHeaders:    []string{"Content-Length", "Content-Range", "Accept-Ranges"},
		AllowCredentials: true,
	}))

	api := r.Group("/api/v1")
	{
		tileHandler := tile.NewHandler(tileService)
		tileHandler.RegisterRoutes(api)

		renderHandler := renderer.NewHandler(renderService)
		renderHandler.RegisterRoutes(api)

		annotationHandler := annotation.NewHandler(annotationService)
		annotationHandler.RegisterRoutes(api)

		collabHandler := collaboration.NewHandler(collabService)
		collabHandler.RegisterRoutes(api)

		assetHandler := asset.NewHandler(assetService)
		assetHandler.RegisterRoutes(api)
	}

	r.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{
			"status":  "ok",
			"service": "pointcloud-platform",
		})
	})

	addr := fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port)
	log.Printf("Starting server on %s", addr)
	log.Printf("API docs available at http://%s/health", addr)

	if err := r.Run(addr); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}

func ensureDirectories(cfg *config.Config) error {
	dirs := []string{
		cfg.Storage.UploadDir,
		cfg.Storage.TileDir,
		cfg.Storage.DataDir,
	}

	for _, dir := range dirs {
		if dir == "" {
			continue
		}
		if err := os.MkdirAll(dir, 0755); err != nil {
			return fmt.Errorf("failed to create directory %s: %w", dir, err)
		}
		log.Printf("Ensured directory exists: %s", filepath.Clean(dir))
	}

	return nil
}
