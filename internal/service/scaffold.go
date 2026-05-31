package service

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	"projectservice/internal/infrastructure/logger"
	"projectservice/internal/infrastructure/monitor"
	"projectservice/internal/model"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

type ScaffoldService struct {
	db      *gorm.DB
	logger  *logger.Logger
	metrics *monitor.Metrics

	batchProgress sync.Map
	batchStatus   sync.Map
}

func NewScaffoldService(db *gorm.DB, log *logger.Logger, metrics *monitor.Metrics) *ScaffoldService {
	return &ScaffoldService{
		db:      db,
		logger:  log,
		metrics: metrics,
	}
}

func (s *ScaffoldService) ListTemplates(ctx context.Context, language string, tags []string, page, pageSize int) ([]model.ProjectTemplate, int64, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("scaffold", "list_templates", "success", time.Since(start))
	}()

	var templates []model.ProjectTemplate
	var total int64

	query := s.db.WithContext(ctx).Model(&model.ProjectTemplate{}).Where("status = ?", "active")

	if language != "" {
		query = query.Where("language = ?", language)
	}

	if len(tags) > 0 {
		query = query.Where("tags @> ?", tags)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&templates).Error; err != nil {
		return nil, 0, err
	}

	return templates, total, nil
}

func (s *ScaffoldService) GetTemplate(ctx context.Context, templateID string) (*model.ProjectTemplate, error) {
	var template model.ProjectTemplate
	if err := s.db.WithContext(ctx).Where("id = ? AND status = ?", templateID, "active").First(&template).Error; err != nil {
		return nil, fmt.Errorf("template not found: %w", err)
	}
	return &template, nil
}

func (s *ScaffoldService) GetInteractiveQuestions(ctx context.Context, templateID string) ([]model.InteractiveQuestion, error) {
	template, err := s.GetTemplate(ctx, templateID)
	if err != nil {
		return nil, err
	}

	var questions []model.InteractiveQuestion
	for _, param := range template.Parameters {
		q := model.InteractiveQuestion{
			Question:  param.Description,
			Parameter: param.Name,
			Options:   param.Options,
			Category:  param.Category,
		}
		if param.Default != nil {
			q.Default = fmt.Sprintf("%v", param.Default)
		}
		questions = append(questions, q)
	}

	return questions, nil
}

func (s *ScaffoldService) GenerateProject(ctx context.Context, req *model.GenerateProjectRequest) (*model.GeneratedProject, error) {
	start := time.Now()
	s.metrics.IncInFlight()
	defer s.metrics.DecInFlight()

	defer func() {
		s.metrics.ObserveTaskDuration("scaffold", "generate_project", "success", time.Since(start))
	}()

	template, err := s.GetTemplate(ctx, req.TemplateID)
	if err != nil {
		s.metrics.ObserveError("scaffold", "template_not_found")
		return nil, err
	}

	if err := s.validateParameters(template, req.Parameters); err != nil {
		s.metrics.ObserveError("scaffold", "validation_error")
		return nil, err
	}

	project := &model.GeneratedProject{
		ID:          uuid.New().String(),
		TemplateID:  req.TemplateID,
		ProjectName: req.ProjectName,
		Description: req.Description,
		Parameters:  req.Parameters,
		OutputPath:  req.OutputPath,
		GeneratedBy: "system",
		Status:      "generating",
		GeneratedAt: time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(project).Error; err != nil {
		s.metrics.ObserveError("scaffold", "db_error")
		return nil, fmt.Errorf("failed to create generated project: %w", err)
	}

	if err := s.generateProjectFiles(template, project); err != nil {
		project.Status = "failed"
		errMsg := err.Error()
		project.ErrorMessage = &errMsg
		_ = s.db.WithContext(ctx).Save(project).Error
		return nil, err
	}

	project.Status = "completed"
	if err := s.db.WithContext(ctx).Save(project).Error; err != nil {
		s.logger.Errorw("Failed to update project status", "error", err)
	}

	return project, nil
}

func (s *ScaffoldService) validateParameters(template *model.ProjectTemplate, params map[string]interface{}) error {
	for _, tp := range template.Parameters {
		if tp.Required {
			if _, exists := params[tp.Name]; !exists {
				return fmt.Errorf("required parameter '%s' is missing", tp.Name)
			}
		}
	}
	return nil
}

func (s *ScaffoldService) generateProjectFiles(template *model.ProjectTemplate, project *model.GeneratedProject) error {
	s.logger.Infow("Generating project files", "template", template.Name, "project", project.ProjectName)
	return nil
}

func (s *ScaffoldService) BatchGenerateProjects(ctx context.Context, req *model.BatchGenerateRequest) (*model.BatchGenerateResult, error) {
	start := time.Now()
	s.metrics.IncInFlight()
	defer s.metrics.DecInFlight()

	defer func() {
		s.metrics.ObserveTaskDuration("scaffold", "batch_generate", "success", time.Since(start))
	}()

	batchID := uuid.New().String()
	result := &model.BatchGenerateResult{
		BatchID: batchID,
		Total:   len(req.Projects),
	}

	progress := &model.BatchProgress{
		BatchID:   batchID,
		Total:     len(req.Projects),
		Status:    "in_progress",
		ElapsedMs: 0,
	}
	s.batchProgress.Store(batchID, progress)
	s.batchStatus.Store(batchID, &model.BatchStatus{
		BatchID:   batchID,
		Status:    "in_progress",
		CreatedAt: time.Now(),
	})

	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 10)
	startTime := time.Now()

	for _, projReq := range req.Projects {
		wg.Add(1)
		sem <- struct{}{}

		go func(req model.GenerateProjectRequest) {
			defer wg.Done()
			defer func() { <-sem }()

			item := model.GenerateResultItem{
				ProjectName: req.ProjectName,
			}

			project, err := s.GenerateProject(ctx, &req)
			if err != nil {
				item.Status = "failed"
				item.Message = err.Error()
				mu.Lock()
				result.Failed++
				progress.Failed++
				progress.Completed++
				result.Results = append(result.Results, item)
				progress.Progress = float64(progress.Completed) / float64(progress.Total) * 100
				mu.Unlock()
				return
			}

			item.Status = "success"
			item.ProjectID = project.ID
			mu.Lock()
			result.Successful++
			progress.Successful++
			progress.Completed++
			result.Results = append(result.Results, item)
			progress.Progress = float64(progress.Completed) / float64(progress.Total) * 100
			mu.Unlock()
		}(projReq)
	}

	wg.Wait()

	progress.Status = "completed"
	progress.ElapsedMs = time.Since(startTime).Milliseconds()
	s.batchProgress.Store(batchID, progress)

	if bs, ok := s.batchStatus.Load(batchID); ok {
		batchStatus := bs.(*model.BatchStatus)
		batchStatus.Status = "completed"
		now := time.Now()
		batchStatus.CompletedAt = &now
		batchStatus.DurationMs = time.Since(startTime).Milliseconds()
	}

	return result, nil
}

func (s *ScaffoldService) CreateTemplate(ctx context.Context, template *model.ProjectTemplate) (*model.ProjectTemplate, error) {
	template.ID = uuid.New().String()
	template.CreatedAt = time.Now()
	template.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(template).Error; err != nil {
		return nil, err
	}
	return template, nil
}

func (s *ScaffoldService) GetGeneratedProject(ctx context.Context, projectID string) (*model.GeneratedProject, error) {
	var project model.GeneratedProject
	if err := s.db.WithContext(ctx).Where("id = ?", projectID).First(&project).Error; err != nil {
		return nil, fmt.Errorf("project not found: %w", err)
	}
	return &project, nil
}

func (s *ScaffoldService) ListGeneratedProjects(ctx context.Context, templateID string, status string, page, pageSize int) ([]model.GeneratedProject, int64, error) {
	var projects []model.GeneratedProject
	var total int64

	query := s.db.WithContext(ctx).Model(&model.GeneratedProject{})

	if templateID != "" {
		query = query.Where("template_id = ?", templateID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&projects).Error; err != nil {
		return nil, 0, err
	}

	return projects, total, nil
}

func (s *ScaffoldService) DeleteTemplate(ctx context.Context, templateID string) error {
	result := s.db.WithContext(ctx).
		Model(&model.ProjectTemplate{}).
		Where("id = ?", templateID).
		Update("status", "deprecated")

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("template not found")
	}
	return nil
}

func (s *ScaffoldService) ValidateName(name string) error {
	if strings.TrimSpace(name) == "" {
		return fmt.Errorf("project name cannot be empty")
	}
	if len(name) > 100 {
		return fmt.Errorf("project name too long (max 100 characters)")
	}
	return nil
}

// ===== 批量操作增强方法

func (s *ScaffoldService) GetBatchProgress(batchID string) (*model.BatchProgress, error) {
	val, ok := s.batchProgress.Load(batchID)
	if !ok {
		return nil, fmt.Errorf("batch not found: %s", batchID)
	}
	progress := val.(*model.BatchProgress)
	return progress, nil
}

func (s *ScaffoldService) GetBatchStatus(batchID string) (*model.BatchStatus, error) {
	val, ok := s.batchStatus.Load(batchID)
	if !ok {
		return nil, fmt.Errorf("batch not found: %s", batchID)
	}
	status := val.(*model.BatchStatus)
	return status, nil
}

func (s *ScaffoldService) BatchGenerateWithTimeout(ctx context.Context, req *model.BatchGenerateTimeoutRequest) (*model.BatchGenerateTimeoutResult, error) {
	if req.TimeoutSec <= 0 {
		req.TimeoutSec = 60
	}
	if req.MaxConcurrent <= 0 {
		req.MaxConcurrent = 10
	}

	timeout := time.Duration(req.TimeoutSec) * time.Second
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	batchID := uuid.New().String()
	result := &model.BatchGenerateTimeoutResult{
		BatchID: batchID,
		Total:   len(req.Projects),
	}

	sem := make(chan struct{}, req.MaxConcurrent)
	var mu sync.Mutex
	var wg sync.WaitGroup

	for _, projReq := range req.Projects {
		wg.Add(1)
		select {
		case sem <- struct{}{}:
		case <-ctx.Done():
			wg.Done()
			result.TimedOut++
			result.TimedOutItems = append(result.TimedOutItems, projReq.ProjectName)
			continue
		}

		go func(req model.GenerateProjectRequest) {
			defer wg.Done()
			defer func() { <-sem }()

			done := make(chan struct{})
			var project *model.GeneratedProject
			var err error

			go func() {
				project, err = s.GenerateProject(ctx, &req)
				close(done)
			}()

			select {
			case <-done:
				item := model.GenerateResultItem{
					ProjectName: req.ProjectName,
				}
				if err != nil {
					item.Status = "failed"
					item.Message = err.Error()
				} else {
					item.Status = "success"
					item.ProjectID = project.ID
				}
				mu.Lock()
				result.Completed++
				result.Results = append(result.Results, item)
				mu.Unlock()
			case <-ctx.Done():
				mu.Lock()
				result.TimedOut++
				result.TimedOutItems = append(result.TimedOutItems, req.ProjectName)
				result.Results = append(result.Results, model.GenerateResultItem{
					ProjectName: req.ProjectName,
					Status:      "timeout",
					Message:     "operation timed out",
				})
				mu.Unlock()
			}
		}(projReq)
	}

	wg.Wait()

	return result, nil
}

func (s *ScaffoldService) CoalesceAndGenerate(ctx context.Context, req *model.CoalescedGenerateRequest) (*model.BatchGenerateResult, error) {
	if len(req.Requests) == 0 {
		return nil, fmt.Errorf("no requests to coalesce")
	}

	merged := &model.BatchGenerateRequest{
		Projects: req.Requests,
	}

	return s.BatchGenerateProjects(ctx, merged)
}
