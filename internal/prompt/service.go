package prompt

import (
	"context"
	"errors"
	"fmt"
	"math"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	appErr "session133/pkg/errors"
	"session133/pkg/utils"
)

type PromptService struct {
	db     *gorm.DB
	logger *zap.Logger
}

func NewPromptService(db *gorm.DB, logger *zap.Logger) *PromptService {
	return &PromptService{
		db:     db,
		logger: logger,
	}
}

func (s *PromptService) CreatePrompt(ctx context.Context, req *CreatePromptRequest, userID string) (*Prompt, error) {
	existing := &Prompt{}
	err := s.db.Where("name = ? AND namespace = ? AND version = ?", req.Name, req.Namespace, req.Version).First(existing).Error
	if err == nil {
		return nil, appErr.Conflict(fmt.Sprintf("Prompt %s 版本 %s 已存在", req.Name, req.Version))
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, appErr.Internal(err.Error())
	}

	now := time.Now()
	prompt := &Prompt{
		ID:          utils.GenerateID("prompt"),
		Name:        req.Name,
		Namespace:   req.Namespace,
		Description: req.Description,
		Content:     req.Content,
		Version:     req.Version,
		Status:      PromptStatusDraft,
		ModelID:     req.ModelID,
		Tags:        req.Tags,
		Variables:   req.Variables,
		Metadata:    req.Metadata,
		CreatedBy:   userID,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	if err := s.db.WithContext(ctx).Create(prompt).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}

	return prompt, nil
}

func (s *PromptService) GetPrompt(ctx context.Context, promptID string) (*Prompt, error) {
	prompt := &Prompt{}
	if err := s.db.WithContext(ctx).Where("id = ?", promptID).First(prompt).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, appErr.NotFound("Prompt")
		}
		return nil, appErr.Internal(err.Error())
	}
	return prompt, nil
}

func (s *PromptService) ListPrompts(ctx context.Context, namespace string, page, pageSize int) ([]Prompt, int64, error) {
	var prompts []Prompt
	var total int64

	query := s.db.WithContext(ctx).Model(&Prompt{})
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&prompts).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	return prompts, total, nil
}

func (s *PromptService) UpdatePromptStatus(ctx context.Context, promptID string, status PromptStatus) (*Prompt, error) {
	prompt, err := s.GetPrompt(ctx, promptID)
	if err != nil {
		return nil, err
	}

	now := time.Now()
	if err := s.db.WithContext(ctx).Model(prompt).Updates(map[string]interface{}{
		"status":     status,
		"updated_at": now,
	}).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}

	prompt.Status = status
	prompt.UpdatedAt = now
	return prompt, nil
}

func (s *PromptService) CreateNewVersion(ctx context.Context, promptID string, newContent string, newVersion string, userID string) (*Prompt, error) {
	original, err := s.GetPrompt(ctx, promptID)
	if err != nil {
		return nil, err
	}

	now := time.Now()
	newPrompt := &Prompt{
		ID:          utils.GenerateID("prompt"),
		Name:        original.Name,
		Namespace:   original.Namespace,
		Description: original.Description,
		Content:     newContent,
		Version:     newVersion,
		Status:      PromptStatusDraft,
		ModelID:     original.ModelID,
		Tags:        original.Tags,
		Variables:   original.Variables,
		Metadata:    original.Metadata,
		CreatedBy:   userID,
		ParentID:    original.ID,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	if err := s.db.WithContext(ctx).Create(newPrompt).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}

	return newPrompt, nil
}

func (s *PromptService) CreateExperiment(ctx context.Context, req *CreateExperimentRequest, userID string) (*ABExperiment, error) {
	allPromptIDs := append([]string{req.ControlPromptID}, req.VariantPromptIDs...)
	for _, pid := range allPromptIDs {
		if _, err := s.GetPrompt(ctx, pid); err != nil {
			return nil, appErr.NotFound(fmt.Sprintf("Prompt %s", pid))
		}
	}

	trafficSplit := req.TrafficSplit
	if trafficSplit == nil {
		trafficSplit = make(map[string]int)
		equalShare := 100 / len(allPromptIDs)
		for _, pid := range allPromptIDs {
			trafficSplit[pid] = equalShare
		}
	}

	now := time.Now()
	experiment := &ABExperiment{
		ID:               utils.GenerateID("exp"),
		Name:             req.Name,
		Namespace:        req.Namespace,
		Description:      req.Description,
		Status:           ExperimentStatusDraft,
		ControlPromptID:  req.ControlPromptID,
		VariantPromptIDs: req.VariantPromptIDs,
		TrafficSplit:     trafficSplit,
		TargetMetric:     req.TargetMetric,
		SignificanceLevel: 0.95,
		MinSampleSize:    req.MinSampleSize,
		CreatedBy:        userID,
		CreatedAt:        now,
		UpdatedAt:        now,
	}

	if err := s.db.WithContext(ctx).Create(experiment).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}

	return experiment, nil
}

func (s *PromptService) GetExperiment(ctx context.Context, experimentID string) (*ABExperiment, error) {
	experiment := &ABExperiment{}
	if err := s.db.WithContext(ctx).Where("id = ?", experimentID).First(experiment).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, appErr.NotFound("实验")
		}
		return nil, appErr.Internal(err.Error())
	}
	return experiment, nil
}

func (s *PromptService) ListExperiments(ctx context.Context, namespace string, page, pageSize int) ([]ABExperiment, int64, error) {
	var experiments []ABExperiment
	var total int64

	query := s.db.WithContext(ctx).Model(&ABExperiment{})
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&experiments).Error; err != nil {
		return nil, 0, appErr.Internal(err.Error())
	}

	return experiments, total, nil
}

func (s *PromptService) UpdateExperimentStatus(ctx context.Context, experimentID string, status ExperimentStatus) (*ABExperiment, error) {
	experiment, err := s.GetExperiment(ctx, experimentID)
	if err != nil {
		return nil, err
	}

	now := time.Now()
	updates := map[string]interface{}{
		"status":     status,
		"updated_at": now,
	}

	if status == ExperimentStatusRunning && experiment.StartTime == nil {
		updates["start_time"] = now
	}
	if status == ExperimentStatusCompleted && experiment.EndTime == nil {
		updates["end_time"] = now
	}

	if err := s.db.WithContext(ctx).Model(experiment).Updates(updates).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}

	return s.GetExperiment(ctx, experimentID)
}

func (s *PromptService) RecordExperimentResult(ctx context.Context, experimentID string, promptID string, success bool, latency float64, metrics map[string]float64) error {
	experiment, err := s.GetExperiment(ctx, experimentID)
	if err != nil {
		return err
	}

	var result ExperimentResult
	err = s.db.WithContext(ctx).Where("experiment_id = ? AND prompt_id = ?", experimentID, promptID).First(&result).Error
	if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
		return appErr.Internal(err.Error())
	}

	if errors.Is(err, gorm.ErrRecordNotFound) {
		result = ExperimentResult{
			ID:            utils.GenerateID("res"),
			ExperimentID:  experimentID,
			PromptID:      promptID,
			TotalRequests: 0,
			SuccessCount:  0,
			Metrics:       make(map[string]float64),
			CreatedAt:     time.Now(),
		}
	}

	result.TotalRequests++
	if success {
		result.SuccessCount++
	}
	result.AvgLatency = (result.AvgLatency*float64(result.TotalRequests-1) + latency) / float64(result.TotalRequests)

	if metrics != nil {
		for k, v := range metrics {
			result.Metrics[k] = (result.Metrics[k]*float64(result.TotalRequests-1) + v) / float64(result.TotalRequests)
		}
	}

	if err := s.db.WithContext(ctx).Save(&result).Error; err != nil {
		return appErr.Internal(err.Error())
	}

	if experiment.Status == ExperimentStatusRunning {
		s.checkExperimentCompletion(ctx, experiment)
	}

	return nil
}

func (s *PromptService) checkExperimentCompletion(ctx context.Context, experiment *ABExperiment) {
	var results []ExperimentResult
	if err := s.db.Where("experiment_id = ?", experiment.ID).Find(&results).Error; err != nil {
		return
	}

	totalRequests := int64(0)
	for _, r := range results {
		totalRequests += r.TotalRequests
	}

	if totalRequests >= int64(experiment.MinSampleSize) {
		s.db.Model(experiment).Update("status", ExperimentStatusCompleted)
		s.calculateWinner(ctx, experiment.ID)
	}
}

func (s *PromptService) calculateWinner(ctx context.Context, experimentID string) {
	var results []ExperimentResult
	if err := s.db.Where("experiment_id = ?", experimentID).Find(&results).Error; err != nil {
		return
	}

	if len(results) < 2 {
		return
	}

	var bestResult *ExperimentResult
	bestScore := -1.0

	for i := range results {
		r := &results[i]
		successRate := float64(r.SuccessCount) / float64(r.TotalRequests)
		score := successRate

		if score > bestScore {
			bestScore = score
			bestResult = r
		}

		zScore := s.calculateZScore(r, &results[0])
		r.ConfidenceScore = s.normalCDF(zScore)
		r.StatSignificant = r.ConfidenceScore >= 0.95
		s.db.Save(r)
	}

	if bestResult != nil {
		bestResult.IsWinner = true
		s.db.Save(bestResult)
	}
}

func (s *PromptService) calculateZScore(a, b *ExperimentResult) float64 {
	pA := float64(a.SuccessCount) / float64(a.TotalRequests)
	pB := float64(b.SuccessCount) / float64(b.TotalRequests)
	pPooled := (pA*float64(a.TotalRequests) + pB*float64(b.TotalRequests)) / float64(a.TotalRequests+b.TotalRequests)
	se := math.Sqrt(pPooled * (1 - pPooled) * (1.0/float64(a.TotalRequests) + 1.0/float64(b.TotalRequests)))
	if se == 0 {
		return 0
	}
	return (pA - pB) / se
}

func (s *PromptService) normalCDF(x float64) float64 {
	return 0.5 * (1 + math.Erf(x/math.Sqrt2))
}

func (s *PromptService) GetExperimentResults(ctx context.Context, experimentID string) ([]ExperimentResult, error) {
	var results []ExperimentResult
	if err := s.db.WithContext(ctx).Where("experiment_id = ?", experimentID).Find(&results).Error; err != nil {
		return nil, appErr.Internal(err.Error())
	}
	return results, nil
}

func (s *PromptService) GetPromptByID(ctx context.Context, promptID string) (string, error) {
	prompt, err := s.GetPrompt(ctx, promptID)
	if err != nil {
		return "", err
	}
	return prompt.Content, nil
}
