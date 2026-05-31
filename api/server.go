package api

import (
	"net/http"

	"github.com/edgevision/edgevision/internal/aggregation"
	"github.com/edgevision/edgevision/internal/cache"
	"github.com/edgevision/edgevision/internal/inference"
	"github.com/edgevision/edgevision/internal/lifecycle"
	"github.com/edgevision/edgevision/internal/ota"
	"github.com/edgevision/edgevision/internal/protocol"
	"github.com/edgevision/edgevision/internal/rules"
	"github.com/edgevision/edgevision/internal/shadow"
	"github.com/gin-gonic/gin"
)

type Server struct {
	router            *gin.Engine
	inferenceHandler  *inference.Handler
	rulesHandler      *rules.RuleHandler
	protocolHandler   *protocol.ProtocolHandler
	cacheHandler      *cache.CacheHandler
	otaHandler        *ota.OTAHandler
	aggregationHandler *aggregation.AggregationHandler
	shadowHandler     *shadow.ShadowHandler
	lifecycleHandler  *lifecycle.LifecycleHandler
}

func NewServer(
	inferenceScheduler *inference.Scheduler,
	rulesEngine *rules.Engine,
	protocolAdapter *protocol.Adapter,
	cacheManager *cache.Manager,
	otaManager *ota.Manager,
	aggregator *aggregation.Aggregator,
	shadowManager *shadow.Manager,
	lifecycleManager *lifecycle.Manager,
) *Server {
	r := gin.Default()
	server := &Server{
		router:            r,
		inferenceHandler:  inference.NewHandler(inferenceScheduler),
		rulesHandler:      rules.NewRuleHandler(rulesEngine),
		protocolHandler:   protocol.NewProtocolHandler(protocolAdapter),
		cacheHandler:      cache.NewCacheHandler(cacheManager),
		otaHandler:        ota.NewOTAHandler(otaManager),
		aggregationHandler: aggregation.NewAggregationHandler(aggregator),
		shadowHandler:     shadow.NewShadowHandler(shadowManager),
		lifecycleHandler:  lifecycle.NewLifecycleHandler(lifecycleManager),
	}
	server.setupRoutes()
	return server
}

func (s *Server) setupRoutes() {
	api := s.router.Group("/api/v1")
	api.GET("/health", s.HealthCheck)
	inference := api.Group("/inference")
	{
		inference.POST("/tasks", s.inferenceHandler.SubmitTask)
		inference.GET("/tasks/:id/status", s.inferenceHandler.GetTaskStatus)
		inference.PUT("/config/:namespace", s.inferenceHandler.UpdateConfig)
		inference.GET("/config/:namespace", s.inferenceHandler.GetConfig)
		inference.GET("/configs", s.inferenceHandler.ListConfigs)
	}
	rules := api.Group("/rules")
	{
		rules.POST("", s.rulesHandler.CreateRule)
		rules.GET("", s.rulesHandler.ListRules)
		rules.GET("/:id", s.rulesHandler.GetRule)
		rules.PUT("/:id", s.rulesHandler.UpdateRule)
		rules.DELETE("/:id", s.rulesHandler.DeleteRule)
		rules.GET("/strategies", s.rulesHandler.ListStrategies)
		rules.POST("/strategies/default", s.rulesHandler.SetDefaultStrategy)
		rules.POST("/events", s.rulesHandler.TriggerEvent)
	}
	protocol := api.Group("/protocol")
	{
		protocol.GET("/drivers", s.protocolHandler.ListDrivers)
		protocol.POST("/drivers/connect", s.protocolHandler.ConnectDriver)
		protocol.POST("/convert", s.protocolHandler.ConvertAsync)
		protocol.GET("/convert/:id/status", s.protocolHandler.GetConversionStatus)
		protocol.POST("/read", s.protocolHandler.ReadAsync)
	}
	cache := api.Group("/cache")
	{
		cache.POST("", s.cacheHandler.Store)
		cache.GET("/:id", s.cacheHandler.Get)
		cache.DELETE("/:id", s.cacheHandler.Delete)
		cache.GET("/device/:device_id", s.cacheHandler.ListByDevice)
		cache.GET("/stats", s.cacheHandler.GetStats)
		cache.POST("/network", s.cacheHandler.SetNetworkStatus)
	}
	ota := api.Group("/ota")
	{
		ota.POST("/firmware", s.otaHandler.UploadFirmware)
		ota.GET("/firmware", s.otaHandler.ListFirmwares)
		ota.GET("/firmware/:id", s.otaHandler.GetFirmware)
		ota.DELETE("/firmware/:id", s.otaHandler.DeleteFirmware)
		ota.POST("/jobs", s.otaHandler.CreateJob)
		ota.GET("/jobs", s.otaHandler.ListJobs)
		ota.GET("/jobs/:id", s.otaHandler.GetJob)
		ota.POST("/jobs/:id/cancel", s.otaHandler.CancelJob)
		ota.GET("/strategy", s.otaHandler.GetStrategy)
		ota.PUT("/strategy", s.otaHandler.UpdateStrategy)
	}
	aggregation := api.Group("/aggregation")
	{
		aggregation.POST("/rules", s.aggregationHandler.AddRule)
		aggregation.GET("/rules", s.aggregationHandler.ListRules)
		aggregation.GET("/rules/:id", s.aggregationHandler.GetRule)
		aggregation.PUT("/rules/:id", s.aggregationHandler.UpdateRule)
		aggregation.DELETE("/rules/:id", s.aggregationHandler.DeleteRule)
		aggregation.POST("/ingest", s.aggregationHandler.Ingest)
		aggregation.GET("/results", s.aggregationHandler.GetResults)
	}
	shadow := api.Group("/shadow")
	{
		shadow.GET("/devices", s.shadowHandler.ListDevices)
		shadow.GET("/:device_id", s.shadowHandler.GetShadow)
		shadow.PUT("/:device_id/reported", s.shadowHandler.UpdateReported)
		shadow.PUT("/:device_id/desired", s.shadowHandler.UpdateDesired)
		shadow.GET("/:device_id/delta", s.shadowHandler.GetDelta)
		shadow.POST("/:device_id/merge", s.shadowHandler.Merge)
		shadow.DELETE("/:device_id", s.shadowHandler.DeleteShadow)
	}
	lifecycle := api.Group("/devices")
	{
		lifecycle.POST("/register", s.lifecycleHandler.Register)
		lifecycle.POST("/activate", s.lifecycleHandler.Activate)
		lifecycle.POST("/heartbeat", s.lifecycleHandler.Heartbeat)
		lifecycle.GET("", s.lifecycleHandler.ListDevices)
		lifecycle.GET("/stats", s.lifecycleHandler.GetStats)
		lifecycle.GET("/:id", s.lifecycleHandler.GetDevice)
		lifecycle.PUT("/:id", s.lifecycleHandler.UpdateDevice)
		lifecycle.POST("/:id/status", s.lifecycleHandler.SetStatus)
		lifecycle.POST("/:id/decommission", s.lifecycleHandler.Decommission)
	}
}

func (s *Server) HealthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":  "ok",
		"service": "EdgeVision Video Stream Edge Analysis Engine",
		"version": "1.0.0",
	})
}

func (s *Server) Run(addr string) error {
	return s.router.Run(addr)
}
