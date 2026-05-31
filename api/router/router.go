package router

import (
	"github.com/edgevision/edgevision/api/handler"
	"github.com/edgevision/edgevision/api/middleware"
	"github.com/edgevision/edgevision/internal/domain/aggregation"
	"github.com/edgevision/edgevision/internal/domain/offline"
	"github.com/edgevision/edgevision/internal/domain/ota"
	"github.com/edgevision/edgevision/internal/service"
	"github.com/gin-gonic/gin"
)

type Router struct {
	engine               *gin.Engine
	deviceHandler        *handler.DeviceHandler
	otaHandler           *handler.OTAHandler
	cacheHandler         *handler.OfflineCacheHandler
	aggHandler           *handler.DataAggregationHandler
	inferenceHandler     *handler.InferenceHandler
	shadowHandler        *handler.DeviceShadowHandler
	protocolHandler      *handler.ProtocolHandler
	ruleHandler          *handler.RuleEngineHandler
	enhancementHandler   *handler.EnhancementHandler
}

func NewRouter(
	deviceService *service.DeviceService,
	otaService ota.OTAService,
	cacheService offline.OfflineService,
	aggService aggregation.DataAggregationService,
	inferenceService *service.InferenceService,
	shadowService *service.DeviceShadowService,
	protocolService *service.ProtocolService,
	ruleService *service.RuleEngineService,
) *Router {
	r := &Router{
		engine:             gin.New(),
		deviceHandler:      handler.NewDeviceHandler(deviceService),
		otaHandler:         handler.NewOTAHandler(otaService),
		cacheHandler:       handler.NewOfflineCacheHandler(cacheService),
		aggHandler:         handler.NewDataAggregationHandler(aggService),
		inferenceHandler:   handler.NewInferenceHandler(inferenceService),
		shadowHandler:      handler.NewDeviceShadowHandler(shadowService),
		protocolHandler:    handler.NewProtocolHandler(protocolService),
		ruleHandler:        handler.NewRuleEngineHandler(ruleService),
		enhancementHandler: handler.NewEnhancementHandler(otaService, cacheService, aggService),
	}

	r.setupMiddleware()
	r.setupRoutes()

	return r
}

func (r *Router) setupMiddleware() {
	r.engine.Use(middleware.TraceID())
	r.engine.Use(middleware.Logger())
	r.engine.Use(middleware.Recovery())
	r.engine.Use(middleware.CORS())
	r.engine.Use(middleware.RateLimit(100, 60))
}

func (r *Router) setupRoutes() {
	api := r.engine.Group("/api/v1")
	{
		auth := api.Group("")
		auth.Use(middleware.Auth())
		{
			devices := auth.Group("/devices")
			{
				devices.POST("", r.deviceHandler.Register)
				devices.GET("", r.deviceHandler.List)
				devices.POST("/batch", r.deviceHandler.Batch)
				devices.GET("/:id", r.deviceHandler.GetByID)
				devices.PUT("/:id", r.deviceHandler.Update)
				devices.DELETE("/:id", r.deviceHandler.Delete)
				devices.POST("/:id/activate", r.deviceHandler.Activate)
				devices.POST("/:id/deactivate", r.deviceHandler.Deactivate)
				devices.POST("/:id/heartbeat", r.deviceHandler.Heartbeat)
				devices.GET("/:id/events", r.deviceHandler.GetEvents)
			}

			ota := auth.Group("/ota")
			{
				ota.POST("/firmwares", r.otaHandler.CreateFirmware)
				ota.POST("/delta-packages", r.otaHandler.GenerateDeltaPackage)
				ota.POST("/tasks", r.otaHandler.CreateUpgradeTask)
				ota.POST("/tasks/:id/start", r.otaHandler.StartUpgradeTask)
				ota.GET("/tasks", r.otaHandler.ListTasks)
				ota.GET("/tasks/:id", r.otaHandler.GetTask)
				ota.GET("/tasks/:id/status", r.otaHandler.GetTaskStatus)
				ota.GET("/tasks/:id/devices", r.otaHandler.GetDeviceUpgrades)
				ota.POST("/device-upgrades/:id/status", r.otaHandler.ReportDeviceStatus)
			}

			cache := auth.Group("/offline-cache")
			{
				cache.POST("", r.cacheHandler.CacheData)
				cache.GET("", r.cacheHandler.ListRecords)
				cache.GET("/pending", r.cacheHandler.GetPendingRecords)
				cache.POST("/sync/:device_id", r.cacheHandler.SyncData)
				cache.POST("/network-restored/:device_id", r.cacheHandler.NetworkRestored)
				cache.GET("/sync-sessions", r.cacheHandler.GetSyncSessions)
				cache.GET("/stats", r.cacheHandler.GetStats)
			}

			agg := auth.Group("/data-aggregation")
			{
				agg.POST("/streams", r.aggHandler.CreateStream)
				agg.GET("/streams", r.aggHandler.ListStreams)
				agg.GET("/streams/:id", r.aggHandler.GetStream)
				agg.POST("/streams/:id/ingest", r.aggHandler.IngestDataPoint)
				agg.POST("/streams/:id/aggregate", r.aggHandler.AggregateData)
				agg.GET("/streams/:id/data", r.aggHandler.GetAggregatedData)
				agg.GET("/streams/:id/latest", r.aggHandler.GetLatestAggregatedData)
				agg.GET("/streams/:id/stats", r.aggHandler.GetStats)
			}

			inference := auth.Group("/inference")
			{
				inference.POST("/models", r.inferenceHandler.CreateModel)
				inference.GET("/models", r.inferenceHandler.ListModels)
				inference.GET("/models/:id", r.inferenceHandler.GetModel)
				inference.POST("/deployments", r.inferenceHandler.DeployModel)
				inference.POST("/tasks", r.inferenceHandler.CreateInferenceTask)
				inference.GET("/tasks/:id", r.inferenceHandler.GetTask)
				inference.POST("/tasks/:id/result", r.inferenceHandler.SubmitTaskResult)
			}

			shadow := auth.Group("/device-shadows")
			{
				shadow.GET("/:device_id", r.shadowHandler.GetShadow)
				shadow.PUT("/:device_id/desired", r.shadowHandler.UpdateDesired)
				shadow.PUT("/:device_id/reported", r.shadowHandler.ReportReported)
				shadow.GET("/:device_id/delta", r.shadowHandler.GetDelta)
				shadow.GET("/:device_id/history", r.shadowHandler.GetHistory)
			}

			protocol := auth.Group("/protocol")
			{
				protocol.POST("/drivers", r.protocolHandler.RegisterDriver)
				protocol.GET("/drivers", r.protocolHandler.ListDrivers)
				protocol.POST("/adapters", r.protocolHandler.CreateAdapter)
				protocol.GET("/adapters", r.protocolHandler.ListAdapters)
				protocol.POST("/adapters/:id/start", r.protocolHandler.StartAdapter)
				protocol.POST("/adapters/:id/stop", r.protocolHandler.StopAdapter)
				protocol.GET("/records", r.protocolHandler.GetRecords)
			}

			rules := auth.Group("/rules")
			{
				rules.POST("", r.ruleHandler.CreateRule)
				rules.GET("", r.ruleHandler.ListRules)
				rules.GET("/:id", r.ruleHandler.GetRule)
				rules.POST("/:id/enable", r.ruleHandler.EnableRule)
				rules.POST("/:id/disable", r.ruleHandler.DisableRule)
				rules.POST("/:id/trigger", r.ruleHandler.TriggerRule)
				rules.GET("/executions", r.ruleHandler.GetExecutions)
				rules.GET("/executions/:execution_id/actions", r.ruleHandler.GetActionExecutions)
			}

			otaConfig := auth.Group("/ota/config")
			{
				otaConfig.GET("", r.enhancementHandler.GetOTAConfig)
				otaConfig.GET("/profiles", r.enhancementHandler.ListOTAProfiles)
				otaConfig.PUT("/:profile", r.enhancementHandler.SaveOTAConfig)
				otaConfig.PATCH("/:profile", r.enhancementHandler.UpdateOTAConfig)
			}

			offlineStrategy := auth.Group("/offline/strategy")
			{
				offlineStrategy.GET("", r.enhancementHandler.ListSyncStrategies)
				offlineStrategy.POST("/:device_id", r.enhancementHandler.SetDeviceSyncStrategy)
				offlineStrategy.GET("/:device_id", r.enhancementHandler.GetDeviceSyncStrategy)
				offlineStrategy.PUT("/:strategy/config", r.enhancementHandler.SetStrategyConfig)
			}

			aggAsync := auth.Group("/data-aggregation")
			{
				aggAsync.POST("/streams/:id/async", r.enhancementHandler.AggregateDataAsync)
				aggAsync.GET("/tasks/:task_id", r.enhancementHandler.GetAggregationTaskStatus)
				aggAsync.DELETE("/tasks/:task_id", r.enhancementHandler.CancelAggregationTask)
				aggAsync.GET("/tasks", r.enhancementHandler.ListAggregationTasks)
			}
		}
	}
}

func (r *Router) Engine() *gin.Engine {
	return r.engine
}

func (r *Router) Run(addr string) error {
	return r.engine.Run(addr)
}
