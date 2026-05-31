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

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"session316/internal/apigateway"
	"session316/internal/config"
	"session316/internal/dataclassification"
	"session316/internal/dataaccess"
	"session316/internal/differentialprivacy"
	"session316/internal/federated"
	"session316/internal/logger"
	"session316/internal/monitoring"
	"session316/internal/mpc"
	"session316/internal/storage"
	"session316/internal/tee"
	"session316/pkg/middleware"
)

type App struct {
	cfg             *config.AppConfig
	router          *gin.Engine
	gateway         *apigateway.Gateway
	storageManager  *storage.StorageManager
	teeManager      *tee.EnclaveManager
	mpcManager      *mpc.MPCManager
	federatedManager *federated.FederatedManager
	monitoringManager *monitoring.MonitoringManager
	dataAccessManager *dataaccess.DataAccessManager
	classificationManager *dataclassification.DataClassificationManager
	privacyManager  *differentialprivacy.PrivacyManager
	rateLimiter     *middleware.RateLimiter
	server          *http.Server
}

func NewApp(cfg *config.AppConfig) (*App, error) {
	loggerCfg := &logger.Config{
		Level:       cfg.Logger.Level,
		Encoding:    cfg.Logger.Encoding,
		OutputPaths: cfg.Logger.OutputPaths,
	}
	if err := logger.Init(loggerCfg); err != nil {
		return nil, fmt.Errorf("init logger failed: %w", err)
	}

	gin.SetMode(gin.ReleaseMode)
	router := gin.New()

	app := &App{
		cfg:         cfg,
		router:      router,
		rateLimiter: middleware.NewRateLimiter(cfg.API.RateLimit.MaxConcurrent, cfg.API.RateLimit.QueueSize),
	}

	if err := app.initModules(); err != nil {
		return nil, err
	}

	app.setupMiddleware()
	app.setupRoutes()

	return app, nil
}

func (a *App) initModules() error {
	a.gateway = apigateway.NewGateway(&a.cfg.API)
	logger.Info("API Gateway module initialized")

	backupCfg := storage.BackupConfig{
		BackupDir:     "backups",
		RetentionDays: 30,
		MaxBackups:    10,
		Compress:      true,
	}
	var err error
	a.storageManager, err = storage.NewStorageManager(nil, backupCfg)
	if err != nil {
		logger.Warn("Storage Manager init with warning", zap.Error(err))
	}
	logger.Info("Storage Manager module initialized")

	a.teeManager, err = tee.NewEnclaveManager()
	if err != nil {
		logger.Warn("TEE Manager init with warning", zap.Error(err))
	}
	logger.Info("TEE Enclave module initialized")

	a.mpcManager = mpc.GetManager()
	logger.Info("MPC module initialized")

	a.federatedManager, err = federated.NewFederatedManager()
	if err != nil {
		return fmt.Errorf("init federated manager failed: %w", err)
	}
	logger.Info("Federated Learning module initialized")

	monitoringCfg := &monitoring.MonitoringConfig{
		WindowSize:       120,
		SnapshotInterval: time.Minute,
		Host:             "localhost",
		Region:           "cn-east",
	}
	monitoring.Init(monitoringCfg)
	a.monitoringManager = monitoring.GetManager()
	logger.Info("Monitoring module initialized")

	a.dataAccessManager = dataaccess.GetManager()
	logger.Info("Data Access module initialized")

	a.classificationManager = dataclassification.GetManager()
	logger.Info("Data Classification module initialized")

	privacyCfg := &differentialprivacy.PrivacyConfig{
		DefaultEpsilon: a.cfg.Privacy.Epsilon,
		DefaultDelta:   a.cfg.Privacy.Delta,
		Mechanism:      differentialprivacy.PrivacyMechanism(a.cfg.Privacy.Mechanism),
	}
	a.privacyManager = differentialprivacy.NewPrivacyManager(privacyCfg, "system")
	logger.Info("Differential Privacy module initialized")

	return nil
}

func (a *App) setupMiddleware() {
	a.router.Use(middleware.CORSMiddleware())
	a.router.Use(middleware.TraceIDMiddleware())
	a.router.Use(middleware.RequestLogger())
	a.router.Use(middleware.TimeoutMiddleware(time.Duration(a.cfg.API.Timeout) * time.Second))
	a.router.Use(a.rateLimiter.Middleware())
	a.router.Use(gin.Recovery())
}

func (a *App) setupRoutes() {
	a.gateway.RegisterRoutes(a.router)
	a.setupHealthRoutes()
	a.setupModuleRoutes()
}

func (a *App) setupHealthRoutes() {
	health := a.router.Group("/health")
	{
		health.GET("/live", a.livenessProbe)
		health.GET("/ready", a.readinessProbe)
		health.GET("/metrics", a.metricsHandler)
	}
}

func (a *App) setupModuleRoutes() {
	v1 := a.router.Group("/api/v1")

	storage := v1.Group("/storage")
	{
		storage.POST("/backup", a.createBackup)
		storage.GET("/backup/:id", a.getBackupStatus)
		storage.POST("/restore/:id", a.restoreBackup)
		storage.GET("/backups", a.listBackups)
	}

	tee := v1.Group("/tee")
	{
		tee.POST("/enclave", a.createEnclave)
		tee.DELETE("/enclave/:id", a.destroyEnclave)
		tee.POST("/enclave/:id/attest", a.remoteAttestation)
		tee.POST("/enclave/:id/execute", a.secureExecute)
	}

	mpc := v1.Group("/mpc")
	{
		mpc.POST("/participant", a.registerMPCParticipant)
		mpc.POST("/protocol", a.createMPCProtocol)
		mpc.POST("/protocol/:id/execute", a.executeMPCProtocol)
		mpc.GET("/protocol/:id", a.getMPCProtocolStatus)
	}

	federated := v1.Group("/federated")
	{
		federated.POST("/task", a.createTrainingTask)
		federated.GET("/task/:id", a.getTrainingTaskStatus)
		federated.POST("/task/:id/distribute", a.distributeTrainingTask)
		federated.POST("/task/:id/gradient", a.submitGradient)
		federated.POST("/task/:id/aggregate", a.aggregateGradients)
	}

	monitoring := v1.Group("/monitoring")
	{
		monitoring.GET("/metrics", a.getMonitoringMetrics)
		monitoring.GET("/snapshots", a.getSnapshots)
		monitoring.GET("/window", a.getWindowMetrics)
	}

	data := v1.Group("/data")
	{
		data.POST("/migrate", a.migrateSchema)
		data.GET("/migrations", a.listMigrations)
		data.POST("/rollback", a.rollbackSchema)
		data.POST("/export", a.exportData)
		data.POST("/import", a.importData)
	}

	classification := v1.Group("/classification")
	{
		classification.POST("/scan", a.scanAndClassifyData)
		classification.GET("/patterns", a.listSensitivePatterns)
	}

	privacy := v1.Group("/privacy")
	{
		privacy.POST("/account", a.createPrivacyAccount)
		privacy.GET("/account/:id/budget", a.getPrivacyBudget)
		privacy.POST("/noise", a.addPrivacyNoise)
	}
}

func (a *App) livenessProbe(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "alive", "timestamp": time.Now().UTC()})
}

func (a *App) readinessProbe(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{"status": "ready", "timestamp": time.Now().UTC()})
}

func (a *App) metricsHandler(c *gin.Context) {
	if a.monitoringManager != nil {
		a.monitoringManager.PrometheusHandler().ServeHTTP(c.Writer, c.Request)
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "monitoring not initialized"})
}

func (a *App) createBackup(c *gin.Context) {
	var req struct {
		Type string `json:"type" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	backupType := storage.BackupType(storage.BackupTypeFull)
	if req.Type == "incremental" {
		backupType = storage.BackupType(storage.BackupTypeIncremental)
	}

	backupID, err := a.storageManager.Backup(c.Request.Context(), backupType)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, gin.H{"backup_id": backupID, "status": "started"})
}

func (a *App) getBackupStatus(c *gin.Context) {
	backupID := c.Param("id")
	progress, err := a.storageManager.GetProgress(backupID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, progress)
}

func (a *App) restoreBackup(c *gin.Context) {
	backupID := c.Param("id")
	if err := a.storageManager.Restore(c.Request.Context(), backupID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "completed", "backup_id": backupID})
}

func (a *App) listBackups(c *gin.Context) {
	backups, err := a.storageManager.ListBackups()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"backups": backups})
}

func (a *App) createEnclave(c *gin.Context) {
	var req tee.EnclaveConfig
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	enclave, err := a.teeManager.CreateEnclave(c.Request.Context(), &req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, enclave)
}

func (a *App) destroyEnclave(c *gin.Context) {
	enclaveID := c.Param("id")
	if err := a.teeManager.DestroyEnclave(c.Request.Context(), enclaveID); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"status": "destroyed", "enclave_id": enclaveID})
}

func (a *App) remoteAttestation(c *gin.Context) {
	enclaveID := c.Param("id")
	var req tee.AttestationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	req.EnclaveID = enclaveID

	report, err := a.teeManager.RemoteAttestation(c.Request.Context(), &req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, report)
}

func (a *App) secureExecute(c *gin.Context) {
	enclaveID := c.Param("id")
	var req tee.SecureExecutionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	req.EnclaveID = enclaveID

	result, err := a.teeManager.SecureExecute(c.Request.Context(), &req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, result)
}

func (a *App) registerMPCParticipant(c *gin.Context) {
	var req struct {
		ID      string `json:"id" binding:"required"`
		Address string `json:"address" binding:"required"`
		PubKey  string `json:"pubkey"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	participant := mpc.NewParticipant(req.ID, req.Address, 0)
	if req.PubKey != "" {
		if err := participant.SetPublicKey(req.PubKey); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid public key"})
			return
		}
	}

	if err := a.mpcManager.RegisterParticipant(participant); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, gin.H{"participant_id": req.ID, "status": "registered"})
}

func (a *App) createMPCProtocol(c *gin.Context) {
	var req struct {
		ProtocolType    string   `json:"protocol_type" binding:"required"`
		ParticipantIDs  []string `json:"participant_ids" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	cfg := mpc.ProtocolConfig{
		ProtocolType: req.ProtocolType,
		Timeout:      300,
	}

	protocol, err := a.mpcManager.CreateProtocol(cfg, req.ParticipantIDs)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, protocol)
}

func (a *App) executeMPCProtocol(c *gin.Context) {
	protocolID := c.Param("id")
	var req struct {
		Operation string                 `json:"operation" binding:"required"`
		Inputs    map[string]interface{} `json:"inputs" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	result, err := a.mpcManager.ExecuteProtocol(protocolID, req.Operation, req.Inputs)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, result)
}

func (a *App) getMPCProtocolStatus(c *gin.Context) {
	protocolID := c.Param("id")
	state, err := a.mpcManager.GetProtocol(protocolID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "protocol not found"})
		return
	}
	c.JSON(http.StatusOK, state)
}

func (a *App) createTrainingTask(c *gin.Context) {
	var req struct {
		ModelID         string                 `json:"model_id" binding:"required"`
		Config          map[string]interface{} `json:"config"`
		RequiredClients int                    `json:"required_clients" binding:"min=1"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	task, err := a.federatedManager.CreateTrainingTask(req.ModelID, req.Config, req.RequiredClients)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, task)
}

func (a *App) getTrainingTaskStatus(c *gin.Context) {
	taskID := c.Param("id")
	task, err := a.federatedManager.GetTask(taskID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "task not found"})
		return
	}
	c.JSON(http.StatusOK, task)
}

func (a *App) distributeTrainingTask(c *gin.Context) {
	taskID := c.Param("id")
	clientTasks, err := a.federatedManager.DistributeTask(taskID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"task_id": taskID, "client_tasks": clientTasks})
}

func (a *App) submitGradient(c *gin.Context) {
	taskID := c.Param("id")
	var req struct {
		ClientID string      `json:"client_id" binding:"required"`
		Data     interface{} `json:"data" binding:"required"`
		Weight   float64     `json:"weight" binding:"min=0,max=1"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	dataBytes, ok := req.Data.([]byte)
	if !ok {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid gradient data format"})
		return
	}
	gradient, err := a.federatedManager.SubmitGradient(taskID, req.ClientID, dataBytes, req.Weight)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gradient)
}

func (a *App) aggregateGradients(c *gin.Context) {
	taskID := c.Param("id")
	aggParams, err := a.federatedManager.AggregateGradients(taskID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	result, err := a.federatedManager.UpdateGlobalModel(taskID, aggParams)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, result)
}

func (a *App) getMonitoringMetrics(c *gin.Context) {
	name := c.Query("name")
	if name == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": "metric name required"})
		return
	}

	result, err := a.monitoringManager.GetMetrics(name, nil)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, result)
}

func (a *App) getSnapshots(c *gin.Context) {
	limit := 10
	snapshots, err := a.monitoringManager.GetSnapshots(nil, nil, limit)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, snapshots)
}

func (a *App) getWindowMetrics(c *gin.Context) {
	window, err := a.monitoringManager.GetCurrentWindowMetrics()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, window)
}

func (a *App) migrateSchema(c *gin.Context) {
	var req struct {
		TargetVersion int `json:"target_version"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := a.dataAccessManager.Migrate(c.Request.Context(), req.TargetVersion); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	version, _ := a.dataAccessManager.GetCurrentVersion(c.Request.Context())
	c.JSON(http.StatusOK, gin.H{"status": "migrated", "current_version": version})
}

func (a *App) listMigrations(c *gin.Context) {
	migrations, err := a.dataAccessManager.GetMigrations(c.Request.Context())
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, gin.H{"migrations": migrations})
}

func (a *App) rollbackSchema(c *gin.Context) {
	var req struct {
		TargetVersion int `json:"target_version"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := a.dataAccessManager.Rollback(c.Request.Context(), req.TargetVersion); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	version, _ := a.dataAccessManager.GetCurrentVersion(c.Request.Context())
	c.JSON(http.StatusOK, gin.H{"status": "rolled_back", "current_version": version})
}

func (a *App) exportData(c *gin.Context) {
	var req struct {
		Tables []string `json:"tables"`
		File   string   `json:"file"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.File != "" {
		if err := a.dataAccessManager.ExportToFile(c.Request.Context(), req.Tables, req.File); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}
		c.JSON(http.StatusOK, gin.H{"status": "exported", "file": req.File})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "export_started"})
}

func (a *App) importData(c *gin.Context) {
	var req struct {
		File     string `json:"file" binding:"required"`
		Truncate bool   `json:"truncate"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if err := a.dataAccessManager.ImportFromFile(c.Request.Context(), req.File, req.Truncate); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "imported"})
}

func (a *App) scanAndClassifyData(c *gin.Context) {
	var req struct {
		Data interface{} `json:"data" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	result, processedData, err := a.classificationManager.Process(c.Request.Context(), req.Data)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"classification": result,
		"processed_data": processedData,
		"summary":        result.Summary(),
	})
}

func (a *App) listSensitivePatterns(c *gin.Context) {
	patterns := a.classificationManager.GetPatterns()
	c.JSON(http.StatusOK, gin.H{"patterns": patterns})
}

func (a *App) createPrivacyAccount(c *gin.Context) {
	var req struct {
		EntityID string  `json:"entity_id" binding:"required"`
		Epsilon  float64 `json:"epsilon" binding:"min=0"`
		Delta    float64 `json:"delta" binding:"min=0"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	account, err := a.privacyManager.CreateAccount(req.EntityID, req.Epsilon, req.Delta)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, account)
}

func (a *App) getPrivacyBudget(c *gin.Context) {
	accountID := c.Param("id")
	epsUsage, deltaUsage, err := a.privacyManager.GetBudgetUsage(accountID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	account, err := a.privacyManager.GetAccount(accountID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"account":        account,
		"epsilon_usage":  epsUsage,
		"delta_usage":    deltaUsage,
		"remaining_eps":  account.Epsilon - account.UsedEpsilon,
		"remaining_delta": account.Delta - account.UsedDelta,
	})
}

func (a *App) addPrivacyNoise(c *gin.Context) {
	var req struct {
		AccountID string                           `json:"account_id"`
		Value     float64                          `json:"value" binding:"required"`
		Params    differentialprivacy.NoiseParameters `json:"params"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var noisyValue float64
	var err error

	if req.AccountID != "" {
		noisyValue, err = a.privacyManager.AddNoiseWithBudget(req.AccountID, req.Value, &req.Params)
	} else {
		noisyValue, err = differentialprivacy.AddNoise(req.Value, &req.Params)
	}

	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"original_value": req.Value,
		"noisy_value":    noisyValue,
		"noise_added":    noisyValue - req.Value,
	})
}

func (a *App) Start() error {
	addr := fmt.Sprintf("%s:%d", a.cfg.Server.Host, a.cfg.Server.Port)
	a.server = &http.Server{
		Addr:    addr,
		Handler: a.router,
	}

	logger.Info("Starting server",
		zap.String("address", addr),
		zap.String("version", "1.0.0"),
	)

	go func() {
		if err := a.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("Server failed to start", zap.Error(err))
		}
	}()

	return nil
}

func (a *App) Stop() error {
	logger.Info("Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := a.server.Shutdown(ctx); err != nil {
		logger.Error("Server forced shutdown", zap.Error(err))
		return err
	}

	if a.federatedManager != nil {
		a.federatedManager.Close()
	}

	if a.monitoringManager != nil {
		_ = a.monitoringManager.Shutdown(ctx)
	}

	if a.mpcManager != nil {
		for _, p := range a.mpcManager.ListAllParticipants() {
			a.mpcManager.UnregisterParticipant(p.ID)
		}
	}

	logger.Sync()
	logger.Info("Server shutdown complete")
	return nil
}

func main() {
	cfgPath := os.Getenv("CONFIG_PATH")
	var cfg *config.AppConfig
	var err error

	if cfgPath != "" {
		cfg, err = config.Load(cfgPath)
		if err != nil {
			log.Printf("Warning: Failed to load config from %s, using default: %v", cfgPath, err)
			cfg = config.Default()
		}
	} else {
		cfg = config.Default()
	}

	app, err := NewApp(cfg)
	if err != nil {
		log.Fatalf("Failed to create app: %v", err)
	}

	if err := app.Start(); err != nil {
		log.Fatalf("Failed to start app: %v", err)
	}

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	if err := app.Stop(); err != nil {
		log.Fatalf("Failed to stop app: %v", err)
	}
}
