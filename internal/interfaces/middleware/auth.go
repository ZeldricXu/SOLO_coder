package middleware

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"session189/internal/infrastructure/logger"
	"session189/internal/modules/gateway"
)

type AuthMiddleware struct {
	authManager *gateway.AuthManager
}

func NewAuthMiddleware(authManager *gateway.AuthManager) *AuthMiddleware {
	return &AuthMiddleware{
		authManager: authManager,
	}
}

func (m *AuthMiddleware) JWTAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Authorization header is required"})
			c.Abort()
			return
		}

		token, err := gateway.ExtractTokenFromAuthHeader(authHeader)
		if err != nil {
			c.JSON(http.StatusUnauthorized, gin.H{"error": err.Error()})
			c.Abort()
			return
		}

		claims, err := m.authManager.ValidateToken(token)
		if err != nil {
			logger.Warn("Invalid JWT token", zap.Error(err))
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid or expired token"})
			c.Abort()
			return
		}

		c.Set("user_id", claims.UserID)
		c.Set("username", claims.Username)
		c.Set("role", claims.Role)
		c.Next()
	}
}

func (m *AuthMiddleware) APIKeyAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		apiKey := c.GetHeader("X-API-Key")
		if apiKey == "" {
			apiKey = c.Query("api_key")
		}

		if apiKey == "" {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "API key is required"})
			c.Abort()
			return
		}

		keyInfo, err := m.authManager.ValidateAPIKey(apiKey)
		if err != nil {
			logger.Warn("Invalid API key", zap.Error(err))
			c.JSON(http.StatusUnauthorized, gin.H{"error": "Invalid API key"})
			c.Abort()
			return
		}

		c.Set("api_key_id", keyInfo.KeyID)
		c.Set("user_id", keyInfo.UserID)
		c.Set("role", keyInfo.Role)
		c.Next()
	}
}

func (m *AuthMiddleware) CombinedAuth() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		apiKey := c.GetHeader("X-API-Key")

		if authHeader != "" {
			token, err := gateway.ExtractTokenFromAuthHeader(authHeader)
			if err == nil {
				claims, err := m.authManager.ValidateToken(token)
				if err == nil {
					c.Set("user_id", claims.UserID)
					c.Set("username", claims.Username)
					c.Set("role", claims.Role)
					c.Set("auth_type", "jwt")
					c.Next()
					return
				}
			}
		}

		if apiKey == "" {
			apiKey = c.Query("api_key")
		}

		if apiKey != "" {
			keyInfo, err := m.authManager.ValidateAPIKey(apiKey)
			if err == nil {
				c.Set("api_key_id", keyInfo.KeyID)
				c.Set("user_id", keyInfo.UserID)
				c.Set("role", keyInfo.Role)
				c.Set("auth_type", "api_key")
				c.Next()
				return
			}
		}

		c.JSON(http.StatusUnauthorized, gin.H{"error": "Authentication required"})
		c.Abort()
	}
}

func (m *AuthMiddleware) RequireRole(roles ...string) gin.HandlerFunc {
	return func(c *gin.Context) {
		userRole, exists := c.Get("role")
		if !exists {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "User not authenticated"})
			c.Abort()
			return
		}

		roleStr, ok := userRole.(string)
		if !ok {
			c.JSON(http.StatusForbidden, gin.H{"error": "Invalid role"})
			c.Abort()
			return
		}

		for _, requiredRole := range roles {
			if strings.EqualFold(roleStr, requiredRole) {
				c.Next()
				return
			}
		}

		c.JSON(http.StatusForbidden, gin.H{"error": "Insufficient permissions"})
		c.Abort()
	}
}
