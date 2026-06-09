package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
	"github.com/solocoder/cloudci/internal/common/errors"
	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/logger"
	"github.com/solocoder/cloudci/internal/pipeline"
	"github.com/solocoder/cloudci/internal/plugin"
	"github.com/solocoder/cloudci/internal/scheduler"
	"github.com/solocoder/cloudci/internal/secret"
	"github.com/solocoder/cloudci/internal/storage"
	"github.com/solocoder/cloudci/internal/trigger"
	"go.uber.org/zap"
	"golang.org/x/time/rate"
	"gorm.io/gorm"
)

type APIServer struct {
	cfg            *config.Config
	db             *gorm.DB
	redisClient    *redis.Client
	router         *gin.Engine
	parser         *pipeline.Parser
	scheduler      *scheduler.Scheduler
	triggerAdapter *trigger.TriggerAdapter
	pluginMgr      *plugin.PluginManager
	secretMgr      *secret.SecretManager
	rateLimiters   map[string]*rate.Limiter
	limiterMu      sync.RWMutex
}

type Response struct {
	Code      int         `json:"code"`
	Message   string      `json:"message"`
	Data      interface{} `json:"data,omitempty"`
	RequestID string      `json:"request_id"`
}

type PaginationRequest struct {
	Page     int `form:"page,default=1"`
	PageSize int `form:"page_size,default=20"`
}

type PaginationResponse struct {
	Total    int64       `json:"total"`
	Page     int         `json:"page"`
	PageSize int         `json:"page_size"`
	Items    interface{} `json:"items"`
}

func NewAPIServer(cfg *config.Config) *APIServer {
	gin.SetMode(cfg.Server.Mode)

	server := &APIServer{
		cfg:          cfg,
		db:           storage.GetDB(),
		redisClient:  storage.GetRedis(),
		router:       gin.New(),
		parser:       pipeline.NewParser(),
		rateLimiters: make(map[string]*rate.Limiter),
	}

	server.router.Use(
		server.requestIDMiddleware(),
		server.loggerMiddleware(),
		server.corsMiddleware(),
		server.recoveryMiddleware(),
	)

	return server
}

func (s *APIServer) SetScheduler(sched *scheduler.Scheduler) {
	s.scheduler = sched
}

func (s *APIServer) SetTriggerAdapter(ta *trigger.TriggerAdapter) {
	s.triggerAdapter = ta
}

func (s *APIServer) SetPluginManager(pm *plugin.PluginManager) {
	s.pluginMgr = pm
}

func (s *APIServer) SetSecretManager(sm *secret.SecretManager) {
	s.secretMgr = sm
}

func (s *APIServer) Router() *gin.Engine {
	return s.router
}

func (s *APIServer) Run(addr string) error {
	logger.Info("starting API server", zap.String("addr", addr))
	return s.router.Run(addr)
}

func (s *APIServer) requestIDMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		requestID := c.GetHeader("X-Request-ID")
		if requestID == "" {
			requestID = uuid.NewString()
		}
		c.Set("request_id", requestID)
		c.Header("X-Request-ID", requestID)
		c.Next()
	}
}

func (s *APIServer) loggerMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		query := c.Request.URL.RawQuery

		c.Next()

		requestID, _ := c.Get("request_id")
		logger.Info("http request",
			zap.String("request_id", requestID.(string)),
			zap.String("method", c.Request.Method),
			zap.String("path", path),
			zap.String("query", query),
			zap.Int("status", c.Writer.Status()),
			zap.String("client_ip", c.ClientIP()),
			zap.String("user_agent", c.Request.UserAgent()),
			zap.Duration("duration", time.Since(start)),
		)
	}
}

func (s *APIServer) corsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key, X-Request-ID")
		c.Header("Access-Control-Expose-Headers", "X-Request-ID")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}

func (s *APIServer) authMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		apiKey := c.GetHeader("X-API-Key")
		if apiKey == "" {
			apiKey = c.GetHeader("Authorization")
			if strings.HasPrefix(apiKey, "Bearer ") {
				apiKey = strings.TrimPrefix(apiKey, "Bearer ")
			}
		}

		if apiKey == "" {
			s.sendError(c, errors.New(errors.ErrCodeUnauthorized, "API key is required"))
			c.Abort()
			return
		}

		hash := sha256.Sum256([]byte(apiKey))
		hashedKey := hex.EncodeToString(hash[:])

		var storedKey struct {
			ID     types.ID `gorm:"column:id"`
			Secret string   `gorm:"column:secret_hash"`
		}

		if err := s.db.Table("api_keys").Where("secret_hash = ? AND revoked = ?", hashedKey, false).First(&storedKey).Error; err != nil {
			s.sendError(c, errors.New(errors.ErrCodeUnauthorized, "Invalid API key"))
			c.Abort()
			return
		}

		c.Set("api_key_id", storedKey.ID)
		c.Next()
	}
}

func (s *APIServer) rateLimitMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		clientIP := c.ClientIP()

		s.limiterMu.RLock()
		limiter, exists := s.rateLimiters[clientIP]
		s.limiterMu.RUnlock()

		if !exists {
			s.limiterMu.Lock()
			limiter = rate.NewLimiter(rate.Limit(100), 200)
			s.rateLimiters[clientIP] = limiter
			s.limiterMu.Unlock()
		}

		if !limiter.Allow() {
			s.sendError(c, errors.New(errors.ErrCodeQuotaExceeded, "Rate limit exceeded"))
			c.Abort()
			return
		}

		c.Next()
	}
}

func (s *APIServer) recoveryMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				requestID, _ := c.Get("request_id")
				logger.Error("panic recovered",
					zap.Any("error", err),
					zap.String("request_id", requestID.(string)),
					zap.Stack("stack"),
				)
				s.sendError(c, errors.New(errors.ErrCodeInternal, "Internal server error"))
				c.Abort()
			}
		}()
		c.Next()
	}
}

func (s *APIServer) sendSuccess(c *gin.Context, data interface{}) {
	requestID, _ := c.Get("request_id")
	c.JSON(http.StatusOK, Response{
		Code:      http.StatusOK,
		Message:   "success",
		Data:      data,
		RequestID: requestID.(string),
	})
}

func (s *APIServer) sendCreated(c *gin.Context, data interface{}) {
	requestID, _ := c.Get("request_id")
	c.JSON(http.StatusCreated, Response{
		Code:      http.StatusCreated,
		Message:   "created",
		Data:      data,
		RequestID: requestID.(string),
	})
}

func (s *APIServer) sendError(c *gin.Context, err error) {
	requestID, _ := c.Get("request_id")

	var apiErr *errors.Error
	if e, ok := err.(*errors.Error); ok {
		apiErr = e
	} else {
		apiErr = errors.Wrap(err, errors.ErrCodeInternal, "Internal server error")
	}

	c.JSON(apiErr.HTTPStatus(), Response{
		Code:      apiErr.HTTPStatus(),
		Message:   apiErr.Message,
		Data:      apiErr.Details,
		RequestID: requestID.(string),
	})
}

func (s *APIServer) getPagination(c *gin.Context) (offset, limit int, err error) {
	var req PaginationRequest
	if err = c.ShouldBindQuery(&req); err != nil {
		return 0, 0, errors.Wrap(err, errors.ErrCodeValidation, "Invalid pagination parameters")
	}

	if req.Page < 1 {
		req.Page = 1
	}
	if req.PageSize < 1 || req.PageSize > 100 {
		req.PageSize = 20
	}

	return (req.Page - 1) * req.PageSize, req.PageSize, nil
}

func (s *APIServer) paginateResponse(c *gin.Context, total int64, items interface{}) *PaginationResponse {
	var req PaginationRequest
	c.ShouldBindQuery(&req)
	if req.Page < 1 {
		req.Page = 1
	}
	if req.PageSize < 1 || req.PageSize > 100 {
		req.PageSize = 20
	}

	return &PaginationResponse{
		Total:    total,
		Page:     req.Page,
		PageSize: req.PageSize,
		Items:    items,
	}
}

func (s *APIServer) getIDParam(c *gin.Context, name string) (types.ID, error) {
	id := c.Param(name)
	if id == "" {
		return "", errors.New(errors.ErrCodeValidation, name+" is required")
	}
	return types.ID(id), nil
}

func (s *APIServer) healthCheck(c *gin.Context) {
	ctx := context.Background()

	status := map[string]interface{}{
		"status":    "healthy",
		"timestamp": time.Now().Format(time.RFC3339),
	}

	dbStatus := "healthy"
	if err := s.db.WithContext(ctx).Raw("SELECT 1").Error; err != nil {
		dbStatus = "unhealthy"
		status["status"] = "degraded"
	}
	status["database"] = dbStatus

	redisStatus := "healthy"
	if err := s.redisClient.Ping(ctx).Err(); err != nil {
		redisStatus = "unhealthy"
		status["status"] = "degraded"
	}
	status["redis"] = redisStatus

	s.sendSuccess(c, status)
}
