package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"session187/cmd/api"
	billingConfig "session187/internal/billing/config"
	billingRepo "session187/internal/billing/repository"
	billingSvc "session187/internal/billing/service"
	"session187/internal/common"
	"session187/internal/doccompare"
	"session187/internal/logger"
	"session187/internal/monitor"
	schedulerAsync "session187/internal/scheduler/async"
	schedulerRepo "session187/internal/scheduler/repository"
	schedulerExec "session187/internal/scheduler/executor"
	schedulerSvc "session187/internal/scheduler/service"
	"session187/internal/skillgraph"
	storageAdapter "session187/internal/storage/adapter"
	storagePolicy "session187/internal/storage/policy"
	storageRepo "session187/internal/storage/repository"
	storageSvc "session187/internal/storage/service"
	"session187/internal/tenant"
	"session187/internal/ticket"
)

func main() {
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		dsn = "host=localhost user=postgres password=postgres dbname=session187 port=5432 sslmode=disable"
	}
	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}
	db.AutoMigrate(
		&common.Entity{},
		&common.Config{},
		&common.RunInstance{},
		&common.Snapshot{},
		&tenant.Tenant{},
		&tenant.Usage{},
		&storage.Bucket{},
		&storage.ObjectMetadata{},
		&billing.UsageRecord{},
		&billing.BillingPlan{},
		&billing.Invoice{},
		&scheduler.Task{},
		&scheduler.TaskExecution{},
		&logger.LogConfig{},
		&logger.LogEntry{},
		&doccompare.Document{},
		&doccompare.CompareResult{},
		&skillgraph.SkillNode{},
		&skillgraph.EmployeeSkill{},
		&skillgraph.LearningPath{},
		&ticket.Ticket{},
		&ticket.Agent{},
		&monitor.MetricDefinition{},
		&monitor.MetricDataPoint{},
	)
	tenantMgr := tenant.NewManager(db)
	bucketRepo := storageRepo.NewBucketRepository(db)
	metadataRepo := storageRepo.NewMetadataRepository(db)
	storageAdapter := storageAdapter.NewInMemoryStorage()
	policyManager := storagePolicy.NewPolicyManager()
	compressionPolicy := storagePolicy.NewCompressionPolicy(6, 1024)
	encryptionPolicy := storagePolicy.NewEncryptionPolicy("your-32-byte-encryption-key-here")
	dedupPolicy := storagePolicy.NewDeduplicationPolicy()
	cachePolicy := storagePolicy.NewCachePolicy(1024 * 1024 * 1024)
	policyManager.AddPolicy(compressionPolicy)
	policyManager.AddPolicy(encryptionPolicy)
	policyManager.AddPolicy(dedupPolicy)
	policyManager.AddPolicy(cachePolicy)
	storageSvc := storageSvc.NewStorageService(bucketRepo, metadataRepo, storageAdapter, policyManager)
	usageRepo := billingRepo.NewUsageRepository(db)
	planRepo := billingRepo.NewPlanRepository(db)
	invoiceRepo := billingRepo.NewInvoiceRepository(db)
	billingConfigMgr := billingConfig.NewDynamicConfigManager()
	usageSvc := billingSvc.NewUsageService(usageRepo)
	billingSvc := billingSvc.NewBillingService(planRepo, invoiceRepo, usageRepo, billingConfigMgr)
	taskRepo := schedulerRepo.NewTaskRepository(db)
	executionRepo := schedulerRepo.NewExecutionRepository(db)
	taskExecutor := schedulerExec.NewTaskExecutor(executionRepo, taskRepo)
	asyncExecutor := schedulerAsync.NewAsyncExecutor(10)
	schedulerSvc := schedulerSvc.NewSchedulerService(taskRepo, executionRepo, taskExecutor, asyncExecutor)
	loggerMgr := logger.GetManager(db)
	docComparer := doccompare.NewComparer(db)
	skillMgr := skillgraph.NewManager(db)
	ticketAlloc := ticket.NewAllocator(db)
	monitor := monitor.NewMonitor(db)
	schedulerSvc.RegisterHandler("generate_invoice", func(ctx context.Context, params map[string]interface{}) (map[string]interface{}, error) {
		tenantID, _ := params["tenant_id"].(string)
		scenario, _ := params["scenario"].(string)
		now := time.Now()
		start := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
		invoice, err := billingSvc.GenerateInvoice(tenantID, start, now, scenario)
		if err != nil {
			return nil, err
		}
		return map[string]interface{}{"invoice_id": invoice.ID}, nil
	})
	schedulerSvc.RegisterHandler("collect_metrics", func(ctx context.Context, params map[string]interface{}) (map[string]interface{}, error) {
		return map[string]interface{}{"collected": true}, nil
	})
	schedulerSvc.Start()
	r := gin.Default()
	handler := api.NewHandler(tenantMgr, storageSvc, usageSvc, billingSvc, schedulerSvc, loggerMgr, docComparer, skillMgr, ticketAlloc, monitor)
	api.SetupRoutes(r, handler)
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	srv := &http.Server{
		Addr:    ":" + port,
		Handler: r,
	}
	go func() {
		log.Printf("Server starting on port %s", port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()
	ctx, cancel := context.WithCancel(context.Background())
	go monitor.Start(ctx)
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("Shutting down server...")
	cancel()
	schedulerSvc.Stop()
	asyncExecutor.Close()
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer shutdownCancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Fatalf("Server forced to shutdown: %v", err)
	}
	log.Println("Server exited properly")
}
