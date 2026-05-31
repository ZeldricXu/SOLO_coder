package application

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/solocoder/session148/internal/domain"
	apperr "github.com/solocoder/session148/pkg/errors"
	"github.com/solocoder/session148/pkg/utils"
)

type AppService struct {
	logger      domain.Logger
	processor   domain.DataProcessor
	configMgr   domain.ConfigManager
	storage     domain.StorageManager
	monitor     domain.Monitor
	audit       domain.AuditTrail
	masker      domain.DataMasker
	dataAccess  domain.DataAccessor
	flCoord     domain.FLCoordinator
	runs        map[string]*domain.RunInstance
	mu          sync.RWMutex
}

type ServiceDeps struct {
	Logger     domain.Logger
	Processor  domain.DataProcessor
	ConfigMgr  domain.ConfigManager
	Storage    domain.StorageManager
	Monitor    domain.Monitor
	Audit      domain.AuditTrail
	Masker     domain.DataMasker
	DataAccess domain.DataAccessor
	FLCoord    domain.FLCoordinator
}

func NewAppService(deps ServiceDeps) *AppService {
	return &AppService{
		logger:     deps.Logger,
		processor:  deps.Processor,
		configMgr:  deps.ConfigMgr,
		storage:    deps.Storage,
		monitor:    deps.Monitor,
		audit:      deps.Audit,
		masker:     deps.Masker,
		dataAccess: deps.DataAccess,
		flCoord:    deps.FLCoord,
		runs:       make(map[string]*domain.RunInstance),
	}
}

type ProcessRequest struct {
	TraceID   string                 `json:"trace_id"`
	Namespace string                 `json:"namespace"`
	Payload   map[string]interface{} `json:"payload"`
	UserID    string                 `json:"user_id,omitempty"`
}

type ProcessResponse struct {
	RunID  string                 `json:"run_id"`
	Status string                 `json:"status"`
	Result map[string]interface{} `json:"result,omitempty"`
}

func (s *AppService) ExecuteHandler(ctx context.Context, req ProcessRequest) (*ProcessResponse, error) {
	log := s.logger.WithTraceID(req.TraceID)

	run := &domain.RunInstance{
		RunID:     utils.NewRunID(),
		EntityID:  req.TraceID,
		Phase:     "validating",
		Progress:  0.0,
		StartedAt: time.Now().UTC(),
	}
	s.trackRun(run)

	defer func() {
		s.monitor.RecordMetric("request.total", 1, nil)
	}()

	if err := s.processor.Validate(ctx, req.Payload); err != nil {
		run.Phase = "failed"
		run.ErrorDetail = strPtr(err.Error())
		s.completeRun(run)
		s.monitor.RecordMetric("request.validation_error", 1, nil)
		return nil, err
	}

	run.Phase = "loading_config"
	run.Progress = 0.2
	s.trackRun(run)

	config, err := s.configMgr.Load(ctx, req.Namespace)
	if err != nil {
		run.Phase = "failed"
		run.ErrorDetail = strPtr(err.Error())
		s.completeRun(run)
		return nil, err
	}

	if !config.Enabled {
		run.Phase = "failed"
		run.ErrorDetail = strPtr("config is disabled")
		s.completeRun(run)
		return nil, apperr.NewValidationError("config disabled", req.Namespace)
	}

	run.Phase = "processing"
	run.Progress = 0.4
	s.trackRun(run)

	rules, _ := config.Parameters["rules"].(map[string]interface{})
	result, err := s.processor.Transform(ctx, req.Payload, rules)
	if err != nil {
		run.Phase = "failed"
		run.ErrorDetail = strPtr(err.Error())
		s.completeRun(run)
		s.monitor.RecordMetric("request.processing_error", 1, nil)
		return nil, err
	}

	normalized, err := s.processor.Normalize(ctx, result)
	if err != nil {
		run.Phase = "failed"
		run.ErrorDetail = strPtr(err.Error())
		s.completeRun(run)
		return nil, err
	}

	run.Phase = "persisting"
	run.Progress = 0.7
	s.trackRun(run)

	record := &domain.DataRecord{
		Payload: normalized,
	}
	if err := s.dataAccess.SaveRecord(ctx, record); err != nil {
		run.Phase = "failed"
		run.ErrorDetail = strPtr(err.Error())
		s.completeRun(run)
		return nil, err
	}

	if s.audit != nil {
		auditRec := &domain.AuditRecord{
			Operation: "process_request",
			UserID:    req.UserID,
			Resource:  fmt.Sprintf("record:%s", record.ID),
			Data:      map[string]interface{}{"trace_id": req.TraceID},
		}
		s.audit.Record(ctx, auditRec)
	}

	run.Phase = "finalizing"
	run.Progress = 0.9
	s.trackRun(run)

	run.Phase = "completed"
	run.Progress = 1.0
	s.completeRun(run)

	s.monitor.RecordMetric("request.success", 1, nil)
	log.Info("request processed", "run_id", run.RunID, "duration_ms", 0)

	return &ProcessResponse{
		RunID:  run.RunID,
		Status: "completed",
		Result: normalized,
	}, nil
}

func (s *AppService) GetRunStatus(ctx context.Context, runID string) (*domain.RunInstance, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	run, exists := s.runs[runID]
	if !exists {
		return nil, apperr.NewNotFoundError(fmt.Sprintf("run not found: %s", runID))
	}

	result := *run
	return &result, nil
}

func (s *AppService) trackRun(run *domain.RunInstance) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.runs[run.RunID] = run
}

func (s *AppService) completeRun(run *domain.RunInstance) {
	now := time.Now().UTC()
	run.CompletedAt = &now
	s.trackRun(run)
}

func strPtr(s string) *string {
	return &s
}

type BatchOperation struct {
	Action string                 `json:"action"`
	ID     string                 `json:"id"`
	Params map[string]interface{} `json:"params,omitempty"`
}

type BatchResult struct {
	ID     string      `json:"id"`
	Status string      `json:"status"`
	Result interface{} `json:"result,omitempty"`
	Error  string      `json:"error,omitempty"`
}

func (s *AppService) ExecuteBatch(ctx context.Context, operations []BatchOperation) (string, []BatchResult, error) {
	batchID := utils.NewBatchID()
	results := make([]BatchResult, len(operations))

	for i, op := range operations {
		results[i] = s.executeOperation(ctx, op)
	}

	return batchID, results, nil
}

func (s *AppService) executeOperation(ctx context.Context, op BatchOperation) BatchResult {
	switch op.Action {
	case "stop":
		return BatchResult{ID: op.ID, Status: "success", Result: "stopped"}
	case "restart":
		return BatchResult{ID: op.ID, Status: "success", Result: "restarted"}
	case "delete":
		if err := s.dataAccess.DeleteRecord(ctx, op.ID); err != nil {
			return BatchResult{ID: op.ID, Status: "failed", Error: err.Error()}
		}
		return BatchResult{ID: op.ID, Status: "success"}
	default:
		return BatchResult{ID: op.ID, Status: "failed", Error: "unknown action"}
	}
}

func (s *AppService) CreateResource(ctx context.Context, type_ string, config map[string]interface{}, labels map[string]string) (string, string, error) {
	resourceID := utils.NewResourceID()

	entity := &domain.Entity{
		ID:         resourceID,
		Type:       type_,
		Status:     "provisioning",
		Attributes: config,
		CreatedAt:  time.Now().UTC(),
		UpdatedAt:  time.Now().UTC(),
	}

	record := &domain.DataRecord{
		Payload: map[string]interface{}{
			"type":    type_,
			"config":  config,
			"labels":  labels,
			"entity":  entity,
		},
	}

	if err := s.dataAccess.SaveRecord(ctx, record); err != nil {
		return "", "", err
	}

	return resourceID, "provisioning", nil
}

type ResourceStatus struct {
	ID       string  `json:"id"`
	Status   string  `json:"status"`
	Progress float64 `json:"progress"`
}

func (s *AppService) GetResourceStatus(ctx context.Context, id string) (*ResourceStatus, error) {
	record, err := s.dataAccess.GetRecord(ctx, id)
	if err != nil {
		return nil, err
	}

	entity, _ := record.Payload["entity"].(map[string]interface{})
	status, _ := entity["status"].(string)

	return &ResourceStatus{
		ID:       id,
		Status:   status,
		Progress: 1.0,
	}, nil
}

func (s *AppService) GetMaskedData(ctx context.Context, recordID string, user *domain.User) (map[string]interface{}, error) {
	record, err := s.dataAccess.GetRecord(ctx, recordID)
	if err != nil {
		return nil, err
	}

	masked, err := s.masker.Mask(ctx, record.Payload, user)
	if err != nil {
		return nil, err
	}

	return masked, nil
}

func (s *AppService) BackupData(ctx context.Context) (*domain.BackupInfo, error) {
	return s.storage.Backup(ctx, "./data")
}

func (s *AppService) RestoreData(ctx context.Context, backupID string, dest string) error {
	return s.storage.Restore(ctx, backupID, dest)
}

func (s *AppService) ListBackups(ctx context.Context) ([]domain.BackupInfo, error) {
	return s.storage.ListBackups(ctx)
}

func (s *AppService) VerifyAuditIntegrity(ctx context.Context) (bool, []string, error) {
	return s.audit.VerifyIntegrity(ctx)
}

func (s *AppService) GetMetricsSnapshot(ctx context.Context) (*domain.MetricsSnapshot, error) {
	return s.monitor.GetSnapshot(ctx)
}

func (s *AppService) GetActiveRuns() []domain.RunInstance {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var runs []domain.RunInstance
	for _, r := range s.runs {
		if r.CompletedAt == nil {
			runs = append(runs, *r)
		}
	}
	return runs
}

func (s *AppService) EvaluateAlerts(ctx context.Context) ([]domain.Alert, error) {
	return s.monitor.EvaluateRules(ctx)
}

func (s *AppService) MigrateSchema(ctx context.Context, targetVersion int) error {
	return s.dataAccess.Migrate(ctx, targetVersion)
}

func (s *AppService) GetSchemaVersion(ctx context.Context) (int, error) {
	return s.dataAccess.GetSchemaVersion(ctx)
}
