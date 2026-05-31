package response

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	apperrors "loglevelplatform/internal/common/errors"
	"loglevelplatform/internal/common/logger"
)

type APIResponse struct {
	Code    int         `json:"code"`
	Data    interface{} `json:"data,omitempty"`
	Message string      `json:"message,omitempty"`
}

func JSON(c *gin.Context, code int, data interface{}, message string) {
	c.JSON(code, APIResponse{
		Code:    code,
		Data:    data,
		Message: message,
	})
}

func OK(c *gin.Context, data interface{}) {
	JSON(c, http.StatusOK, data, "")
}

func Created(c *gin.Context, data interface{}) {
	JSON(c, http.StatusCreated, data, "")
}

func Accepted(c *gin.Context, message string, data interface{}) {
	JSON(c, http.StatusAccepted, data, message)
}

func NoContent(c *gin.Context) {
	c.Status(http.StatusNoContent)
}

func BadRequest(c *gin.Context, message string) {
	log := logger.FromContext(c.Request.Context())
	log.Warn("bad request", zap.String("error", message))
	JSON(c, http.StatusBadRequest, nil, message)
}

func Unauthorized(c *gin.Context, message string) {
	log := logger.FromContext(c.Request.Context())
	log.Warn("unauthorized", zap.String("error", message))
	JSON(c, http.StatusUnauthorized, nil, message)
}

func Forbidden(c *gin.Context, message string) {
	log := logger.FromContext(c.Request.Context())
	log.Warn("forbidden", zap.String("error", message))
	JSON(c, http.StatusForbidden, nil, message)
}

func NotFound(c *gin.Context, message string) {
	log := logger.FromContext(c.Request.Context())
	log.Warn("not found", zap.String("error", message))
	JSON(c, http.StatusNotFound, nil, message)
}

func Conflict(c *gin.Context, message string) {
	log := logger.FromContext(c.Request.Context())
	log.Warn("conflict", zap.String("error", message))
	JSON(c, http.StatusConflict, nil, message)
}

func TooManyRequests(c *gin.Context, message string) {
	log := logger.FromContext(c.Request.Context())
	log.Warn("too many requests", zap.String("error", message))
	JSON(c, http.StatusTooManyRequests, nil, message)
}

func InternalError(c *gin.Context, err error, message string) {
	log := logger.FromContext(c.Request.Context())
	log.Error(message, zap.Error(err))
	JSON(c, http.StatusInternalServerError, nil, message)
}

func ServiceUnavailable(c *gin.Context, message string) {
	log := logger.FromContext(c.Request.Context())
	log.Warn("service unavailable", zap.String("error", message))
	JSON(c, http.StatusServiceUnavailable, nil, message)
}

func HandleError(c *gin.Context, err error, defaultMessage string) {
	switch e := err.(type) {
	case *apperrors.AppError:
		handleAppError(c, e)
	default:
		InternalError(c, err, defaultMessage)
	}
}

func handleAppError(c *gin.Context, err *apperrors.AppError) {
	switch err.Code {
	case apperrors.ErrCodeValidation:
		BadRequest(c, err.Message)
	case apperrors.ErrCodeNotFound:
		NotFound(c, err.Message)
	case apperrors.ErrCodeUnauthorized:
		Unauthorized(c, err.Message)
	case apperrors.ErrCodeForbidden:
		Forbidden(c, err.Message)
	case apperrors.ErrCodeConflict:
		Conflict(c, err.Message)
	case apperrors.ErrCodeTooManyRequests:
		TooManyRequests(c, err.Message)
	case apperrors.ErrCodeTimeout:
		InternalError(c, err, err.Message)
	default:
		InternalError(c, err, err.Message)
	}
}

func BindJSON(c *gin.Context, obj interface{}) bool {
	if err := c.ShouldBindJSON(obj); err != nil {
		BadRequest(c, "invalid request body: "+err.Error())
		return false
	}
	return true
}

func GetQueryInt(c *gin.Context, key string, defaultValue int) int {
	valStr := c.Query(key)
	if valStr == "" {
		return defaultValue
	}
	return defaultValue
}

func GetQueryBool(c *gin.Context, key string, defaultValue bool) bool {
	valStr := c.DefaultQuery(key, "")
	if valStr == "" {
		return defaultValue
	}
	return valStr == "true" || valStr == "1"
}
