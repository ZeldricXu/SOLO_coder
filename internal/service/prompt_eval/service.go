package prompt_eval

import (
	"errors"
	"fmt"
	"math"
	"math/rand"

	"gorm.io/gorm"

	"llmgateway/internal/domain/entity"
	"llmgateway/internal/infrastructure/database"
	"llmgateway/internal/infrastructure/logger"
	"llmgateway/pkg/utils"
)

type Service struct {
	db *gorm.DB
}

func NewService() *Service {
	return &Service{
		db: database.DB(),
	}
}

type CreatePromptRequest struct {
	Name        string                 `json:"name" binding:"required"`
	Description string                 `json:"description"`
	Content     string                 `json:"content" binding:"required"`
	ModelID     string                 `json:"model_id"`
	Parameters  map[string]interface{} `json:"parameters"`
	Tags        []string               `json:"tags"`
	CreatedBy   string                 `json:"-"`
}

func (s *Service) CreatePrompt(req *CreatePromptRequest) (*entity.Prompt, error) {
	now := utils.Now()
	prompt := &entity.Prompt{
		BaseEntity: entity.BaseEntity{
			ID:        utils.GenerateID("prompt"),
			Type:      "prompt",
			Status:    string(entity.PromptStatusDraft),
			CreatedAt: now,
			UpdatedAt: now,
		},
		Name:        req.Name,
		Description: req.Description,
		Content:     req.Content,
		ModelID:     req.ModelID,
		Parameters:  req.Parameters,
		Tags:        req.Tags,
		CreatedBy:   req.CreatedBy,
	}

	if err := s.db.Create(prompt).Error; err != nil {
		return nil, fmt.Errorf("failed to create prompt: %w", err)
	}

	version := &entity.PromptVersion{
		ID:         utils.GenerateID("pv"),
		PromptID:   prompt.ID,
		Version:    "v1",
		Content:    prompt.Content,
		Parameters: prompt.Parameters,
		Status:     string(entity.PromptStatusDraft),
		CreatedBy:  req.CreatedBy,
		CommitMsg:  "Initial version",
		CreatedAt:  now,
	}
	if err := s.db.Create(version).Error; err != nil {
		return nil, fmt.Errorf("failed to create initial version: %w", err)
	}

	logger.Info("prompt created", "prompt_id", prompt.ID, "name", prompt.Name)
	return prompt, nil
}

func (s *Service) GetPrompt(id string) (*entity.Prompt, error) {
	var prompt entity.Prompt
	if err := s.db.Where("id = ?", id).First(&prompt).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("prompt not found")
		}
		return nil, fmt.Errorf("failed to get prompt: %w", err)
	}
	return &prompt, nil
}

func (s *Service) ListPrompts(page, pageSize int, createdBy, status string) ([]entity.Prompt, int64, error) {
	var prompts []entity.Prompt
	var total int64

	query := s.db.Model(&entity.Prompt{})

	if createdBy != "" {
		query = query.Where("created_by = ?", createdBy)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to count prompts: %w", err)
	}

	offset := (page - 1) * pageSize
	if err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&prompts).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to list prompts: %w", err)
	}

	return prompts, total, nil
}

type CreateVersionRequest struct {
	PromptID   string                 `json:"prompt_id" binding:"required"`
	Content    string                 `json:"content" binding:"required"`
	Parameters map[string]interface{} `json:"parameters"`
	CommitMsg  string                 `json:"commit_msg"`
	CreatedBy  string                 `json:"-"`
}

func (s *Service) CreateVersion(req *CreateVersionRequest) (*entity.PromptVersion, error) {
	var maxVersion int
	s.db.Model(&entity.PromptVersion{}).Where("prompt_id = ?", req.PromptID).
		Select("COALESCE(MAX(CAST(SUBSTRING(version, 2) AS INTEGER)), 0)").Scan(&maxVersion)

	now := utils.Now()
	version := &entity.PromptVersion{
		ID:         utils.GenerateID("pv"),
		PromptID:   req.PromptID,
		Version:    fmt.Sprintf("v%d", maxVersion+1),
		Content:    req.Content,
		Parameters: req.Parameters,
		Status:     string(entity.PromptStatusDraft),
		CreatedBy:  req.CreatedBy,
		CommitMsg:  req.CommitMsg,
		CreatedAt:  now,
	}

	if err := s.db.Create(version).Error; err != nil {
		return nil, fmt.Errorf("failed to create version: %w", err)
	}

	logger.Info("prompt version created", "version_id", version.ID, "prompt_id", req.PromptID, "version", version.Version)
	return version, nil
}

func (s *Service) ListVersions(promptID string, page, pageSize int) ([]entity.PromptVersion, int64, error) {
	var versions []entity.PromptVersion
	var total int64

	query := s.db.Model(&entity.PromptVersion{}).Where("prompt_id = ?", promptID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to count versions: %w", err)
	}

	offset := (page - 1) * pageSize
	if err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&versions).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to list versions: %w", err)
	}

	return versions, total, nil
}

type CreateExperimentRequest struct {
	Name         string               `json:"name" binding:"required"`
	Description  string               `json:"description"`
	PromptID     string               `json:"prompt_id" binding:"required"`
	ControlGroup entity.ExperimentGroup `json:"control_group" binding:"required"`
	TestGroups   []entity.ExperimentGroup `json:"test_groups"`
	Metrics      []string             `json:"metrics"`
	CreatedBy    string               `json:"-"`
}

func (s *Service) CreateExperiment(req *CreateExperimentRequest) (*entity.ABExperiment, error) {
	totalWeight := req.ControlGroup.TrafficWeight
	for _, g := range req.TestGroups {
		totalWeight += g.TrafficWeight
	}

	trafficSplit := make(map[string]int)
	trafficSplit[req.ControlGroup.VersionID] = req.ControlGroup.TrafficWeight
	for _, g := range req.TestGroups {
		trafficSplit[g.VersionID] = g.TrafficWeight
	}

	now := utils.Now()
	exp := &entity.ABExperiment{
		ID:           utils.GenerateID("exp"),
		Name:         req.Name,
		Description:  req.Description,
		Status:       string(entity.ExpStatusCreated),
		PromptID:     req.PromptID,
		ControlGroup: req.ControlGroup,
		TestGroups:   req.TestGroups,
		TrafficSplit: trafficSplit,
		Metrics:      req.Metrics,
		StartTime:    now,
		CreatedBy:    req.CreatedBy,
		CreatedAt:    now,
		UpdatedAt:    now,
	}

	if err := s.db.Create(exp).Error; err != nil {
		return nil, fmt.Errorf("failed to create experiment: %w", err)
	}

	logger.Info("experiment created", "experiment_id", exp.ID, "name", exp.Name)
	return exp, nil
}

func (s *Service) GetExperiment(id string) (*entity.ABExperiment, error) {
	var exp entity.ABExperiment
	if err := s.db.Where("id = ?", id).First(&exp).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("experiment not found")
		}
		return nil, fmt.Errorf("failed to get experiment: %w", err)
	}
	return &exp, nil
}

func (s *Service) StartExperiment(id string) (*entity.ABExperiment, error) {
	exp, err := s.GetExperiment(id)
	if err != nil {
		return nil, err
	}

	if exp.Status != string(entity.ExpStatusCreated) {
		return nil, fmt.Errorf("experiment in status %s cannot be started", exp.Status)
	}

	exp.Status = string(entity.ExpStatusRunning)
	exp.StartTime = utils.Now()
	exp.UpdatedAt = utils.Now()

	if err := s.db.Save(exp).Error; err != nil {
		return nil, fmt.Errorf("failed to start experiment: %w", err)
	}

	logger.Info("experiment started", "experiment_id", id)
	return exp, nil
}

func (s *Service) GetAssignedVersion(experimentID string, userID string) (string, error) {
	exp, err := s.GetExperiment(experimentID)
	if err != nil {
		return "", err
	}

	if exp.Status != string(entity.ExpStatusRunning) {
		return exp.ControlGroup.VersionID, nil
	}

	rng := rand.New(rand.NewSource(utils.Now().UnixNano()))
	randomValue := rng.Intn(100)

	cumulativeWeight := 0
	for versionID, weight := range exp.TrafficSplit {
		cumulativeWeight += weight
		if randomValue < cumulativeWeight {
			return versionID, nil
		}
	}

	return exp.ControlGroup.VersionID, nil
}

func (s *Service) RecordExperimentResult(experimentID, groupID string, metrics map[string]float64) (*entity.ExperimentResult, error) {
	now := utils.Now()
	result := &entity.ExperimentResult{
		ID:              utils.GenerateID("res"),
		ExperimentID:    experimentID,
		GroupID:         groupID,
		Metrics:         metrics,
		SampleSize:      1,
		StatSignificant: false,
		Confidence:      0.0,
		Timestamp:       now,
		CreatedAt:       now,
	}

	if err := s.db.Create(result).Error; err != nil {
		return nil, fmt.Errorf("failed to record result: %w", err)
	}

	return result, nil
}

func (s *Service) GetExperimentResults(experimentID string) ([]entity.ExperimentResult, error) {
	var results []entity.ExperimentResult
	if err := s.db.Where("experiment_id = ?", experimentID).
		Order("timestamp DESC").Find(&results).Error; err != nil {
		return nil, fmt.Errorf("failed to get results: %w", err)
	}
	return results, nil
}

func (s *Service) CalculateConfidence(control, test []float64) (float64, bool) {
	if len(control) == 0 || len(test) == 0 {
		return 0.0, false
	}

	controlMean := mean(control)
	testMean := mean(test)
	controlVar := variance(control, controlMean)
	testVar := variance(test, testMean)

	se := math.Sqrt(controlVar/float64(len(control)) + testVar/float64(len(test)))
	if se == 0 {
		return 0.0, false
	}

	zScore := (testMean - controlMean) / se
	pValue := 2 * (1 - normalCDF(math.Abs(zScore)))

	confidence := 1 - pValue
	significant := pValue < 0.05

	return confidence, significant
}

func mean(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sum := 0.0
	for _, v := range values {
		sum += v
	}
	return sum / float64(len(values))
}

func variance(values []float64, mean float64) float64 {
	if len(values) <= 1 {
		return 0
	}
	sum := 0.0
	for _, v := range values {
		diff := v - mean
		sum += diff * diff
	}
	return sum / float64(len(values)-1)
}

func normalCDF(x float64) float64 {
	return 0.5 * (1 + math.Erf(x/math.Sqrt2))
}
