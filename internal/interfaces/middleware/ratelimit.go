package middleware

import (
	"fmt"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"session189/internal/infrastructure/logger"
	"session189/internal/modules/gateway"
)

type RateLimitMiddleware struct {
	limiter gateway.RateLimiter
}

func NewRateLimitMiddleware(limiter gateway.RateLimiter) *RateLimitMiddleware {
	return &RateLimitMiddleware{
		limiter: limiter,
	}
}

func (m *RateLimitMiddleware) Limit() gin.HandlerFunc {
	return func(c *gin.Context) {
		key := getClientIP(c)

		allowed, remaining, reset, err := m.limiter.Allow(c.Request.Context(), key)
		if err != nil {
			logger.Warn("Rate limiter error", zap.Error(err))
			c.Next()
			return
		}

		c.Header("X-RateLimit-Limit", "100")
		c.Header("X-RateLimit-Remaining", string(rune(remaining)))

		if !allowed {
			c.Header("X-RateLimit-Reset", fmt.Sprintf("%d", reset.Milliseconds()))
			c.Header("Retry-After", fmt.Sprintf("%d", int(reset.Seconds())+1))
			c.JSON(http.StatusTooManyRequests, gin.H{
				"error":       "Rate limit exceeded",
				"retry_after": reset.Seconds(),
			})
			c.Abort()
			return
		}

		c.Next()
	}
}

func (m *RateLimitMiddleware) LimitByUser() gin.HandlerFunc {
	return func(c *gin.Context) {
		userID, exists := c.Get("user_id")
		key := ""
		if exists {
			key = userID.(string)
		} else {
			key = getClientIP(c)
		}

		allowed, remaining, reset, err := m.limiter.Allow(c.Request.Context(), key)
		if err != nil {
			logger.Warn("Rate limiter error", zap.Error(err))
			c.Next()
			return
		}

		c.Header("X-RateLimit-Limit", "100")
		c.Header("X-RateLimit-Remaining", string(rune(remaining)))

		if !allowed {
			c.Header("X-RateLimit-Reset", fmt.Sprintf("%d", reset.Milliseconds()))
			c.Header("Retry-After", fmt.Sprintf("%d", int(reset.Seconds())+1))
			c.JSON(http.StatusTooManyRequests, gin.H{
				"error":       "Rate limit exceeded",
				"retry_after": reset.Seconds(),
			})
			c.Abort()
			return
		}

		c.Next()
	}
}

func (m *RateLimitMiddleware) LimitWithKey(keyFunc func(c *gin.Context) string, limit int, window time.Duration) gin.HandlerFunc {
	limiter := gateway.NewFixedWindowLimiter(limit, window)

	return func(c *gin.Context) {
		key := keyFunc(c)

		allowed, remaining, reset, err := limiter.Allow(c.Request.Context(), key)
		if err != nil {
			logger.Warn("Rate limiter error", zap.Error(err))
			c.Next()
			return
		}

		c.Header("X-RateLimit-Limit", fmt.Sprintf("%d", limit))
		c.Header("X-RateLimit-Remaining", fmt.Sprintf("%d", remaining))

		if !allowed {
			c.Header("X-RateLimit-Reset", fmt.Sprintf("%d", reset.Milliseconds()))
			c.Header("Retry-After", fmt.Sprintf("%d", int(reset.Seconds())+1))
			c.JSON(http.StatusTooManyRequests, gin.H{
				"error":       "Rate limit exceeded",
				"retry_after": reset.Seconds(),
			})
			c.Abort()
			return
		}

		c.Next()
	}
}

func getClientIP(c *gin.Context) string {
	ip := c.GetHeader("X-Forwarded-For")
	if ip == "" {
		ip = c.GetHeader("X-Real-IP")
	}
	if ip == "" {
		ip = c.ClientIP()
	}
	return ip
}
