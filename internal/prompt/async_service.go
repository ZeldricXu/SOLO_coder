package prompt

import (
	"context"
	"fmt"
	"math"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type AsyncPromptService struct {
	db          *gorm.DB
	logger      *zap.Logger
	taskManager *AsyncTaskManager
}

func NewAsyncPromptService(db *gorm.DB, logger *zap.Logger) *AsyncPromptService {
	service := &AsyncPromptService{
		db:     db,
		logger: logger,
	}

	taskManager := NewAsyncTaskManager(5, logger)
	taskManager.SetProcessor(service.processTask)
	taskManager.RegisterHandler(service)

	service.taskManager = taskManager
	return service
}

func (s *AsyncPromptService) Start(ctx context.Context) {
	s.taskManager.Start(ctx)
	s.logger.Info("Async prompt service started")
}

func (s *AsyncPromptService) Stop() {
	s.taskManager.Stop()
	s.logger.Info("Async prompt service stopped")
}

func (s *AsyncPromptService) GetTaskManager() *AsyncTaskManager {
	return s.taskManager
}

func (s *AsyncPromptService) SubmitABTestAnalysis(abTestID string, callbackURL string) (*AsyncTask, error) {
	payload := map[string]interface{}{
		"ab_test_id": abTestID,
	}

	return s.taskManager.SubmitTask(TaskTypeABTestAnalysis, payload, 3, callbackURL)
}

func (s *AsyncPromptService) SubmitPromptEvaluation(promptID string, evalCases []map[string]interface{}, callbackURL string) (*AsyncTask, error) {
	payload := map[string]interface{}{
		"prompt_id":  promptID,
		"eval_cases": evalCases,
	}

	return s.taskManager.SubmitTask(TaskTypePromptEvaluation, payload, 2, callbackURL)
}

func (s *AsyncPromptService) SubmitVersionCleanup(promptID string, maxVersions int, callbackURL string) (*AsyncTask, error) {
	payload := map[string]interface{}{
		"prompt_id":    promptID,
		"max_versions": maxVersions,
	}

	return s.taskManager.SubmitTask(TaskTypeVersionCleanup, payload, 1, callbackURL)
}

func (s *AsyncPromptService) SubmitMetricAggregation(startDate, endDate time.Time, callbackURL string) (*AsyncTask, error) {
	payload := map[string]interface{}{
		"start_date": startDate,
		"end_date":   endDate,
	}

	return s.taskManager.SubmitTask(TaskTypeMetricAggregation, payload, 3, callbackURL)
}

func (s *AsyncPromptService) SubmitReportGeneration(reportType string, params map[string]interface{}, callbackURL string) (*AsyncTask, error) {
	payload := map[string]interface{}{
		"report_type": reportType,
		"params":      params,
	}

	return s.taskManager.SubmitTask(TaskTypeReportGeneration, payload, 2, callbackURL)
}

func (s *AsyncPromptService) processTask(ctx context.Context, task *AsyncTask) error {
	switch task.Type {
	case TaskTypeABTestAnalysis:
		return s.processABTestAnalysis(ctx, task)
	case TaskTypePromptEvaluation:
		return s.processPromptEvaluation(ctx, task)
	case TaskTypeVersionCleanup:
		return s.processVersionCleanup(ctx, task)
	case TaskTypeMetricAggregation:
		return s.processMetricAggregation(ctx, task)
	case TaskTypeReportGeneration:
		return s.processReportGeneration(ctx, task)
	default:
		return fmt.Errorf("unknown task type: %s", task.Type)
	}
}

func (s *AsyncPromptService) processABTestAnalysis(ctx context.Context, task *AsyncTask) error {
	abTestID, ok := task.Payload["ab_test_id"].(string)
	if !ok {
		return fmt.Errorf("invalid ab_test_id in payload")
	}

	abTest := &ABTest{}
	if err := s.db.Where("id = ?", abTestID).First(abTest).Error; err != nil {
		return fmt.Errorf("ab test not found: %w", err)
	}

	var controlResults []ABTestResult
	var testResults []ABTestResult

	if err := s.db.Where("ab_test_id = ? AND group_type = ?", abTestID, "control").Find(&controlResults).Error; err != nil {
		return fmt.Errorf("failed to get control results: %w", err)
	}

	if err := s.db.Where("ab_test_id = ? AND group_type = ?", abTestID, "test").Find(&testResults).Error; err != nil {
		return fmt.Errorf("failed to get test results: %w", err)
	}

	controlSuccess := 0
	for _, r := range controlResults {
		if r.Success {
			controlSuccess++
		}
	}

	testSuccess := 0
	for _, r := range testResults {
		if r.Success {
			testSuccess++
		}
	}

	controlRate := float64(controlSuccess) / float64(len(controlResults))
	testRate := float64(testSuccess) / float64(len(testResults))

	controlSE := math.Sqrt(controlRate * (1 - controlRate) / float64(len(controlResults)))
	testSE := math.Sqrt(testRate * (1 - testRate) / float64(len(testResults)))
	seDiff := math.Sqrt(controlSE*controlSE + testSE*testSE)

	zScore := (testRate - controlRate) / seDiff
	pValue := 2 * (1 - normalCDF(math.Abs(zScore)))

	improvement := ((testRate - controlRate) / controlRate) * 100

	isSignificant := pValue < 0.05
	winner := "tie"
	if isSignificant && testRate > controlRate {
		winner = "test"
	} else if isSignificant && controlRate > testRate {
		winner = "control"
	}

	task.Result = map[string]interface{}{
		"ab_test_id":          abTestID,
		"control_samples":     len(controlResults),
		"test_samples":        len(testResults),
		"control_success":     controlSuccess,
		"test_success":        testSuccess,
		"control_success_rate": controlRate,
		"test_success_rate":   testRate,
		"z_score":             zScore,
		"p_value":             pValue,
		"improvement_percent": improvement,
		"is_significant":      isSignificant,
		"winner":              winner,
		"confidence_level":    0.95,
	}

	return nil
}

func (s *AsyncPromptService) processPromptEvaluation(ctx context.Context, task *AsyncTask) error {
	promptID, ok := task.Payload["prompt_id"].(string)
	if !ok {
		return fmt.Errorf("invalid prompt_id in payload")
	}

	evalCases, ok := task.Payload["eval_cases"].([]map[string]interface{})
	if !ok {
		return fmt.Errorf("invalid eval_cases in payload")
	}

	prompt := &Prompt{}
	if err := s.db.Where("id = ?", promptID).First(prompt).Error; err != nil {
		return fmt.Errorf("prompt not found: %w", err)
	}

	results := make([]map[string]interface{}, 0, len(evalCases))
	totalScore := 0.0

	for i, evalCase := range evalCases {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}

		input, _ := evalCase["input"].(string)
		expectedOutput, _ := evalCase["expected_output"].(string)

		score := simulateEvaluation(prompt.Content, input, expectedOutput)
		totalScore += score

		results = append(results, map[string]interface{}{
			"case_index":      i,
			"input":           input,
			"expected_output": expectedOutput,
			"score":           score,
			"passed":          score >= 0.7,
		})

		time.Sleep(100 * time.Millisecond)
	}

	avgScore := totalScore / float64(len(evalCases))
	passedCount := 0
	for _, r := range results {
		if r["passed"].(bool) {
			passedCount++
		}
	}

	task.Result = map[string]interface{}{
		"prompt_id":       promptID,
		"total_cases":     len(evalCases),
		"passed_cases":    passedCount,
		"average_score":   avgScore,
		"pass_rate":       float64(passedCount) / float64(len(evalCases)),
		"detailed_results": results,
	}

	return nil
}

func (s *AsyncPromptService) processVersionCleanup(ctx context.Context, task *AsyncTask) error {
	promptID, ok := task.Payload["prompt_id"].(string)
	if !ok {
		return fmt.Errorf("invalid prompt_id in payload")
	}

	maxVersions, ok := task.Payload["max_versions"].(int)
	if !ok {
		maxVersions = 10
	}

	var versions []PromptVersion
	err := s.db.Where("prompt_id = ?", promptID).
		Order("version_number DESC").
		Find(&versions).Error
	if err != nil {
		return fmt.Errorf("failed to get versions: %w", err)
	}

	if len(versions) <= maxVersions {
		task.Result = map[string]interface{}{
			"prompt_id":       promptID,
			"total_versions":  len(versions),
			"deleted_versions": 0,
			"message":         "No versions need to be deleted",
		}
		return nil
	}

	versionsToDelete := versions[maxVersions:]
	deletedIDs := make([]string, 0, len(versionsToDelete))

	for _, v := range versionsToDelete {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}

		if err := s.db.Delete(&v).Error; err != nil {
			s.logger.Warn("Failed to delete version",
				zap.String("version_id", v.ID),
				zap.Error(err),
			)
			continue
		}
		deletedIDs = append(deletedIDs, v.ID)
	}

	task.Result = map[string]interface{}{
		"prompt_id":         promptID,
		"total_versions":    len(versions),
		"remaining_versions": maxVersions,
		"deleted_versions":  len(deletedIDs),
		"deleted_ids":       deletedIDs,
	}

	return nil
}

func (s *AsyncPromptService) processMetricAggregation(ctx context.Context, task *AsyncTask) error {
	startDate, _ := task.Payload["start_date"].(time.Time)
	endDate, _ := task.Payload["end_date"].(time.Time)

	var totalPrompts int64
	var totalABTests int64
	var totalVersions int64

	s.db.Model(&Prompt{}).Where("created_at BETWEEN ? AND ?", startDate, endDate).Count(&totalPrompts)
	s.db.Model(&ABTest{}).Where("created_at BETWEEN ? AND ?", startDate, endDate).Count(&totalABTests)
	s.db.Model(&PromptVersion{}).Where("created_at BETWEEN ? AND ?", startDate, endDate).Count(&totalVersions)

	var runningABTests int64
	s.db.Model(&ABTest{}).Where("status = ?", "running").Count(&runningABTests)

	task.Result = map[string]interface{}{
		"start_date":        startDate,
		"end_date":          endDate,
		"total_prompts":     totalPrompts,
		"total_ab_tests":    totalABTests,
		"total_versions":    totalVersions,
		"running_ab_tests":  runningABTests,
		"aggregation_time":  time.Now(),
	}

	return nil
}

func (s *AsyncPromptService) processReportGeneration(ctx context.Context, task *AsyncTask) error {
	reportType, _ := task.Payload["report_type"].(string)
	params, _ := task.Payload["params"].(map[string]interface{})

	select {
	case <-ctx.Done():
		return ctx.Err()
	default:
	}

	time.Sleep(500 * time.Millisecond)

	task.Result = map[string]interface{}{
		"report_id":   fmt.Sprintf("report_%s_%d", reportType, time.Now().Unix()),
		"report_type": reportType,
		"params":      params,
		"generated_at": time.Now(),
		"format":      "pdf",
		"download_url": fmt.Sprintf("/api/reports/%s/download", reportType),
	}

	return nil
}

func (s *AsyncPromptService) OnTaskCreated(task *AsyncTask) {
	s.logger.Debug("Task event: created",
		zap.String("task_id", task.ID),
		zap.String("type", string(task.Type)),
	)
}

func (s *AsyncPromptService) OnTaskStarted(task *AsyncTask) {
	s.logger.Debug("Task event: started",
		zap.String("task_id", task.ID),
	)
}

func (s *AsyncPromptService) OnTaskCompleted(task *AsyncTask) {
	s.logger.Info("Task event: completed",
		zap.String("task_id", task.ID),
		zap.Any("result", task.Result),
	)
}

func (s *AsyncPromptService) OnTaskFailed(task *AsyncTask) {
	s.logger.Error("Task event: failed",
		zap.String("task_id", task.ID),
		zap.String("error", task.Error),
	)
}

func normalCDF(x float64) float64 {
	return 0.5 * (1 + math.Erf(x/math.Sqrt(2)))
}

func simulateEvaluation(promptContent, input, expectedOutput string) float64 {
	return 0.7 + 0.3*math.Abs(math.Sin(float64(len(input)+len(expectedOutput))))
}
