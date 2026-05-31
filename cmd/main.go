package main

import (
	"context"
	"log"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/parking-platform/platform/internal/apigateway"
	"github.com/parking-platform/platform/internal/config"
	"github.com/parking-platform/platform/internal/dns"
	"github.com/parking-platform/platform/internal/faultinjection"
	"github.com/parking-platform/platform/internal/imagedistribution"
	"github.com/parking-platform/platform/internal/monitoring"
	"github.com/parking-platform/platform/internal/mtls"
	"github.com/parking-platform/platform/internal/scheduler"
	"github.com/parking-platform/platform/internal/sidecar"
	"github.com/parking-platform/platform/internal/storage"
	"github.com/parking-platform/platform/pkg/models"
)

type App struct {
	logger           *zap.Logger
	certManager      *mtls.CertificateManager
	alertManager     *monitoring.AlertManager
	alertManagerWithCache *monitoring.AlertManagerWithCache
	storageMgr       *storage.StorageManager
	storageMgrWithConfig *storage.StorageManagerWithConfig
	configWatcher    *storage.ConfigWatcher
	sidecarMgr       *sidecar.SidecarManager
	dnsProxy         *dns.DNSProxy
	faultMgr         *faultinjection.FaultManager
	configMgr        *config.ConfigManager
	scheduler        *scheduler.Scheduler
	imageMgr         *imagedistribution.ImageManager
	apiGateway       *apigateway.APIGateway
}

func NewApp() *App {
	logger, _ := zap.NewProduction()

	certManager := mtls.NewCertificateManager()
	certManager.EnablePersistence(mtls.NewFilePersistence("./data/certificates.gob"))
	_ = certManager.LoadFromPersistence()

	metricsCache := monitoring.NewMetricsCache(5*time.Minute, 10000)
	alertManagerWithCache := monitoring.NewAlertManagerWithCache(metricsCache, nil)

	storageAdapter := storage.NewInMemoryStorage()
	storageMgr := storage.NewStorageManager(storageAdapter)
	configWatcher := storage.NewConfigWatcher("./data/storage_config.json", 5*time.Second)
	storageMgrWithConfig := storage.NewStorageManagerWithConfig(storageAdapter, configWatcher)

	return &App{
		logger:                logger,
		certManager:           certManager,
		alertManager:          alertManagerWithCache.AlertManager,
		alertManagerWithCache: alertManagerWithCache,
		storageMgr:            storageMgr,
		storageMgrWithConfig:  storageMgrWithConfig,
		configWatcher:         configWatcher,
		sidecarMgr:            sidecar.NewSidecarManager(),
		dnsProxy:              dns.NewDNSProxy(nil),
		faultMgr:              faultinjection.NewFaultManager(nil),
		configMgr:             config.NewConfigManager(),
		scheduler:             scheduler.NewScheduler(nil),
		imageMgr:              imagedistribution.NewImageManager(nil, nil),
		apiGateway:            apigateway.NewAPIGateway(),
	}
}

func main() {
	app := NewApp()
	defer app.logger.Sync()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if app.configWatcher != nil {
		_ = app.configWatcher.Start(ctx)
	}

	if app.alertManagerWithCache != nil {
		go func() {
			hotMetrics := []string{"error_rate", "latency_p99", "throughput", "requests_total"}
			_ = app.alertManagerWithCache.WarmUp(ctx, hotMetrics)
		}()
	}

	r := gin.Default()

	r.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{"status": "ok"})
	})

	api := r.Group("/api/v1")

	api.POST("/resources", app.createResource)
	api.GET("/resources/:id/status", app.getResourceStatus)
	api.POST("/resources/batch", app.batchOperations)

	api.GET("/certificates", app.listCertificates)
	api.POST("/certificates", app.issueCertificate)
	api.POST("/certificates/:id/revoke", app.revokeCertificate)
	api.POST("/certificates/:id/rotate", app.rotateCertificate)
	api.POST("/certificates/persist", app.persistCertificates)
	api.POST("/certificates/restore", app.restoreCertificates)

	api.GET("/alert-rules", app.listAlertRules)
	api.POST("/alert-rules", app.createAlertRule)
	api.POST("/alert-rules/:id/toggle", app.toggleAlertRule)
	api.GET("/alerts", app.listAlerts)
	api.POST("/metrics", app.recordMetric)
	api.POST("/alerts/evaluate", app.evaluateAlerts)
	api.GET("/metrics/cache/stats", app.getCacheStats)
	api.POST("/metrics/cache/invalidate", app.invalidateCache)
	api.POST("/metrics/cache/warmup", app.warmupCache)

	api.POST("/storage/:bucket/:key", app.storeObject)
	api.GET("/storage/:bucket/:key", app.getObject)
	api.DELETE("/storage/:bucket/:key", app.deleteObject)
	api.GET("/storage/:bucket", app.listObjects)
	api.GET("/storage/config", app.getStorageConfig)
	api.POST("/storage/config/reload", app.reloadStorageConfig)

	api.GET("/sidecars", app.listSidecars)
	api.POST("/sidecars", app.registerSidecar)
	api.PUT("/sidecars/:id/config", app.updateSidecarConfig)
	api.POST("/sidecars/inject", app.injectSidecar)

	api.GET("/dns/upstreams", app.listDNSUpstreams)
	api.POST("/dns/upstreams", app.addDNSUpstream)
	api.GET("/dns/resolve/:domain", app.resolveDNS)

	api.GET("/faults", app.listFaultScenarios)
	api.POST("/faults", app.createFaultScenario)
	api.POST("/faults/:id/inject", app.injectFault)
	api.POST("/faults/:id/rollback", app.rollbackFault)

	api.GET("/configs/:namespace", app.getConfig)
	api.POST("/configs/:namespace", app.createConfig)
	api.GET("/configs/:namespace/versions", app.listConfigVersions)
	api.POST("/configs/:namespace/rollback", app.rollbackConfig)

	api.GET("/tasks", app.listTasks)
	api.POST("/tasks", app.createTask)
	api.POST("/tasks/:id/run", app.runTask)

	api.POST("/images/pull", app.pullImage)
	api.POST("/images/sync", app.syncImage)

	api.GET("/routes", app.listRoutes)
	api.POST("/routes", app.addRoute)

	log.Fatal(r.Run(":8080"))
}

type createResourceRequest struct {
	Type   string                 `json:"type"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

func (app *App) createResource(c *gin.Context) {
	var req createResourceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}

	c.JSON(201, gin.H{
		"code": 201,
		"data": gin.H{
			"id":     "rsc_466",
			"status": "provisioning",
		},
	})
}

func (app *App) getResourceStatus(c *gin.Context) {
	id := c.Param("id")
	c.JSON(200, gin.H{
		"code": 200,
		"data": gin.H{
			"id":       id,
			"status":   "completed",
			"progress": 0.8,
		},
	})
}

type batchOperation struct {
	Action string `json:"action"`
	ID     string `json:"id"`
}

type batchRequest struct {
	Operations []batchOperation `json:"operations"`
}

func (app *App) batchOperations(c *gin.Context) {
	var req batchRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	results := make([]gin.H, 0, len(req.Operations))
	for _, op := range req.Operations {
		results = append(results, gin.H{
			"id":     op.ID,
			"action": op.Action,
			"status": "ok",
		})
	}
	c.JSON(200, gin.H{
		"code": 200,
		"data": gin.H{
			"batch_id": "batch_602",
			"results":  results,
		},
	})
}

func (app *App) listCertificates(c *gin.Context) {
	c.JSON(200, gin.H{"data": app.certManager.ListCertificates()})
}

type issueCertRequest struct {
	CN      string   `json:"cn"`
	SANs    []string `json:"sans"`
	Validity int64    `json:"validity_seconds"`
}

func (app *App) issueCertificate(c *gin.Context) {
	var req issueCertRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	if req.Validity == 0 {
		req.Validity = 86400
	}
	cert, err := app.certManager.IssueCertificate(req.CN, req.SANs, time.Duration(req.Validity)*time.Second)
	if err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	c.JSON(201, gin.H{"data": cert})
}

func (app *App) revokeCertificate(c *gin.Context) {
	id := c.Param("id")
	if err := app.certManager.RevokeCertificate(id); err != nil {
		c.JSON(404, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"status": "ok"})
}

func (app *App) rotateCertificate(c *gin.Context) {
	id := c.Param("id")
	cert, err := app.certManager.RotateIfNeeded(id)
	if err != nil {
		c.JSON(404, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"data": cert})
}

func (app *App) listAlertRules(c *gin.Context) {
	c.JSON(200, gin.H{"data": app.alertManager.ListRules()})
}

type createRuleRequest struct {
	Name        string            `json:"name"`
	Expression  string            `json:"expression"`
	Severity    string            `json:"severity"`
	Annotations map[string]string `json:"annotations"`
}

func (app *App) createAlertRule(c *gin.Context) {
	var req createRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	rule := app.alertManager.AddRule(req.Name, req.Expression, req.Severity, req.Annotations)
	c.JSON(201, gin.H{"data": rule})
}

func (app *App) toggleAlertRule(c *gin.Context) {
	id := c.Param("id")
	var body struct {
		Enabled bool `json:"enabled"`
	}
	if err := c.ShouldBindJSON(&body); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	if err := app.alertManager.UpdateRule(id, body.Enabled); err != nil {
		c.JSON(404, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"status": "ok"})
}

func (app *App) listAlerts(c *gin.Context) {
	c.JSON(200, gin.H{"data": app.alertManager.ListNotifications()})
}

type recordMetricRequest struct {
	Name  string  `json:"name"`
	Value float64 `json:"value"`
}

func (app *App) recordMetric(c *gin.Context) {
	var req recordMetricRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	app.alertManager.RecordMetric(req.Name, req.Value)
	c.JSON(200, gin.H{"status": "ok"})
}

func (app *App) evaluateAlerts(c *gin.Context) {
	alerts := app.alertManager.EvaluateRules()
	c.JSON(200, gin.H{"fired": alerts})
}

func (app *App) storeObject(c *gin.Context) {
	bucket := c.Param("bucket")
	key := c.Param("key")
	contentType := c.GetHeader("Content-Type")
	data, _ := c.GetRawData()

	var tags map[string]string
	_ = c.ShouldBindHeader(&tags)

	meta, err := app.storageMgr.Store(bucket, key, data, contentType, tags)
	if err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	c.JSON(201, gin.H{"data": meta})
}

func (app *App) getObject(c *gin.Context) {
	bucket := c.Param("bucket")
	key := c.Param("key")
	data, err := app.storageMgr.Retrieve(bucket, key)
	if err != nil {
		c.JSON(404, gin.H{"error": err.Error()})
		return
	}
	c.Data(200, "application/octet-stream", data)
}

func (app *App) deleteObject(c *gin.Context) {
	bucket := c.Param("bucket")
	key := c.Param("key")
	if err := app.storageMgr.Remove(bucket, key); err != nil {
		c.JSON(404, gin.H{"error": err.Error()})
		return
	}
	c.JSON(204, nil)
}

func (app *App) listObjects(c *gin.Context) {
	bucket := c.Param("bucket")
	objects, err := app.storageMgr.ListObjects(bucket)
	if err != nil {
		c.JSON(404, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"data": objects})
}

func (app *App) listSidecars(c *gin.Context) {
	c.JSON(200, gin.H{"data": app.sidecarMgr.ListSidecars()})
}

type registerSidecarRequest struct {
	Name            string                 `json:"name"`
	Image           string                 `json:"image"`
	InjectionPolicy string                 `json:"injection_policy"`
	Resources       models.ResourceLimit   `json:"resources"`
	Config          map[string]interface{} `json:"config"`
	HotReload       bool                   `json:"hot_reload"`
}

func (app *App) registerSidecar(c *gin.Context) {
	var req registerSidecarRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	spec := app.sidecarMgr.RegisterSidecar(req.Name, req.Image, req.InjectionPolicy, req.Resources, req.Config, req.HotReload)
	c.JSON(201, gin.H{"data": spec})
}

func (app *App) updateSidecarConfig(c *gin.Context) {
	id := c.Param("id")
	var config map[string]interface{}
	if err := c.ShouldBindJSON(&config); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	if err := app.sidecarMgr.UpdateConfig(id, config); err != nil {
		c.JSON(404, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"status": "ok"})
}

type injectRequest struct {
	Namespace string   `json:"namespace"`
	SidecarIDs []string `json:"sidecar_ids"`
}

func (app *App) injectSidecar(c *gin.Context) {
	var req injectRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	if err := app.sidecarMgr.Inject(req.Namespace, req.SidecarIDs); err != nil {
		c.JSON(404, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"status": "ok"})
}

func (app *App) listDNSUpstreams(c *gin.Context) {
	c.JSON(200, gin.H{"data": app.dnsProxy.ListUpstreams()})
}

type addUpstreamRequest struct {
	Name     string `json:"name"`
	Address  string `json:"address"`
	Priority int    `json:"priority"`
}

func (app *App) addDNSUpstream(c *gin.Context) {
	var req addUpstreamRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	upstream := app.dnsProxy.AddUpstream(req.Name, req.Address, req.Priority)
	c.JSON(201, gin.H{"data": upstream})
}

func (app *App) resolveDNS(c *gin.Context) {
	domain := c.Param("domain")
	records, err := app.dnsProxy.SmartResolve(domain)
	if err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"records": records})
}

func (app *App) listFaultScenarios(c *gin.Context) {
	c.JSON(200, gin.H{"data": app.faultMgr.ListScenarios()})
}

type createFaultRequest struct {
	Name         string                 `json:"name"`
	Type         string                 `json:"type"`
	Target       models.FaultTarget     `json:"target"`
	Duration     int64                  `json:"duration"`
	AutoRollback bool                   `json:"auto_rollback"`
	Parameters   map[string]interface{} `json:"parameters"`
}

func (app *App) createFaultScenario(c *gin.Context) {
	var req createFaultRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	scenario := app.faultMgr.CreateScenario(req.Name, req.Type, req.Target, req.Duration, req.AutoRollback, req.Parameters)
	c.JSON(201, gin.H{"data": scenario})
}

func (app *App) injectFault(c *gin.Context) {
	id := c.Param("id")
	if err := app.faultMgr.InjectScenario(id); err != nil {
		c.JSON(404, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"status": "injected"})
}

func (app *App) rollbackFault(c *gin.Context) {
	id := c.Param("id")
	if err := app.faultMgr.RollbackScenario(id); err != nil {
		c.JSON(404, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"status": "rolled_back"})
}

func (app *App) getConfig(c *gin.Context) {
	namespace := c.Param("namespace")
	cfg, ok := app.configMgr.Get(namespace)
	if !ok {
		c.JSON(404, gin.H{"error": "config not found"})
		return
	}
	c.JSON(200, gin.H{"data": cfg})
}

type createConfigRequest struct {
	Parameters map[string]interface{} `json:"parameters"`
	Enabled    bool                   `json:"enabled"`
}

func (app *App) createConfig(c *gin.Context) {
	namespace := c.Param("namespace")
	var req createConfigRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	cfg := app.configMgr.Create(namespace, req.Parameters, req.Enabled)
	c.JSON(201, gin.H{"data": cfg})
}

func (app *App) listConfigVersions(c *gin.Context) {
	namespace := c.Param("namespace")
	c.JSON(200, gin.H{"data": app.configMgr.ListVersions(namespace)})
}

type rollbackConfigRequest struct {
	Version int64 `json:"version"`
}

func (app *App) rollbackConfig(c *gin.Context) {
	namespace := c.Param("namespace")
	var req rollbackConfigRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	cfg, err := app.configMgr.Rollback(namespace, req.Version)
	if err != nil {
		c.JSON(404, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"data": cfg})
}

func (app *App) listTasks(c *gin.Context) {
	c.JSON(200, gin.H{"data": app.scheduler.ListTasks()})
}

type createTaskRequest struct {
	Name         string   `json:"name"`
	Dependencies []string `json:"dependencies"`
}

func (app *App) createTask(c *gin.Context) {
	var req createTaskRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	task := app.scheduler.AddTask(req.Name, req.Dependencies)
	c.JSON(201, gin.H{"data": task})
}

func (app *App) runTask(c *gin.Context) {
	id := c.Param("id")
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	go app.scheduler.Run(ctx, id)
	c.JSON(200, gin.H{"status": "started"})
}

type pullImageRequest struct {
	Registry string `json:"registry"`
	Repo     string `json:"repo"`
	Tag      string `json:"tag"`
}

func (app *App) pullImage(c *gin.Context) {
	var req pullImageRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	manifest, _, err := app.imageMgr.PullImage(ctx, req.Registry, req.Repo, req.Tag)
	if err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"manifest": manifest})
}

type syncImageRequest struct {
	SourceRegistry string `json:"source_registry"`
	TargetRegistry string `json:"target_registry"`
	Repo           string `json:"repo"`
	Tag            string `json:"tag"`
}

func (app *App) syncImage(c *gin.Context) {
	var req syncImageRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()

	if err := app.imageMgr.SyncRegistry(ctx, req.SourceRegistry, req.TargetRegistry, req.Repo, req.Tag); err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"status": "synced"})
}

func (app *App) listRoutes(c *gin.Context) {
	c.JSON(200, gin.H{"data": app.apiGateway.ListRoutes()})
}

type addRouteRequest struct {
	Path        string `json:"path"`
	Method      string `json:"method"`
	Backend     string `json:"backend"`
	Protocol    string `json:"protocol"`
	RewritePath string `json:"rewrite_path"`
	Timeout     int    `json:"timeout"`
}

func (app *App) addRoute(c *gin.Context) {
	var req addRouteRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	if req.Method == "" {
		req.Method = "GET"
	}
	route := app.apiGateway.AddRoute(req.Path, req.Method, req.Backend, req.Protocol, req.RewritePath, req.Timeout)
	c.JSON(201, gin.H{"data": route})
}

func (app *App) persistCertificates(c *gin.Context) {
	if err := app.certManager.Persist(); err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"status": "persisted"})
}

func (app *App) restoreCertificates(c *gin.Context) {
	if err := app.certManager.LoadFromPersistence(); err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"status": "restored"})
}

func (app *App) getCacheStats(c *gin.Context) {
	if app.alertManagerWithCache == nil {
		c.JSON(500, gin.H{"error": "cache not enabled"})
		return
	}
	c.JSON(200, app.alertManagerWithCache.CacheStats())
}

type invalidateCacheRequest struct {
	Metrics []string `json:"metrics"`
}

func (app *App) invalidateCache(c *gin.Context) {
	if app.alertManagerWithCache == nil {
		c.JSON(500, gin.H{"error": "cache not enabled"})
		return
	}
	var req invalidateCacheRequest
	_ = c.ShouldBindJSON(&req)
	app.alertManagerWithCache.InvalidateCache(req.Metrics...)
	c.JSON(200, gin.H{"status": "invalidated"})
}

type warmupCacheRequest struct {
	Metrics []string `json:"metrics"`
}

func (app *App) warmupCache(c *gin.Context) {
	if app.alertManagerWithCache == nil {
		c.JSON(500, gin.H{"error": "cache not enabled"})
		return
	}
	var req warmupCacheRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := app.alertManagerWithCache.WarmUp(ctx, req.Metrics); err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"status": "warmed_up"})
}

func (app *App) getStorageConfig(c *gin.Context) {
	if app.storageMgrWithConfig == nil {
		c.JSON(500, gin.H{"error": "config watcher not enabled"})
		return
	}
	c.JSON(200, gin.H{"data": app.storageMgrWithConfig.CurrentConfig()})
}

func (app *App) reloadStorageConfig(c *gin.Context) {
	if app.configWatcher == nil {
		c.JSON(500, gin.H{"error": "config watcher not enabled"})
		return
	}
	if err := app.configWatcher.ReloadNow(); err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"status": "reloaded"})
}
