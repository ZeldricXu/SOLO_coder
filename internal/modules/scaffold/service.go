package scaffold

import (
	"crypto/sha256"
	"depguard/internal/common/utils"
	"depguard/internal/database"
	"depguard/internal/logger"
	apperrors "depguard/pkg/errors"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"go.uber.org/zap"
)

type ScaffoldService struct {
	templateRepo    TemplateRepository
	projectRepo     ProjectRepository
	taskRepo        TaskRepository
	sessionRepo     SessionRepository
	checkpointMutex sync.Map
	retryMaxAttempts int
	retryDelay       time.Duration
}

func NewScaffoldService() *ScaffoldService {
	return &ScaffoldService{
		templateRepo:     NewTemplateRepository(),
		projectRepo:      NewProjectRepository(),
		taskRepo:         NewTaskRepository(),
		sessionRepo:      NewSessionRepository(),
		retryMaxAttempts: 3,
		retryDelay:       5 * time.Second,
	}
}

func (s *ScaffoldService) CreateTemplate(req *CreateTemplateRequest) (*Template, error) {
	existing, _ := s.templateRepo.GetByName(req.Name)
	if existing != nil {
		return nil, apperrors.New(409, "template name already exists")
	}

	template := &Template{
		Name:        req.Name,
		Description: req.Description,
		Language:    req.Language,
		Framework:   req.Framework,
		Version:     req.Version,
		Tags:        req.Tags,
		Parameters:  req.Parameters,
		FileTree:    req.FileTree,
		IsPublic:    req.IsPublic,
		Author:      req.Author,
	}

	err := s.templateRepo.Create(template)
	if err != nil {
		return nil, apperrors.Wrap(500, "failed to create template", err)
	}
	return template, nil
}

func (s *ScaffoldService) UpdateTemplate(id string, req *UpdateTemplateRequest) error {
	template, err := s.templateRepo.GetByID(id)
	if err != nil {
		return apperrors.ErrNotFound
	}

	updates := make(map[string]interface{})
	if req.Name != "" && req.Name != template.Name {
		existing, _ := s.templateRepo.GetByName(req.Name)
		if existing != nil && existing.ID != id {
			return apperrors.New(409, "template name already exists")
		}
		updates["name"] = req.Name
	}
	if req.Description != "" {
		updates["description"] = req.Description
	}
	if req.Language != "" {
		updates["language"] = req.Language
	}
	if req.Framework != "" {
		updates["framework"] = req.Framework
	}
	if req.Version != "" {
		updates["version"] = req.Version
	}
	if req.Tags != nil {
		updates["tags"] = req.Tags
	}
	if req.Parameters != nil {
		updates["parameters"] = req.Parameters
	}
	if req.FileTree != nil {
		updates["file_tree"] = req.FileTree
	}
	if req.IsPublic != nil {
		updates["is_public"] = *req.IsPublic
	}

	return s.templateRepo.Update(id, updates)
}

func (s *ScaffoldService) DeleteTemplate(id string) error {
	_, err := s.templateRepo.GetByID(id)
	if err != nil {
		return apperrors.ErrNotFound
	}
	return s.templateRepo.Delete(id)
}

func (s *ScaffoldService) GetTemplate(id string) (*Template, error) {
	template, err := s.templateRepo.GetByID(id)
	if err != nil {
		return nil, apperrors.ErrNotFound
	}
	return template, nil
}

func (s *ScaffoldService) ListTemplates(page, pageSize int, keyword, language string) ([]Template, int64, error) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	return s.templateRepo.List(page, pageSize, keyword, language)
}

func (s *ScaffoldService) GenerateProject(req *GenerateProjectRequest) (*GenerationTask, error) {
	_, err := s.templateRepo.GetByID(req.TemplateID)
	if err != nil {
		return nil, apperrors.New(400, "invalid template id")
	}

	project := &Project{
		Name:        req.Name,
		Description: req.Description,
		TemplateID:  req.TemplateID,
		Namespace:   req.Namespace,
		Config:      req.Config,
		OwnerID:     req.OwnerID,
		Status:      "generating",
	}

	err = s.projectRepo.Create(project)
	if err != nil {
		return nil, apperrors.Wrap(500, "failed to create project", err)
	}

	taskID := utils.GenerateID("gen")
	task := &GenerationTask{
		TaskID:     taskID,
		ProjectID:  project.ID,
		TemplateID: req.TemplateID,
		Status:     "running",
		Progress:   0,
		Parameters: req.Config,
		Logs:       []string{"Task started"},
		StartedAt:  utils.TimeNowPtr(),
	}

	err = s.taskRepo.Create(task)
	if err != nil {
		return nil, apperrors.Wrap(500, "failed to create generation task", err)
	}

	go s.executeGenerationWithCheckpoints(task, project)

	return task, nil
}

func (s *ScaffoldService) GetTaskStatus(taskID string) (*GenerationTask, error) {
	task, err := s.taskRepo.GetByTaskID(taskID)
	if err != nil {
		return nil, apperrors.ErrNotFound
	}
	return task, nil
}

func (s *ScaffoldService) StartInteractiveSession(req *InteractiveStartRequest) (*InteractiveSession, error) {
	_, err := s.templateRepo.GetByID(req.TemplateID)
	if err != nil {
		return nil, apperrors.New(400, "invalid template id")
	}

	session := &InteractiveSession{
		SessionID:   utils.GenerateID("isess"),
		TemplateID:  req.TemplateID,
		UserID:      req.UserID,
		CurrentStep: 0,
		TotalSteps:  5,
		Answers:     make(map[string]interface{}),
		Status:      "active",
		ExpiresAt:   time.Now().Add(30 * time.Minute),
	}

	err = s.sessionRepo.Create(session)
	if err != nil {
		return nil, apperrors.Wrap(500, "failed to create session", err)
	}

	return session, nil
}

func (s *ScaffoldService) SubmitAnswer(sessionID string, req *InteractiveAnswerRequest) (*InteractiveSession, error) {
	session, err := s.sessionRepo.GetBySessionID(sessionID)
	if err != nil {
		return nil, apperrors.ErrNotFound
	}

	if session.Status != "active" {
		return nil, apperrors.New(400, "session is not active")
	}

	if session.ExpiresAt.Before(time.Now()) {
		_ = s.sessionRepo.Update(session.ID, map[string]interface{}{"status": "expired"})
		return nil, apperrors.New(400, "session has expired")
	}

	answers := session.Answers
	answers[req.QuestionID] = req.Answer

	nextStep := session.CurrentStep + 1
	status := "active"
	if nextStep >= session.TotalSteps {
		status = "completed"
	}

	updates := map[string]interface{}{
		"answers":      answers,
		"current_step": nextStep,
		"status":       status,
	}

	err = s.sessionRepo.Update(session.ID, updates)
	if err != nil {
		return nil, apperrors.Wrap(500, "failed to update session", err)
	}

	session.CurrentStep = nextStep
	session.Answers = answers
	session.Status = status

	return session, nil
}

func (s *ScaffoldService) GetProject(id string) (*Project, error) {
	project, err := s.projectRepo.GetByID(id)
	if err != nil {
		return nil, apperrors.ErrNotFound
	}
	return project, nil
}

func (s *ScaffoldService) ListProjects(page, pageSize int, ownerID, namespace string) ([]Project, int64, error) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	return s.projectRepo.List(page, pageSize, ownerID, namespace)
}

func (s *ScaffoldService) DeleteProject(id string) error {
	_, err := s.projectRepo.GetByID(id)
	if err != nil {
		return apperrors.ErrNotFound
	}
	return s.projectRepo.Delete(id)
}

func (s *ScaffoldService) createCheckpoint(taskID, stepName string, stepIndex int, progress float64, stateData map[string]interface{}) error {
	checksum := s.generateChecksum(stateData)

	checkpoint := &TaskCheckpoint{
		TaskID:      taskID,
		StepName:    stepName,
		StepIndex:   stepIndex,
		Progress:    progress,
		StateData:   stateData,
		IsCompleted: false,
		Checksum:    checksum,
	}

	if err := database.DB.Create(checkpoint).Error; err != nil {
		logger.Log.Error("Failed to create checkpoint", zap.String("task_id", taskID), zap.Error(err))
		return err
	}

	return nil
}

func (s *ScaffoldService) completeCheckpoint(taskID string, stepIndex int) error {
	now := time.Now()
	return database.DB.Model(&TaskCheckpoint{}).
		Where("task_id = ? AND step_index = ?", taskID, stepIndex).
		Update("is_completed", true).
		Update("last_attempt_at", &now).Error
}

func (s *ScaffoldService) getLastCheckpoint(taskID string) (*TaskCheckpoint, error) {
	var checkpoint TaskCheckpoint
	err := database.DB.Where("task_id = ?", taskID).
		Order("step_index DESC").
		First(&checkpoint).Error
	if err != nil {
		return nil, err
	}
	return &checkpoint, nil
}

func (s *ScaffoldService) listCheckpoints(taskID string) ([]TaskCheckpoint, error) {
	var checkpoints []TaskCheckpoint
	err := database.DB.Where("task_id = ?", taskID).
		Order("step_index ASC").
		Find(&checkpoints).Error
	return checkpoints, err
}

func (s *ScaffoldService) generateChecksum(data interface{}) string {
	jsonData, _ := json.Marshal(data)
	hash := sha256.Sum256(jsonData)
	return hex.EncodeToString(hash[:])[:16])
}

func (s *ScaffoldService) retryWithRetry(operation func() error, taskID string) error {
	var lastErr error
	for attempt := 0; attempt < s.retryMaxAttempts; attempt++ {
		if err := operation(); err == nil {
			return nil
		} else {
			lastErr = err
			logger.Log.Warn("Operation failed, retrying",
				zap.String("task_id", taskID),
				zap.Int("attempt", attempt+1),
				zap.Error(err))

			database.DB.Model(&TaskCheckpoint{}).
				Where("task_id = ?", taskID).
				Update("retry_count", attempt+1).
				Update("error_message", err.Error())

			time.Sleep(s.retryDelay)
		}
	}
	return lastErr
}

func (s *ScaffoldService) CreateBackup(resourceType, resourceID string, backupType string, createdBy string) (*DataBackup, error) {
	var dataSnapshot map[string]interface{}

	switch resourceType {
	case "template":
		template, err := s.templateRepo.GetByID(resourceID)
		if err != nil {
			return nil, apperrors.ErrNotFound
		}
		data, _ := json.Marshal(template)
		json.Unmarshal(data, &dataSnapshot)
	case "project":
		project, err := s.projectRepo.GetByID(resourceID)
		if err != nil {
			return nil, apperrors.ErrNotFound
		}
		data, _ := json.Marshal(project)
		json.Unmarshal(data, &dataSnapshot)
	default:
		return nil, apperrors.New(400, "unsupported resource type")
	}

	jsonData, _ := json.Marshal(dataSnapshot)
	checksum := s.generateChecksum(dataSnapshot)
	expiresAt := time.Now().Add(30 * 24 * time.Hour)

	backup := &DataBackup{
		BackupID:     utils.GenerateID("bck"),
		ResourceType: resourceType,
		ResourceID:   resourceID,
		DataSnapshot: dataSnapshot,
		BackupType:   backupType,
		Checksum:     checksum,
		SizeBytes:    int64(len(jsonData)),
		ExpiresAt:    &expiresAt,
		CreatedBy:    createdBy,
	}

	if err := database.DB.Create(backup).Error; err != nil {
		logger.Log.Error("Failed to create backup", zap.Error(err))
		return nil, apperrors.Wrap(500, "failed to create backup", err)
	}

	logger.Log.Info("Backup created successfully",
		zap.String("backup_id", backup.BackupID),
		zap.String("resource_type", resourceType),
		zap.String("resource_id", resourceID))

	return backup, nil
}

func (s *ScaffoldService) ListBackups(resourceType, resourceID string) ([]DataBackup, int64, error) {
	var backups []DataBackup
	var total int64

	query := database.DB.Model(&DataBackup{})
	if resourceType != "" {
		query = query.Where("resource_type = ?", resourceType)
	}
	if resourceID != "" {
		query = query.Where("resource_id = ?", resourceID)
	}

	query.Count(&total)
	err := query.Order("created_at DESC").Find(&backups).Error
	return backups, total, err
}

func (s *ScaffoldService) RestoreFromBackup(backupID, recoveredBy string) (*RecoveryRecord, error) {
	startTime := time.Now()
	var backup DataBackup
	if err := database.DB.Where("backup_id = ?", backupID).First(&backup).Error; err != nil {
		return nil, apperrors.ErrNotFound
	}

	recoveryLog := []string{"Starting recovery process"}
	recoveryLog = append(recoveryLog, "Loading backup data: "+backup.BackupID)

	var status = "success"

	switch backup.ResourceType {
	case "template":
		var template Template
		data, _ := json.Marshal(backup.DataSnapshot)
		if err := json.Unmarshal(data, &template); err != nil {
			recoveryLog = append(recoveryLog, "ERROR: Failed to unmarshal template data")
			status = "failed"
		} else {
			template.ID = ""
			template.CreatedAt = time.Now()
			template.UpdatedAt = time.Now()
			if err := database.DB.Create(&template).Error; err != nil {
				recoveryLog = append(recoveryLog, "ERROR: "+err.Error())
				status = "failed"
			} else {
				recoveryLog = append(recoveryLog, "Template restored successfully: "+template.ID)
			}
		}
	case "project":
		var project Project
		data, _ := json.Marshal(backup.DataSnapshot)
		if err := json.Unmarshal(data, &project); err != nil {
			recoveryLog = append(recoveryLog, "ERROR: Failed to unmarshal project data")
			status = "failed"
		} else {
			project.ID = ""
			project.CreatedAt = time.Now()
			project.UpdatedAt = time.Now()
			if err := database.DB.Create(&project).Error; err != nil {
				recoveryLog = append(recoveryLog, "ERROR: "+err.Error())
				status = "failed"
			} else {
				recoveryLog = append(recoveryLog, "Project restored successfully: "+project.ID)
			}
		}
	}

	recoveryLog = append(recoveryLog, "Recovery completed with status: "+status)

	record := &RecoveryRecord{
		RecoveryID:  utils.GenerateID("rec"),
		BackupID:   backupID,
		ResourceID: backup.ResourceID,
		Status:     status,
		RecoveredAt: time.Now(),
		RecoveredBy: recoveredBy,
		RecoveryLog: recoveryLog,
		DurationMs:  time.Since(startTime).Milliseconds(),
	}

	database.DB.Create(record)

	logger.Log.Info("Recovery completed",
		zap.String("recovery_id", record.RecoveryID),
		zap.String("status", status))

	return record, nil
}

func (s *ScaffoldService) DeleteBackup(backupID string) error {
	result := database.DB.Where("backup_id = ?", backupID).Delete(&DataBackup{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return apperrors.ErrNotFound
	}
	return nil
}

func (s *ScaffoldService) VerifyBackup(backupID string) (bool, error) {
	var backup DataBackup
	if err := database.DB.Where("backup_id = ?", backupID).First(&backup).Error; err != nil {
		return false, apperrors.ErrNotFound
	}

	calculatedChecksum := s.generateChecksum(backup.DataSnapshot)
	valid := calculatedChecksum == backup.Checksum

	return valid, nil
}

func (s *ScaffoldService) executeGenerationWithCheckpoints(task *GenerationTask, project *Project) {
	steps := []string{"Initializing project structure", "Generating files", "Installing dependencies", "Finalizing setup"}

	mutexKey := "task_" + task.TaskID
	if _, loaded := s.checkpointMutex.LoadOrStore(mutexKey, true); loaded {
		logger.Log.Warn("Task already in progress", zap.String("task_id", task.TaskID))
		return
	}
	defer s.checkpointMutex.Delete(mutexKey)

	lastCheckpoint, _ := s.getLastCheckpoint(task.TaskID)
	startIndex := 0
	if lastCheckpoint != nil && lastCheckpoint.IsCompleted {
		startIndex = lastCheckpoint.StepIndex + 1
		logger.Log.Info("Resuming task from checkpoint",
			zap.String("task_id", task.TaskID),
			zap.Int("resume_from", startIndex))
	}

	for i := startIndex; i < len(steps); i++ {
		step := steps[i]
		progress := float64(i+1) / float64(len(steps))

		stateData := map[string]interface{}{
			"step":      step,
			"step_index": i,
			"project_id": project.ID,
			"timestamp":  time.Now().Unix(),
		}

		if err := s.createCheckpoint(task.TaskID, step, i, progress, stateData); err != nil {
			logger.Log.Error("Failed to create checkpoint", zap.Error(err))
		}

		operation := func() error {
			time.Sleep(500 * time.Millisecond)
			return nil
		}

		if err := s.retryWithRetry(operation, task.TaskID); err != nil {
			logger.Log.Error("Step failed after retries",
				zap.String("task_id", task.TaskID),
				zap.String("step", step),
				zap.Error(err))

			_ = s.taskRepo.Update(task.ID, map[string]interface{}{
				"status":    "failed",
				"error_msg": err.Error(),
			})
			return
		}

		if err := s.completeCheckpoint(task.TaskID, i); err != nil {
			logger.Log.Warn("Failed to mark checkpoint complete", zap.Error(err))
		}

		updates := map[string]interface{}{
			"progress": progress,
			"logs":     append(task.Logs, step+" completed"),
		}
		_ = s.taskRepo.Update(task.ID, updates)
	}

	now := time.Now()
	_ = s.taskRepo.Update(task.ID, map[string]interface{}{
		"status":       "completed",
		"progress":     1.0,
		"completed_at": &now,
	})

	_ = s.projectRepo.Update(project.ID, map[string]interface{}{
		"status":       "completed",
		"generated_at": &now,
	})

	logger.Log.Info("Task completed successfully",
		zap.String("task_id", task.TaskID))
}

func (s *ScaffoldService) ResumeTask(taskID string) (*GenerationTask, error) {
	task, err := s.taskRepo.GetByTaskID(taskID)
	if err != nil {
		return nil, apperrors.ErrNotFound
	}

	if task.Status == "completed" {
		return nil, apperrors.New(400, "task already completed")
	}

	project, err := s.projectRepo.GetByID(task.ProjectID)
	if err != nil {
		return nil, apperrors.New(400, "associated project not found")
	}

	go s.executeGenerationWithCheckpoints(task, project)

	return task, nil
}

func (s *ScaffoldService) GetTaskCheckpoints(taskID string) ([]TaskCheckpoint, error) {
	return s.listCheckpoints(taskID)
}
