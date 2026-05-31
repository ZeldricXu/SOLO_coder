package handler

import (
	"net/http"
	"strconv"

	"projectservice/internal/model"
	"projectservice/internal/service"

	"github.com/gin-gonic/gin"
)

type FeatureFlagHandler struct {
	*Handler
	service *service.FeatureFlagService
}

func NewFeatureFlagHandler(h *Handler, svc *service.FeatureFlagService) *FeatureFlagHandler {
	return &FeatureFlagHandler{
		Handler: h,
		service: svc,
	}
}

func (h *FeatureFlagHandler) CreateFlag(c *gin.Context) {
	var req model.CreateFeatureFlagRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	flag, err := h.service.CreateFlag(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "CREATE_ERROR", "Failed to create feature flag", err.Error())
		return
	}

	h.CreatedResponse(c, flag)
}

func (h *FeatureFlagHandler) GetFlag(c *gin.Context) {
	flagID := c.Param("flag_id")

	flag, err := h.service.GetFlag(c.Request.Context(), flagID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Flag not found", err.Error())
		return
	}

	h.SuccessResponse(c, flag)
}

func (h *FeatureFlagHandler) GetFlagByKey(c *gin.Context) {
	key := c.Param("key")

	flag, err := h.service.GetFlagByKey(c.Request.Context(), key)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Flag not found", err.Error())
		return
	}

	h.SuccessResponse(c, flag)
}

func (h *FeatureFlagHandler) ListFlags(c *gin.Context) {
	enabledStr := c.Query("enabled")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	var enabled *bool
	if enabledStr != "" {
		e := enabledStr == "true"
		enabled = &e
	}

	flags, total, err := h.service.ListFlags(c.Request.Context(), enabled, page, pageSize)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "QUERY_ERROR", "Failed to list flags", err.Error())
		return
	}

	h.PaginatedResponse(c, flags, page, pageSize, total)
}

func (h *FeatureFlagHandler) UpdateFlag(c *gin.Context) {
	flagID := c.Param("flag_id")

	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid update data", err.Error())
		return
	}

	if err := h.service.UpdateFlag(c.Request.Context(), flagID, updates); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Flag not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Flag updated successfully"})
}

func (h *FeatureFlagHandler) UpdateRollout(c *gin.Context) {
	flagID := c.Param("flag_id")

	var req model.UpdateRolloutRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	if err := h.service.UpdateRollout(c.Request.Context(), flagID, &req); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Flag not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Rollout updated successfully"})
}

func (h *FeatureFlagHandler) DeleteFlag(c *gin.Context) {
	flagID := c.Param("flag_id")

	if err := h.service.DeleteFlag(c.Request.Context(), flagID); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Flag not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Flag deleted successfully"})
}

func (h *FeatureFlagHandler) EvaluateFlag(c *gin.Context) {
	var req model.FlagEvaluationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	result, err := h.service.EvaluateFlag(c.Request.Context(), &req)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "EVALUATE_ERROR", "Flag evaluation failed", err.Error())
		return
	}

	h.SuccessResponse(c, result)
}

func (h *FeatureFlagHandler) CreateSegment(c *gin.Context) {
	var segment model.UserSegment
	if err := c.ShouldBindJSON(&segment); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid segment data", err.Error())
		return
	}

	created, err := h.service.CreateSegment(c.Request.Context(), &segment)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "CREATE_ERROR", "Failed to create segment", err.Error())
		return
	}

	h.CreatedResponse(c, created)
}

func (h *FeatureFlagHandler) GetSegment(c *gin.Context) {
	segmentID := c.Param("segment_id")

	segment, err := h.service.GetSegment(c.Request.Context(), segmentID)
	if err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Segment not found", err.Error())
		return
	}

	h.SuccessResponse(c, segment)
}

func (h *FeatureFlagHandler) ListSegments(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	segments, total, err := h.service.ListSegments(c.Request.Context(), page, pageSize)
	if err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "QUERY_ERROR", "Failed to list segments", err.Error())
		return
	}

	h.PaginatedResponse(c, segments, page, pageSize, total)
}

func (h *FeatureFlagHandler) DeleteSegment(c *gin.Context) {
	segmentID := c.Param("segment_id")

	if err := h.service.DeleteSegment(c.Request.Context(), segmentID); err != nil {
		h.ErrorResponse(c, http.StatusNotFound, "NOT_FOUND", "Segment not found", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "Segment deleted successfully"})
}

func (h *FeatureFlagHandler) AddUserToSegment(c *gin.Context) {
	segmentID := c.Param("segment_id")

	var req struct {
		UserID string `json:"user_id" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		h.ErrorResponse(c, http.StatusUnprocessableEntity, "VALIDATION_ERROR", "Invalid request parameters", err.Error())
		return
	}

	if err := h.service.AddUserToSegment(c.Request.Context(), segmentID, req.UserID); err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "UPDATE_ERROR", "Failed to add user to segment", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "User added to segment successfully"})
}

func (h *FeatureFlagHandler) RemoveUserFromSegment(c *gin.Context) {
	segmentID := c.Param("segment_id")
	userID := c.Param("user_id")

	if err := h.service.RemoveUserFromSegment(c.Request.Context(), segmentID, userID); err != nil {
		h.ErrorResponse(c, http.StatusInternalServerError, "UPDATE_ERROR", "Failed to remove user from segment", err.Error())
		return
	}

	h.SuccessResponse(c, gin.H{"message": "User removed from segment successfully"})
}
