package main

import (
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"netproxy/internal/api"
	"netproxy/internal/config"
	"netproxy/internal/forward"
	"netproxy/internal/health"
	"netproxy/internal/logger"
	"netproxy/internal/pool"
	"netproxy/internal/protocol"
	"netproxy/internal/stats"
)

func main() {
	configPath := flag.String("config", "configs/config.yaml", "Path to configuration file")
	flag.Parse()

	configMgr := config.NewConfigManager()
	if err := configMgr.LoadConfig(*configPath); err != nil {
		fmt.Printf("Failed to load configuration: %v\n", err)
		os.Exit(1)
	}

	cfg := configMgr.GetConfig()

	if err := logger.InitLogger(&cfg.Log); err != nil {
		fmt.Printf("Failed to initialize logger: %v\n", err)
		os.Exit(1)
	}
	defer logger.Close()

	logger.Info("NetProxy starting...")
	logger.Info("Configuration loaded from: %s", *configPath)

	statsConfig := &stats.StatsPersistenceConfig{
		Enabled:       cfg.Stats.Enabled,
		FilePath:      cfg.Stats.FilePath,
		Interval:      cfg.Stats.Interval,
		MaxRecords:    cfg.Stats.MaxRecords,
		BufferSize:    cfg.Stats.BufferSize,
		FlushInterval: cfg.Stats.FlushInterval,
	}
	stats.InitWithPersistence(statsConfig)

	pool.InitPoolManagerWithConfig(&cfg.Pool, configMgr)
	poolMgr := pool.GetPoolManager()
	if poolMgr == nil {
		logger.Error("Failed to initialize pool manager")
		os.Exit(1)
	}

	health.InitHealthChecker(&cfg.Health, poolMgr)
	healthChecker := health.GetHealthChecker()

	if healthChecker != nil {
		health.SetAutoClean(cfg.Health.AutoClean)
	}

	forwardEng := forward.NewForwardEngine(configMgr, poolMgr, healthChecker)

	apiServer := api.NewAPIServer(configMgr, forwardEng, poolMgr, healthChecker)

	if cfg.Server.APIAddress != "" {
		if err := apiServer.Start(cfg.Server.APIAddress); err != nil {
			logger.Error("Failed to start API server: %v", err)
		} else {
			logger.Info("API server started on %s", cfg.Server.APIAddress)
		}
	}

	if cfg.Server.HTTPAddress != "" {
		if err := apiServer.StartProxyServer(cfg.Server.HTTPAddress, protocol.ProtocolHTTP); err != nil {
			logger.Error("Failed to start HTTP proxy server: %v", err)
		}
	}

	if cfg.Server.HTTPSAddress != "" {
		if err := apiServer.StartProxyServer(cfg.Server.HTTPSAddress, protocol.ProtocolHTTPS); err != nil {
			logger.Error("Failed to start HTTPS proxy server: %v", err)
		}
	}

	if cfg.Server.SOCKS5Address != "" {
		if err := apiServer.StartProxyServer(cfg.Server.SOCKS5Address, protocol.ProtocolSOCKS5); err != nil {
			logger.Error("Failed to start SOCKS5 proxy server: %v", err)
		}
	}

	if cfg.Health.Enabled && healthChecker != nil {
		rules := configMgr.GetAllRules()
		for _, rule := range rules {
			if rule.Enabled {
				hosts := extractHostsFromPattern(rule.TargetPattern)
				for _, host := range hosts {
					health.RegisterTarget(host, 80)
					health.RegisterTarget(host, 443)
				}
			}
		}
		health.Start()
	}

	logger.Info("NetProxy started successfully")

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	<-sigChan
	logger.Info("Shutting down NetProxy...")

	if stats.IsPersistenceEnabled() {
		stats.StopPersistence()
	}

	if healthChecker != nil {
		health.Stop()
	}

	if err := apiServer.Stop(); err != nil {
		logger.Error("Error stopping API server: %v", err)
	}

	if poolMgr != nil {
		poolMgr.CloseAll()
	}

	logger.Info("NetProxy shutdown complete")
}

func extractHostsFromPattern(pattern string) []string {
	if pattern == "*" {
		return []string{}
	}

	pattern = pattern[1:]
	return []string{pattern}
}
