package main

import (
	"flag"
	"os"
	"os/signal"
	"syscall"

	"github.com/edgevision/edgevision/api"
	"github.com/edgevision/edgevision/internal/aggregation"
	"github.com/edgevision/edgevision/internal/cache"
	"github.com/edgevision/edgevision/internal/common/logger"
	"github.com/edgevision/edgevision/internal/inference"
	"github.com/edgevision/edgevision/internal/lifecycle"
	"github.com/edgevision/edgevision/internal/ota"
	"github.com/edgevision/edgevision/internal/protocol"
	"github.com/edgevision/edgevision/internal/rules"
	"github.com/edgevision/edgevision/internal/shadow"
	"go.uber.org/zap"
)

var (
	Version   = "dev"
	Commit    = "none"
	BuildTime = "unknown"
	Profile   = "prod"
)

func main() {
	configPath := flag.String("config", "", "Path to configuration file")
	flag.Parse()

	logger.Init()
	defer logger.Sync()
	log := logger.Get()

	log.Info("Starting EdgeVision Video Stream Edge Analysis Engine",
		zap.String("version", Version),
		zap.String("commit", Commit),
		zap.String("build_time", BuildTime),
		zap.String("profile", Profile))
	inferenceScheduler := inference.NewScheduler(4)
	inferenceScheduler.Start()
	defer inferenceScheduler.Stop()
	rulesEngine := rules.NewEngine()
	rulesEngine.Start()
	defer rulesEngine.Stop()
	protocolAdapter := protocol.NewAdapter(4)
	protocolAdapter.Start()
	defer protocolAdapter.Stop()
	cacheManager, err := cache.NewManager("./data/cache", 1024*1024*100, 10000)
	if err != nil {
		log.Fatal("Failed to initialize cache manager", zap.Error(err))
	}
	cacheManager.Start()
	defer cacheManager.Stop()
	otaManager := ota.NewManager(2)
	otaManager.Start()
	defer otaManager.Stop()
	aggregator := aggregation.NewAggregator(1000)
	aggregator.Start()
	defer aggregator.Stop()
	shadowManager := shadow.NewManager()
	shadowManager.Start()
	defer shadowManager.Stop()
	lifecycleManager := lifecycle.NewManager("edgevision-secret-key", 180)
	lifecycleManager.Start()
	defer lifecycleManager.Stop()
	server := api.NewServer(
		inferenceScheduler,
		rulesEngine,
		protocolAdapter,
		cacheManager,
		otaManager,
		aggregator,
		shadowManager,
		lifecycleManager,
	)
	go func() {
		if err := server.Run(":8080"); err != nil {
			log.Error("Server error", zap.Error(err))
		}
	}()
	log.Info("EdgeVision server started on :8080")
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Info("Shutting down EdgeVision server...")
}
