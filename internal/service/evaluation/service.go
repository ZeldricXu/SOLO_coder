package evaluation

import (
	"errors"
	"fmt"
	"math"
	"math/rand"
	"sync"
	"time"

	"gorm.io/gorm"

	"llmgateway/internal/domain/entity"
	"llmgateway/internal/infrastructure/database"
	"llmgateway/internal/infrastructure/logger"
	"llmgateway/pkg/utils"
)

const (
	defaultBatchSize    = 100
	defaultTotalSamples = 1000
	statusRunning       = "running"
	statusCompleted     = "completed"
	statusCanceled      = "canceled"
	statusStreaming     = "streaming"
	metricTypeOffline   = "offline"
	metricTypeStreaming = "streaming"
)

var (
	ErrEvaluationNotFound = errors.New("streaming evaluation not found")
	ErrDatasetNotFound    = errors.New("dataset not found")
	randPool              = sync.Pool{
		New: func() interface{} {
			return rand.New(rand.NewSource(time.Now().UnixNano()))
		},
	}
)

type StreamBatchHandler func(batchIndex int, metrics map[string]float64, progress float64) error

type Service struct {
	db                  *gorm.DB
	streamingEvaluations map[string]*StreamingEvaluation
	mu                  sync.RWMutex
}

type StreamingEvaluation struct {
	RunID          string
	ModelVersionID string
	BatchSize      int
	TotalBatches   int
	CurrentBatch   int
	Progress       float64
	Metrics        map[string][]float64
	Status         string
	Handler        StreamBatchHandler
	CreatedAt      time.Time
	UpdatedAt      time.Time
	mu             sync.Mutex
}

type StartStreamEvaluationRequest struct {
	ModelVersionID string                 `json:"model_version_id" binding:"required"`
	DatasetID      string                 `json:"dataset_id" binding:"required"`
	Name           string                 `json:"name"`
	BatchSize      int                    `json:"batch_size"`
	TotalSamples   int                    `json:"total_samples"`
	Parameters     map[string]interface{} `json:"parameters"`
	CreatedBy      string                 `json:"-"`
}

type CreateDatasetRequest struct {
	Name        string                 `json:"name" binding:"required"`
	Description string                 `json:"description"`
	Type        string                 `json:"type" binding:"required"`
	Source      string                 `json:"source"`
	SampleCount int                    `json:"sample_count"`
	Schema      map[string]interface{} `json:"schema"`
	CreatedBy   string                 `json:"-"`
}

type StartEvaluationRequest struct {
	ModelVersionID string                 `json:"model_version_id" binding:"required"`
	DatasetID      string                 `json:"dataset_id" binding:"required"`
	Name           string                 `json:"name"`
	Parameters     map[string]interface{} `json:"parameters"`
	CreatedBy      string                 `json:"-"`
}

type DriftDetectionConfig struct {
	ModelVersionID string  `json:"model_version_id" binding:"required"`
	FeatureName    string  `json:"feature_name" binding:"required"`
	DriftType      string  `json:"drift_type" binding:"required"`
	Threshold      float64 `json:"threshold" binding:"required"`
}

func NewService() *Service {
	return &Service{
		db:                  database.DB(),
		streamingEvaluations: make(map[string]*StreamingEvaluation),
	}
}

func (s *Service) StartStreamEvaluation(req *StartStreamEvaluationRequest, handler StreamBatchHandler) (*entity.EvaluationRun, error) {
	params := s.normalizeStreamParams(req)
	totalBatches := calcTotalBatches(params.TotalSamples, params.BatchSize)

	run, err := s.createEvaluationRun(req, statusStreaming)
	if err != nil {
		return nil, fmt.Errorf("create evaluation run: %w", err)
	}

	streamEval := s.initStreamingEvaluation(run, params, handler)
	s.storeStreamingEval(run.ID, streamEval)

	logger.Info("streaming evaluation started",
		"run_id", run.ID,
		"model_version_id", req.ModelVersionID,
		"total_batches", totalBatches,
		"batch_size", params.BatchSize)

	go s.processStreamingEvaluation(run, streamEval)

	return run, nil
}

func (s *Service) normalizeStreamParams(req *StartStreamEvaluationRequest) *StartStreamEvaluationRequest {
	if req.BatchSize <= 0 {
		req.BatchSize = defaultBatchSize
	}
	if req.TotalSamples <= 0 {
		req.TotalSamples = defaultTotalSamples
	}
	return req
}

func calcTotalBatches(total, batchSize int) int {
	if batchSize == 0 {
		return 0
	}
	return (total + batchSize - 1) / batchSize
}

func (s *Service) createEvaluationRun(req *StartStreamEvaluationRequest, status string) (*entity.EvaluationRun, error) {
	now := utils.Now()
	run := &entity.EvaluationRun{
		ID:             utils.GenerateID("eval"),
		ModelVersionID: req.ModelVersionID,
		DatasetID:      req.DatasetID,
		Name:           req.Name,
		Status:         status,
		Parameters:     req.Parameters,
		StartTime:      now,
		CreatedBy:      req.CreatedBy,
		CreatedAt:      now,
	}

	if err := s.db.Create(run).Error; err != nil {
		return nil, err
	}
	return run, nil
}

func (s *Service) initStreamingEvaluation(run *entity.EvaluationRun, params *StartStreamEvaluationRequest, handler StreamBatchHandler) *StreamingEvaluation {
	totalBatches := calcTotalBatches(params.TotalSamples, params.BatchSize)
	return &StreamingEvaluation{
		RunID:          run.ID,
		ModelVersionID: params.ModelVersionID,
		BatchSize:      params.BatchSize,
		TotalBatches:   totalBatches,
		CurrentBatch:   0,
		Progress:       0.0,
		Metrics:        make(map[string][]float64, 8),
		Status:         statusRunning,
		Handler:        handler,
		CreatedAt:      run.CreatedAt,
		UpdatedAt:      run.CreatedAt,
	}
}

func (s *Service) storeStreamingEval(runID string, streamEval *StreamingEvaluation) {
	s.mu.Lock()
	s.streamingEvaluations[runID] = streamEval
	s.mu.Unlock()
}

func (s *Service) processStreamingEvaluation(run *entity.EvaluationRun, streamEval *StreamingEvaluation) {
	for batchIndex := 0; batchIndex < streamEval.TotalBatches; batchIndex++ {
		if s.isCanceled(streamEval) {
			return
		}

		s.updateBatchProgress(streamEval, batchIndex+1)

		batchMetrics := s.processBatch(batchIndex, streamEval.BatchSize)

		s.accumulateMetrics(streamEval, batchMetrics)
		s.invokeBatchHandler(streamEval, batchIndex, batchMetrics)
		s.persistBatchMetrics(streamEval.ModelVersionID, batchMetrics)

		time.Sleep(50 * time.Millisecond)
	}

	s.finalizeStreamingEvaluation(run, streamEval)
}

func (s *Service) isCanceled(streamEval *StreamingEvaluation) bool {
	streamEval.mu.Lock()
	defer streamEval.mu.Unlock()
	return streamEval.Status == statusCanceled
}

func (s *Service) updateBatchProgress(streamEval *StreamingEvaluation, batchNum int) {
	streamEval.mu.Lock()
	defer streamEval.mu.Unlock()
	streamEval.CurrentBatch = batchNum
	streamEval.Progress = float64(batchNum) / float64(streamEval.TotalBatches)
	streamEval.UpdatedAt = utils.Now()
}

func (s *Service) accumulateMetrics(streamEval *StreamingEvaluation, batchMetrics map[string]float64) {
	streamEval.mu.Lock()
	defer streamEval.mu.Unlock()
	for name, value := range batchMetrics {
		streamEval.Metrics[name] = append(streamEval.Metrics[name], value)
	}
}

func (s *Service) invokeBatchHandler(streamEval *StreamingEvaluation, batchIndex int, batchMetrics map[string]float64) {
	if streamEval.Handler == nil {
		return
	}

	streamEval.mu.Lock()
	progress := streamEval.Progress
	streamEval.mu.Unlock()

	if err := streamEval.Handler(batchIndex, batchMetrics, progress); err != nil {
		logger.Error("stream handler error", "run_id", streamEval.RunID, "error", err)
	}
}

func (s *Service) persistBatchMetrics(modelVersionID string, batchMetrics map[string]float64) {
	for name, value := range batchMetrics {
		if _, err := s.RecordMetric(modelVersionID, metricTypeStreaming, name, value, 0.0, ""); err != nil {
			logger.Error("failed to record batch metric", "metric", name, "error", err)
		}
	}
}

func (s *Service) finalizeStreamingEvaluation(run *entity.EvaluationRun, streamEval *StreamingEvaluation) {
	streamEval.mu.Lock()
	metricsCopy := make(map[string][]float64, len(streamEval.Metrics))
	for k, v := range streamEval.Metrics {
		metricsCopy[k] = v
	}
	streamEval.mu.Unlock()

	aggregated := s.aggregateMetrics(metricsCopy)
	endTime := utils.Now()

	run.Metrics = aggregated
	run.EndTime = &endTime
	run.Status = statusCompleted
	s.db.Save(run)

	streamEval.mu.Lock()
	streamEval.Status = statusCompleted
	streamEval.Progress = 1.0