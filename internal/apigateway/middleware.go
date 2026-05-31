package apigateway

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"session133/pkg/errors"
	"session133/pkg/utils"
)

type Middleware struct {
	authService      *AuthService
	rateLimitManager *RateLimitManager
}

func NewMiddleware(authService *AuthService, rateLimitManager *RateLimitManager) *Middleware {
	return &Middleware{
		authService:      authService,
		rateLimitManager: rateLimitManager,
	}
}

func (m *Middleware) AuthRequired() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		apiKey := c.GetHeader("X-API-Key")

		if authHeader == "" && apiKey == "" {
			utils.Error(c, errors.Unauthorized("缺少认证信息"))
			c.Abort()
			return
		}

		var userID string
		var role Role

		if authHeader != "" {
			parts := strings.Split(authHeader, " ")
			if len(parts) != 2 || parts[0] != "Bearer" {
				utils.Error(c, errors.Unauthorized("认证格式错误"))
				c.Abort()
				return
			}

			claims, err := m.authService.ValidateToken(parts[1])
			if err != nil {
				utils.Error(c, errors.Unauthorized("Token无效或已过期"))
				c.Abort()
				return
			}

			userID = claims["user_id"].(string)
			role = Role(claims["role"].(string))
		} else {
			user, err := m.authService.ValidateAPIKey(apiKey)
			if err != nil {
				utils.Error(c, errors.Unauthorized("API Key无效"))
				c.Abort()
				return
			}
			userID = user.ID
			role = user.Role
		}

		c.Set("user_id", userID)
		c.Set("role", string(role))
		c.Next()
	}
}

func (m *Middleware) RequirePermission(permission Permission) gin.HandlerFunc {
	return func(c *gin.Context) {
		roleStr, exists := c.Get("role")
		if !exists {
			utils.Error(c, errors.Unauthorized("未登录"))
			c.Abort()
			return
		}

		role := Role(roleStr.(string))
		if !m.authService.CheckPermission(role, permission) {
			utils.Error(c, errors.Forbidden("权限不足"))
			c.Abort()
			return
		}

		c.Next()
	}
}

func (m *Middleware) RateLimit() gin.HandlerFunc {
	return func(c *gin.Context) {
		userID, exists := c.Get("user_id")
		if !exists {
			userID = c.ClientIP()
		}

		path := c.Request.URL.Path
		allowed, result, err := m.rateLimitManager.Allow(c.Request.Context(), userID.(string), path)
		if err != nil {
			utils.Error(c, errors.Internal("限流服务错误"))
			c.Abort()
			return
		}

		if result != nil {
			c.Header("X-RateLimit-Limit", string(rune(result.Limit)))
			c.Header("X-RateLimit-Remaining", string(rune(result.Remaining)))
			c.Header("X-RateLimit-Reset", result.Reset.Format(http.TimeFormat))
		}

		if !allowed {
			utils.Error(c, errors.RateLimitExceeded())
			c.Abort()
			return
		}

		c.Next()
	}
}

func (m *Middleware) CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Writer.Header().Set("Access-Control-Allow-Origin", "*")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}

		c.Next()
	}
}

func (m *Middleware) RequestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		requestID := c.GetHeader("X-Request-ID")
		if requestID == "" {
			requestID = utils.GenerateID("req")
		}
		c.Set("request_id", requestID)
		c.Header("X-Request-ID", requestID)
		c.Next()
	}
}
