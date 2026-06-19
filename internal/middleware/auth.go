package middleware

import (
	"context"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/jwt"
	"github.com/enterprise/knowledgebase/internal/pkg/response"
	"github.com/enterprise/knowledgebase/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
)

type contextKey string

const (
	UserIDKey   contextKey = "user_id"
	TenantIDKey contextKey = "tenant_id"
)

func JWTAuth(secret string) gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			response.Unauthorized(c, "missing authorization header")
			c.Abort()
			return
		}

		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || parts[0] != "Bearer" {
			response.Unauthorized(c, "invalid authorization header format")
			c.Abort()
			return
		}

		claims, err := jwt.ParseToken(parts[1], secret)
		if err != nil {
			response.Unauthorized(c, "invalid or expired token")
			c.Abort()
			return
		}

		userID := claims.UserID.String()
		tenantID := claims.TenantID.String()

		c.Set(string(UserIDKey), userID)
		c.Set(string(TenantIDKey), tenantID)

		ctx := context.WithValue(c.Request.Context(), database.TenantIDKey, tenantID)
		c.Request = c.Request.WithContext(ctx)

		c.Next()
	}
}

func TenantFromHeader() gin.HandlerFunc {
	return func(c *gin.Context) {
		tenantID := c.GetHeader("X-Tenant-ID")
		if tenantID == "" {
			response.Unauthorized(c, "missing X-Tenant-ID header")
			c.Abort()
			return
		}

		if _, err := uuid.Parse(tenantID); err != nil {
			response.BadRequest(c, "invalid X-Tenant-ID format")
			c.Abort()
			return
		}

		c.Set(string(TenantIDKey), tenantID)

		ctx := context.WithValue(c.Request.Context(), database.TenantIDKey, tenantID)
		c.Request = c.Request.WithContext(ctx)

		c.Next()
	}
}

func RequirePermission(resourceType model.ResourceType, action model.PermissionAction, permRepo service.PermissionRepository) gin.HandlerFunc {
	return func(c *gin.Context) {
		userIDStr, exists := c.Get(string(UserIDKey))
		if !exists {
			response.Unauthorized(c, "user not authenticated")
			c.Abort()
			return
		}

		resourceIDStr := c.Param("id")
		if resourceIDStr == "" {
			resourceIDStr = c.Param("space_id")
		}
		if resourceIDStr == "" {
			resourceIDStr = c.Param("doc_id")
		}

		if resourceIDStr == "" {
			response.BadRequest(c, "resource id not found in path")
			c.Abort()
			return
		}

		userID, err := uuid.Parse(userIDStr.(string))
		if err != nil {
			response.BadRequest(c, "invalid user id")
			c.Abort()
			return
		}

		resourceID, err := uuid.Parse(resourceIDStr)
		if err != nil {
			response.BadRequest(c, "invalid resource id")
			c.Abort()
			return
		}

		allowed, err := permRepo.CheckPermission(c.Request.Context(), userID, resourceID, resourceType, action)
		if err != nil {
			response.InternalError(c, "failed to check permission")
			c.Abort()
			return
		}

		if !allowed {
			allowed, err = permRepo.CheckByGroups(c.Request.Context(), userID, resourceID, resourceType, action)
			if err != nil {
				response.InternalError(c, "failed to check group permission")
				c.Abort()
				return
			}
		}

		if !allowed {
			response.Forbidden(c, "insufficient permissions")
			c.Abort()
			return
		}

		c.Next()
	}
}

func RateLimit(redisClient *redis.Client, limit int, window time.Duration) gin.HandlerFunc {
	return func(c *gin.Context) {
		if redisClient == nil {
			c.Next()
			return
		}

		now := time.Now().UnixNano()
		windowStart := now - window.Nanoseconds()

		key := "rate_limit:" + c.ClientIP() + ":" + c.FullPath()

		ctx := context.Background()

		pipe := redisClient.TxPipeline()
		pipe.ZRemRangeByScore(ctx, key, "0", strconv.FormatInt(windowStart, 10))
		pipe.ZAdd(ctx, key, redis.Z{
			Score:  float64(now),
			Member: strconv.FormatInt(now, 10),
		})
		pipe.ZCard(ctx, key)
		pipe.Expire(ctx, key, window)

		results, err := pipe.Exec(ctx)
		if err != nil {
			c.Next()
			return
		}

		if len(results) >= 3 {
			cardCmd, ok := results[2].(*redis.IntCmd)
			if ok {
				count := cardCmd.Val()
				if count > int64(limit) {
					c.JSON(http.StatusTooManyRequests, response.Response{
						Code:    429,
						Message: "rate limit exceeded",
					})
					c.Abort()
					return
				}
			}
		}

		c.Next()
	}
}
