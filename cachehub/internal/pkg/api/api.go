package api

import (
	"net/http"
	"strconv"

	"github.com/cachehub/internal/pkg/alert"
	"github.com/cachehub/internal/pkg/cache_manager"
	"github.com/cachehub/internal/pkg/cache_readwrite"
	"github.com/cachehub/internal/pkg/expiration"
	"github.com/cachehub/internal/pkg/models"
	"github.com/cachehub/internal/pkg/monitoring"
	"github.com/cachehub/internal/pkg/preheating"
	"github.com/cachehub/internal/pkg/strategy"
	"github.com/cachehub/internal/pkg/synchronization"
	"github.com/gin-gonic/gin"
	"github.com/sirupsen/logrus"
)

type APIServer struct {
	cm    *cache_manager.CacheManager
	rw    *cache_readwrite.CacheReadWrite
	sm    *strategy.StrategyManager
	mm    *monitoring.MonitoringManager
	em    *expiration.ExpirationManager
	pm    *preheating.PreheatingManager
	syncM *synchronization.SyncManager
	am    *alert.AlertManager
	logger *logrus.Logger
	engine *gin.Engine
}

func NewAPIServer(
	cm *cache_manager.CacheManager,
	rw *cache_readwrite.CacheReadWrite,
	sm *strategy.StrategyManager,
	mm *monitoring.MonitoringManager,
	em *expiration.ExpirationManager,
	pm *preheating.PreheatingManager,
	syncM *synchronization.SyncManager,
	am *alert.AlertManager,
	logger *logrus.Logger,
) *APIServer {
	gin.SetMode(gin.ReleaseMode)

	server := &APIServer{
		cm:     cm,
		rw:     rw,
		sm:     sm,
		mm:     mm,
		em:     em,
		pm:     pm,
		syncM:  syncM,
		am:      am,
		logger:  logger,
		engine:  gin.New(),
	}

	server.engine.Use(gin.Recovery())
	server.registerRoutes()
	return server
}

func (s *APIServer) registerRoutes() {
	api := s.engine.Group("/api/v1")

	api.POST("/cache/operate", s.handleCacheOperate)
	api.POST("/cache/policy", s.handleSetPolicy)
	api.GET("/cache/stats", s.handleGetStats)
	api.GET("/cache/stats/history", s.handleGetStatsHistory)

	api.POST("/cache/instances", s.handleRegisterInstance)
	api.GET("/cache/instances", s.handleListInstances)
	api.GET("/cache/instances/:cache_id", s.handleGetInstance)
	api.PUT("/cache/instances/:cache_id", s.handleUpdateInstance)
	api.DELETE("/cache/instances/:cache_id", s.handleRemoveInstance)

	api.POST("/cache/invalidate", s.handleInvalidate)
	api.GET("/cache/expire-records", s.handleGetExpireRecords)

	api.POST("/cache/alert", s.handleSetAlert)
	api.GET("/cache/alert/:alert_id", s.handleGetAlert)
	api.DELETE("/cache/alert/:alert_id", s.handleRemoveAlert)
	api.GET("/cache/alert-events", s.handleGetAlertEvents)

	api.POST("/sync/config", s.handleSetSyncConfig)
	api.POST("/sync/full/:cache_id", s.handleFullSync)
	api.GET("/sync/consistency/:cache_id", s.handleCheckConsistency)

	api.POST("/preheat/execute", s.handleExecutePreheat)
	api.GET("/preheat/result/:task_id", s.handleGetPreheatResult)
}

func (s *APIServer) Run(addr string) error {
	s.logger.Infof("API server starting on %s", addr)
	return s.engine.Run(addr)
}

func (s *APIServer) handleCacheOperate(c *gin.Context) {
	var req models.CacheOperationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.CacheOperationResponse{
			Code:  400,
			Error: "invalid request body",
		})
		return
	}

	switch req.Operation {
	case "get":
		value, found, err := s.rw.Get(req.CacheID, req.Key)
		if err != nil {
			c.JSON(http.StatusOK, models.CacheOperationResponse{
				Code:  500,
				Error: err.Error(),
			})
			return
		}
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code: 200,
			Data: map[string]interface{}{
				"found": found,
				"value": value,
			},
		})

	case "set":
		err := s.rw.Set(req.CacheID, req.Key, req.Value, req.TTL)
		if err != nil {
			c.JSON(http.StatusOK, models.CacheOperationResponse{
				Code:  500,
				Error: err.Error(),
			})
			return
		}

		s.syncM.SyncSet(req.CacheID, req.Key, req.Value, req.TTL)

		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code: 200,
			Data: map[string]interface{}{
				"success": true,
			},
		})

	case "delete":
		deleted, err := s.rw.Delete(req.CacheID, req.Key)
		if err != nil {
			c.JSON(http.StatusOK, models.CacheOperationResponse{
				Code:  500,
				Error: err.Error(),
			})
			return
		}

		s.syncM.SyncDelete(req.CacheID, req.Key)

		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code: 200,
			Data: map[string]interface{}{
				"deleted": deleted,
			},
		})

	case "exists":
		exists, err := s.rw.Exists(req.CacheID, req.Key)
		if err != nil {
			c.JSON(http.StatusOK, models.CacheOperationResponse{
				Code:  500,
				Error: err.Error(),
			})
			return
		}
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code: 200,
			Data: map[string]interface{}{
				"exists": exists,
			},
		})

	case "keys":
		keys, err := s.rw.Keys(req.CacheID)
		if err != nil {
			c.JSON(http.StatusOK, models.CacheOperationResponse{
				Code:  500,
				Error: err.Error(),
			})
			return
		}
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code: 200,
			Data: map[string]interface{}{
				"keys": keys,
			},
		})

	case "flush":
		count, err := s.rw.Flush(req.CacheID)
		if err != nil {
			c.JSON(http.StatusOK, models.CacheOperationResponse{
				Code:  500,
				Error: err.Error(),
			})
			return
		}
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code: 200,
			Data: map[string]interface{}{
				"flushed": count,
			},
		})

	default:
		c.JSON(http.StatusBadRequest, models.CacheOperationResponse{
			Code:  400,
			Error: "unknown operation",
		})
	}
}

func (s *APIServer) handleSetPolicy(c *gin.Context) {
	var policy models.CachePolicy
	if err := c.ShouldBindJSON(&policy); err != nil {
		c.JSON(http.StatusBadRequest, models.CacheOperationResponse{
			Code:  400,
			Error: "invalid request body",
		})
		return
	}

	err := s.sm.SetPolicy(&policy)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"policy_id": policy.PolicyID,
		},
	})
}

func (s *APIServer) handleGetStats(c *gin.Context) {
	cacheID := c.Query("cache_id")

	if cacheID == "" {
		allStats := s.mm.GetAllStats()
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code: 200,
			Data: map[string]interface{}{
				"stats": allStats,
			},
		})
		return
	}

	stats, err := s.mm.GetStats(cacheID)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"stats": stats,
		},
	})
}

func (s *APIServer) handleGetStatsHistory(c *gin.Context) {
	cacheID := c.Query("cache_id")
	limitStr := c.Query("limit")
	limit := 10

	if limitStr != "" {
		if l, err := strconv.Atoi(limitStr); err == nil {
			limit = l
		}
	}

	history, err := s.mm.GetStatsHistory(cacheID, limit)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"history": history,
		},
	})
}

func (s *APIServer) handleRegisterInstance(c *gin.Context) {
	var instance models.CacheInstance
	if err := c.ShouldBindJSON(&instance); err != nil {
		c.JSON(http.StatusBadRequest, models.CacheOperationResponse{
			Code:  400,
			Error: "invalid request body",
		})
		return
	}

	err := s.cm.RegisterInstance(&instance)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"cache_id": instance.CacheID,
		},
	})
}

func (s *APIServer) handleListInstances(c *gin.Context) {
	instances := s.cm.ListInstances()
	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"instances": instances,
		},
	})
}

func (s *APIServer) handleGetInstance(c *gin.Context) {
	cacheID := c.Param("cache_id")
	instance, err := s.cm.GetInstance(cacheID)
	if err != nil {
		c.JSON(http.StatusNotFound, models.CacheOperationResponse{
			Code:  404,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"instance": instance,
		},
	})
}

func (s *APIServer) handleUpdateInstance(c *gin.Context) {
	cacheID := c.Param("cache_id")
	var updates models.CacheInstance
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, models.CacheOperationResponse{
			Code:  400,
			Error: "invalid request body",
		})
		return
	}

	err := s.cm.UpdateInstance(cacheID, &updates)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"success": true,
		},
	})
}

func (s *APIServer) handleRemoveInstance(c *gin.Context) {
	cacheID := c.Param("cache_id")
	err := s.cm.RemoveInstance(cacheID)
	if err != nil {
		c.JSON(http.StatusNotFound, models.CacheOperationResponse{
			Code:  404,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"success": true,
		},
	})
}

func (s *APIServer) handleInvalidate(c *gin.Context) {
	var req struct {
		CacheID string `json:"cache_id"`
		Key     string `json:"key"`
		Pattern string `json:"pattern"`
		Reason  string `json:"reason"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.CacheOperationResponse{
			Code:  400,
			Error: "invalid request body",
		})
		return
	}

	reason := req.Reason
	if reason == "" {
		reason = "manual_invalidation"
	}

	if req.Pattern != "" {
		count := s.em.InvalidateByPattern(req.CacheID, req.Pattern)
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code: 200,
			Data: map[string]interface{}{
				"invalidated": count,
			},
		})
		return
	}

	if req.Key != "" {
		invalidated := s.em.InvalidateKey(req.CacheID, req.Key, reason)
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code: 200,
			Data: map[string]interface{}{
				"invalidated": invalidated,
			},
		})
		return
	}

	c.JSON(http.StatusBadRequest, models.CacheOperationResponse{
		Code:  400,
		Error: "key or pattern required",
	})
}

func (s *APIServer) handleGetExpireRecords(c *gin.Context) {
	cacheID := c.Query("cache_id")
	limitStr := c.Query("limit")
	limit := 50

	if limitStr != "" {
		if l, err := strconv.Atoi(limitStr); err == nil {
			limit = l
		}
	}

	records, err := s.em.GetExpireRecords(cacheID, limit)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"records": records,
		},
	})
}

func (s *APIServer) handleSetAlert(c *gin.Context) {
	var config models.AlertConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		c.JSON(http.StatusBadRequest, models.CacheOperationResponse{
			Code:  400,
			Error: "invalid request body",
		})
		return
	}

	err := s.am.SetConfig(&config)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"alert_id": config.AlertID,
		},
	})
}

func (s *APIServer) handleGetAlert(c *gin.Context) {
	alertID := c.Param("alert_id")
	config, err := s.am.GetConfig(alertID)
	if err != nil {
		c.JSON(http.StatusNotFound, models.CacheOperationResponse{
			Code:  404,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"alert": config,
		},
	})
}

func (s *APIServer) handleRemoveAlert(c *gin.Context) {
	alertID := c.Param("alert_id")
	err := s.am.RemoveConfig(alertID)
	if err != nil {
		c.JSON(http.StatusNotFound, models.CacheOperationResponse{
			Code:  404,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"success": true,
		},
	})
}

func (s *APIServer) handleGetAlertEvents(c *gin.Context) {
	cacheID := c.Query("cache_id")
	limitStr := c.Query("limit")
	limit := 50

	if limitStr != "" {
		if l, err := strconv.Atoi(limitStr); err == nil {
			limit = l
		}
	}

	events, err := s.am.GetAlertEvents(cacheID, limit)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"events": events,
		},
	})
}

func (s *APIServer) handleSetSyncConfig(c *gin.Context) {
	var config synchronization.SyncConfig
	if err := c.ShouldBindJSON(&config); err != nil {
		c.JSON(http.StatusBadRequest, models.CacheOperationResponse{
			Code:  400,
			Error: "invalid request body",
		})
		return
	}

	err := s.syncM.RegisterSyncConfig(&config)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"success": true,
		},
	})
}

func (s *APIServer) handleFullSync(c *gin.Context) {
	cacheID := c.Param("cache_id")
	count, err := s.syncM.FullSync(cacheID)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"synced": count,
		},
	})
}

func (s *APIServer) handleCheckConsistency(c *gin.Context) {
	cacheID := c.Param("cache_id")
	consistency, err := s.syncM.CheckConsistency(cacheID)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"consistency": consistency,
		},
	})
}

func (s *APIServer) handleExecutePreheat(c *gin.Context) {
	var req struct {
		TaskID  string   `json:"task_id"`
		CacheID string   `json:"cache_id"`
		Keys    []string `json:"keys"`
		TTL     int      `json:"ttl"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, models.CacheOperationResponse{
			Code:  400,
			Error: "invalid request body",
		})
		return
	}

	task := &preheating.PreheatTask{
		CacheID: req.CacheID,
		Keys:    req.Keys,
		TTL:     req.TTL,
		Loader: func(key string) (interface{}, error) {
			return map[string]interface{}{
				"_preloaded": true,
				"key":         key,
				"timestamp":   "preheated",
			}, nil
		},
	}

	err := s.pm.RegisterTask(req.TaskID, task)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	result, err := s.pm.ExecuteTask(req.TaskID)
	if err != nil {
		c.JSON(http.StatusOK, models.CacheOperationResponse{
			Code:  500,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"result": result,
		},
	})
}

func (s *APIServer) handleGetPreheatResult(c *gin.Context) {
	taskID := c.Param("task_id")
	result, err := s.pm.GetTaskResult(taskID)
	if err != nil {
		c.JSON(http.StatusNotFound, models.CacheOperationResponse{
			Code:  404,
			Error: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, models.CacheOperationResponse{
		Code: 200,
		Data: map[string]interface{}{
			"result": result,
		},
	})
}
