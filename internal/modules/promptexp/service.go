package promptexp

import (
	"context"
	"fmt"
	"math"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"notificationplatform/internal/common/database"
	"notificationplatform/internal/common/errors"
	"notificationplatform/internal/common/logger"
	"notificationplatform/internal/common/models"
	"notificationplatform/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

const (
	defaultMaxWorkers     = 10
	metricsBufferSize     = 1024
	variantResultPoolSize = 128
)

type experimentVariantRun struct {
	ID           string
	ExperimentID string
	Status       string
	Progress     float64
	TotalTasks   int
	Completed    int64
	Results      map[string]*variantMetric
	ResultsMu    sync.RWMutex
	StartedAt    time.Time
	CompletedAt  *time.Time
	Error        string
	cancelFunc   context.CancelFunc
}

type variantMetric struct {
	VariantID   string
	PromptID    string
	VersionID   string
	SampleCount int64
	Metrics     map[string]float64
	Rank        int
	IsWinner    bool
}

type testResult struct {
	TestCaseID    string
	VariantID     string
	PromptID      string
	VersionID     string
	Output        string
	LatencyMs     int64
	Error         string
	Success       bool
	Score         float64
	ExecutionTime time.Time
}

type resultProcessor struct {
	run       *experimentVariantRun
	resultCh  chan *testResult
	doneCh    chan struct{}
}

type promptQueryFilters struct {
	taskType string
	status   string
	owner    string
}

type experimentQueryFilters struct {
	status string
	expType string
}

var (
	instance          *Service
	once              sync.Once
	variantResultPool = sync.Pool{
		New: func() interface{} {
			return &variantMetric{
				Metrics: make(map[string]float64, 8),
			}
		},
	}
	testResultPool = sync.Pool{
		New: func() interface{} {
			return &testResult{}
		},
	}
)

type Service struct {
	db           *gorm.DB
	workerPool   chan struct{}
	maxWorkers   int
	activeRuns   map[string]*experimentVariantRun
	activeRunsMu sync.RWMutex
}

func NewService(maxWorkers int) *Service {
	once.Do(func() {
		if maxWorkers <= 0 {
			maxWorkers = defaultMaxWorkers
		}
		instance = &Service{
			db:         database.GetDB(),
			workerPool: make(chan struct{}, maxWorkers),
			maxWorkers: maxWorkers,
			activeRuns: make(map[string]*experimentVariantRun, 32),
		}
	})
	return instance
}

func (s *Service) CreatePrompt(ctx context.Context, name, description, taskType, createdBy string, tags []string) (*models.Prompt, error) {
	log := logger.FromContext(ctx)

	now := time.Now()
	prompt := &models.Prompt{
		ID:          utils.NewID("prompt"),
		Name:        name,
		Description: description,
		TaskType:    taskType,
		Tags:        tags,
		Status:      string(models.PromptStatusDraft),
		CreatedBy:   createdBy,
		Owner:       createdBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	if err := s.createRecord(ctx, prompt, "prompt"); err != nil {
		log.Error("failed to create prompt", zap.Error(err))
		return nil, err
	}

	log.Info("prompt created",
		zap.String("prompt_id", prompt.ID),
		zap.String("name", prompt.Name),
	)
	return prompt, nil
}

func (s *Service) CreateVersion(ctx context.Context, promptID string, version *models.PromptVersion) (*models.PromptVersion, error) {
	log := logger.FromContext(ctx)

	latestVersion, err := s.getLatestVersionNumber(ctx, promptID)
	if err != nil {
		return nil, err
	}

	now := time.Now()
	version.ID = utils.NewID("pver")
	version.PromptID = promptID
	version.Version = latestVersion + 1
	version.Status = string(models.PromptStatusDraft)
	version.CreatedAt = now
	version.UpdatedAt = now

	if err := s.createRecord(ctx, version, "prompt version"); err != nil {
		log.Error("failed to create prompt version", zap.Error(err))
		return nil, err
	}

	log.Info("prompt version created",
		zap.String("prompt_id", promptID),
		zap.Int("version", version.Version),
	)
	return version, nil
}

func (s *Service) GetPrompt(ctx context.Context, id string) (*models.Prompt, error) {
	if s.db == nil {
		return nil, errors.NewInternal("database not available", "")
	}

	var prompt models.Prompt
	if err := s.db.First(&prompt, "id = ?", id).Error; err != nil {
		return nil, s.handleDBError(err, "prompt", id)
	}
	return &prompt, nil
}

func (s *Service) ListPrompts(ctx context.Context, page, pageSize int, filters map[string]interface{}) ([]*models.Prompt, int64, error) {
	if s.db == nil {
		return nil, 0, errors.NewInternal("database not available", "")
	}

	queryFilters := parsePromptFilters(filters)
	query := s.buildPromptQuery(queryFilters)

	total, err := s.countRecords(query)
	if err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	var prompts []models.Prompt
	if err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&prompts).Error; err != nil {
		return nil, 0, errors.NewInternal("database error", err.Error())
	}

	result := make([]*models.Prompt, 0, len(prompts))
	for i := range prompts {
		result = append(result, &prompts[i])
	}

	return result, total, nil
}

func (s *Service) GetVersions(ctx context.Context, promptID string) ([]*models.PromptVersion, error) {
	if s.db == nil {
		return nil, errors.NewInternal("database not available", "")
	}

	var versions []models.PromptVersion
	if err := s.db.Where("prompt_id = ?", promptID).Order("version DESC").Find(&versions).Error; err != nil {
		return nil, errors.NewInternal("database error", err.Error())
	}

	result := make([]*models.PromptVersion, 0, len(versions))
	for i := range versions {
		result = append(result, &versions[i])
	}

	return result, nil
}

func (s *Service) CreateExperiment(ctx context.Context, exp *models.ABExperiment) (*models.ABExperiment, error) {
	log := logger.FromContext(ctx)

	now := time.Now()
	exp.ID = utils.NewID("exp")
	exp.Status = string(models.ExperimentStatusDraft)
	exp.CreatedAt = now
	exp.UpdatedAt = now

	for i := range exp.Variants {
		exp.Variants[i].ID = utils.NewID("var")
	}

	if err := s.createRecord(ctx, exp, "experiment"); err != nil {
		log.Error("failed to create experiment", zap.Error(err))
		return nil, err
	}

	log.Info("experiment created",
		zap.String("experiment_id", exp.ID),
		zap.String("name", exp.Name),
		zap.String("type", exp.Type),
	)
	return exp, nil
}

func (s *Service) StartExperiment(ctx context.Context, experimentID string) (*models.ABExperiment, error) {
	log := logger.FromContext(ctx)

	exp, err := s.GetExperiment(ctx, experimentID)
	if err != nil {
		return nil, err
	}

	now := time.Now()
	exp.Status = string(models.ExperimentStatusRunning)
	exp.StartedAt = &now
	exp.UpdatedAt = now

	if s.db != nil {
		if err := s.db.Save(exp).Error; err != nil {
			log.Error("failed to start experiment", zap.Error(err))
			return nil, errors.NewInternal("failed to start experiment", err.Error())
		}
	}

	log.Info("experiment started", zap.String("experiment_id", experimentID))
	return exp, nil
}

func (s *Service) GetExperiment(ctx context.Context, id string) (*models.ABExperiment, error) {
	if s.db == nil {
		return nil, errors.NewInternal("database not available", "")
	}

	var exp models.ABExperiment
	if err := s.db.First(&exp, "id = ?", id).Error; err != nil {
		return nil, s.handleDBError(err, "experiment", id)
	}
	return &exp, nil
}

func (s *Service) ListExperiments(ctx context.Context, page, pageSize int, filters map[string]interface{}) ([]*models.ABExperiment, int64, error) {
	if s.db == nil {
		return nil, 0, errors.NewInternal("database not available", "")
	}

	queryFilters := parseExperimentFilters(filters)
	query := s.buildExperimentQuery(queryFilters)

	total, err := s.countRecords(query)
	if err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	var exps []models.ABExperiment
	if err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&exps).Error; err != nil {
		return nil, 0, errors.NewInternal("database error", err.Error())
	}

	result := make([]*models.ABExperiment, 0, len(exps))
	for i := range exps {
		result = append(result, &exps[i])
	}

	return result, total, nil
}

func (s *Service) RunBatchTest(ctx context.Context, req *models.BatchTestRequest) (string, error) {
	log := logger.FromContext(ctx)

	exp, err := s.GetExperiment(ctx, req.ExperimentID)
	if err != nil {
		return "", err
	}

	if exp.Status != string(models.ExperimentStatusRunning) {
		return "", errors.NewBadRequest("experiment not running", "experiment must be in running state")
	}

	concurrency := s.normalizeConcurrency(req.Concurrency)
	runID := utils.NewID("run")
	runCtx, cancel := context.WithCancel(ctx)

	totalTasks := len(req.TestCases) * (len(exp.Variants) + 1)
	run := s.createExperimentRun(runID, req.ExperimentID, totalTasks, cancel)

	s.registerActiveRun(runID, run)

	go s.executeParallelRun(runCtx, log, run, exp, req.TestCases, concurrency)

	log.Info("batch test started",
		zap.String("run_id", runID),
		zap.String("experiment_id", req.ExperimentID),
		zap.Int("test_cases", len(req.TestCases)),
		zap.Int("concurrency", concurrency),
		zap.Int("total_tasks", totalTasks),
	)

	return runID, nil
}

func (s *Service) executeParallelRun(ctx context.Context, log *zap.Logger, run *experimentVariantRun, exp *models.ABExperiment, testCases []*models.TestCase, concurrency int) {
	var wg sync.WaitGroup

	allVariants := s.buildAllVariants(exp)
	processor := s.startResultProcessor(run, log)

	semaphore := make(chan struct{}, concurrency)
	defer close(semaphore)

	for _, tc := range testCases {
		for _, variant := range allVariants {
			if s.isCancelled(ctx) {
				log.Info("run cancelled", zap.String("run_id", run.ID))
				wg.Wait()
				close(processor.resultCh)
				<-processor.doneCh
				s.finalizeRun(run, "cancelled by user")
				return
			}

			wg.Add(1)
			select {
			case semaphore <- struct{}{}:
			case <-ctx.Done():
				wg.Done()
				continue
			}

			go s.executeTestTask(ctx, tc, variant, &wg, semaphore, processor.resultCh)
		}
	}

	wg.Wait()
	close(processor.resultCh)
	<-processor.doneCh

	s.finalizeRun(run, "")
	log.Info("batch test completed",
		zap.String("run_id", run.ID),
		zap.Int("total_tasks", run.TotalTasks),
		zap.Duration("duration", time.Since(run.StartedAt)),
	)
}

func (s *Service) executeTestTask(ctx context.Context, tc *models.TestCase, variant *models.ExperimentVariant, wg *sync.WaitGroup, semaphore chan struct{}, resultCh chan<- *testResult) {
	defer wg.Done()
	defer func() { <-semaphore }()

	result := s.executeTestCase(ctx, tc, variant)
	select {
	case resultCh <- result:
	case <-ctx.Done():
		testResultPool.Put(result)
	}
}

func (s *Service) startResultProcessor(run *experimentVariantRun, log *zap.Logger) *resultProcessor {
	processor := &resultProcessor{
		run:      run,
		resultCh: make(chan *testResult, metricsBufferSize),
		doneCh:   make(chan struct{}),
	}

	go func() {
		defer close(processor.doneCh)
		s.processResults(processor, log)
	}()

	return processor
}

func (s *Service) processResults(processor *resultProcessor, log *zap.Logger) {
	run := processor.run

	for result := range processor.resultCh {
		s.processSingleResult(run, result, log)
		testResultPool.Put(result)
	}
}

func (s *Service) processSingleResult(run *experimentVariantRun, result *testResult, log *zap.Logger) {
	completed := atomic.AddInt64(&run.Completed, 1)
	run.Progress = float64(completed) / float64(run.TotalTasks)

	vm := s.getOrCreateVariantMetric(run, result)
	s.updateVariantMetrics(vm, result)

	log.Debug("task completed",
		zap.String("test_case_id", result.TestCaseID),
		zap.String("variant_id", result.VariantID),
		zap.Bool("success", result.Success),
		zap.Int64("latency_ms", result.LatencyMs),
		zap.Float64("progress", run.Progress),
	)
}

func (s *Service) executeTestCase(ctx context.Context, tc *models.TestCase, variant *models.ExperimentVariant) *testResult {
	startTime := time.Now()
	result := testResultPool.Get().(*testResult)
	*result = testResult{
		TestCaseID:    tc.ID,
		VariantID:     variant.ID,
		PromptID:      variant.PromptID,
		VersionID:     variant.VersionID,
		ExecutionTime: startTime,
	}

	select {
	case <-ctx.Done():
		result.Error = "context cancelled"
		result.Success = false
		return result
	default:
	}

	time.Sleep(10 * time.Millisecond)

	result.Output = fmt.Sprintf("Response for: %s (variant: %s)", tc.Input, variant.Name)
	result.LatencyMs = time.Since(startTime).Milliseconds()
	result.Success = true
	result.Score = 0.75 + float64(utils.RandomInt(0, 25))/100.0

	return result
}

func (s *Service) finalizeRun(run *experimentVariantRun, errMsg string) {
	now := time.Now()
	run.CompletedAt = &now

	if errMsg != "" {
		run.Status = "failed"
		run.Error = errMsg
		return
	}

	run.Status = "completed"

	variantList := s.computeFinalMetrics(run)
	s.determineWinners(variantList, run)

	evaluation := s.buildEvaluationResult(run, variantList, now)
	s.createEvaluationIfNeeded(evaluation)
}

func (s *Service) GetRunStatus(ctx context.Context, runID string) (*experimentVariantRun, error) {
	s.activeRunsMu.RLock()
	defer s.activeRunsMu.RUnlock()

	run, exists := s.activeRuns[runID]
	if !exists {
		return nil, errors.NewNotFound("run not found", runID)
	}
	return run, nil
}

func (s *Service) CancelRun(ctx context.Context, runID string) error {
	s.activeRunsMu.Lock()
	defer s.activeRunsMu.Unlock()

	run, exists := s.activeRuns[runID]
	if !exists {
		return errors.NewNotFound("run not found", runID)
	}

	if run.Status == "running" && run.cancelFunc != nil {
		run.cancelFunc()
		run.Status = "cancelling"
	}
	return nil
}

func (s *Service) CalculateStatSignificance(variantA, variantB *variantMetric) float64 {
	if variantA.SampleCount < 30 || variantB.SampleCount < 30 {
		return 0.0
	}

	rateA := variantA.Metrics["success_rate"]
	rateB := variantB.Metrics["success_rate"]

	totalA := float64(variantA.SampleCount)
	totalB := float64(variantB.SampleCount)
	pooledRate := (rateA*totalA + rateB*totalB) / (totalA + totalB)

	se := math.Sqrt(pooledRate * (1 - pooledRate) * (1.0/totalA + 1.0/totalB))
	if se == 0 {
		return 1.0
	}

	zScore := (rateB - rateA) / se
	return 1.0 - math.Exp(-math.Abs(zScore))
}

func (s *Service) GetEvaluation(ctx context.Context, runID string) (*models.EvaluationResult, error) {
	if s.db == nil {
		return nil, errors.NewInternal("database not available", "")
	}

	var eval models.EvaluationResult
	if err := s.db.Where("run_id = ?", runID).First(&eval).Error; err != nil {
		return nil, s.handleDBError(err, "evaluation", runID)
	}
	return &eval, nil
}

func (s *Service) PromoteVersion(ctx context.Context, promptID, versionID string) error {
	log := logger.FromContext(ctx)

	prompt, err := s.GetPrompt(ctx, promptID)
	if err != nil {
		return err
	}

	version, err := s.getVersion(ctx, promptID, versionID)
	if err != nil {
		return err
	}

	now := time.Now()
	prompt.ActiveVersionID = versionID
	prompt.UpdatedAt = now
	prompt.Status = string(models.PromptStatusActive)

	if s.db != nil {
		tx := s.db.Begin()
		tx.Model(&models.PromptVersion{}).Where("prompt_id = ?", promptID).Update("status", string(models.PromptStatusArchived))
		tx.Model(&models.PromptVersion{}).Where("id = ?", versionID).Update("status", string(models.PromptStatusActive))
		tx.Save(prompt)
		tx.Commit()
	}

	log.Info("version promoted",
		zap.String("prompt_id", promptID),
		zap.String("version_id", versionID),
	)
	return nil
}

func (s *Service) createRecord(ctx context.Context, record interface{}, recordType string) error {
	if s.db == nil {
		return nil
	}
	if err := s.db.Create(record).Error; err != nil {
		return errors.NewInternal(fmt.Sprintf("failed to create %s", recordType), err.Error())
	}
	return nil
}

func (s *Service) handleDBError(err error, entityType, id string) error {
	if err == gorm.ErrRecordNotFound {
		return errors.NewNotFound(fmt.Sprintf("%s not found", entityType), id)
	}
	return errors.NewInternal("database error", err.Error())
}

func (s *Service) countRecords(query *gorm.DB) (int64, error) {
	var total int64
	if err := query.Count(&total).Error; err != nil {
		return 0, errors.NewInternal("database error", err.Error())
	}
	return total, nil
}

func (s *Service) getLatestVersionNumber(ctx context.Context, promptID string) (int, error) {
	if s.db == nil {
		return 0, nil
	}
	var maxVer int
	if err := s.db.Model(&models.PromptVersion{}).
		Where("prompt_id = ?", promptID).
		Select("COALESCE(MAX(version), 0)").
		Scan(&maxVer).Error; err != nil {
		return 0, errors.NewInternal("database error", err.Error())
	}
	return maxVer, nil
}

func (s *Service) getVersion(ctx context.Context, promptID, versionID string) (*models.PromptVersion, error) {
	if s.db == nil {
		return nil, errors.NewInternal("database not available", "")
	}
	var version models.PromptVersion
	if err := s.db.First(&version, "id = ? AND prompt_id = ?", versionID, promptID).Error; err != nil {
		return nil, errors.NewNotFound("version not found", versionID)
	}
	return &version, nil
}

func (s *Service) normalizeConcurrency(reqConcurrency int) int {
	switch {
	case reqConcurrency <= 0:
		return s.maxWorkers
	case reqConcurrency > s.maxWorkers:
		return s.maxWorkers
	default:
		return reqConcurrency
	}
}

func (s *Service) createExperimentRun(runID, experimentID string, totalTasks int, cancel context.CancelFunc) *experimentVariantRun {
	return &experimentVariantRun{
		ID:           runID,
		ExperimentID: experimentID,
		Status:       "running",
		Progress:     0,
		TotalTasks:   totalTasks,
		Completed:    0,
		Results:      make(map[string]*variantMetric, 16),
		StartedAt:    time.Now(),
		cancelFunc:   cancel,
	}
}

func (s *Service) registerActiveRun(runID string, run *experimentVariantRun) {
	s.activeRunsMu.Lock()
	defer s.activeRunsMu.Unlock()
	s.activeRuns[runID] = run
}

func (s *Service) buildAllVariants(exp *models.ABExperiment) []*models.ExperimentVariant {
	allVariants := make([]*models.ExperimentVariant, 0, len(exp.Variants)+1)
	allVariants = append(allVariants, &models.ExperimentVariant{
		ID:           "control",
		PromptID:     exp.ControlPromptID,
		VersionID:    exp.ControlVersionID,
		Name:         "Control Group",
		TrafficShare: exp.TrafficPercentage / 100,
	})
	allVariants = append(allVariants, exp.Variants...)
	return allVariants
}

func (s *Service) isCancelled(ctx context.Context) bool {
	select {
	case <-ctx.Done():
		return true
	default:
		return false
	}
}

func (s *Service) getOrCreateVariantMetric(run *experimentVariantRun, result *testResult) *variantMetric {
	run.ResultsMu.RLock()
	vm, exists := run.Results[result.VariantID]
	run.ResultsMu.RUnlock()

	if exists {
		return vm
	}

	run.ResultsMu.Lock()
	defer run.ResultsMu.Unlock()

	if vm, exists = run.Results[result.VariantID]; exists {
		return vm
	}

	vm = variantResultPool.Get().(*variantMetric)
	vm.VariantID = result.VariantID
	vm.PromptID = result.PromptID
	vm.VersionID = result.VersionID
	vm.SampleCount = 0
	vm.Rank = 0
	vm.IsWinner = false
	for k := range vm.Metrics {
		delete(vm.Metrics, k)
	}

	run.Results[result.VariantID] = vm
	return vm
}

func (s *Service) updateVariantMetrics(vm *variantMetric, result *testResult) {
	vm.SampleCount++
	if result.Success {
		vm.Metrics["success_count"]++
		vm.Metrics["total_latency"] += float64(result.LatencyMs)
		vm.Metrics["total_score"] += result.Score
	} else {
		vm.Metrics["error_count"]++
	}
}

func (s *Service) computeFinalMetrics(run *experimentVariantRun) []*variantMetric {
	variantList := make([]*variantMetric, 0, len(run.Results))

	run.ResultsMu.RLock()
	defer run.ResultsMu.RUnlock()

	for _, vr := range run.Results {
		if vr.SampleCount > 0 {
			vr.Metrics["avg_latency"] = vr.Metrics["total_latency"] / float64(vr.SampleCount)
			vr.Metrics["avg_score"] = vr.Metrics["total_score"] / float64(vr.SampleCount)
			vr.Metrics["success_rate"] = vr.Metrics["success_count"] / float64(vr.SampleCount)
			vr.Metrics["error_rate"] = vr.Metrics["error_count"] / float64(vr.SampleCount)
		}
		variantList = append(variantList, vr)
	}

	sort.Slice(variantList, func(i, j int) bool {
		return variantList[i].Metrics["avg_score"] > variantList[j].Metrics["avg_score"]
	})

	return variantList
}

func (s *Service) determineWinners(variantList []*variantMetric, run *experimentVariantRun) {
	for i, vr := range variantList {
		vr.Rank = i + 1
		if i == 0 {
			vr.IsWinner = true
			run.WinningVariantID = vr.VariantID
		}
	}
}

func (s *Service) buildEvaluationResult(run *experimentVariantRun, variantList []*variantMetric, now time.Time) *models.EvaluationResult {
	evaluation := &models.EvaluationResult{
		ID:               utils.NewID("eval"),
		ExperimentID:     run.ExperimentID,
		RunID:            run.ID,
		VariantResults:   make(map[string]*models.VariantResult, len(variantList)),
		WinningVariantID: run.WinningVariantID,
		OverallMetrics:   make(map[string]float64, 8),
		CreatedAt:        now,
	}

	var totalSamples int64
	for _, vr := range run.Results {
		totalSamples += vr.SampleCount
		mvr := &models.VariantResult{
			VariantID:   vr.VariantID,
			PromptID:    vr.PromptID,
			VersionID:   vr.VersionID,
			SampleCount: vr.SampleCount,
			Metrics:     vr.Metrics,
			Rank:        vr.Rank,
			IsWinner:    vr.IsWinner,
		}
		evaluation.VariantResults[vr.VariantID] = mvr
	}

	evaluation.OverallMetrics["total_samples"] = float64(totalSamples)
	evaluation.OverallMetrics["total_variants"] = float64(len(run.Results))
	evaluation.OverallMetrics["duration_seconds"] = now.Sub(run.StartedAt).Seconds()

	evaluation.Conclusion = s.buildConclusion(run, totalSamples)
	evaluation.Recommendations = []string{
		"Review winning variant metrics",
		"Consider statistical significance before rollout",
		"Monitor production metrics after deployment",
	}

	return evaluation
}

func (s *Service) buildConclusion(run *experimentVariantRun, totalSamples int64) string {
	conclusion := fmt.Sprintf("Experiment completed. Tested %d variants with %d total samples. ",
		len(run.Results), totalSamples)
	if run.WinningVariantID != "" {
		winner := run.Results[run.WinningVariantID]
		conclusion += fmt.Sprintf("Winner: variant %s with avg score %.2f.",
			run.WinningVariantID, winner.Metrics["avg_score"])
	}
	return conclusion
}

func (s *Service) createEvaluationIfNeeded(evaluation *models.EvaluationResult) {
	if s.db != nil {
		s.db.Create(evaluation)
	}
}

func parsePromptFilters(filters map[string]interface{}) promptQueryFilters {
	result := promptQueryFilters{}
	if taskType, ok := filters["task_type"].(string); ok {
		result.taskType = taskType
	}
	if status, ok := filters["status"].(string); ok {
		result.status = status
	}
	if owner, ok := filters["owner"].(string); ok {
		result.owner = owner
	}
	return result
}

func parseExperimentFilters(filters map[string]interface{}) experimentQueryFilters {
	result := experimentQueryFilters{}
	if status, ok := filters["status"].(string); ok {
		result.status = status
	}
	if expType, ok := filters["type"].(string); ok {
		result.expType = expType
	}
	return result
}

func (s *Service) buildPromptQuery(filters promptQueryFilters) *gorm.DB {
	query := s.db.Model(&models.Prompt{})
	if filters.taskType != "" {
		query = query.Where("task_type = ?", filters.taskType)
	}
	if filters.status != "" {
		query = query.Where("status = ?", filters.status)
	}
	if filters.owner != "" {
		query = query.Where("owner = ?", filters.owner)
	}
	return query
}

func (s *Service) buildExperimentQuery(filters experimentQueryFilters) *gorm.DB {
	query := s.db.Model(&models.ABExperiment{})
	if filters.status != "" {
		query = query.Where("status = ?", filters.status)
	}
	if filters.expType != "" {
		query = query.Where("type = ?", filters.expType)
	}
	return query
}
