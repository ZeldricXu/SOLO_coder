package scaffold

import (
	"depguard/internal/common/response"
	"strconv"

	"github.com/gin-gonic/gin"
)

type Handler struct {
	service *ScaffoldService
}

func NewHandler() *Handler {
	return &Handler{
		service: NewScaffoldService(),
	}
}

type CreateBackupRequest struct {
	ResourceType string `json:"resource_type" binding:"required,oneof=template project"`
	ResourceID   string `json:"resource_id" binding:"required"`
	BackupType   string `json:"backup_type" binding:"required,oneof=manual automatic snapshot"`
	CreatedBy    string `json:"created_by"`
}

type RestoreBackupRequest struct {
	BackupID    string `json:"backup_id" binding:"required"`
	RecoveredBy string `json:"recovered_by"`
}

func (h *Handler) CreateTemplate(c *gin.Context) {
	var req CreateTemplateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	template, err := h.service.CreateTemplate(&req)
	if err != nil {
		if appErr, ok := err.(*response); ok {
			response.Error(c, appErr.Code, appErr.Message)
			return
		}
		response.InternalServerError(c, err.Error())
		return
	}

	response.SuccessCreated(c, template)
}

func (h *Handler) UpdateTemplate(c *gin.Context) {
	id := c.Param("id")
	var req UpdateTemplateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	err := h.service.UpdateTemplate(id, &req)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, nil)
}

func (h *Handler) DeleteTemplate(c *gin.Context) {
	id := c.Param("id")
	err := h.service.DeleteTemplate(id)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, nil)
}

func (h *Handler) GetTemplate(c *gin.Context) {
	id := c.Param("id")
	template, err := h.service.GetTemplate(id)
	if err != nil {
		response.NotFound(c, "Template not found")
		return
	}
	response.Success(c, template)
}

func (h *Handler) ListTemplates(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	keyword := c.Query("keyword")
	language := c.Query("language")

	templates, total, err := h.service.ListTemplates(page, pageSize, keyword, language)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"items": templates,
		"total": total,
		"page":  page,
		"size":  pageSize,
	})
}

func (h *Handler) GenerateProject(c *gin.Context) {
	var req GenerateProjectRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	task, err := h.service.GenerateProject(&req)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.SuccessCreated(c, gin.H{
		"task_id":     task.TaskID,
		"status":      task.Status,
		"progress":    task.Progress,
		"project_id":  task.ProjectID,
		"template_id": task.TemplateID,
	})
}

func (h *Handler) GetTaskStatus(c *gin.Context) {
	taskID := c.Param("task_id")
	task, err := h.service.GetTaskStatus(taskID)
	if err != nil {
		response.NotFound(c, "Task not found")
		return
	}

	response.Success(c, gin.H{
		"task_id":      task.TaskID,
		"status":       task.Status,
		"progress":     task.Progress,
		"project_id":   task.ProjectID,
		"template_id":  task.TemplateID,
		"logs":         task.Logs,
		"started_at":   task.StartedAt,
		"completed_at": task.CompletedAt,
		"error_msg":    task.ErrorMsg,
	})
}

func (h *Handler) StartInteractiveSession(c *gin.Context) {
	var req InteractiveStartRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	session, err := h.service.StartInteractiveSession(&req)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.SuccessCreated(c, gin.H{
		"session_id":   session.SessionID,
		"template_id":  session.TemplateID,
		"current_step": session.CurrentStep,
		"total_steps":  session.TotalSteps,
		"status":       session.Status,
		"expires_at":   session.ExpiresAt,
	})
}

func (h *Handler) SubmitAnswer(c *gin.Context) {
	sessionID := c.Param("session_id")
	var req InteractiveAnswerRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	session, err := h.service.SubmitAnswer(sessionID, &req)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"session_id":   session.SessionID,
		"current_step": session.CurrentStep,
		"total_steps":  session.TotalSteps,
		"status":       session.Status,
		"answers":      session.Answers,
	})
}

func (h *Handler) GetProject(c *gin.Context) {
	id := c.Param("id")
	project, err := h.service.GetProject(id)
	if err != nil {
		response.NotFound(c, "Project not found")
		return
	}
	response.Success(c, project)
}

func (h *Handler) ListProjects(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	ownerID := c.Query("owner_id")
	namespace := c.Query("namespace")

	projects, total, err := h.service.ListProjects(page, pageSize, ownerID, namespace)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"items": projects,
		"total": total,
		"page":  page,
		"size":  pageSize,
	})
}

func (h *Handler) DeleteProject(c *gin.Context) {
	id := c.Param("id")
	err := h.service.DeleteProject(id)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, nil)
}

func (h *Handler) CreateBackup(c *gin.Context) {
	var req CreateBackupRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	backup, err := h.service.CreateBackup(req.ResourceType, req.ResourceID, req.BackupType, req.CreatedBy)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.SuccessCreated(c, backup)
}

func (h *Handler) ListBackups(c *gin.Context) {
	resourceType := c.Query("resource_type")
	resourceID := c.Query("resource_id")

	backups, total, err := h.service.ListBackups(resourceType, resourceID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"items": backups,
		"total": total,
	})
}

func (h *Handler) RestoreBackup(c *gin.Context) {
	var req RestoreBackupRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, "Invalid request parameters")
		return
	}

	record, err := h.service.RestoreFromBackup(req.BackupID, req.RecoveredBy)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, record)
}

func (h *Handler) DeleteBackup(c *gin.Context) {
	backupID := c.Param("backup_id")
	err := h.service.DeleteBackup(backupID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}
	response.Success(c, nil)
}

func (h *Handler) VerifyBackup(c *gin.Context) {
	backupID := c.Param("backup_id")
	valid, err := h.service.VerifyBackup(backupID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"backup_id": backupID,
		"valid":     valid,
	})
}

func (h *Handler) ResumeTask(c *gin.Context) {
	taskID := c.Param("task_id")
	task, err := h.service.ResumeTask(taskID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, gin.H{
		"task_id":  task.TaskID,
		"status":   "resumed",
		"progress": task.Progress,
	})
}

func (h *Handler) GetTaskCheckpoints(c *gin.Context) {
	taskID := c.Param("task_id")
	checkpoints, err := h.service.GetTaskCheckpoints(taskID)
	if err != nil {
		response.InternalServerError(c, err.Error())
		return
	}

	response.Success(c, checkpoints)
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	templates := r.Group("/templates")
	{
		templates.POST("", h.CreateTemplate)
		templates.GET("", h.ListTemplates)
		templates.GET("/:id", h.GetTemplate)
		templates.PUT("/:id", h.UpdateTemplate)
		templates.DELETE("/:id", h.DeleteTemplate)
	}

	projects := r.Group("/projects")
	{
		projects.POST("/generate", h.GenerateProject)
		projects.GET("", h.ListProjects)
		projects.GET("/:id", h.GetProject)
		projects.DELETE("/:id", h.DeleteProject)
	}

	tasks := r.Group("/tasks")
	{
		tasks.GET("/:task_id/status", h.GetTaskStatus)
		tasks.POST("/:task_id/resume", h.ResumeTask)
		tasks.GET("/:task_id/checkpoints", h.GetTaskCheckpoints)
	}

	interactive := r.Group("/interactive")
	{
		interactive.POST("/start", h.StartInteractiveSession)
		interactive.POST("/:session_id/answer", h.SubmitAnswer)
	}

	backups := r.Group("/backups")
	{
		backups.POST("", h.CreateBackup)
		backups.GET("", h.ListBackups)
		backups.DELETE("/:backup_id", h.DeleteBackup)
		backups.GET("/:backup_id/verify", h.VerifyBackup)
	}

	r.POST("/restore", h.RestoreBackup)
}
