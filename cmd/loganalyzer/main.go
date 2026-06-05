package main

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/datateam/loganalyzer/internal/aggregator"
	"github.com/datateam/loganalyzer/internal/api"
	"github.com/datateam/loganalyzer/internal/collector"
	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/correlator"
	"github.com/datateam/loganalyzer/internal/detector"
	"github.com/datateam/loganalyzer/internal/models"
	"github.com/datateam/loganalyzer/internal/notifier"
	"github.com/datateam/loganalyzer/internal/pipeline"
	"github.com/datateam/loganalyzer/internal/storage"
)

func main() {
	configPath := flag.String("config", "config/config.yaml", "Path to configuration file")
	geoIPPath := flag.String("geoip", "config/GeoLite2-City.mmdb", "Path to GeoIP database")
	flag.Parse()

	log.Printf("Starting Log Analyzer Service...")

	cfg, err := config.Load(*configPath)
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}
	log.Printf("Configuration loaded successfully")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	clickhouse, err := storage.NewClickHouseClient(cfg.Storage.ClickHouse)
	if err != nil {
		log.Fatalf("Failed to create ClickHouse client: %v", err)
	}
	if err := clickhouse.InitSchema(); err != nil {
		log.Printf("Warning: Failed to init ClickHouse schema: %v", err)
	}
	log.Printf("ClickHouse client initialized")

	redisClient, err := storage.NewRedisClient(cfg.Storage.Redis)
	if err != nil {
		log.Fatalf("Failed to create Redis client: %v", err)
	}
	log.Printf("Redis client initialized")

	kafkaBuffer, err := storage.NewKafkaBuffer(cfg.Storage.Kafka)
	if err != nil {
		log.Printf("Warning: Failed to create Kafka buffer: %v", err)
	} else {
		log.Printf("Kafka buffer initialized")
	}

	collectorManager := collector.NewManager()
	collectorOutput := make(chan *models.LogEvent, 10000)

	for _, esCfg := range cfg.Collectors.Elasticsearch {
		if !esCfg.Enabled {
			continue
		}
		esCollector, err := collector.NewElasticsearchCollector(esCfg)
		if err != nil {
			log.Printf("Failed to create Elasticsearch collector %s: %v", esCfg.Name, err)
			continue
		}
		collectorManager.AddCollector(esCollector)
		esCollector.SetOutput(collectorOutput)
		log.Printf("Elasticsearch collector added: %s", esCfg.Name)
	}

	for _, lokiCfg := range cfg.Collectors.Loki {
		if !lokiCfg.Enabled {
			continue
		}
		lokiCollector, err := collector.NewLokiCollector(lokiCfg)
		if err != nil {
			log.Printf("Failed to create Loki collector %s: %v", lokiCfg.Name, err)
			continue
		}
		collectorManager.AddCollector(lokiCollector)
		lokiCollector.SetOutput(collectorOutput)
		log.Printf("Loki collector added: %s", lokiCfg.Name)
	}

	for _, kafkaCfg := range cfg.Collectors.Kafka {
		if !kafkaCfg.Enabled {
			continue
		}
		kafkaCollector, err := collector.NewKafkaCollector(kafkaCfg)
		if err != nil {
			log.Printf("Failed to create Kafka collector %s: %v", kafkaCfg.Name, err)
			continue
		}
		collectorManager.AddCollector(kafkaCollector)
		kafkaCollector.SetOutput(collectorOutput)
		log.Printf("Kafka collector added: %s", kafkaCfg.Name)
	}

	for _, syslogCfg := range cfg.Collectors.Syslog {
		if !syslogCfg.Enabled {
			continue
		}
		syslogCollector, err := collector.NewSyslogCollector(syslogCfg)
		if err != nil {
			log.Printf("Failed to create Syslog collector %s: %v", syslogCfg.Name, err)
			continue
		}
		collectorManager.AddCollector(syslogCollector)
		syslogCollector.SetOutput(collectorOutput)
		log.Printf("Syslog collector added: %s", syslogCfg.Name)
	}

	pipe, err := pipeline.NewPipeline(cfg.Pipeline, collectorOutput, *geoIPPath)
	if err != nil {
		log.Fatalf("Failed to create pipeline: %v", err)
	}
	log.Printf("Pipeline initialized with %d rules", len(cfg.Pipeline.Rules))

	detectorEngine := detector.NewDetector(cfg.Detection, pipe.Output(), redisClient)
	log.Printf("Anomaly detector initialized")

	correlatorEngine := correlator.NewCorrelator(cfg.Correlation, detectorEngine.Output(), clickhouse)
	log.Printf("Correlator initialized")

	aggregatorEngine := aggregator.NewAggregator(cfg.Aggregation, correlatorEngine.Output(), redisClient)
	log.Printf("Alert aggregator initialized")

	notifierEngine := notifier.NewNotifier(cfg.Notification, aggregatorEngine.Output())
	log.Printf("Notifier initialized with %d channels", len(cfg.Notification.Channels))

	apiServer := api.NewServer(cfg.API, clickhouse, redisClient, aggregatorEngine, correlatorEngine)
	log.Printf("API server initialized")

	if cfg.Server.Environment != "production" {
		config.RegisterCallback(func(newCfg *config.Config) {
			log.Printf("Configuration changed, reloading...")
			if err := pipe.ReloadRules(newCfg.Pipeline.Rules); err != nil {
				log.Printf("Failed to reload pipeline rules: %v", err)
			}
			detectorEngine.ReloadRules(newCfg.Detection.Rules)
			notifierEngine.ReloadConfig(newCfg.Notification)
			log.Printf("Configuration reloaded successfully")
		})
	}

	if err := collectorManager.StartAll(ctx); err != nil {
		log.Fatalf("Failed to start collectors: %v", err)
	}
	log.Println("All collectors started")

	if err := pipe.Start(ctx); err != nil {
		log.Fatalf("Failed to start pipeline: %v", err)
	}
	log.Println("Pipeline started")

	if err := detectorEngine.Start(ctx); err != nil {
		log.Fatalf("Failed to start detector: %v", err)
	}
	log.Println("Anomaly detector started")

	if err := correlatorEngine.Start(ctx); err != nil {
		log.Fatalf("Failed to start correlator: %v", err)
	}
	log.Println("Correlator started")

	if err := aggregatorEngine.Start(ctx); err != nil {
		log.Fatalf("Failed to start aggregator: %v", err)
	}
	log.Println("Alert aggregator started")

	if err := notifierEngine.Start(ctx); err != nil {
		log.Fatalf("Failed to start notifier: %v", err)
	}
	log.Println("Notifier started")

	if err := apiServer.Start(); err != nil {
		log.Fatalf("Failed to start API server: %v", err)
	}
	log.Printf("API server started on port %d", cfg.API.HTTPPort)

	go func() {
		for event := range pipe.Output() {
			if kafkaBuffer != nil {
				if err := kafkaBuffer.Write(ctx, event); err != nil {
					log.Printf("Failed to write to Kafka buffer: %v", err)
				}
			}
			if err := clickhouse.Insert(ctx, event); err != nil {
				log.Printf("Failed to insert to ClickHouse: %v", err)
			}
		}
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	sig := <-sigCh
	log.Printf("Received signal %v, shutting down...", sig)

	cancel()

	log.Println("Stopping API server...")
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer shutdownCancel()
	if err := apiServer.Stop(shutdownCtx); err != nil {
		log.Printf("Error stopping API server: %v", err)
	}

	log.Println("Stopping collectors...")
	if err := collectorManager.StopAll(); err != nil {
		log.Printf("Error stopping collectors: %v", err)
	}

	log.Println("Stopping pipeline...")
	pipe.Stop()

	log.Println("Stopping detector...")
	detectorEngine.Stop()

	log.Println("Stopping correlator...")
	correlatorEngine.Stop()

	log.Println("Stopping aggregator...")
	aggregatorEngine.Stop()

	log.Println("Stopping notifier...")
	notifierEngine.Stop()

	if kafkaBuffer != nil {
		log.Println("Closing Kafka buffer...")
		kafkaBuffer.Close()
	}

	log.Println("Closing ClickHouse connection...")
	clickhouse.Close()

	log.Println("Closing Redis connection...")
	redisClient.Close()

	log.Println("Shutdown complete")
}
