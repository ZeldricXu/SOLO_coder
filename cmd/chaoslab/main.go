package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/chaoslab/platform/internal/api"
	"github.com/chaoslab/platform/internal/audit"
	"github.com/chaoslab/platform/internal/chaos"
	"github.com/chaoslab/platform/internal/chaos/coordinator"
	"github.com/chaoslab/platform/internal/chaos/injectors"
	chaosmetrics "github.com/chaoslab/platform/internal/chaos/metrics"
	"github.com/chaoslab/platform/internal/chaos/repository"
	"github.com/chaoslab/platform/internal/common"
	"github.com/chaoslab/platform/internal/core/di"
	"github.com/chaoslab/platform/internal/core/ports"
	"github.com/chaoslab/platform/internal/dns"
	"github.com/chaoslab/platform/internal/dns/cache"
	"github.com/chaoslab/platform/internal/dns/strategy"
	"github.com/chaoslab/platform/internal/dns/upstream"
	"github.com/chaoslab/platform/internal/eventstore"
	"github.com/chaoslab/platform/internal/mtls"
	"github.com/chaoslab/platform/internal/mtls/ca"
	"github.com/chaoslab/platform/internal/mtls/crl"
	mtlsrepo "github.com/chaoslab/platform/internal/mtls/repository"
	"github.com/chaoslab/platform/internal/mtls/rotation"
	"github.com/chaoslab/platform/internal/registry"
	"github.com/chaoslab/platform/internal/sidecar"
	"github.com/chaoslab/platform/internal/traffic"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.uber.org/zap"
)

func main() {
	common.InitLogger("info")
	defer common.Sync()

	logger := common.GetLogger()
	logger.Info("ChaosLab platform starting...")

	container := di.NewContainer()

	registerDNSDependencies(container, logger)
	registerMTLSDependencies(container, logger)
	registerChaosDependencies(container, logger)

	container.RegisterSingleton("logger", func() (interface{}, error) {
		return logger, nil
	})

	trafficService := traffic.NewTrafficControllerService()
	eventService := eventstore.NewEventStoreService()
	registryService := registry.NewImageDistributionService()
	sidecarService := sidecar.NewSidecarLifecycleService()
	auditService := audit.NewAuditService()

	dnsService := container.MustGet("dnsService").(ports.DNSProxyService)
	mtlsService := container.MustGet("mtlsService").(ports.MTLSCertificateService)
	chaosService := container.MustGet("chaosService").(ports.ChaosOrchestratorService)

	prometheusExporter := chaosService.GetPrometheusExporter()
	if prometheusExporter != nil {
		http.Handle("/metrics", promhttp.HandlerFor(prometheusExporter.GetRegistry(), promhttp.HandlerOpts{}))
		logger.Info("Prometheus metrics endpoint registered at /metrics")
	}

	handler := api.NewAPIHandler(
		dnsService,
		mtlsService,
		chaosService,
		trafficService,
		eventService,
		registryService,
		sidecarService,
		auditService,
		logger,
	)

	router := api.SetupRouter(handler)

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	srv := &http.Server{
		Addr:    fmt.Sprintf(":%s", port),
		Handler: router,
	}

	go func() {
		logger.Info("server starting", zap.String("port", port))
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("server failed to start", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	logger.Info("shutdown signal received, shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		logger.Fatal("server forced shutdown", zap.Error(err))
	}

	logger.Info("server exited properly")
}

func registerDNSDependencies(container *di.Container, logger *zap.Logger) {
	container.RegisterSingleton("dnsUpstreamManager", func() (interface{}, error) {
		return upstream.NewManager(logger), nil
	})

	container.RegisterSingleton("dnsL1Cache", func() (interface{}, error) {
		return cache.NewCache(10000, logger), nil
	})

	container.RegisterSingleton("dnsL2Cache", func() (interface{}, error) {
		redisAddr := os.Getenv("REDIS_ADDR")
		if redisAddr == "" {
			redisAddr = "localhost:6379"
		}
		return cache.NewL2Cache(redisAddr, "chaoslab:dns:", logger), nil
	})

	container.RegisterSingleton("dnsCache", func() (interface{}, error) {
		l1 := container.MustGet("dnsL1Cache").(ports.DNSCache)
		l2 := container.MustGet("dnsL2Cache").(ports.DNSCache)
		return cache.NewMultiLevelCache(l1, l2, logger), nil
	})

	container.RegisterSingleton("dnsStrategyFactory", func() (interface{}, error) {
		upstreamMgr := container.MustGet("dnsUpstreamManager").(ports.DNSUpstreamManager)
		return strategy.NewFactory(upstreamMgr, logger), nil
	})

	container.RegisterSingleton("dnsService", func() (interface{}, error) {
		upstreamMgr := container.MustGet("dnsUpstreamManager").(ports.DNSUpstreamManager)
		dnsCache := container.MustGet("dnsCache").(ports.DNSCache)
		strategyFactory := container.MustGet("dnsStrategyFactory").(ports.DNSStrategyFactory)
		return dns.NewProxyService(upstreamMgr, dnsCache, strategyFactory, 3, logger), nil
	})

	logger.Info("DNS module dependencies registered (with multi-level cache)")
}

func registerMTLSDependencies(container *di.Container, logger *zap.Logger) {
	container.RegisterSingleton("caStore", func() (interface{}, error) {
		return ca.NewManager("ChaosLab CA", "ChaosLab Inc.", logger)
	})

	container.RegisterSingleton("certRepository", func() (interface{}, error) {
		return mtlsrepo.NewBatchCertificateRepository(), nil
	})

	container.RegisterSingleton("rotationManager", func() (interface{}, error) {
		caStore := container.MustGet("caStore").(ports.CAStore)
		policyStore, certRotator := rotation.NewRotationManager(caStore, logger)
		return struct {
			ports.RotationPolicyStore
			ports.CertificateRotator
		}{policyStore, certRotator}, nil
	})

	container.RegisterSingleton("crlManager", func() (interface{}, error) {
		caStore := container.MustGet("caStore").(ports.CAStore)
		return crl.NewManager(caStore, logger), nil
	})

	container.RegisterSingleton("mtlsService", func() (interface{}, error) {
		caStore := container.MustGet("caStore").(ports.CAStore)
		certRepo := container.MustGet("certRepository").(ports.BatchCertificateRepository)
		rotationMgr := container.MustGet("rotationManager").(struct {
			ports.RotationPolicyStore
			ports.CertificateRotator
		})
		crlMgr := container.MustGet("crlManager").(ports.CRLManager)
		return mtls.NewCertificateServiceWithBatch(
			caStore,
			certRepo,
			rotationMgr.RotationPolicyStore,
			rotationMgr.CertificateRotator,
			crlMgr,
			logger,
		), nil
	})

	logger.Info("mTLS module dependencies registered (with batch support)")
}

func registerChaosDependencies(container *di.Container, logger *zap.Logger) {
	container.RegisterSingleton("delayInjector", func() (interface{}, error) {
		return injectors.NewDelayInjector(logger), nil
	})

	container.RegisterSingleton("failureInjector", func() (interface{}, error) {
		return injectors.NewFailureInjector(logger), nil
	})

	container.RegisterSingleton("cpuInjector", func() (interface{}, error) {
		return injectors.NewCPUStressInjector(logger), nil
	})

	container.RegisterSingleton("memoryInjector", func() (interface{}, error) {
		return injectors.NewMemoryStressInjector(logger), nil
	})

	container.RegisterSingleton("diskInjector", func() (interface{}, error) {
		return injectors.NewDiskFailureInjector(logger), nil
	})

	container.RegisterSingleton("injectorRegistry", func() (interface{}, error) {
		delay := container.MustGet("delayInjector").(ports.ChaosInjector)
		failure := container.MustGet("failureInjector").(ports.ChaosInjector)
		cpu := container.MustGet("cpuInjector").(ports.ChaosInjector)
		memory := container.MustGet("memoryInjector").(ports.ChaosInjector)
		disk := container.MustGet("diskInjector").(ports.ChaosInjector)
		return injectors.NewInjectorRegistry(logger, delay, failure, cpu, memory, disk), nil
	})

	container.RegisterSingleton("scenarioRepository", func() (interface{}, error) {
		return repository.NewScenarioRepository(), nil
	})

	container.RegisterSingleton("runRepository", func() (interface{}, error) {
		return repository.NewRunRepository(), nil
	})

	container.RegisterSingleton("metricsCollector", func() (interface{}, error) {
		return chaosmetrics.NewMetricsCollector(), nil
	})

	container.RegisterSingleton("prometheusExporter", func() (interface{}, error) {
		return chaosmetrics.NewPrometheusExporter(), nil
	})

	container.RegisterSingleton("executionCoordinator", func() (interface{}, error) {
		scenarioRepo := container.MustGet("scenarioRepository").(ports.ScenarioRepository)
		runRepo := container.MustGet("runRepository").(ports.RunInstanceRepository)
		injectorReg := container.MustGet("injectorRegistry").(ports.InjectorRegistry)
		metricsCollector := container.MustGet("metricsCollector").(ports.MetricsCollector)
		promExporter := container.MustGet("prometheusExporter").(*chaosmetrics.PrometheusExporter)
		return coordinator.NewExecutionCoordinatorWithMetrics(
			scenarioRepo,
			runRepo,
			injectorReg,
			metricsCollector,
			promExporter,
			logger,
		), nil
	})

	container.RegisterSingleton("chaosService", func() (interface{}, error) {
		scenarioRepo := container.MustGet("scenarioRepository").(ports.ScenarioRepository)
		runRepo := container.MustGet("runRepository").(ports.RunInstanceRepository)
		injectorReg := container.MustGet("injectorRegistry").(ports.InjectorRegistry)
		coordinator := container.MustGet("executionCoordinator").(ports.ExecutionCoordinator)
		return chaos.NewOrchestratorService(scenarioRepo, runRepo, injectorReg, coordinator, logger), nil
	})

	logger.Info("Chaos module dependencies registered (with monitoring)")
}
