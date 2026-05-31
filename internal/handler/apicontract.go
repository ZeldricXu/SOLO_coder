package handler

import (
	"net/http"
	"strconv"

	"projectservice/internal/model"
	"projectservice/internal/service"

	"github.com/gin-gonic/gin"
)

type APIContractHandler struct {
	*Handler
	service *service.APIContractService
}

func NewAPIContractHandler(h *Handler, svc *service.APIContractService) *APIContractHandler {
	return &APIContractHandler{
		Handler: h,
		service: svc,
	}
}

func (h *APIContractHandler) RegisterContract(c *gin.Context) {
	var req model.RegisterContractRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	contract, err := h.service.RegisterContract(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "REGISTER_ERROR", "Contract registration failed", err.Error())
		return
	}

	h.CreatedResponse(c, contract)
}

func (h *APIContractHandler) GetContract(c *gin.Context) {
	contractID := c.Param("contract_id")

	contract, err := h.service.GetContract(c.Request.Context(), contractID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Contract not found", err.Error())
		return
	}

	h.SuccessResponse(c, contract)
}

func (h *APIContractHandler) ListContracts(c *gin.Context) {
	serviceID := c.Query("service_id")
	contractType := c.Query("contract_type")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	contracts, total, err := h.service.ListContracts(c.Request.Context(), serviceID, contractType, page, pageSize)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "QUERY_ERROR", "Failed to list contracts", err.Error())
		return
	}

	h.PaginatedResponse(c, contracts, page, pageSize, total)
}

func (h *APIContractHandler) ValidateRequest(c *gin.Context) {
	var req model.ValidateContractRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	result, err := h.service.ValidateRequest(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "VALIDATION_ERROR", "Request validation failed", err.Error())
		return
	}

	h.SuccessResponse(c, result)
}

func (h *APIContractHandler) CreateMockServer(c *gin.Context) {
	var req model.CreateMockServerRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	mock, err := h.service.CreateMockServer(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "CREATE_ERROR", "Failed to create mock server", err.Error())
		return
	}

	h.CreatedResponse(c, mock)
}

func (h *APIContractHandler) GetMockServer(c *gin.Context) {
	mockID := c.Param("mock_id")

	mock, err := h.service.GetMockServer(c.Request.Context(), mockID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Mock server not found", err.Error())
		return
	}

	h.SuccessResponse(c, mock)
}

func (h *APIContractHandler) GetMockServerStatus(c *gin.Context) {
	mockID := c.Param("mock_id")

	status, err := h.service.GetMockServerStatus(c.Request.Context(), mockID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Mock server not found", err.Error())
		return
	}

	h.SuccessResponse(c, status)
}

func (h *APIContractHandler) ListMockServers(c *gin.Context) {
	contractID := c.Query("contract_id")
	status := c.Query("status")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	mocks, total, err := h.service.ListMockServers(c.Request.Context(), contractID, status, page, pageSize)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "QUERY_ERROR", "Failed to list mock servers", err.Error())
		return
	}

	h.PaginatedResponse(c, mocks, page, pageSize, total)
}

func (h *APIContractHandler) StopMockServer(c *gin.Context) {
	mockID := c.Param("mock_id")

	if err := h.service.StopMockServer(c.Request.Context(), mockID); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Mock server not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Mock server stopped successfully"})
}

func (h *APIContractHandler) DeleteContract(c *gin.Context) {
	contractID := c.Param("contract_id")

	if err := h.service.DeleteContract(c.Request.Context(), contractID); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Contract not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Contract deleted successfully"})
}
