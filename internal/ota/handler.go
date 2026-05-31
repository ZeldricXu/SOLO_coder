package ota

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

type OTAHandler struct {
	manager *Manager
}

func NewOTAHandler(manager *Manager) *OTAHandler {
	return &OTAHandler{manager: manager}
}

type UploadFirmwareRequest struct {
	Version     string `json:"version" binding:"required"`
	Model       string `json:"model" binding:"required"`
	Data        []byte `json:"data" binding:"required"`
	Description string `json:"description"`
	MinVersion  string `json:"min_version"`
}

func (h *OTAHandler) UploadFirmware(c *gin.Context) {
	var req UploadFirmwareRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	firmware, err := h.manager.UploadFirmware(req.Version, req.Model, req.Data, req.Description, req.MinVersion)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, firmware)
}

func (h *OTAHandler) GetFirmware(c *gin.Context) {
	id := c.Param("id")
	firmware, exists := h.manager.GetFirmware(id)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "firmware not found"})
		return
	}
	c.JSON(http.StatusOK, firmware)
}

func (h *OTAHandler) ListFirmwares(c *gin.Context) {
	firmwares := h.manager.ListFirmwares()
	c.JSON(http.StatusOK, firmwares)
}

func (h *OTAHandler) DeleteFirmware(c *gin.Context) {
	id := c.Param("id")
	if !h.manager.DeleteFirmware(id) {
		c.JSON(http.StatusNotFound, gin.H{"error": "firmware not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "firmware deleted successfully"})
}

type CreateJobRequest struct {
	DeviceID       string `json:"device_id" binding:"required"`
	FirmwareID     string `json:"firmware_id" binding:"required"`
	CurrentVersion string `json:"current_version" binding:"required"`
}

func (h *OTAHandler) CreateJob(c *gin.Context) {
	var req CreateJobRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	jobID, err := h.manager.CreateUpgradeJob(req.DeviceID, req.FirmwareID, req.CurrentVersion)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusCreated, gin.H{
		"job_id":  jobID,
		"message": "upgrade job created",
	})
}

func (h *OTAHandler) GetJob(c *gin.Context) {
	id := c.Param("id")
	job, exists := h.manager.GetJob(id)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "job not found"})
		return
	}
	c.JSON(http.StatusOK, job)
}

func (h *OTAHandler) ListJobs(c *gin.Context) {
	jobs := h.manager.ListJobs()
	c.JSON(http.StatusOK, jobs)
}

func (h *OTAHandler) CancelJob(c *gin.Context) {
	id := c.Param("id")
	if !h.manager.CancelJob(id) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "cannot cancel job or job not found"})
		return
	}
	c.JSON(http.StatusOK, gin.H{"message": "job cancelled successfully"})
}

func (h *OTAHandler) GetStrategy(c *gin.Context) {
	strategy := h.manager.GetStrategy()
	c.JSON(http.StatusOK, strategy)
}

func (h *OTAHandler) UpdateStrategy(c *gin.Context) {
	var strategy UpgradeStrategy
	if err := c.ShouldBindJSON(&strategy); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	h.manager.UpdateStrategy(strategy)
	c.JSON(http.StatusOK, gin.H{"message": "strategy updated successfully"})
}
