package service

import (
	"context"
	"log"
	"sync"
	"time"

	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type SnapshotScheduler struct {
	db           *gorm.DB
	snapshotSvc  *SnapshotService
	ticker       *time.Ticker
	stopCh       chan struct{}
	mu           sync.Mutex
	running      bool
	semaphore    chan struct{}
	enqueueCh    chan uuid.UUID
}

func NewSnapshotScheduler(db *gorm.DB, snapshotSvc *SnapshotService, maxConcurrent int) *SnapshotScheduler {
	if maxConcurrent <= 0 {
		maxConcurrent = 2
	}
	return &SnapshotScheduler{
		db:          db,
		snapshotSvc: snapshotSvc,
		ticker:      time.NewTicker(1 * time.Minute),
		stopCh:      make(chan struct{}),
		semaphore:   make(chan struct{}, maxConcurrent),
		enqueueCh:   make(chan uuid.UUID, 100),
	}
}

func (sch *SnapshotScheduler) Start(ctx context.Context) {
	sch.mu.Lock()
	if sch.running {
		sch.mu.Unlock()
		return
	}
	sch.running = true
	sch.mu.Unlock()

	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case <-sch.stopCh:
				return
			case policyID := <-sch.enqueueCh:
				go sch.executePolicyByID(ctx, policyID)
			case <-sch.ticker.C:
				sch.checkDuePolicies(ctx)
			}
		}
	}()

	log.Printf("[SnapshotScheduler] started with tick interval 1m")
}

func (sch *SnapshotScheduler) Stop() {
	sch.mu.Lock()
	defer sch.mu.Unlock()
	if !sch.running {
		return
	}
	sch.running = false
	sch.ticker.Stop()
	close(sch.stopCh)
	log.Printf("[SnapshotScheduler] stopped")
}

func (sch *SnapshotScheduler) EnqueuePolicy(policyID uuid.UUID) {
	select {
	case sch.enqueueCh <- policyID:
		log.Printf("[SnapshotScheduler] policy %s enqueued", policyID)
	default:
		log.Printf("[SnapshotScheduler] enqueue channel full, dropping policy %s", policyID)
	}
}

func (sch *SnapshotScheduler) checkDuePolicies(ctx context.Context) {
	now := time.Now()
	var policies []*model.SnapshotPolicy

	err := sch.db.WithContext(ctx).
		Where("is_enabled = ? AND next_run_at IS NOT NULL AND next_run_at <= ?", true, now).
		Find(&policies).Error
	if err != nil {
		log.Printf("[SnapshotScheduler] query due policies error: %v", err)
		return
	}

	for _, policy := range policies {
		select {
		case sch.semaphore <- struct{}{}:
			go func(p *model.SnapshotPolicy) {
				defer func() { <-sch.semaphore }()
				sch.executePolicy(ctx, p)
			}(policy)
		default:
			log.Printf("[SnapshotScheduler] max concurrent reached, skipping policy %s", policy.ID)
		}
	}
}

func (sch *SnapshotScheduler) executePolicyByID(ctx context.Context, policyID uuid.UUID) {
	var policy model.SnapshotPolicy
	err := sch.db.WithContext(ctx).Where("id = ?", policyID.String()).First(&policy).Error
	if err != nil {
		log.Printf("[SnapshotScheduler] get policy %s error: %v", policyID, err)
		return
	}
	sch.executePolicy(ctx, &policy)
}

func (sch *SnapshotScheduler) executePolicy(ctx context.Context, policy *model.SnapshotPolicy) {
	log.Printf("[SnapshotScheduler] executing policy %s (space=%s, freq=%s)", policy.ID, policy.SpaceID, policy.Frequency)

	policyCtx := database.WithTenant(ctx, policy.TenantID)

	now := time.Now()
	nextRun := sch.snapshotSvc.CalculateNextRun(policy)

	err := sch.db.WithContext(policyCtx).Model(policy).Updates(map[string]interface{}{
		"last_run_at": now,
		"next_run_at": nextRun,
	}).Error
	if err != nil {
		log.Printf("[SnapshotScheduler] update policy %s run times error: %v", policy.ID, err)
	}

	_, err = sch.snapshotSvc.ExecuteSnapshot(policyCtx, policy)
	if err != nil {
		log.Printf("[SnapshotScheduler] execute snapshot for policy %s error: %v", policy.ID, err)
	} else {
		log.Printf("[SnapshotScheduler] snapshot completed for policy %s", policy.ID)
	}
}
