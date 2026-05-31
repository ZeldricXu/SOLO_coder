package gateway

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"session154/internal/logger"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

type Config struct {
	Port            int           `json:"port"`
	JWTSecret       string        `json:"jwt_secret"`
	TokenExpiration time.Duration `json:"token_expiration"`
	RateLimit       int           `json:"rate_limit"`
	RateLimitWindow time.Duration `json:"rate_limit_window"`
}

type APIGateway struct {
	config      Config
	router      *gin.Engine
	auth        *Authenticator
	authorizer  *Authorizer
	rateLimiter *RateLimitMiddleware
	server      *http.Server
	mu          sync.Mutex
	started     bool
	stopped     bool
}

func NewAPIGateway(cfg Config) *APIGateway {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.Use(gin.Recovery())

	gw := &APIGateway{
		config:      cfg,
		router:      router,
		auth:        NewAuthenticator(cfg.JWTSecret, cfg.TokenExpiration),
		authorizer:  NewAuthorizer(),
		rateLimiter: NewRateLimitMiddleware(cfg.RateLimit, cfg.RateLimitWindow),
	}

	gw.setupMiddleware()
	gw.setupHealthEndpoint()

	return gw
}

func (gw *APIGateway) setupMiddleware() {
	gw.router.Use(gw.rateLimiter.Limit())
	gw.router.Use(gw.requestLogger())
}

func (gw *APIGateway) requestLogger() gin.HandlerFunc {
	return func(ctx *gin.Context) {
		start := time.Now()
		path := ctx.Request.URL.Path

		ctx.Next()

		latency := time.Since(start)
		statusCode := ctx.Writer.Status()

		logger.Info("api request",
			zap.String("path", path),
			zap.String("method", ctx.Request.Method),
			zap.Int("status", statusCode),
			zap.Duration("latency", latency),
			zap.String("client_ip", ctx.ClientIP()),
		)
	}
}

func (gw *APIGateway) setupHealthEndpoint() {
	gw.router.GET("/health", func(ctx *gin.Context) {
		ctx.JSON(http.StatusOK, gin.H{"status": "ok", "timestamp": time.Now().UTC()})
	})

	auth := gw.router.Group("/api/v1/auth")
	{
		auth.POST("/login", gw.Login)
	}
}

func (gw *APIGateway) Login(ctx *gin.Context) {
	var req struct {
		UserID   string `json:"user_id" binding:"required"`
		Password string `json:"password" binding:"required"`
	}

	if err := ctx.ShouldBindJSON(&req); err != nil {
		ctx.JSON(http.StatusBadRequest, gin.H{"code": 400, "msg": "invalid request"})
		return
	}

	token, err := gw.auth.Login(req.UserID, req.Password)
	if err != nil {
		ctx.JSON(http.StatusUnauthorized, gin.H{"code": 401, "msg": "invalid credentials"})
		return
	}

	ctx.JSON(http.StatusOK, gin.H{"code": 200, "data": gin.H{"token": token}})
}

func (gw *APIGateway) Router() *gin.Engine      { return gw.router }
func (gw *APIGateway) Auth() *Authenticator    { return gw.auth }
func (gw *APIGateway) Authorizer() *Authorizer { return gw.authorizer }

func (gw *APIGateway) Start() error {
	gw.mu.Lock()
	if gw.started {
		gw.mu.Unlock()
		return errors.New("gateway already started")
	}

	addr := fmt.Sprintf(":%d", gw.config.Port)
	if gw.config.Port <= 0 {
		addr = ":8080"
	}

	gw.server = &http.Server{
		Addr:    addr,
		Handler: gw.router,
	}

	gw.started = true
	gw.mu.Unlock()

	logger.Info("api gateway starting", zap.String("addr", addr))

	errChan := make(chan error, 1)
	go func() {
		if err := gw.server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Error("gateway server error", zap.Error(err))
			errChan <- err
			return
		}
		errChan <- nil
	}()

	select {
	case err := <-errChan:
		if err != nil {
			gw.mu.Lock()
			gw.started = false
			gw.mu.Unlock()
			return err
		}
	case <-time.After(100 * time.Millisecond):
	}

	return nil
}

func (gw *APIGateway) Stop(ctx context.Context) error {
	gw.mu.Lock()
	if !gw.started || gw.stopped {
		gw.mu.Unlock()
		return nil
	}
	gw.stopped = true
	gw.mu.Unlock()

	if gw.server != nil {
		if err := gw.server.Shutdown(ctx); err != nil {
			logger.Error("gateway shutdown error", zap.Error(err))
			return err
		}
	}

	gw.mu.Lock()
	gw.started = false
	gw.stopped = false
	gw.mu.Unlock()

	logger.Info("api gateway stopped")
	return nil
}
