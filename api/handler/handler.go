package handler

import (
	"net/http"
	"strconv"

	"github.com/edgevision/edgevision/pkg/errors"
	"github.com/gin-gonic/gin"
)

type Response struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

func Success(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, Response{
		Code:    200,
		Message: "success",
		Data:    data,
	})
}

func Created(c *gin.Context, data interface{}) {
	c.JSON(http.StatusCreated, Response{
		Code:    201,
		Message: "created",
		Data:    data,
	})
}

func Error(c *gin.Context, err *errors.AppError) {
	c.JSON(errors.ToHTTPStatus(err.Code), Response{
		Code:    int(err.Code),
		Message: err.Message,
		Data:    err.Details,
	})
}

func GetPagination(c *gin.Context) (int, int) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	return page, pageSize
}

type PagedResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data"`
	Total   int64       `json:"total"`
	Page    int         `json:"page"`
	Pages   int         `json:"pages"`
}

func SuccessPaged(c *gin.Context, data interface{}, total int64, page, pageSize int) {
	pages := int((total + int64(pageSize) - 1) / int64(pageSize))
	c.JSON(http.StatusOK, PagedResponse{
		Code:    200,
		Message: "success",
		Data:    data,
		Total:   total,
		Page:    page,
		Pages:   pages,
	})
}
