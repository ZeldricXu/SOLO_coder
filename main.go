package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"session172/internal/cdc"
	"session172/internal/config"
	"session172/internal/core"
	"session172/internal/dataaccess"
	"session172/internal/datquality"
	"session172/internal/gateway"
	"session172/internal/logger"
	"session172/internal/monitoring"
	"session172/internal/storage"
	"session172/internal/timeseries"
	"session172/pkg/models"
	"session172/pkg/utils"
)

func main() {
	logger.Init(&logger.Config{
		Level: "debug",
		Debug: true,
	})
	defer logger.Sync()

	logger.Info("Starting session172 application...")

	cfg := config.NewManager("default", nil)
	defer cfg.Close()

	monitor := monitoring.GetMonitor()
	gateway := gateway.GetGateway()
	qualityEngine := datquality.GetEngine()
	compressor := timeseries.GetCompressor()
	store := storage.GetStorageManager()
	cdcCapture := cdc.GetCDCCapture()
	processor := core.GetProcessor()

	qualityEngine.Start()
	defer qualityEngine.Stop()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go monitor.StartCollection(ctx, 30*time.Second)
	go cdcCapture.Start(ctx)

	setupRoutes(gateway, monitor, qualityEngine, compressor, store, cdcCapture, processor)

	server := &http.Server{
		Addr:    ":8080",
		Handler: gateway.GetRouter(),
	}

	go func() {
		logger.Info("Server starting on :8080")
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatalf("Failed to start server: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("Shutting down server...")

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer shutdownCancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Errorf("Server forced to shutdown: %v", err)
	}

	pool := dataaccess.GetPool()
	if pool != nil {
		pool.Close()
	}

	cdcCapture.Stop()
	store.Close()

	logger.Info("Application stopped")
}

func setupRoutes(g *gateway.Gateway, monitor *monitoring.Monitor,
	quality *datquality.RuleEngine, compressor *timeseries.Compressor,
	store *storage.StorageManager, cdcCap *cdc.CDCCapture,
	processor *core.Processor) {

	api := g.Group("/api/v1")

	api.POST("/resources", createResourceHandler)
	api.GET("/resources/:id/status", getResourceStatusHandler)
	api.POST("/resources/batch", batchOperationHandler)

	api.GET("/monitor/metrics", gin.WrapH(monitor.HTTPHandler()))
	api.GET("/monitor/snapshots", getSnapshotsHandler(monitor))
	api.GET("/monitor/health", healthCheckHandler(monitor))

	api.POST("/quality/rules", addQualityRuleHandler(quality))
	api.GET("/quality/rules", getQualityRulesHandler(quality))
	api.POST("/quality/rules/:id/execute", executeQualityRuleHandler(quality))
	api.GET("/quality/results", getQualityResultsHandler(quality))

	api.POST("/timeseries/compress", compressTimeseriesHandler(compressor))
	api.GET("/timeseries/blocks", getCompressedBlocksHandler(compressor))

	api.POST("/storage/files", uploadFileHandler(store))
	api.GET("/storage/files", listFilesHandler(store))
	api.DELETE("/storage/files/:path", deleteFileHandler(store))

	api.POST("/cdc/events", processCDCEventHandler(cdcCap))
	api.GET("/cdc/events", getCDCEventsHandler(cdcCap))

	api.POST("/process", processDataHandler(processor))

	api.GET("/config", getConfigHandler())
	api.POST("/config/reload", reloadConfigHandler())

	api.GET("/pool/scenarios", getPoolScenariosHandler())
	api.POST("/pool/scenario/:scenario", setPoolScenarioHandler())
	api.GET("/pool/config", getPoolConfigHandler())

	api.GET("/quality/strategies", getQualityStrategiesHandler())
	api.POST("/quality/strategy/:strategy", setQualityStrategyHandler())
	api.POST("/quality/rules/:id/strategy/:strategy", setRuleStrategyHandler())
	api.POST("/quality/execute-strategy/:id", executeQualityWithStrategyHandler(quality))

	api.POST("/async/process", gateway.AsyncEndpoint(asyncProcessHandler))
	api.GET("/async/result/:request_id", gateway.GetAsyncResultEndpoint)
	api.DELETE("/async/request/:request_id", gateway.CancelAsyncRequestEndpoint)
	api.GET("/async/stats", getAsyncStatsHandler())
}

func createResourceHandler(c *gin.Context) {
	var req models.ResourceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.ResourceResponse{
			Code:    400,
			Message: "Invalid request: " + err.Error(),
		})
		return
	}

	resourceID := utils.GenerateID("rsc")

	c.JSON(http.StatusCreated, models.ResourceResponse{
		Code: 201,
		Data: map[string]interface{}{
			"id":     resourceID,
			"status": "provisioning",
			"type":   req.Type,
		},
	})
}

func getResourceStatusHandler(c *gin.Context) {
	id := c.Param("id")

	c.JSON(http.StatusOK, models.ResourceResponse{
		Code: 200,
		Data: map[string]interface{}{
			"id":       id,
			"status":   "running",
			"progress": 0.75,
		},
	})
}

func batchOperationHandler(c *gin.Context) {
	var req struct {
		Operations []map[string]interface{} `json:"operations"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.BatchResponse{
			Code:    400,
			Message: "Invalid request",
		})
		return
	}

	batchID := utils.GenerateID("batch")
	results := make([]map[string]interface{}, 0)

	for _, op := range req.Operations {
		action, _ := op["action"].(string)
		id, _ := op["id"].(string)
		results = append(results, map[string]interface{}{
			"id":     id,
			"action": action,
			"status": "success",
		})
	}

	c.JSON(http.StatusOK, models.BatchResponse{
		Code: 200,
		Data: models.BatchResponseData{
			BatchID: batchID,
			Results: results,
		},
	})
}

func getSnapshotsHandler(monitor *monitoring.Monitor) gin.HandlerFunc {
	return func(c *gin.Context) {
		limit := 10
		snapshots := monitor.GetSnapshots(limit)
		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": snapshots,
		})
	}
}

func healthCheckHandler(monitor *monitoring.Monitor) gin.HandlerFunc {
	return func(c *gin.Context) {
		health := monitor.HealthCheck()
		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": health,
		})
	}
}

func addQualityRuleHandler(engine *datquality.RuleEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		var rule models.DataQualityRule
		if err := c.ShouldBindJSON(&rule); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
			return
		}

		if err := engine.AddRule(&rule); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
			return
		}

		c.JSON(http.StatusCreated, gin.H{"code": 201, "data": rule})
	}
}

func getQualityRulesHandler(engine *datquality.RuleEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		rules := engine.GetAllRules()
		c.JSON(http.StatusOK, gin.H{"code": 200, "data": rules})
	}
}

func executeQualityRuleHandler(engine *datquality.RuleEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		ruleID := c.Param("id")
		result, err := engine.ExecuteRule(c.Request.Context(), ruleID)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
			return
		}
		c.JSON(http.StatusOK, gin.H{"code": 200, "data": result})
	}
}

func getQualityResultsHandler(engine *datquality.RuleEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		ruleID := c.Query("rule_id")
		limit := 50
		results := engine.GetResults(ruleID, limit)
		c.JSON(http.StatusOK, gin.H{"code": 200, "data": results})
	}
}

func compressTimeseriesHandler(compressor *timeseries.Compressor) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			Points     []models.TimeSeriesPoint `json:"points"`
			Resolution string                   `json:"resolution"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
			return
		}

		resolution, err := time.ParseDuration(req.Resolution)
		if err != nil {
			resolution = time.Minute
		}

		block, err := compressor.Compress(req.Points, resolution)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{"code": 200, "data": block})
	}
}

func getCompressedBlocksHandler(compressor *timeseries.Compressor) gin.HandlerFunc {
	return func(c *gin.Context) {
		blocks := compressor.GetAllBlocks()
		c.JSON(http.StatusOK, gin.H{"code": 200, "data": blocks})
	}
}

func uploadFileHandler(store *storage.StorageManager) gin.HandlerFunc {
	return func(c *gin.Context) {
		file, err := c.FormFile("file")
		if err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
			return
		}

		f, err := file.Open()
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
			return
		}
		defer f.Close()

		stored, err := store.SaveReader(file.Filename, f, file.Header.Get("Content-Type"))
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
			return
		}

		c.JSON(http.StatusCreated, gin.H{"code": 201, "data": stored})
	}
}

func listFilesHandler(store *storage.StorageManager) gin.HandlerFunc {
	return func(c *gin.Context) {
		prefix := c.Query("prefix")
		files, err := store.List(prefix)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
			return
		}
		c.JSON(http.StatusOK, gin.H{"code": 200, "data": files})
	}
}

func deleteFileHandler(store *storage.StorageManager) gin.HandlerFunc {
	return func(c *gin.Context) {
		path := c.Param("path")
		if err := store.Delete(path); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
			return
		}
		c.JSON(http.StatusOK, gin.H{"code": 200, "message": "File deleted"})
	}
}

func processCDCEventHandler(capture *cdc.CDCCapture) gin.HandlerFunc {
	return func(c *gin.Context) {
		var rawData map[string]interface{}
		if err := c.ShouldBindJSON(&rawData); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
			return
		}

		data, _ := utils.ToJSON(rawData)
		event, err := capture.Process(c.Request.Context(), []byte(data))
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{"code": 200, "data": event})
	}
}

func getCDCEventsHandler(capture *cdc.CDCCapture) gin.HandlerFunc {
	return func(c *gin.Context) {
		limit := 100
		events := capture.GetEvents(limit)
		c.JSON(http.StatusOK, gin.H{"code": 200, "data": events})
	}
}

func processDataHandler(processor *core.Processor) gin.HandlerFunc {
	return func(c *gin.Context) {
		var req struct {
			Data  interface{}            `json:"data"`
			Rules map[string]interface{} `json:"rules"`
		}
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": err.Error()})
			return
		}

		result, err := processor.Process(c.Request.Context(), req.Data, req.Rules)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": err.Error()})
			return
		}

		c.JSON(http.StatusOK, gin.H{"code": 200, "data": result})
	}
}

func getConfigHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		cfg := config.GetManager()
		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": cfg.GetAllConfigs(),
		})
	}
}

func reloadConfigHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"code":    200,
			"message": "Config reloaded",
		})
	}
}

func getPoolScenariosHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		dc := dataaccess.GetDynamicConfig()
		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": dc.GetScenarioInfo(),
		})
	}
}

func setPoolScenarioHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		scenario := c.Param("scenario")
		dc := dataaccess.GetDynamicConfig()

		if err := dc.SetScenario(dataaccess.Scenario(scenario)); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{
				"code":    400,
				"message": err.Error(),
			})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code":    200,
			"message": "Scenario changed",
			"data":    dc.GetActiveConfig(),
		})
	}
}

func getPoolConfigHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		dc := dataaccess.GetDynamicConfig()
		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": gin.H{
				"scenario":        dc.GetScenario(),
				"active_config":   dc.GetActiveConfig(),
				"all_scenarios":   dc.GetScenarios(),
			},
		})
	}
}

func getQualityStrategiesHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		sm := datquality.GetStrategyManager()
		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": gin.H{
				"active_strategy":     sm.GetStrategy(),
				"available_strategies": sm.GetAvailableStrategies(),
			},
		})
	}
}

func setQualityStrategyHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		strategy := c.Param("strategy")
		sm := datquality.GetStrategyManager()

		if err := sm.SetStrategy(datquality.StrategyType(strategy)); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{
				"code":    400,
				"message": err.Error(),
			})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code":    200,
			"message": "Strategy changed",
			"data":    sm.GetStrategy(),
		})
	}
}

func setRuleStrategyHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		ruleID := c.Param("id")
		strategy := c.Param("strategy")
		sm := datquality.GetStrategyManager()

		if err := sm.SetRuleStrategy(ruleID, datquality.StrategyType(strategy)); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{
				"code":    400,
				"message": err.Error(),
			})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code":    200,
			"message": "Rule strategy changed",
		})
	}
}

func executeQualityWithStrategyHandler(engine *datquality.RuleEngine) gin.HandlerFunc {
	return func(c *gin.Context) {
		ruleID := c.Param("id")
		sm := datquality.GetStrategyManager()
		pool := dataaccess.GetPool()
		if pool == nil {
			c.JSON(http.StatusInternalServerError, gin.H{
				"code":    500,
				"message": "Connection pool not initialized",
			})
			return
		}
		db := pool.GetDB()

		result, err := sm.ExecuteWithStrategy(c.Request.Context(), engine, ruleID, db)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{
				"code":    500,
				"message": err.Error(),
			})
			return
		}

		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": result,
		})
	}
}

func asyncProcessHandler(ctx context.Context, request map[string]interface{}) (interface{}, error) {
	action, _ := request["action"].(string)
	data, _ := request["data"]

	time.Sleep(1 * time.Second)

	return map[string]interface{}{
		"action":  action,
		"result":  "processed",
		"data":    data,
		"processed_at": time.Now(),
	}, nil
}

func getAsyncStatsHandler() gin.HandlerFunc {
	return func(c *gin.Context) {
		am := gateway.GetAsyncManager()
		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": am.GetStats(),
		})
	}
}
