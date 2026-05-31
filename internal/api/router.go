package api

import (
	"time"

	"github.com/chaoslab/platform/internal/common"
	"github.com/gin-gonic/gin"
)

func SetupRouter(handler *APIHandler) *gin.Engine {
	r := gin.New()

	r.Use(
		RequestIDMiddleware(),
		LoggingMiddleware(),
		RecoveryMiddleware(),
		CORSMiddleware(),
		ErrorHandler(),
	)

	api := r.Group("/api/v1")
	{
		resources := api.Group("/resources")
		{
			resources.POST("", handler.CreateResource)
			resources.GET("/:id/status", handler.GetResourceStatus)
			resources.POST("/batch", handler.BatchOperation)
		}

		workflow := api.Group("/workflow")
		{
			workflow.POST("/execute", handler.ExecuteWorkflow)
		}

		dns := api.Group("/dns")
		{
			dns.GET("/resolve/:domain", func(c *gin.Context) {
				domain := c.Param("domain")
				recordType := c.DefaultQuery("type", "A")
				resp, err := handler.dnsService.Resolve(c.Request.Context(), domain, recordType)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": resp})
			})
			dns.POST("/upstreams", func(c *gin.Context) {
				var upstream common.DNSUpstream
				if err := c.ShouldBindJSON(&upstream); err != nil {
					c.Error(err)
					return
				}
				if err := handler.dnsService.AddUpstream(c.Request.Context(), &upstream); err != nil {
					c.Error(err)
					return
				}
				c.JSON(201, gin.H{"code": 201, "message": "upstream added"})
			})
			dns.GET("/upstreams", func(c *gin.Context) {
				list, err := handler.dnsService.GetUpstreams(c.Request.Context())
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": list})
			})
			dns.DELETE("/cache", func(c *gin.Context) {
				if err := handler.dnsService.ClearCache(c.Request.Context()); err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "message": "cache cleared"})
			})
			dns.GET("/cache/stats", func(c *gin.Context) {
				stats, err := handler.dnsService.GetCacheStats(c.Request.Context())
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": stats})
			})
		}

		mtls := api.Group("/mtls")
		{
			mtls.POST("/certificates", func(c *gin.Context) {
				var req common.CertificateRequest
				if err := c.ShouldBindJSON(&req); err != nil {
					c.Error(err)
					return
				}
				cert, err := handler.mtlsService.IssueCertificate(c.Request.Context(), &req)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(201, gin.H{"code": 201, "data": cert})
			})
			mtls.POST("/certificates/:id/rotate", func(c *gin.Context) {
				certID := c.Param("id")
				cert, err := handler.mtlsService.RotateCertificate(c.Request.Context(), certID)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": cert})
			})
			mtls.POST("/certificates/:id/revoke", func(c *gin.Context) {
				certID := c.Param("id")
				var body struct {
					Reason string `json:"reason"`
				}
				c.ShouldBindJSON(&body)
				if err := handler.mtlsService.RevokeCertificate(c.Request.Context(), certID, body.Reason); err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "message": "certificate revoked"})
			})
			mtls.GET("/crl", func(c *gin.Context) {
				crl, err := handler.mtlsService.GetCRL(c.Request.Context())
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": crl})
			})
		}

		chaos := api.Group("/chaos")
		{
			chaos.POST("/scenarios", func(c *gin.Context) {
				var scenario common.ChaosScenario
				if err := c.ShouldBindJSON(&scenario); err != nil {
					c.Error(err)
					return
				}
				created, err := handler.chaosService.DefineScenario(c.Request.Context(), &scenario)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(201, gin.H{"code": 201, "data": created})
			})
			chaos.POST("/scenarios/:id/execute", func(c *gin.Context) {
				scenarioID := c.Param("id")
				var scope common.InjectionScope
				if err := c.ShouldBindJSON(&scope); err != nil {
					c.Error(err)
					return
				}
				run, err := handler.chaosService.ExecuteScenario(c.Request.Context(), scenarioID, &scope)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": run})
			})
			chaos.GET("/runs/:id/status", func(c *gin.Context) {
				runID := c.Param("id")
				status, err := handler.chaosService.GetExecutionStatus(c.Request.Context(), runID)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": status})
			})
			chaos.POST("/runs/:id/cancel", func(c *gin.Context) {
				runID := c.Param("id")
				if err := handler.chaosService.CancelExecution(c.Request.Context(), runID); err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "message": "execution cancelled"})
			})
		}

		traffic := api.Group("/traffic")
		{
			traffic.POST("/canary", func(c *gin.Context) {
				var cfg common.CanaryConfig
				if err := c.ShouldBindJSON(&cfg); err != nil {
					c.Error(err)
					return
				}
				policy, err := handler.trafficService.ConfigureCanary(c.Request.Context(), &cfg)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(201, gin.H{"code": 201, "data": policy})
			})
			traffic.POST("/bluegreen", func(c *gin.Context) {
				var cfg common.BlueGreenConfig
				if err := c.ShouldBindJSON(&cfg); err != nil {
					c.Error(err)
					return
				}
				policy, err := handler.trafficService.ConfigureBlueGreen(c.Request.Context(), &cfg)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(201, gin.H{"code": 201, "data": policy})
			})
			traffic.POST("/mirror", func(c *gin.Context) {
				var cfg common.MirrorConfig
				if err := c.ShouldBindJSON(&cfg); err != nil {
					c.Error(err)
					return
				}
				policy, err := handler.trafficService.ConfigureMirroring(c.Request.Context(), &cfg)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(201, gin.H{"code": 201, "data": policy})
			})
			traffic.POST("/circuitbreaker", func(c *gin.Context) {
				var cfg common.CircuitBreakerConfig
				if err := c.ShouldBindJSON(&cfg); err != nil {
					c.Error(err)
					return
				}
				policy, err := handler.trafficService.ConfigureCircuitBreaker(c.Request.Context(), &cfg)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(201, gin.H{"code": 201, "data": policy})
			})
			traffic.PATCH("/policies/:id/weight", func(c *gin.Context) {
				policyID := c.Param("id")
				var body struct {
					Weight int32 `json:"weight"`
				}
				if err := c.ShouldBindJSON(&body); err != nil {
					c.Error(err)
					return
				}
				if err := handler.trafficService.UpdateTrafficWeight(c.Request.Context(), policyID, body.Weight); err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "message": "weight updated"})
			})
		}

		events := api.Group("/events")
		{
			events.POST("", func(c *gin.Context) {
				var event common.DomainEvent
				if err := c.ShouldBindJSON(&event); err != nil {
					c.Error(err)
					return
				}
				if err := handler.eventService.AppendEvent(c.Request.Context(), &event); err != nil {
					c.Error(err)
					return
				}
				c.JSON(201, gin.H{"code": 201, "message": "event appended"})
			})
			events.GET("/:entityId", func(c *gin.Context) {
				entityID := c.Param("entityId")
				events, err := handler.eventService.GetEvents(c.Request.Context(), entityID, 0)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": events})
			})
			events.POST("/snapshots/:entityId", func(c *gin.Context) {
				entityID := c.Param("entityId")
				var body struct {
					Version int64       `json:"version"`
					State   interface{} `json:"state"`
				}
				if err := c.ShouldBindJSON(&body); err != nil {
					c.Error(err)
					return
				}
				if err := handler.eventService.CreateSnapshot(c.Request.Context(), entityID, body.Version, body.State); err != nil {
					c.Error(err)
					return
				}
				c.JSON(201, gin.H{"code": 201, "message": "snapshot created"})
			})
			events.GET("/timetravel/:entityId", func(c *gin.Context) {
				entityID := c.Param("entityId")
				timeStr := c.Query("time")
				targetTime, err := time.Parse(time.RFC3339, timeStr)
				if err != nil {
					c.Error(common.NewValidationError("invalid time format, use RFC3339", "time"))
					return
				}
				state, err := handler.eventService.TimeTravelQuery(c.Request.Context(), entityID, targetTime)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": state})
			})
			events.GET("/stats", func(c *gin.Context) {
				stats, err := handler.eventService.GetEventStats(c.Request.Context())
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": stats})
			})
		}

		registry := api.Group("/registry")
		{
			registry.POST("/pull", func(c *gin.Context) {
				var body struct {
					Ref    string   `json:"ref"`
					Layers []string `json:"layers"`
				}
				if err := c.ShouldBindJSON(&body); err != nil {
					c.Error(err)
					return
				}
				result, err := handler.registryService.PullImage(c.Request.Context(), body.Ref, body.Layers)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": result})
			})
			registry.POST("/sync", func(c *gin.Context) {
				var body struct {
					SourceRef string `json:"source_ref"`
					TargetRef string `json:"target_ref"`
				}
				if err := c.ShouldBindJSON(&body); err != nil {
					c.Error(err)
					return
				}
				result, err := handler.registryService.SyncImage(c.Request.Context(), body.SourceRef, body.TargetRef)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": result})
			})
			registry.POST("/p2p/enable", func(c *gin.Context) {
				var body struct {
					ImageRef string   `json:"image_ref"`
					Nodes    []string `json:"nodes"`
				}
				if err := c.ShouldBindJSON(&body); err != nil {
					c.Error(err)
					return
				}
				status, err := handler.registryService.EnableP2P(c.Request.Context(), body.ImageRef, body.Nodes)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": status})
			})
		}

		sidecar := api.Group("/sidecar")
		{
			sidecar.POST("/inject", func(c *gin.Context) {
				var body struct {
					Target *common.InjectionTarget `json:"target"`
					Config *common.SidecarConfig   `json:"config"`
				}
				if err := c.ShouldBindJSON(&body); err != nil {
					c.Error(err)
					return
				}
				instance, err := handler.sidecarService.InjectSidecar(c.Request.Context(), body.Target, body.Config)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(201, gin.H{"code": 201, "data": instance})
			})
			sidecar.DELETE("/:id", func(c *gin.Context) {
				instanceID := c.Param("id")
				if err := handler.sidecarService.EjectSidecar(c.Request.Context(), instanceID); err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "message": "sidecar ejected"})
			})
			sidecar.PATCH("/:id/config", func(c *gin.Context) {
				instanceID := c.Param("id")
				var cfg common.SidecarConfig
				if err := c.ShouldBindJSON(&cfg); err != nil {
					c.Error(err)
					return
				}
				if err := handler.sidecarService.HotUpdateConfig(c.Request.Context(), instanceID, &cfg); err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "message": "config updated"})
			})
			sidecar.POST("/policy", func(c *gin.Context) {
				var policy common.InjectionPolicy
				if err := c.ShouldBindJSON(&policy); err != nil {
					c.Error(err)
					return
				}
				if err := handler.sidecarService.SetInjectionPolicy(c.Request.Context(), &policy); err != nil {
					c.Error(err)
					return
				}
				c.JSON(201, gin.H{"code": 201, "message": "policy set"})
			})
		}

		audit := api.Group("/audit")
		{
			audit.POST("/commands", func(c *gin.Context) {
				var cmd common.Command
				if err := c.ShouldBindJSON(&cmd); err != nil {
					c.Error(err)
					return
				}
				if err := handler.auditService.PersistCommand(c.Request.Context(), &cmd); err != nil {
					c.Error(err)
					return
				}
				c.JSON(201, gin.H{"code": 201, "data": cmd})
			})
			audit.GET("/commands/:id", func(c *gin.Context) {
				cmdID := c.Param("id")
				cmd, err := handler.auditService.GetCommand(c.Request.Context(), cmdID)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": cmd})
			})
			audit.GET("/logs", func(c *gin.Context) {
				var filter common.AuditFilter
				c.ShouldBindQuery(&filter)
				logs, err := handler.auditService.GetAuditLogs(c.Request.Context(), &filter)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": logs})
			})
			audit.POST("/report", func(c *gin.Context) {
				var req common.ComplianceRequest
				if err := c.ShouldBindJSON(&req); err != nil {
					c.Error(err)
					return
				}
				report, err := handler.auditService.GenerateComplianceReport(c.Request.Context(), &req)
				if err != nil {
					c.Error(err)
					return
				}
				c.JSON(200, gin.H{"code": 200, "data": report})
			})
		}
	}

	r.GET("/health", handler.HealthCheck)

	return r
}
