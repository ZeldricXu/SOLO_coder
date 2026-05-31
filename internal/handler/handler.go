package handler

import (
	"net/http"
	"time"

	"projectservice/internal/infrastructure/monitor"
	"projectservice/internal/model"

	"github.com/gin-gonic/gin"
)

type Handler struct {
	metrics *monitor.Metrics
}

func NewHandler(metrics *monitor.Metrics) *Handler {
	return &Handler{
		metrics: metrics,
	}
}

func (h *Handler) AuthMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		apiKey := c.GetHeader("X-API-Key")
		if apiKey == "" {
			c.JSON(http.StatusUnauthorized, model.APIResponse{
				Code:    401,
				Message: "Authentication required",
				Error: &model.ErrorDetail{
					Code:    "AUTH_REQUIRED",
					Message: "Missing API key",
				},
			})
			c.Abort()
			return
		}
		c.Set("api_key", apiKey)
		c.Next()
	}
}

func (h *Handler) MetricsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		h.metrics.IncInFlight()
		c.Next()
		h.metrics.DecInFlight()

		status := c.Writer.Status()
		duration := time.Since(start)
		h.metrics.ObserveRequestDuration(c.Request.Method, c.FullPath(), http.StatusText(status), duration)
	}
}

func (h *Handler) ErrorHandlingMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Next()

		if len(c.Errors) > 0 {
			err := c.Errors.Last()
			c.JSON(http.StatusInternalServerError, model.APIResponse{
				Code:    500,
				Message: "Internal server error",
				Error: &model.ErrorDetail{
					Code:    "INTERNAL_ERROR",
					Message: err.Error(),
				},
			})
		}
	}
}

func (h *Handler) RateLimitMiddleware(maxRequests int, window time.Duration) gin.HandlerFunc {
	var requestCount = make(map[string]int)
	var lastReset = time.Now()

	return func(c *gin.Context) {
		now := time.Now()
		if now.Sub(lastReset) > window {
			requestCount = make(map[string]int)
			lastReset = now
		}

		clientIP := c.ClientIP()
		if requestCount[clientIP] >= maxRequests {
			c.JSON(http.StatusTooManyRequests, model.APIResponse{
				Code:    429,
				Message: "Rate limit exceeded",
			})
			c.Abort()
			return
		}

		requestCount[clientIP]++
		c.Next()
	}
}

func (h *Handler) SuccessResponse(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, model.APIResponse{
		Code: 200,
		Data: data,
	})
}

func (h *Handler) CreatedResponse(c *gin.Context, data interface{}) {
	c.JSON(http.StatusCreated, model.APIResponse{
		Code: 201,
		Data: data,
	})
}

func (h *Handler) ErrorResponse(c *gin.Context, statusCode int, errorCode, message, details string) {
	c.JSON(statusCode, model.APIResponse{
		Code:    statusCode,
		Message: message,
		Error: &model.ErrorDetail{
			Code:    errorCode,
			Message: message,
			Details: details,
		},
	})
}

func (h *Handler) PaginatedResponse(c *gin.Context, data interface{}, page, pageSize int, total int64) {
	c.JSON(http.StatusOK, model.PaginatedResponse{
		Code: 200,
		Data: data,
		Pagination: model.Pagination{
			Page:     page,
			PageSize: pageSize,
			Total:    total,
		},
	})
}
