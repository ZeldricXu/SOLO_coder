package utils

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"session133/pkg/errors"
)

type Response struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

type PaginatedResponse struct {
	Code      int         `json:"code"`
	Message   string      `json:"message"`
	Data      interface{} `json:"data"`
	Total     int64       `json:"total"`
	Page      int         `json:"page"`
	PageSize  int         `json:"page_size"`
	TotalPages int        `json:"total_pages"`
}

func Success(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, Response{
		Code:    200,
		Message: "success",
		Data:    data,
	})
}

func SuccessCreated(c *gin.Context, data interface{}) {
	c.JSON(http.StatusCreated, Response{
		Code:    201,
		Message: "created",
		Data:    data,
	})
}

func SuccessPaginated(c *gin.Context, data interface{}, total int64, page, pageSize int) {
	totalPages := int(total) / pageSize
	if int(total)%pageSize > 0 {
		totalPages++
	}
	c.JSON(http.StatusOK, PaginatedResponse{
		Code:       200,
		Message:    "success",
		Data:       data,
		Total:      total,
		Page:       page,
		PageSize:   pageSize,
		TotalPages: totalPages,
	})
}

func Error(c *gin.Context, err *errors.AppError) {
	httpStatus := http.StatusInternalServerError
	switch err.Code {
	case errors.ErrCodeInvalidParams:
		httpStatus = http.StatusBadRequest
	case errors.ErrCodeUnauthorized:
		httpStatus = http.StatusUnauthorized
	case errors.ErrCodeForbidden:
		httpStatus = http.StatusForbidden
	case errors.ErrCodeNotFound:
		httpStatus = http.StatusNotFound
	case errors.ErrCodeConflict:
		httpStatus = http.StatusConflict
	case errors.ErrCodeRateLimitExceeded:
		httpStatus = http.StatusTooManyRequests
	case errors.ErrCodeServiceUnavailable:
		httpStatus = http.StatusServiceUnavailable
	}
	c.JSON(httpStatus, Response{
		Code:    int(err.Code),
		Message: err.Message,
		Data:    gin.H{"details": err.Details},
	})
}
