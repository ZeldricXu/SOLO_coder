package api

import (
	"net/http"

	"github.com/gin-gonic/gin"

	"llmgateway/pkg/errors"
)

type Response struct {
	Code    int         `json:"code"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}

func Success(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, Response{
		Code: 0,
		Data: data,
	})
}

func Created(c *gin.Context, data interface{}) {
	c.JSON(http.StatusCreated, Response{
		Code: 0,
		Data: data,
	})
}

func Error(c *gin.Context, err *errors.AppError) {
	c.JSON(err.HTTPStatus(), Response{
		Code:    int(err.Code),
		Message: err.Message,
		Data:    err.Details,
	})
}

func BadRequest(c *gin.Context, message string) {
	Error(c, errors.BadRequest(message))
}

func Unauthorized(c *gin.Context, message string) {
	Error(c, errors.Unauthorized(message))
}

func Forbidden(c *gin.Context, message string) {
	Error(c, errors.Forbidden(message))
}

func NotFound(c *gin.Context, message string) {
	Error(c, errors.NotFound(message))
}

func Conflict(c *gin.Context, message string, resourceID string) {
	Error(c, errors.Conflict(message, resourceID))
}

func ValidationFailed(c *gin.Context, message string, details interface{}) {
	Error(c, errors.ValidationFailed(message, details))
}

func InternalError(c *gin.Context, message string) {
	Error(c, errors.InternalError(message))
}

func ServiceUnavailable(c *gin.Context, message string) {
	Error(c, errors.ServiceUnavailable(message))
}

func GatewayTimeout(c *gin.Context, message string) {
	Error(c, errors.GatewayTimeout(message))
}

func PageResult(items interface{}, total int64, page, pageSize int) map[string]interface{} {
	return map[string]interface{}{
		"items":      items,
		"total":      total,
		"page":       page,
		"page_size":  pageSize,
		"total_page": (total + int64(pageSize) - 1) / int64(pageSize),
	}
}
