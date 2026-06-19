package apiserver

import (
	"context"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/gin-contrib/cors"
	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.uber.org/zap"
	"golang.org/x/time/rate"
	"gorm.io/gorm"

	"github.com/df1-96/experiment/internal/config"
	"github.com/df1-96/experiment/pkg/util"
)

type Server struct {
	cfg    *config.Config
	db     *gorm.DB
	logger *zap.Logger
	router *gin.Engine
	server *http.Server
}

func NewServer(cfg *config.Config, db *gorm.DB) *Server {
	logger := util.With(zap.String("component", "apiserver"))

	if cfg.Log.Level == config.LogLevelDebug {
		gin.SetMode(gin.DebugMode)
	} else {
		gin.SetMode(gin.ReleaseMode)
	}

	router := gin.New()

	s := &Server{
		cfg:    cfg,
		db:     db,
		logger: logger,
		router: router,
	}

	s.setupMiddleware()
	s.setupRoutes()

	return s
}

func (s *Server) setupMiddleware() {
	s.router.Use(gin.Recovery())

	s.router.Use(s.corsMiddleware())

	s.router.Use(s.loggingMiddleware())

	s.router.Use(s.rateLimitMiddleware())

	s.router.Use(s.authMiddleware())

	s.router.Use(s.prometheusMiddleware())
}

func (s *Server) corsMiddleware() gin.HandlerFunc {
	corsConfig := cors.DefaultConfig()
	corsConfig.AllowAllOrigins = true
	corsConfig.AllowMethods = []string{"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"}
	corsConfig.AllowHeaders = []string{"Origin", "Content-Type", "Accept", "Authorization", "X-Request-ID"}
	corsConfig.ExposeHeaders = []string{"Content-Length", "X-Total-Count", "X-Request-ID"}
	corsConfig.AllowCredentials = true
	corsConfig.MaxAge = 12 * time.Hour

	return cors.New(corsConfig)
}

func (s *Server) loggingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path
		query := c.Request.URL.RawQuery
		requestID := c.GetHeader("X-Request-ID")
		if requestID == "" {
			requestID = util.GenerateIDString()
		}
		c.Set("request_id", requestID)
		c.Header("X-Request-ID", requestID)

		c.Next()

		cost := time.Since(start)
		statusCode := c.Writer.Status()
		clientIP := c.ClientIP()
		method := c.Request.Method
		errors := c.Errors.String()

		logFields := []zap.Field{
			zap.String("request_id", requestID),
			zap.Int("status", statusCode),
			zap.String("method", method),
			zap.String("path", path),
			zap.String("query", query),
			zap.String("ip", clientIP),
			zap.Duration("duration", cost),
		}

		if errors != "" {
			logFields = append(logFields, zap.String("errors", errors))
		}

		if statusCode >= 500 {
			s.logger.Error("HTTP Request", logFields...)
		} else if statusCode >= 400 {
			s.logger.Warn("HTTP Request", logFields...)
		} else {
			s.logger.Info("HTTP Request", logFields...)
		}
	}
}

type IPRateLimiter struct {
	ips map[string]*rate.Limiter
}

func NewIPRateLimiter() *IPRateLimiter {
	return &IPRateLimiter{
		ips: make(map[string]*rate.Limiter),
	}
}

func (i *IPRateLimiter) GetLimiter(ip string) *rate.Limiter {
	limiter, exists := i.ips[ip]
	if !exists {
		limiter = rate.NewLimiter(rate.Limit(100), 200)
		i.ips[ip] = limiter
	}
	return limiter
}

func (s *Server) rateLimitMiddleware() gin.HandlerFunc {
	limiter := NewIPRateLimiter()

	return func(c *gin.Context) {
		ip := c.ClientIP()
		l := limiter.GetLimiter(ip)
		if !l.Allow() {
			c.JSON(http.StatusTooManyRequests, gin.H{
				"error": "Rate limit exceeded",
				"code":  429,
			})
			c.Abort()
			return
		}
		c.Next()
	}
}

func (s *Server) authMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if strings.HasPrefix(c.Request.URL.Path, "/health") ||
			strings.HasPrefix(c.Request.URL.Path, "/metrics") ||
			strings.HasPrefix(c.Request.URL.Path, "/api/v1/experiments") ||
			strings.HasPrefix(c.Request.URL.Path, "/api/v1/tasks") ||
			strings.HasPrefix(c.Request.URL.Path, "/api/v1/workers") {
			c.Next()
			return
		}

		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.JSON(http.StatusUnauthorized, gin.H{
				"error": "Authorization header is required",
				"code":  401,
			})
			c.Abort()
			return
		}

		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || parts[0] != "Bearer" {
			c.JSON(http.StatusUnauthorized, gin.H{
				"error": "Invalid authorization format",
				"code":  401,
			})
			c.Abort()
			return
		}

		token := parts[1]
		if token == "" {
			c.JSON(http.StatusUnauthorized, gin.H{
				"error": "Token is empty",
				"code":  401,
			})
			c.Abort()
			return
		}

		c.Set("user_id", int64(1))
		c.Next()
	}
}

func (s *Server) prometheusMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		duration := time.Since(start).Seconds()

		status := strconv.Itoa(c.Writer.Status())
		method := c.Request.Method
		endpoint := c.FullPath()

		HTTPRequestDuration.WithLabelValues(method, endpoint, status).Observe(duration)
		HTTPRequestTotal.WithLabelValues(method, endpoint, status).Inc()
	}
}

func (s *Server) setupRoutes() {
	s.router.GET("/health", s.healthCheck)
	s.router.GET("/metrics", gin.WrapH(promhttp.Handler()))

	apiV1 := s.router.Group("/api/v1")
	{
		expHandler := NewExperimentHandler(s.db, s.logger)
		expGroup := apiV1.Group("/experiments")
		{
			expGroup.GET("", expHandler.List)
			expGroup.POST("", expHandler.Create)
			expGroup.GET("/:id", expHandler.Get)
			expGroup.PUT("/:id", expHandler.Update)
			expGroup.DELETE("/:id", expHandler.Delete)
			expGroup.POST("/:id/start", expHandler.Start)
			expGroup.POST("/:id/pause", expHandler.Pause)
			expGroup.POST("/:id/resume", expHandler.Resume)
			expGroup.POST("/:id/cancel", expHandler.Cancel)
		}

		taskHandler := NewTaskHandler(s.db, s.logger)
		expGroup.GET("/:expId/tasks", taskHandler.ListByExperiment)
		taskGroup := apiV1.Group("/tasks")
		{
			taskGroup.GET("/:id", taskHandler.Get)
			taskGroup.GET("/:id/checkpoints", taskHandler.GetCheckpoints)
			taskGroup.GET("/:id/results", taskHandler.GetResults)
		}

		workerHandler := NewWorkerHandler(s.db, s.logger)
		workerGroup := apiV1.Group("/workers")
		{
			workerGroup.GET("", workerHandler.List)
			workerGroup.GET("/:id", workerHandler.Get)
			workerGroup.GET("/:id/history", workerHandler.GetHistory)
		}

		resultHandler := NewResultHandler(s.db, s.logger)
		expResultGroup := expGroup.Group("/:expId/results")
		{
			expResultGroup.GET("", resultHandler.List)
			expResultGroup.GET("/statistics", resultHandler.GetStatistics)
			expResultGroup.GET("/sensitivity", resultHandler.GetSensitivity)
			expResultGroup.GET("/export/csv", resultHandler.ExportCSV)
			expResultGroup.GET("/export/parquet", resultHandler.ExportParquet)
		}
	}
}

func (s *Server) Router() *gin.Engine {
	return s.router
}

func (s *Server) healthCheck(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":    "healthy",
		"timestamp": time.Now().UTC(),
		"service":   "experiment-apiserver",
	})
}

func (s *Server) Run() error {
	addr := ":" + strconv.Itoa(s.cfg.Server.HTTPPort)

	s.server = &http.Server{
		Addr:         addr,
		Handler:      s.router,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  120 * time.Second,
	}

	go func() {
		s.logger.Info("Starting HTTP server", zap.String("addr", addr))
		if err := s.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			s.logger.Fatal("Failed to start HTTP server", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	s.logger.Info("Shutting down HTTP server...")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := s.server.Shutdown(ctx); err != nil {
		s.logger.Fatal("Server forced to shutdown", zap.Error(err))
		return err
	}

	s.logger.Info("HTTP server exited properly")
	return nil
}
