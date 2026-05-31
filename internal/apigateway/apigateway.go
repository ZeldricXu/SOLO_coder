package apigateway

import (
	"context"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/datatransform/platform/pkg/logger"
	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"go.uber.org/zap"
)

type User struct {
	ID       string
	Username string
	Roles    []string
}

type AuthConfig struct {
	JWTSecret string
}

type RateLimiterConfig struct {
	RequestsPerSecond int
	BurstSize         int
}

type RateLimiter struct {
	config    RateLimiterConfig
	tokens    map[string]int
	lastCheck map[string]time.Time
	mu        sync.Mutex
}

type APIGateway struct {
	authConfig  AuthConfig
	rateLimiter *RateLimiter
}

func NewAPIGateway(authConfig AuthConfig, rateLimiterConfig RateLimiterConfig) *APIGateway {
	return &APIGateway{
		authConfig:  authConfig,
		rateLimiter: NewRateLimiter(rateLimiterConfig),
	}
}

func NewRateLimiter(config RateLimiterConfig) *RateLimiter {
	return &RateLimiter{
		config:    config,
		tokens:    make(map[string]int),
		lastCheck: make(map[string]time.Time),
	}
}

func (rl *RateLimiter) Allow(clientID string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()

	if lastCheck, exists := rl.lastCheck[clientID]; exists {
		elapsed := now.Sub(lastCheck).Seconds()
		newTokens := int(elapsed * float64(rl.config.RequestsPerSecond))

		if newTokens > 0 {
			rl.tokens[clientID] = min(rl.tokens[clientID]+newTokens, rl.config.BurstSize)
			rl.lastCheck[clientID] = now
		}
	} else {
		rl.tokens[clientID] = rl.config.BurstSize
		rl.lastCheck[clientID] = now
	}

	if rl.tokens[clientID] > 0 {
		rl.tokens[clientID]--
		return true
	}

	return false
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func (gw *APIGateway) AuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.JSON(http.StatusUnauthorized, gin.H{
				"code":    401,
				"message": "missing authorization header",
			})
			c.Abort()
			return
		}

		if len(authHeader) < 7 || authHeader[:7] != "Bearer " {
			c.JSON(http.StatusUnauthorized, gin.H{
				"code":    401,
				"message": "invalid authorization format",
			})
			c.Abort()
			return
		}

		tokenString := authHeader[7:]

		claims, err := gw.ValidateToken(tokenString)
		if err != nil {
			c.JSON(http.StatusUnauthorized, gin.H{
				"code":    401,
				"message": "invalid or expired token",
			})
			c.Abort()
			return
		}

		user := &User{
			ID:       claims["user_id"].(string),
			Username: claims["username"].(string),
			Roles:    convertToStringSlice(claims["roles"]),
		}

		ctx := context.WithValue(c.Request.Context(), "user", user)
		c.Request = c.Request.WithContext(ctx)

		logger.Info("user authenticated",
			zap.String("user_id", user.ID),
			zap.String("username", user.Username),
		)

		c.Next()
	}
}

func (gw *APIGateway) ValidateToken(tokenString string) (jwt.MapClaims, error) {
	token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
		}
		return []byte(gw.authConfig.JWTSecret), nil
	})

	if err != nil {
		return nil, err
	}

	if claims, ok := token.Claims.(jwt.MapClaims); ok && token.Valid {
		return claims, nil
	}

	return nil, fmt.Errorf("invalid token")
}

func (gw *APIGateway) GenerateToken(user *User) (string, error) {
	claims := jwt.MapClaims{
		"user_id":  user.ID,
		"username": user.Username,
		"roles":    user.Roles,
		"exp":      time.Now().Add(24 * time.Hour).Unix(),
		"iat":      time.Now().Unix(),
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(gw.authConfig.JWTSecret))
}

func (gw *APIGateway) RoleMiddleware(requiredRoles ...string) gin.HandlerFunc {
	return func(c *gin.Context) {
		user, exists := c.Request.Context().Value("user").(*User)
		if !exists {
			c.JSON(http.StatusForbidden, gin.H{
				"code":    403,
				"message": "user not authenticated",
			})
			c.Abort()
			return
		}

		for _, requiredRole := range requiredRoles {
			for _, userRole := range user.Roles {
				if userRole == requiredRole {
					c.Next()
					return
				}
			}
		}

		c.JSON(http.StatusForbidden, gin.H{
			"code":    403,
			"message": "insufficient permissions",
		})
		c.Abort()
	}
}

func (gw *APIGateway) RateLimitMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		clientID := gw.getClientID(c)

		if !gw.rateLimiter.Allow(clientID) {
			c.JSON(http.StatusTooManyRequests, gin.H{
				"code":    429,
				"message": "rate limit exceeded",
			})
			c.Abort()
			return
		}

		c.Next()
	}
}

func (gw *APIGateway) getClientID(c *gin.Context) string {
	user, exists := c.Request.Context().Value("user").(*User)
	if exists {
		return user.ID
	}

	ip := c.ClientIP()
	return ip
}

func (gw *APIGateway) RequestLoggingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		startTime := time.Now()

		logger.Info("request started",
			zap.String("method", c.Request.Method),
			zap.String("path", c.Request.URL.Path),
			zap.String("client_ip", c.ClientIP()),
		)

		c.Next()

		duration := time.Since(startTime)
		logger.Info("request completed",
			zap.String("method", c.Request.Method),
			zap.String("path", c.Request.URL.Path),
			zap.Int("status", c.Writer.Status()),
			zap.Duration("duration", duration),
		)
	}
}

func (gw *APIGateway) RecoveryMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				logger.Error("panic recovered", zap.Any("error", err))
				c.JSON(http.StatusInternalServerError, gin.H{
					"code":    500,
					"message": "internal server error",
				})
				c.Abort()
			}
		}()

		c.Next()
	}
}

func GetUserFromContext(ctx context.Context) *User {
	user, _ := ctx.Value("user").(*User)
	return user
}

func convertToStringSlice(value interface{}) []string {
	if value == nil {
		return []string{}
	}

	switch v := value.(type) {
	case []string:
		return v
	case []interface{}:
		result := make([]string, len(v))
		for i, item := range v {
			result[i] = fmt.Sprintf("%v", item)
		}
		return result
	default:
		return []string{}
	}
}

func (gw *APIGateway) SetupRoutes(router *gin.Engine) {
	router.Use(gw.RecoveryMiddleware())
	router.Use(gw.RequestLoggingMiddleware())

	public := router.Group("/api/v1/public")
	{
		public.POST("/auth/login", gw.LoginHandler)
	}

	protected := router.Group("/api/v1")
	protected.Use(gw.RateLimitMiddleware())
	protected.Use(gw.AuthMiddleware())
	{
		protected.GET("/resources/:id/status", gw.ResourceStatusHandler)
		protected.POST("/resources", gw.CreateResourceHandler)
		protected.POST("/resources/batch", gw.BatchOperationHandler)

		admin := protected.Group("/admin")
		admin.Use(gw.RoleMiddleware("admin"))
		{
			admin.GET("/users", gw.ListUsersHandler)
		}
	}
}

func (gw *APIGateway) LoginHandler(c *gin.Context) {
	var loginRequest struct {
		Username string `json:"username" binding:"required"`
		Password string `json:"password" binding:"required"`
	}

	if err := c.ShouldBindJSON(&loginRequest); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "invalid request body",
		})
		return
	}

	user := &User{
		ID:       "user_" + loginRequest.Username,
		Username: loginRequest.Username,
		Roles:    []string{"user"},
	}

	if loginRequest.Username == "admin" {
		user.Roles = append(user.Roles, "admin")
	}

	token, err := gw.GenerateToken(user)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    500,
			"message": "failed to generate token",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"token": token,
			"user":  user,
		},
	})
}

func (gw *APIGateway) ResourceStatusHandler(c *gin.Context) {
	resourceID := c.Param("id")

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"id":       resourceID,
			"status":   "running",
			"progress": 0.8,
		},
	})
}

func (gw *APIGateway) CreateResourceHandler(c *gin.Context) {
	c.JSON(http.StatusCreated, gin.H{
		"code": 201,
		"data": gin.H{
			"id":     "rsc_" + generateResourceID(),
			"status": "provisioning",
		},
	})
}

func (gw *APIGateway) BatchOperationHandler(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"batch_id": "batch_" + generateResourceID(),
			"results":  []interface{}{},
		},
	})
}

func (gw *APIGateway) ListUsersHandler(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"users": []interface{}{},
		},
	})
}

func generateResourceID() string {
	return fmt.Sprintf("%d", time.Now().UnixNano())
}
