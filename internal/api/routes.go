package api

import (
	"context"
	"encoding/json"
	"io"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/solocoder/cloudci/internal/common/errors"
	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/models"
	"gorm.io/datatypes"
	"gorm.io/gorm"
)

func (s *APIServer) SetupRoutes() {
	s.router.GET("/health", s.healthCheck)

	apiV1 := s.router.Group("/api/v1")
	apiV1.Use(s.rateLimitMiddleware(), s.authMiddleware())
	{
		pipelines := apiV1.Group("/pipelines")
		{
			pipelines.POST("", s.createPipeline)
			pipelines.GET("", s.listPipelines)
			pipelines.GET("/:id", s.getPipeline)
			pipelines.PUT("/:id", s.updatePipeline)
			pipelines.DELETE("/:id", s.deletePipeline)
			pipelines.POST("/:id/trigger", s.triggerPipeline)
		}

		executions := apiV1.Group("/executions")
		{
			executions.GET("", s.listExecutions)
			executions.GET("/:id", s.getExecution)
			executions.POST("/:id/cancel", s.cancelExecution)
			executions.GET("/:id/logs", s.getExecutionLogs)
		}

		webhooks := apiV1.Group("/webhooks")
		{
			webhooks.POST("/github", s.handleGitHubWebhook)
			webhooks.POST("/gitlab", s.handleGitLabWebhook)
		}

		plugins := apiV1.Group("/plugins")
		{
			plugins.GET("", s.listPlugins)
			plugins.GET("/:name/:version", s.getPlugin)
			plugins.POST("", s.registerPlugin)
			plugins.DELETE("/:name/:version", s.unregisterPlugin)
			plugins.GET("/:name/:version/health", s.checkPluginHealth)
		}

		secrets := apiV1.Group("/secrets")
		{
			secrets.POST("", s.createSecret)
			secrets.GET("", s.listSecrets)
			secrets.GET("/:name", s.getSecret)
			secrets.PUT("/:name", s.updateSecret)
			secrets.DELETE("/:name", s.deleteSecret)
		}
	}
}

func (s *APIServer) createPipeline(c *gin.Context) {
	var req struct {
		Name        string                   `json:"name" binding:"required"`
		ProjectID   string                   `json:"project_id" binding:"required"`
		Description string                   `json:"description"`
		Definition  types.PipelineDefinition `json:"definition" binding:"required"`
		Labels      map[string]string        `json:"labels"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeValidation, "Invalid request body"))
		return
	}

	parseResult, err := s.parser.ParseJSON(mustJSON(req.Definition))
	if err != nil {
		s.sendError(c, err)
		return
	}

	pipeline := &models.Pipeline{
		ID:          types.NewID(),
		Name:        req.Name,
		ProjectID:   req.ProjectID,
		Description: req.Description,
		Status:      types.PipelineStatusActive,
		Version:     1,
	}

	if err := pipeline.SetDefinition(parseResult.Definition); err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to set pipeline definition"))
		return
	}

	if req.Labels != nil {
		pipeline.Labels = mustJSON(req.Labels)
	}

	if err := s.db.Create(pipeline).Error; err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to create pipeline"))
		return
	}

	s.sendCreated(c, pipeline)
}

func (s *APIServer) listPipelines(c *gin.Context) {
	offset, limit, err := s.getPagination(c)
	if err != nil {
		s.sendError(c, err)
		return
	}

	projectID := c.Query("project_id")
	status := c.Query("status")

	query := s.db.Model(&models.Pipeline{})
	if projectID != "" {
		query = query.Where("project_id = ?", projectID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	var total int64
	if err := query.Count(&total).Error; err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to count pipelines"))
		return
	}

	var pipelines []models.Pipeline
	if err := query.Offset(offset).Limit(limit).Order("created_at DESC").Find(&pipelines).Error; err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to list pipelines"))
		return
	}

	s.sendSuccess(c, s.paginateResponse(c, total, pipelines))
}

func (s *APIServer) getPipeline(c *gin.Context) {
	id, err := s.getIDParam(c, "id")
	if err != nil {
		s.sendError(c, err)
		return
	}

	var pipeline models.Pipeline
	if err := s.db.First(&pipeline, "id = ?", id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			s.sendError(c, errors.New(errors.ErrCodeNotFound, "Pipeline not found"))
			return
		}
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to get pipeline"))
		return
	}

	s.sendSuccess(c, pipeline)
}

func (s *APIServer) updatePipeline(c *gin.Context) {
	id, err := s.getIDParam(c, "id")
	if err != nil {
		s.sendError(c, err)
		return
	}

	var pipeline models.Pipeline
	if err := s.db.First(&pipeline, "id = ?", id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			s.sendError(c, errors.New(errors.ErrCodeNotFound, "Pipeline not found"))
			return
		}
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to get pipeline"))
		return
	}

	var req struct {
		Name        string                    `json:"name"`
		Description string                    `json:"description"`
		Definition  *types.PipelineDefinition `json:"definition"`
		Status      string                    `json:"status"`
		Labels      map[string]string         `json:"labels"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeValidation, "Invalid request body"))
		return
	}

	if req.Name != "" {
		pipeline.Name = req.Name
	}
	if req.Description != "" {
		pipeline.Description = req.Description
	}
	if req.Definition != nil {
		parseResult, err := s.parser.ParseJSON(mustJSON(req.Definition))
		if err != nil {
			s.sendError(c, err)
			return
		}
		if err := pipeline.SetDefinition(parseResult.Definition); err != nil {
			s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to set pipeline definition"))
			return
		}
		pipeline.Version++
	}
	if req.Status != "" {
		pipeline.Status = types.PipelineStatus(req.Status)
	}
	if req.Labels != nil {
		pipeline.Labels = mustJSON(req.Labels)
	}

	if err := s.db.Save(&pipeline).Error; err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to update pipeline"))
		return
	}

	s.sendSuccess(c, pipeline)
}

func (s *APIServer) deletePipeline(c *gin.Context) {
	id, err := s.getIDParam(c, "id")
	if err != nil {
		s.sendError(c, err)
		return
	}

	result := s.db.Delete(&models.Pipeline{}, "id = ?", id)
	if result.Error != nil {
		s.sendError(c, errors.Wrap(result.Error, errors.ErrCodeInternal, "Failed to delete pipeline"))
		return
	}
	if result.RowsAffected == 0 {
		s.sendError(c, errors.New(errors.ErrCodeNotFound, "Pipeline not found"))
		return
	}

	s.sendSuccess(c, map[string]interface{}{"deleted": true})
}

func (s *APIServer) triggerPipeline(c *gin.Context) {
	id, err := s.getIDParam(c, "id")
	if err != nil {
		s.sendError(c, err)
		return
	}

	var pipeline models.Pipeline
	if err := s.db.First(&pipeline, "id = ?", id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			s.sendError(c, errors.New(errors.ErrCodeNotFound, "Pipeline not found"))
			return
		}
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to get pipeline"))
		return
	}

	if pipeline.Status != types.PipelineStatusActive {
		s.sendError(c, errors.New(errors.ErrCodeValidation, "Pipeline is not active"))
		return
	}

	var req struct {
		Variables map[string]string `json:"variables"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeValidation, "Invalid request body"))
		return
	}

	event, err := s.triggerAdapter.TriggerManual(c.Request.Context(), id, req.Variables)
	if err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to trigger pipeline"))
		return
	}

	s.sendSuccess(c, map[string]interface{}{
		"event_id":    event.ID,
		"pipeline_id": id,
		"triggered":   true,
	})
}

func (s *APIServer) listExecutions(c *gin.Context) {
	offset, limit, err := s.getPagination(c)
	if err != nil {
		s.sendError(c, err)
		return
	}

	pipelineID := c.Query("pipeline_id")
	status := c.Query("status")
	projectID := c.Query("project_id")

	query := s.db.Model(&models.PipelineExecution{})
	if pipelineID != "" {
		query = query.Where("pipeline_id = ?", pipelineID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if projectID != "" {
		query = query.Where("project_id = ?", projectID)
	}

	var total int64
	if err := query.Count(&total).Error; err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to count executions"))
		return
	}

	var executions []models.PipelineExecution
	if err := query.Offset(offset).Limit(limit).Order("created_at DESC").Find(&executions).Error; err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to list executions"))
		return
	}

	s.sendSuccess(c, s.paginateResponse(c, total, executions))
}

func (s *APIServer) getExecution(c *gin.Context) {
	id, err := s.getIDParam(c, "id")
	if err != nil {
		s.sendError(c, err)
		return
	}

	var execution models.PipelineExecution
	if err := s.db.Preload("Stages").First(&execution, "id = ?", id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			s.sendError(c, errors.New(errors.ErrCodeNotFound, "Execution not found"))
			return
		}
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to get execution"))
		return
	}

	s.sendSuccess(c, execution)
}

func (s *APIServer) cancelExecution(c *gin.Context) {
	id, err := s.getIDParam(c, "id")
	if err != nil {
		s.sendError(c, err)
		return
	}

	if err := s.scheduler.CancelExecution(id); err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to cancel execution"))
		return
	}

	s.sendSuccess(c, map[string]interface{}{
		"execution_id": id,
		"canceled":     true,
	})
}

func (s *APIServer) getExecutionLogs(c *gin.Context) {
	id, err := s.getIDParam(c, "id")
	if err != nil {
		s.sendError(c, err)
		return
	}

	stageID := c.Query("stage_id")
	limit := 1000
	if l := c.Query("limit"); l != "" {
		if parsed, err := strconv.Atoi(l); err == nil && parsed > 0 && parsed <= 10000 {
			limit = parsed
		}
	}

	query := s.db.Model(&models.LogRecord{}).Where("execution_id = ?", id)
	if stageID != "" {
		query = query.Where("stage_id = ?", stageID)
	}

	var logs []models.LogRecord
	if err := query.Order("timestamp ASC").Limit(limit).Find(&logs).Error; err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to get execution logs"))
		return
	}

	s.sendSuccess(c, map[string]interface{}{
		"execution_id": id,
		"stage_id":     stageID,
		"logs":         logs,
	})
}

func (s *APIServer) handleGitHubWebhook(c *gin.Context) {
	payload, err := io.ReadAll(c.Request.Body)
	if err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to read webhook payload"))
		return
	}

	headers := make(map[string]string)
	for k, v := range c.Request.Header {
		if len(v) > 0 {
			headers[k] = v[0]
		}
	}

	event, err := s.triggerAdapter.HandleWebhook(c.Request.Context(), types.EventSourceGitHub, payload, headers)
	if err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeValidation, "Invalid webhook"))
		return
	}

	s.sendSuccess(c, map[string]interface{}{
		"event_id": event.ID,
		"received": true,
	})
}

func (s *APIServer) handleGitLabWebhook(c *gin.Context) {
	payload, err := io.ReadAll(c.Request.Body)
	if err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to read webhook payload"))
		return
	}

	headers := make(map[string]string)
	for k, v := range c.Request.Header {
		if len(v) > 0 {
			headers[k] = v[0]
		}
	}

	event, err := s.triggerAdapter.HandleWebhook(c.Request.Context(), types.EventSourceGitLab, payload, headers)
	if err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeValidation, "Invalid webhook"))
		return
	}

	s.sendSuccess(c, map[string]interface{}{
		"event_id": event.ID,
		"received": true,
	})
}

func (s *APIServer) listPlugins(c *gin.Context) {
	pluginType := c.Query("type")
	var filter *types.StageType
	if pluginType != "" {
		ft := types.StageType(pluginType)
		filter = &ft
	}

	plugins, err := s.pluginMgr.List(c.Request.Context(), filter)
	if err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to list plugins"))
		return
	}

	s.sendSuccess(c, plugins)
}

func (s *APIServer) getPlugin(c *gin.Context) {
	name := c.Param("name")
	version := c.Param("version")

	plugin, err := s.pluginMgr.GetPlugin(c.Request.Context(), name, version)
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			s.sendError(c, errors.New(errors.ErrCodeNotFound, "Plugin not found"))
			return
		}
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to get plugin"))
		return
	}

	s.sendSuccess(c, plugin)
}

func (s *APIServer) registerPlugin(c *gin.Context) {
	var req struct {
		Name        string `json:"name" binding:"required"`
		Version     string `json:"version" binding:"required"`
		Type        string `json:"type" binding:"required"`
		Description string `json:"description"`
		BinaryPath  string `json:"binary_path" binding:"required"`
		Author      string `json:"author"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeValidation, "Invalid request body"))
		return
	}

	plugin, err := s.pluginMgr.Install(
		c.Request.Context(),
		req.Name,
		req.Version,
		req.BinaryPath,
		types.StageType(req.Type),
	)
	if err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to register plugin"))
		return
	}

	plugin.Description = req.Description
	plugin.Author = req.Author

	if err := s.db.Save(plugin).Error; err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to update plugin metadata"))
		return
	}

	s.sendCreated(c, plugin)
}

func (s *APIServer) unregisterPlugin(c *gin.Context) {
	name := c.Param("name")
	version := c.Param("version")

	if err := s.pluginMgr.Unregister(c.Request.Context(), name, version); err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to unregister plugin"))
		return
	}

	s.sendSuccess(c, map[string]interface{}{
		"name":         name,
		"version":      version,
		"unregistered": true,
	})
}

func (s *APIServer) checkPluginHealth(c *gin.Context) {
	name := c.Param("name")
	version := c.Param("version")

	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()

	client, err := s.pluginMgr.GetClient(ctx, name, version)
	if err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to get plugin client"))
		return
	}

	health, err := client.HealthCheck(ctx, name)
	if err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Health check failed"))
		return
	}

	s.sendSuccess(c, map[string]interface{}{
		"name":       name,
		"version":    version,
		"healthy":    health.Healthy,
		"message":    health.Message,
		"checked_at": time.Now().Format(time.RFC3339),
	})
}

func (s *APIServer) createSecret(c *gin.Context) {
	var req struct {
		Name             string     `json:"name" binding:"required"`
		Description      string     `json:"description"`
		Source           string     `json:"source" binding:"required"`
		VaultPath        string     `json:"vault_path"`
		VaultKey         string     `json:"vault_key"`
		EnvVarName       string     `json:"env_var_name"`
		ProjectID        string     `json:"project_id"`
		AllowedPipelines []string   `json:"allowed_pipelines"`
		AllowedStages    []string   `json:"allowed_stages"`
		ExpiresAt        *time.Time `json:"expires_at"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeValidation, "Invalid request body"))
		return
	}

	secret := &models.Secret{
		ID:          types.NewID(),
		Name:        req.Name,
		Description: req.Description,
		Source:      types.SecretSource(req.Source),
		VaultPath:   req.VaultPath,
		VaultKey:    req.VaultKey,
		EnvVarName:  req.EnvVarName,
		ProjectID:   req.ProjectID,
		ExpiresAt:   req.ExpiresAt,
		Version:     1,
	}

	if req.AllowedPipelines != nil {
		secret.AllowedPipelines = mustJSON(req.AllowedPipelines)
	}
	if req.AllowedStages != nil {
		secret.AllowedStages = mustJSON(req.AllowedStages)
	}

	if err := s.db.Create(secret).Error; err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to create secret"))
		return
	}

	s.sendCreated(c, maskSecret(secret))
}

func (s *APIServer) listSecrets(c *gin.Context) {
	offset, limit, err := s.getPagination(c)
	if err != nil {
		s.sendError(c, err)
		return
	}

	projectID := c.Query("project_id")
	source := c.Query("source")

	query := s.db.Model(&models.Secret{})
	if projectID != "" {
		query = query.Where("project_id = ?", projectID)
	}
	if source != "" {
		query = query.Where("source = ?", source)
	}

	var total int64
	if err := query.Count(&total).Error; err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to count secrets"))
		return
	}

	var secrets []models.Secret
	if err := query.Offset(offset).Limit(limit).Order("created_at DESC").Find(&secrets).Error; err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to list secrets"))
		return
	}

	maskedSecrets := make([]map[string]interface{}, len(secrets))
	for i, secret := range secrets {
		maskedSecrets[i] = maskSecret(&secret)
	}

	s.sendSuccess(c, s.paginateResponse(c, total, maskedSecrets))
}

func (s *APIServer) getSecret(c *gin.Context) {
	name := c.Param("name")

	var secret models.Secret
	if err := s.db.Where("name = ?", name).First(&secret).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			s.sendError(c, errors.New(errors.ErrCodeNotFound, "Secret not found"))
			return
		}
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to get secret"))
		return
	}

	s.sendSuccess(c, maskSecret(&secret))
}

func (s *APIServer) updateSecret(c *gin.Context) {
	name := c.Param("name")

	var secret models.Secret
	if err := s.db.Where("name = ?", name).First(&secret).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			s.sendError(c, errors.New(errors.ErrCodeNotFound, "Secret not found"))
			return
		}
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to get secret"))
		return
	}

	var req struct {
		Description      *string    `json:"description"`
		VaultPath        *string    `json:"vault_path"`
		VaultKey         *string    `json:"vault_key"`
		EnvVarName       *string    `json:"env_var_name"`
		AllowedPipelines []string   `json:"allowed_pipelines"`
		AllowedStages    []string   `json:"allowed_stages"`
		ExpiresAt        *time.Time `json:"expires_at"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeValidation, "Invalid request body"))
		return
	}

	if req.Description != nil {
		secret.Description = *req.Description
	}
	if req.VaultPath != nil {
		secret.VaultPath = *req.VaultPath
	}
	if req.VaultKey != nil {
		secret.VaultKey = *req.VaultKey
	}
	if req.EnvVarName != nil {
		secret.EnvVarName = *req.EnvVarName
	}
	if req.AllowedPipelines != nil {
		secret.AllowedPipelines = mustJSON(req.AllowedPipelines)
	}
	if req.AllowedStages != nil {
		secret.AllowedStages = mustJSON(req.AllowedStages)
	}
	if req.ExpiresAt != nil {
		secret.ExpiresAt = req.ExpiresAt
	}

	secret.Version++
	now := time.Now()
	secret.RotatedAt = &now

	if err := s.db.Save(&secret).Error; err != nil {
		s.sendError(c, errors.Wrap(err, errors.ErrCodeInternal, "Failed to update secret"))
		return
	}

	s.sendSuccess(c, maskSecret(&secret))
}

func (s *APIServer) deleteSecret(c *gin.Context) {
	name := c.Param("name")

	result := s.db.Where("name = ?", name).Delete(&models.Secret{})
	if result.Error != nil {
		s.sendError(c, errors.Wrap(result.Error, errors.ErrCodeInternal, "Failed to delete secret"))
		return
	}
	if result.RowsAffected == 0 {
		s.sendError(c, errors.New(errors.ErrCodeNotFound, "Secret not found"))
		return
	}

	s.sendSuccess(c, map[string]interface{}{
		"name":    name,
		"deleted": true,
	})
}

func mustJSON(v interface{}) datatypes.JSON {
	data, _ := json.Marshal(v)
	return datatypes.JSON(data)
}

func maskSecret(secret *models.Secret) map[string]interface{} {
	return map[string]interface{}{
		"id":                secret.ID,
		"name":              secret.Name,
		"description":       secret.Description,
		"source":            secret.Source,
		"vault_path":        secret.VaultPath,
		"vault_key":         secret.VaultKey,
		"env_var_name":      secret.EnvVarName,
		"project_id":        secret.ProjectID,
		"allowed_pipelines": secret.AllowedPipelines,
		"allowed_stages":    secret.AllowedStages,
		"rotated_at":        secret.RotatedAt,
		"expires_at":        secret.ExpiresAt,
		"version":           secret.Version,
		"created_by":        secret.CreatedBy,
		"created_at":        secret.CreatedAt,
		"updated_at":        secret.UpdatedAt,
	}
}
