package main

import (
	"depguard/internal/cache"
	"depguard/internal/config"
	"depguard/internal/database"
	"depguard/internal/logger"
	"depguard/internal/middleware"
	"depguard/internal/modules/apicontract"
	"depguard/internal/modules/docindex"
	"depguard/internal/modules/environment"
	"depguard/internal/modules/featuretoggle"
	"depguard/internal/modules/qualitygate"
	"depguard/internal/modules/scaffold"
	"depguard/internal/modules/softwarecatalog"
	"depguard/internal/modules/vulnerability"
	"log"
	"net/http"

	"github.com/gin-gonic/gin"
)

func main() {
	config.Load()
	logger.Init()
	defer logger.Sync()
	database.Init()
	cache.Init()

	runMigrations()

	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(middleware.CORSMiddleware())
	r.Use(middleware.LoggerMiddleware())
	r.Use(middleware.RecoveryMiddleware())
	r.Use(middleware.ConcurrencyLimit(100))

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"code":    200,
			"status":  "ok",
			"message": "DepGuard service is running",
		})
	})

	apiV1 := r.Group("/api/v1")

	scaffoldHandler := scaffold.NewHandler()
	scaffoldHandler.RegisterRoutes(apiV1.Group("/scaffold"))

	apiContractHandler := apicontract.NewHandler()
	apiContractHandler.RegisterRoutes(apiV1.Group("/api-contract"))

	envHandler := environment.NewHandler()
	envHandler.RegisterRoutes(apiV1.Group("/environment"))

	catalogHandler := softwarecatalog.NewHandler()
	catalogHandler.RegisterRoutes(apiV1.Group("/catalog"))

	docHandler := docindex.NewHandler()
	docHandler.RegisterRoutes(apiV1.Group("/docs"))

	qualityHandler := qualitygate.NewHandler()
	qualityHandler.RegisterRoutes(apiV1.Group("/quality"))

	vulnHandler := vulnerability.NewHandler()
	vulnHandler.RegisterRoutes(apiV1.Group("/vulnerability"))

	featureHandler := featuretoggle.NewHandler()
	featureHandler.RegisterRoutes(apiV1.Group("/features"))

	log.Printf("DepGuard server starting on port %s", config.AppConfig.AppPort)
	if err := r.Run(":" + config.AppConfig.AppPort); err != nil {
		log.Fatalf("Failed to start server: %v", err)
	}
}

func runMigrations() {
	database.AutoMigrate(
		&scaffold.Template{},
		&scaffold.GenerationTask{},
		&scaffold.TaskCheckpoint{},
		&scaffold.DataBackup{},
		&scaffold.RecoveryRecord{},

		&apicontract.APISchema{},
		&apicontract.ValidationResult{},
		&apicontract.MockServer{},
		&apicontract.ContractTest{},

		&environment.Environment{},
		&environment.EnvironmentRequest{},
		&environment.RecyclePolicy{},
		&environment.UsageStats{},
		&environment.DynamicConfig{},
		&environment.ConfigChangeLog{},

		&softwarecatalog.Service{},
		&softwarecatalog.Library{},
		&softwarecatalog.ServiceDependency{},

		&docindex.Document{},
		&docindex.DocumentIndex{},

		&qualitygate.QualityRule{},
		&qualitygate.QualityGate{},
		&qualitygate.QualityReport{},

		&vulnerability.SBOM{},
		&vulnerability.Vulnerability{},
		&vulnerability.ScanTask{},

		&featuretoggle.FeatureToggle{},
		&featuretoggle.UserSegment{},
		&featuretoggle.ToggleTarget{},
	)

	log.Println("Database migrations completed")
}
