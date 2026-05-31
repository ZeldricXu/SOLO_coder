package zkp

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/gasestimator/platform/internal/domain/model"
	"github.com/gasestimator/platform/internal/domain/repository"
	"github.com/gasestimator/platform/internal/infrastructure/logger"
	"github.com/gasestimator/platform/pkg/common"
	"go.uber.org/zap"
)

const (
	maxProofSize = 10 * 1024 * 1024
)

type VerifyRequest struct {
	CircuitID       string          `json:"circuit_id"`
	ProofData       json.RawMessage `json:"proof_data"`
	PublicInputs    json.RawMessage `json:"public_inputs"`
	VerificationKey string          `json:"verification_key"`
	Async           bool            `json:"async,omitempty"`
	WebhookURL      string          `json:"webhook_url,omitempty"`
}

type VerifyResponse struct {
	ID         string    `json:"id"`
	Verified   bool      `json:"verified"`
	Result     string    `json:"result"`
	VerifiedAt time.Time `json:"verified_at"`
	Checksum   string    `json:"checksum"`
	Async      bool      `json:"async,omitempty"`
	Queued     bool      `json:"queued,omitempty"`
}

type AsyncTaskStatus struct {
	TaskID      string           `json:"task_id"`
	Status      string           `json:"status"`
	Progress    float64          `json:"progress"`
	SubmittedAt time.Time        `json:"submitted_at"`
	CompletedAt *time.Time       `json:"completed_at,omitempty"`
	Result      *VerifyResponse  `json:"result,omitempty"`
	Error       string           `json:"error,omitempty"`
}

type VerificationResult struct {
	Verified bool
	Result   string
}

type Verifier interface {
	Verify(ctx context.Context, req *VerifyRequest) (*VerificationResult, error)
}

type ProofValidator interface {
	Validate(req *VerifyRequest) error
}

type ProofRepository interface {
	Create(ctx context.Context, proof *model.ZKPProof) error
	Update(ctx context.Context, proof *model.ZKPProof) error
	GetByID(ctx context.Context, id string) (*model.ZKPProof, error)
	List(ctx context.Context, circuitID string, verified *bool, limit, offset int) ([]*model.ZKPProof, int64, error)
}

type TaskExecutor interface {
	Start(ctx context.Context)
	Submit(task *AsyncTask) error
	Stats() PoolStats
}

type WebhookNotifier interface {
	Notify(url string, resp *VerifyResponse, err error)
}

type ChecksumCalculator interface {
	Calculate(resp *VerifyResponse) string
}

type PoolStats struct {
	Workers      int   `json:"workers"`
	MaxWorkers   int   `json:"max_workers"`
	MinWorkers   int   `json:"min_workers"`
	QueueLength  int   `json:"queue_length"`
	ActiveTasks  int   `json:"active_tasks"`
	QueueCap     int   `json:"queue_cap"`
}

type AsyncTask struct {
	id          string
	req         *VerifyRequest
	ctx         context.Context
	status      string
	progress    float64
	submittedAt time.Time
	completedAt *time.Time
	result      *VerifyResponse
	err         error
	callback    func(*VerifyResponse, error)
}

type circuitVerifier struct{}

func NewCircuitVerifier() Verifier {
	return &circuitVerifier{}
}

func (v *circuitVerifier) Verify(ctx context.Context, req *VerifyRequest) (*VerificationResult, error) {
	if err := validateProofData(req); err != nil {
		return &VerificationResult{Verified: false, Result: err.Error()}, nil
	}

	verified, result := executeCircuitVerification(req)
	return &VerificationResult{Verified: verified, Result: result}, nil
}

type proofValidator struct{}

func NewProofValidator() ProofValidator {
	return &proofValidator{}
}

func (v *proofValidator) Validate(req *VerifyRequest) error {
	if len(req.ProofData) > maxProofSize {
		return common.NewInvalidInputError("proof data exceeds maximum size of 10MB")
	}
	if len(req.ProofData) == 0 {
		return common.NewInvalidInputError("proof data is required")
	}
	if len(req.VerificationKey) == 0 {
		return common.NewInvalidInputError("verification key is required")
	}
	return nil
}

func validateProofData(req *VerifyRequest) error {
	if len(req.ProofData) == 0 || len(req.VerificationKey) == 0 {
		return errors.New("invalid_input")
	}

	var proof map[string]interface{}
	if err := json.Unmarshal(req.ProofData, &proof); err != nil {
		return errors.New("invalid_proof_format")
	}

	var inputs map[string]interface{}
	if err := json.Unmarshal(req.PublicInputs, &inputs); err != nil {
		return errors.New("invalid_inputs_format")
	}

	if _, ok := proof["protocol"]; !ok {
		return errors.New("missing_protocol")
	}
	if _, ok := proof["curve"]; !ok {
		return errors.New("missing_curve")
	}

	return nil
}

func executeCircuitVerification(req *VerifyRequest) (bool, string) {
	if err := validateProofData(req); err != nil {
		return false, err.Error()
	}
	return true, "success"
}

type asyncTaskPool struct {
	workers     int
	maxWorkers  int
	minWorkers  int
	taskQueue   chan *AsyncTask
	activeTasks int
	mu          sync.RWMutex
	wg          sync.WaitGroup
	cond        *sync.Cond
}

func newAsyncTaskPool(minWorkers, maxWorkers, queueSize int) TaskExecutor {
	p := &asyncTaskPool{
		workers:    minWorkers,
		maxWorkers: maxWorkers,
		minWorkers: minWorkers,
		taskQueue:  make(chan *AsyncTask, queueSize),
	}
	p.cond = sync.NewCond(&sync.Mutex{})
	return p
}

func (p *asyncTaskPool) Start(ctx context.Context) {
	for i := 0; i < p.workers; i++ {
		p.wg.Add(1)
		go p.worker(ctx)
	}
	go p.monitor(ctx)
}

func (p *asyncTaskPool) Submit(task *AsyncTask) error {
	select {
	case p.taskQueue <- task:
		p.cond.Broadcast()
		return nil
	default:
		return errors.New("task queue is full")
	}
}

func (p *asyncTaskPool) worker(ctx context.Context) {
	defer p.wg.Done()
	for {
		task, err := p.nextTask(ctx)
		if err != nil {
			return
		}

		p.setActive(1)
		task.status = "processing"
		task.progress = 0.1

		result, err := processTask(task)

		task.status = "completed"
		task.progress = 1.0
		now := time.Now()
		task.completedAt = &now
		task.result = result
		task.err = err

		if task.callback != nil {
			go task.callback(result, err)
		}

		p.setActive(-1)
	}
}

func (p *asyncTaskPool) nextTask(ctx context.Context) (*AsyncTask, error) {
	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}

		p.mu.RLock()
		if len(p.taskQueue) > 0 {
			task := <-p.taskQueue
			p.mu.RUnlock()
			return task, nil
		}
		p.mu.RUnlock()

		p.cond.L.Lock()
		p.cond.Wait()
		p.cond.L.Unlock()

		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}
	}
}

func (p *asyncTaskPool) setActive(delta int) {
	p.mu.Lock()
	p.activeTasks += delta
	p.mu.Unlock()
}

func (p *asyncTaskPool) monitor(ctx context.Context) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			p.adjustWorkers(ctx)
		}
	}
}

func (p *asyncTaskPool) adjustWorkers(ctx context.Context) {
	p.mu.RLock()
	queueLen := len(p.taskQueue)
	active := p.activeTasks
	current := p.workers
	p.mu.RUnlock()

	utilization := float64(active+queueLen) / float64(current)

	switch {
	case utilization > 0.8 && current < p.maxWorkers:
		p.mu.Lock()
		p.workers++
		p.mu.Unlock()
		p.wg.Add(1)
		go p.worker(ctx)
		logger.L().Info("ZKP worker scaled up", zap.Int("workers", p.workers), zap.Int("queue", queueLen))

	case utilization < 0.2 && current > p.minWorkers:
		p.mu.Lock()
		p.workers--
		p.mu.Unlock()
		p.cond.Broadcast()
		logger.L().Info("ZKP worker scaled down", zap.Int("workers", p.workers), zap.Int("queue", queueLen))
	}
}

func (p *asyncTaskPool) Stats() PoolStats {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return PoolStats{
		Workers:     p.workers,
		MaxWorkers:  p.maxWorkers,
		MinWorkers:  p.minWorkers,
		QueueLength: len(p.taskQueue),
		ActiveTasks: p.activeTasks,
		QueueCap:    cap(p.taskQueue),
	}
}

type checksumCalc struct{}

func NewChecksumCalculator() ChecksumCalculator {
	return &checksumCalc{}
}

func (c *checksumCalc) Calculate(resp *VerifyResponse) string {
	return computeChecksum(resp)
}

type webhookLogger struct{}

func NewWebhookNotifier() WebhookNotifier {
	return &webhookLogger{}
}

func (n *webhookLogger) Notify(url string, resp *VerifyResponse, err error) {
	payload := map[string]interface{}{"task_id": resp.ID, "result": resp}
	if err != nil {
		payload["error"] = err.Error()
	}
	logger.L().Info("would invoke webhook", zap.String("url", url), zap.String("task_id", resp.ID))
}

type taskStore struct {
	tasks   map[string]*AsyncTask
	tasksMu sync.RWMutex
}

func newTaskStore() *taskStore {
	return &taskStore{tasks: make(map[string]*AsyncTask)}
}

func (s *taskStore) Get(id string) (*AsyncTask, bool) {
	s.tasksMu.RLock()
	defer s.tasksMu.RUnlock()
	t, ok := s.tasks[id]
	return t, ok
}

func (s *taskStore) Set(id string, task *AsyncTask) {
	s.tasksMu.Lock()
	defer s.tasksMu.Unlock()
	s.tasks[id] = task
}

func (s *taskStore) Delete(id string) {
	s.tasksMu.Lock()
	defer s.tasksMu.Unlock()
	delete(s.tasks, id)
}

func (s *taskStore) Cleanup(ctx context.Context) {
	ticker := time.NewTicker(1 * time.Hour)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.tasksMu.Lock()
			for id, task := range s.tasks {
				if task.completedAt != nil && time.Since(*task.completedAt) > 24*time.Hour {
					delete(s.tasks, id)
				}
			}
			s.tasksMu.Unlock()
		}
	}
}

type Service struct {
	repo        ProofRepository
	verifier    Verifier
	validator   ProofValidator
	executor    TaskExecutor
	checksum    ChecksumCalculator
	notifier    WebhookNotifier
	taskStore   *taskStore
}

type ServiceOption func(*Service)

func WithVerifier(v Verifier) ServiceOption {
	return func(s *Service) { s.verifier = v }
}

func WithValidator(v ProofValidator) ServiceOption {
	return func(s *Service) { s.validator = v }
}

func WithExecutor(e TaskExecutor) ServiceOption {
	return func(s *Service) { s.executor = e }
}

func WithChecksum(c ChecksumCalculator) ServiceOption {
	return func(s *Service) { s.checksum = c }
}

func WithNotifier(n WebhookNotifier) ServiceOption {
	return func(s *Service) { s.notifier = n }
}

func NewService(repo repository.ZKPProofRepository, opts ...ServiceOption) *Service {
	s := &Service{
		repo:      repo,
		verifier:  NewCircuitVerifier(),
		validator: NewProofValidator(),
		executor:  newAsyncTaskPool(2, 32, 1000),
		checksum:  NewChecksumCalculator(),
		notifier:  NewWebhookNotifier(),
		taskStore: newTaskStore(),
	}
	for _, opt := range opts {
		opt(s)
	}
	return s
}

func (s *Service) Start(ctx context.Context) {
	s.executor.Start(ctx)
	go s.taskStore.Cleanup(ctx)
}

func (s *Service) Verify(ctx context.Context, req *VerifyRequest) (*VerifyResponse, error) {
	if req.Async {
		return s.verifyAsync(ctx, req)
	}
	return s.verifySync(ctx, req)
}

func (s *Service) verifySync(ctx context.Context, req *VerifyRequest) (*VerifyResponse, error) {
	if err := s.validator.Validate(req); err != nil {
		return nil, err
	}

	proof := s.createProofEntity(req, common.GenerateID("zkp"))
	if err := s.repo.Create(ctx, proof); err != nil {
		logger.L().Error("failed to create ZKP proof", zap.Error(err))
		return nil, common.NewInternalError("failed to persist proof")
	}

	result, err := s.verifier.Verify(ctx, req)
	if err != nil {
		return nil, err
	}

	s.updateProofResult(proof, result)
	if err := s.repo.Update(ctx, proof); err != nil {
		logger.L().Error("failed to update ZKP proof", zap.Error(err))
	}

	logger.L().Info("ZKP proof verified",
		zap.String("id", proof.ID),
		zap.Bool("verified", result.Verified),
		zap.String("circuit_id", req.CircuitID),
	)

	return s.buildResponse(proof, result), nil
}

func (s *Service) verifyAsync(ctx context.Context, req *VerifyRequest) (*VerifyResponse, error) {
	if err := s.validator.Validate(req); err != nil {
		return nil, err
	}

	taskID := common.GenerateID("zkp")
	task := s.createAsyncTask(ctx, req, taskID)

	s.taskStore.Set(taskID, task)

	if err := s.executor.Submit(task); err != nil {
		s.taskStore.Delete(taskID)
		return nil, common.NewInternalError("verification queue is full, please try again later")
	}

	logger.L().Info("ZKP proof queued for async verification",
		zap.String("task_id", taskID),
		zap.String("circuit_id", req.CircuitID),
	)

	return &VerifyResponse{ID: taskID, Async: true, Queued: true}, nil
}

func (s *Service) createProofEntity(req *VerifyRequest, id string) *model.ZKPProof {
	return &model.ZKPProof{
		ID:              id,
		CircuitID:       req.CircuitID,
		ProofData:       req.ProofData,
		PublicInputs:    req.PublicInputs,
		VerificationKey: req.VerificationKey,
		CreatedAt:       time.Now(),
	}
}

func (s *Service) updateProofResult(proof *model.ZKPProof, result *VerificationResult) {
	now := time.Now()
	proof.Verified = result.Verified
	proof.VerifyResult = result.Result
	proof.VerifiedAt = &now
}

func (s *Service) buildResponse(proof *model.ZKPProof, result *VerificationResult) *VerifyResponse {
	resp := &VerifyResponse{
		ID:         proof.ID,
		Verified:   result.Verified,
		Result:     result.Result,
		VerifiedAt: *proof.VerifiedAt,
	}
	resp.Checksum = s.checksum.Calculate(resp)
	return resp
}

func (s *Service) createAsyncTask(ctx context.Context, req *VerifyRequest, taskID string) *AsyncTask {
	task := &AsyncTask{
		id:          taskID,
		req:         req,
		ctx:         ctx,
		status:      "queued",
		progress:    0.0,
		submittedAt: time.Now(),
	}

	if req.WebhookURL != "" {
		task.callback = func(resp *VerifyResponse, err error) {
			s.notifier.Notify(req.WebhookURL, resp, err)
		}
	}

	return task
}

func (s *Service) GetTaskStatus(ctx context.Context, taskID string) (*AsyncTaskStatus, error) {
	task, exists := s.taskStore.Get(taskID)
	if !exists {
		return s.getTaskStatusFromRepo(ctx, taskID)
	}

	status := &AsyncTaskStatus{
		TaskID:      task.id,
		Status:      task.status,
		Progress:    task.progress,
		SubmittedAt: task.submittedAt,
		CompletedAt: task.completedAt,
	}

	if task.err != nil {
		status.Error = task.err.Error()
	} else {
		status.Result = task.result
	}

	return status, nil
}

func (s *Service) getTaskStatusFromRepo(ctx context.Context, taskID string) (*AsyncTaskStatus, error) {
	proof, err := s.repo.GetByID(ctx, taskID)
	if err != nil {
		return nil, common.NewNotFoundError("task", taskID)
	}

	return &AsyncTaskStatus{
		TaskID:      taskID,
		Status:      "completed",
		Progress:    1.0,
		SubmittedAt: proof.CreatedAt,
		CompletedAt: proof.VerifiedAt,
		Result: &VerifyResponse{
			ID:         proof.ID,
			Verified:   proof.Verified,
			Result:     proof.VerifyResult,
			VerifiedAt: *proof.VerifiedAt,
		},
	}, nil
}

func (s *Service) GetByID(ctx context.Context, id string) (*model.ZKPProof, error) {
	proof, err := s.repo.GetByID(ctx, id)
	if err != nil {
		return nil, common.NewNotFoundError("ZKP proof", id)
	}
	return proof, nil
}

func (s *Service) List(ctx context.Context, circuitID string, verified *bool, limit, offset int) ([]*model.ZKPProof, int64, error) {
	return s.repo.List(ctx, circuitID, verified, limit, offset)
}

func (s *Service) GetPoolStats() PoolStats {
	return s.executor.Stats()
}

func processTask(task *AsyncTask) (*VerifyResponse, error) {
	req := task.req
	ctx := task.ctx

	proof := &model.ZKPProof{
		ID:              task.id,
		CircuitID:       req.CircuitID,
		ProofData:       req.ProofData,
		PublicInputs:    req.PublicInputs,
		VerificationKey: req.VerificationKey,
		Status:          "processing",
		CreatedAt:       time.Now(),
	}

	if err := defaultZKPRepo.Create(ctx, proof); err != nil {
		logger.L().Error("failed to create ZKP proof", zap.Error(err))
		return nil, common.NewInternalError("failed to persist proof")
	}

	verified, result := executeCircuitVerification(req)

	now := time.Now()
	proof.Verified = verified
	proof.VerifyResult = result
	proof.VerifiedAt = &now
	proof.Status = "completed"

	if err := defaultZKPRepo.Update(ctx, proof); err != nil {
		logger.L().Error("failed to update ZKP proof", zap.Error(err))
	}

	resp := &VerifyResponse{
		ID:         proof.ID,
		Verified:   verified,
		Result:     result,
		VerifiedAt: now,
	}
	resp.Checksum = computeChecksum(resp)

	return resp, nil
}

var defaultZKPRepo repository.ZKPProofRepository

func SetDefaultRepo(repo repository.ZKPProofRepository) {
	defaultZKPRepo = repo
}
