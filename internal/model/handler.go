package model

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	errors "session133/pkg/errors"
	"session133/pkg/utils"
)

type Handler struct {
	service *ModelService
}

func NewHandler(service *ModelService) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	models := r.Group("/models")
	{
		models.POST("", h.CreateModel)
		models.GET("", h.ListModels)
		models.GET("/:id", h.GetModel)
		models.DELETE("/:id", h.DeleteModel)

		versions := models.Group("/:id/versions")
		{
			versions.POST("", h.CreateVersion)
			versions.GET("", h.ListVersions)
		}

		versionGroup := r.Group("/versions")
		{
			versionGroup.GET("/:versionId", h.GetVersion)
			versionGroup.POST("/:versionId/transition", h.TransitionStage)
			versionGroup.GET("/:versionId/history", h.GetVersionHistory)
		}
	}
}

func (h *Handler) CreateModel(c *gin.Context) {
	var req CreateModelRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	model, err := h.service.CreateModel(c.Request.Context(), &req, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, model)
}

func (h *Handler) GetModel(c *gin.Context) {
	modelID := c.Param("id")
	model, err := h.service.GetModel(c.Request.Context(), modelID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, model)
}

func (h *Handler) ListModels(c *gin.Context) {
	namespace := c.Query("namespace")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	models, total, err := h.service.ListModels(c.Request.Context(), namespace, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, models, total, page, pageSize)
}

func (h *Handler) DeleteModel(c *gin.Context) {
	modelID := c.Param("id")
	if err := h.service.DeleteModel(c.Request.Context(), modelID); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{"message": "模型删除成功"})
}

func (h *Handler) CreateVersion(c *gin.Context) {
	modelID := c.Param("id")
	var req CreateVersionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	userID := c.GetString("user_id")
	version, err := h.service.CreateVersion(c.Request.Context(), modelID, &req, userID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessCreated(c, version)
}

func (h *Handler) GetVersion(c *gin.Context) {
	versionID := c.Param("versionId")
	version, err := h.service.GetVersion(c.Request.Context(), versionID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, version)
}

func (h *Handler) ListVersions(c *gin.Context) {
	modelID := c.Param("id")
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))

	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	versions, total, err := h.service.ListVersions(c.Request.Context(), modelID, page, pageSize)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.SuccessPaginated(c, versions, total, page, pageSize)
}

func (h *Handler) TransitionStage(c *gin.Context) {
	versionID := c.Param("versionId")
	var req StageTransitionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	version, err := h.service.TransitionStage(c.Request.Context(), versionID, &req)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, version)
}

func (h *Handler) GetVersionHistory(c *gin.Context) {
	versionID := c.Param("versionId")
	transitions, err := h.service.GetVersionHistory(c.Request.Context(), versionID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, transitions)
}
