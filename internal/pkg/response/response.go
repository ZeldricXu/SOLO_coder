package response

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

type Response struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

type PageResponse struct {
	Code       int         `json:"code"`
	Message    string      `json:"message"`
	Data       interface{} `json:"data,omitempty"`
	Total      int64       `json:"total"`
	Page       int         `json:"page"`
	PageSize   int         `json:"page_size"`
	TotalPages int         `json:"total_pages"`
}

const (
	CodeSuccess        = 0
	CodeBadRequest     = 400
	CodeUnauthorized   = 401
	CodeForbidden      = 403
	CodeNotFound       = 404
	CodeInternalError  = 500
)

func Success(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, Response{
		Code:    CodeSuccess,
		Message: "success",
		Data:    data,
	})
}

func SuccessWithMessage(c *gin.Context, data interface{}, message string) {
	c.JSON(http.StatusOK, Response{
		Code:    CodeSuccess,
		Message: message,
		Data:    data,
	})
}

func Fail(c *gin.Context, code int, message string) {
	statusCode := http.StatusBadRequest
	if code >= 400 && code < 600 {
		statusCode = code
	}
	c.JSON(statusCode, Response{
		Code:    code,
		Message: message,
	})
}

func Forbidden(c *gin.Context, message string) {
	if message == "" {
		message = "forbidden"
	}
	c.JSON(http.StatusForbidden, Response{
		Code:    CodeForbidden,
		Message: message,
	})
}

func NotFound(c *gin.Context, message string) {
	if message == "" {
		message = "resource not found"
	}
	c.JSON(http.StatusNotFound, Response{
		Code:    CodeNotFound,
		Message: message,
	})
}

func Unauthorized(c *gin.Context, message string) {
	if message == "" {
		message = "unauthorized"
	}
	c.JSON(http.StatusUnauthorized, Response{
		Code:    CodeUnauthorized,
		Message: message,
	})
}

func BadRequest(c *gin.Context, message string) {
	if message == "" {
		message = "bad request"
	}
	c.JSON(http.StatusBadRequest, Response{
		Code:    CodeBadRequest,
		Message: message,
	})
}

func InternalError(c *gin.Context, message string) {
	if message == "" {
		message = "internal server error"
	}
	c.JSON(http.StatusInternalServerError, Response{
		Code:    CodeInternalError,
		Message: message,
	})
}

func PageSuccess(c *gin.Context, data interface{}, total int64, page, pageSize int) {
	totalPages := int(total) / pageSize
	if int(total)%pageSize > 0 {
		totalPages++
	}
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}
	c.JSON(http.StatusOK, PageResponse{
		Code:       CodeSuccess,
		Message:    "success",
		Data:       data,
		Total:      total,
		Page:       page,
		PageSize:   pageSize,
		TotalPages: totalPages,
	})
}
