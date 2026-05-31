package multisig

import (
	"container/heap"
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

type CreateProposalRequest struct {
	WalletID      string        `json:"wallet_id"`
	TransactionID string        `json:"transaction_id"`
	Threshold     uint32        `json:"threshold"`
	Signers       []string      `json:"signers"`
	ExpiresAt     *time.Time    `json:"expires_at"`
	Priority      int32         `json:"priority,omitempty"`
}

type ApproveProposalRequest struct {
	ProposalID string `json:"proposal_id"`
	Signer     string `json:"signer"`
	Signature  string `json:"signature"`
}

type SchedulerConfig struct {
	MinWorkers      int
	MaxWorkers      int
	QueueSize       int
	ScaleUpThreshold   float64
	ScaleDownThreshold float64
	ScanInterval    time.Duration
	BatchSize       int
	MaxBatchLatency time.Duration
}

type PriorityLevel int

const (
	PriorityLow    PriorityLevel = 1
	PriorityMedium PriorityLevel = 5
	PriorityHigh   PriorityLevel = 10
	PriorityUrgent PriorityLevel = 100
)

type ProposalJob struct {
	proposalID string
	priority   PriorityLevel
	submittedAt time.Time
	executor   func(ctx context.Context, proposalID string) error
}

type PriorityQueue []*ProposalJob

func (pq PriorityQueue) Len() int { return len(pq) }

func (pq PriorityQueue) Less(i, j int) bool {
	if pq[i].priority != pq[j].priority {
		return pq[i].priority > pq[j].priority
	}
	return pq[i].submittedAt.Before(pq[j].submittedAt)
}

func (pq PriorityQueue) Swap(i, j int) {
	pq[i], pq[j] = pq[j], pq[i]
}

func (pq *PriorityQueue) Push(x interface{}) {
	item := x.(*ProposalJob)
	*pq = append(*pq, item)
}

func (pq *PriorityQueue) Pop() interface{} {
	old := *pq
	n := len(old)
	item := old[n-1]
	old[n-1] = nil
	*pq = old[0 : n-1]
	return item
}

func (pq *PriorityQueue) Peek() *ProposalJob {
	if len(*pq) == 0 {
		return nil
	}
	return (*pq)[0]
}

type SchedulerStats struct {
	Workers         int   `json:"workers"`
	MinWorkers      int   `json:"min_workers"`
	MaxWorkers      int   `json:"max_workers"`
	QueueLength     int   `json:"queue_length"`
	QueueCapacity   int   `json:"queue_capacity"`
	ActiveJobs      int   `json:"active_jobs"`
	ProcessedTotal  int64 `json:"processed_total"`
	FailedTotal     int64 `json:"failed_total"`
	ScaleUpEvents   int64 `json:"scale_up_events"`
	ScaleDownEvents int64 `json:"scale_down_events"`
}

type Scheduler struct {
	config      SchedulerConfig
	pq          PriorityQueue
	pqMu        sync.Mutex
	workers     int
	activeJobs  int
	processed   int64
	failed      int64
	scaleUpEv   int64
	scaleDownEv int64
	jobChan     chan *ProposalJob
	wg          sync.WaitGroup
	cond        *sync.Cond
}

func NewScheduler(config SchedulerConfig) *Scheduler {
	if config.MinWorkers <= 0 {
		config.MinWorkers = 1
	}
	if config.MaxWorkers <= 0 {
		config.MaxWorkers = 10
	}
	if config.QueueSize <= 0 {
		config.QueueSize = 1000
	}
	if config.ScaleUpThreshold <= 0 {
		config.ScaleUpThreshold = 0.7
	}
	if config.ScaleDownThreshold <= 0 {
		config.ScaleDownThreshold = 0.2
	}
	if config.ScanInterval == 0 {
		config.ScanInterval = 5 * time.Second
	}
	if config.BatchSize <= 0 {
		config.BatchSize = 10
	}
	if config.MaxBatchLatency == 0 {
		config.MaxBatchLatency = 500 * time.Millisecond
	}

	s := &Scheduler{
		config:  config,
		pq:      make(PriorityQueue, 0, config.QueueSize),
		workers: config.MinWorkers,
		jobChan: make(chan *ProposalJob, config.QueueSize),
	}
	s.cond = sync.NewCond(&sync.Mutex{})

	heap.Init(&s.pq)
	return s
}

func (s *Scheduler) Start(ctx context.Context) {
	for i := 0; i < s.workers; i++ {
		s.wg.Add(1)
		go s.worker(ctx)
	}

	go s.monitor(ctx)
	go s.scanPendingProposals(ctx)
}

func (s *Scheduler) Submit(job *ProposalJob) error {
	s.pqMu.Lock()
	if s.pq.Len() >= s.config.QueueSize {
		s.pqMu.Unlock()
		return errors.New("scheduler queue is full")
	}
	heap.Push(&s.pq, job)
	s.pqMu.Unlock()
	s.cond.Broadcast()
	return nil
}

func (s *Scheduler) worker(ctx context.Context) {
	defer s.wg.Done()

	for {
		job, err := s.nextJob(ctx)
		if err != nil {
			if err == context.Canceled {
				return
			}
			continue
		}

		s.cond.L.Lock()
		s.activeJobs++
		s.cond.L.Unlock()

		err = job.executor(ctx, job.proposalID)

		s.cond.L.Lock()
		s.activeJobs--
		if err != nil {
			s.failed++
		} else {
			s.processed++
		}
		s.cond.L.Unlock()
	}
}

func (s *Scheduler) nextJob(ctx context.Context) (*ProposalJob, error) {
	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}

		s.pqMu.Lock()
		if s.pq.Len() > 0 {
			job := heap.Pop(&s.pq).(*ProposalJob)
			s.pqMu.Unlock()
			return job, nil
		}
		s.pqMu.Unlock()

		s.cond.L.Lock()
		s.cond.Wait()
		s.cond.L.Unlock()

		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}
	}
}

func (s *Scheduler) monitor(ctx context.Context) {
	ticker := time.NewTicker(s.config.ScanInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.pqMu.Lock()
			queueLen := s.pq.Len()
			s.pqMu.Unlock()

			s.cond.L.Lock()
			active := s.activeJobs
			currentWorkers := s.workers
			s.cond.L.Unlock()

			utilization := float64(active+queueLen) / float64(currentWorkers)

			if utilization > s.config.ScaleUpThreshold && currentWorkers < s.config.MaxWorkers {
				s.cond.L.Lock()
				s.workers++
				s.scaleUpEv++
				s.cond.L.Unlock()
				s.wg.Add(1)
				go s.worker(ctx)
				logger.L().Info("multisig scheduler scaled up",
					zap.Int("workers", s.workers),
					zap.Int("queue_length", queueLen),
					zap.Int("active_jobs", active),
				)
			} else if utilization < s.config.ScaleDownThreshold && currentWorkers > s.config.MinWorkers {
				s.cond.L.Lock()
				s.workers--
				s.scaleDownEv++
				s.cond.L.Unlock()
				s.cond.Broadcast()
				logger.L().Info("multisig scheduler scaled down",
					zap.Int("workers", s.workers),
					zap.Int("queue_length", queueLen),
					zap.Int("active_jobs", active),
				)
			}
		}
	}
}

func (s *Scheduler) scanPendingProposals(ctx context.Context) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			logger.L().Debug("multisig scheduler scanning pending proposals")
		}
	}
}

func (s *Scheduler) Stats() SchedulerStats {
	s.pqMu.Lock()
	queueLen := s.pq.Len()
	s.pqMu.Unlock()

	s.cond.L.Lock()
	defer s.cond.L.Unlock()

	return SchedulerStats{
		Workers:         s.workers,
		MinWorkers:      s.config.MinWorkers,
		MaxWorkers:      s.config.MaxWorkers,
		QueueLength:     queueLen,
		QueueCapacity:   s.config.QueueSize,
		ActiveJobs:      s.activeJobs,
		ProcessedTotal:  s.processed,
		FailedTotal:     s.failed,
		ScaleUpEvents:   s.scaleUpEv,
		ScaleDownEvents: s.scaleDownEv,
	}
}

type Service struct {
	proposalRepo repository.MultisigProposalRepository
	txRepo       repository.TransactionRepository
	scheduler    *Scheduler
}

func NewService(
	proposalRepo repository.MultisigProposalRepository,
	txRepo repository.TransactionRepository,
) *Service {
	return &Service{
		proposalRepo: proposalRepo,
		txRepo:       txRepo,
		scheduler: NewScheduler(SchedulerConfig{
			MinWorkers:          2,
			MaxWorkers:          32,
			QueueSize:           1000,
			ScaleUpThreshold:    0.7,
			ScaleDownThreshold:  0.2,
			ScanInterval:        5 * time.Second,
			BatchSize:           10,
			MaxBatchLatency:     500 * time.Millisecond,
		}),
	}
}

func (s *Service) Start(ctx context.Context) {
	s.scheduler.Start(ctx)
}

func (s *Service) CreateProposal(ctx context.Context, req *CreateProposalRequest) (*model.MultisigProposal, error) {
	if req.Threshold == 0 || req.Threshold > uint32(len(req.Signers)) {
		return nil, common.NewInvalidInputError("invalid threshold")
	}
	if len(req.Signers) == 0 {
		return nil, common.NewInvalidInputError("at least one signer required")
	}

	priority := PriorityMedium
	if req.Priority > 0 {
		priority = PriorityLevel(req.Priority)
	}

	proposal := &model.MultisigProposal{
		ID:            common.GenerateID("msp"),
		WalletID:      req.WalletID,
		TransactionID: req.TransactionID,
		Status:        "pending",
		Threshold:     req.Threshold,
		Signers:       req.Signers,
		ApprovedCount: 0,
		ExpiresAt:     req.ExpiresAt,
		CreatedAt:     time.Now(),
	}

	if err := s.proposalRepo.Create(ctx, proposal); err != nil {
		logger.L().Error("failed to create multisig proposal", zap.Error(err))
		return nil, common.NewInternalError("failed to create proposal")
	}

	logger.L().Info("multisig proposal created",
		zap.String("proposal_id", proposal.ID),
		zap.String("wallet_id", req.WalletID),
		zap.Uint32("threshold", req.Threshold),
		zap.Int32("priority", req.Priority),
	)

	return proposal, nil
}

func (s *Service) ApproveProposal(ctx context.Context, req *ApproveProposalRequest) (*model.MultisigProposal, error) {
	proposal, err := s.proposalRepo.GetByID(ctx, req.ProposalID)
	if err != nil {
		return nil, common.NewNotFoundError("proposal", req.ProposalID)
	}

	if proposal.Status != "pending" {
		return nil, common.NewConflictError(req.ProposalID, "proposal is not in pending state")
	}

	if proposal.ExpiresAt != nil && time.Now().After(*proposal.ExpiresAt) {
		proposal.Status = "expired"
		_ = s.proposalRepo.Update(ctx, proposal)
		return nil, common.NewInvalidInputError("proposal has expired")
	}

	found := false
	for _, signer := range proposal.Signers {
		if signer == req.Signer {
			found = true
			break
		}
	}
	if !found {
		return nil, common.NewInvalidInputError("signer not authorized")
	}

	var sigs []map[string]interface{}
	if len(proposal.Signatures) > 0 {
		_ = json.Unmarshal(proposal.Signatures, &sigs)
	}

	for _, sig := range sigs {
		if sig["signer"] == req.Signer {
			return nil, common.NewConflictError(req.ProposalID, "signer already approved")
		}
	}

	sigs = append(sigs, map[string]interface{}{
		"signer":    req.Signer,
		"signature": req.Signature,
		"approved_at": time.Now().UTC().Format(time.RFC3339),
	})

	sigBytes, _ := json.Marshal(sigs)
	proposal.Signatures = sigBytes
	proposal.ApprovedCount = uint32(len(sigs))

	if proposal.ApprovedCount >= proposal.Threshold {
		proposal.Status = "approved"
		logger.L().Info("multisig proposal threshold reached, scheduling execution",
			zap.String("proposal_id", proposal.ID),
			zap.Uint32("approved", proposal.ApprovedCount),
		)

		job := &ProposalJob{
			proposalID:  proposal.ID,
			priority:    PriorityHigh,
			submittedAt: time.Now(),
			executor:    s.ExecuteProposal,
		}
		if err := s.scheduler.Submit(job); err != nil {
			logger.L().Error("failed to submit proposal to scheduler",
				zap.String("proposal_id", proposal.ID),
				zap.Error(err),
			)
		}
	}

	if err := s.proposalRepo.Update(ctx, proposal); err != nil {
		logger.L().Error("failed to update proposal", zap.Error(err))
		return nil, common.NewInternalError("failed to approve proposal")
	}

	return proposal, nil
}

func (s *Service) ExecuteProposal(ctx context.Context, proposalID string) error {
	proposal, err := s.proposalRepo.GetByID(ctx, proposalID)
	if err != nil {
		return common.NewNotFoundError("proposal", proposalID)
	}

	if proposal.Status != "approved" {
		return common.NewInvalidInputError("proposal not approved")
	}

	tx, err := s.txRepo.GetByID(ctx, proposal.TransactionID)
	if err != nil {
		return common.NewNotFoundError("transaction", proposal.TransactionID)
	}

	tx.Signatures = proposal.Signatures
	tx.Status = "ready"
	if err := s.txRepo.Update(ctx, tx); err != nil {
		logger.L().Error("failed to update transaction", zap.Error(err))
		return common.NewInternalError("failed to execute proposal")
	}

	now := time.Now()
	proposal.ExecutedAt = &now
	proposal.Status = "executed"
	_ = s.proposalRepo.Update(ctx, proposal)

	logger.L().Info("multisig proposal executed",
		zap.String("proposal_id", proposalID),
		zap.String("tx_id", proposal.TransactionID),
	)

	return nil
}

func (s *Service) ProcessReadyProposals(ctx context.Context) error {
	proposals, err := s.proposalRepo.ListReadyToExecute(ctx)
	if err != nil {
		return err
	}

	count := 0
	for _, p := range proposals {
		if p.ApprovedCount >= p.Threshold && p.Status == "approved" {
			job := &ProposalJob{
				proposalID:  p.ID,
				priority:    PriorityMedium,
				submittedAt: time.Now(),
				executor:    s.ExecuteProposal,
			}
			if submitErr := s.scheduler.Submit(job); submitErr == nil {
				count++
			}
		}
	}

	logger.L().Info("multisig ready proposals processed",
		zap.Int("scheduled_count", count),
	)

	return nil
}

func (s *Service) GetByID(ctx context.Context, id string) (*model.MultisigProposal, error) {
	proposal, err := s.proposalRepo.GetByID(ctx, id)
	if err != nil {
		return nil, common.NewNotFoundError("proposal", id)
	}
	return proposal, nil
}

func (s *Service) ListByWalletID(ctx context.Context, walletID, status string) ([]*model.MultisigProposal, error) {
	return s.proposalRepo.ListByWalletID(ctx, walletID, status)
}

func (s *Service) GetSchedulerStats() SchedulerStats {
	return s.scheduler.Stats()
}
