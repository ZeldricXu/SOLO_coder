package storage

import (
	"context"
	"sync"
	"time"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/database"
	"github.com/solocoder/task-scheduler/internal/logging"
)

type RetentionPolicy struct {
	db          *database.Database
	providerReg contracts.ProviderRegistry
	ticker      *time.Ticker
	stopCh      chan struct{}
	wg          sync.WaitGroup
	running     bool
	mu          sync.Mutex
}

func NewRetentionPolicy(db *database.Database, providerReg contracts.ProviderRegistry) *RetentionPolicy {
	return &RetentionPolicy{
		db:          db,
		providerReg: providerReg,
		stopCh:      make(chan struct{}),
	}
}

func (r *RetentionPolicy) Start(ctx context.Context) {
	r.mu.Lock()
	if r.running {
		r.mu.Unlock()
		return
	}
	r.running = true
	r.ticker = time.NewTicker(24 * time.Hour)
	r.mu.Unlock()

	r.wg.Add(1)
	go func() {
		defer r.wg.Done()
		for {
			select {
			case <-r.ticker.C:
				r.CleanupExpired(ctx)
			case <-r.stopCh:
				return
			}
		}
	}()

	logging.Info(ctx, "Retention policy started")
}

func (r *RetentionPolicy) Stop() {
	r.mu.Lock()
	if !r.running {
		r.mu.Unlock()
		return
	}
	r.running = false
	if r.ticker != nil {
		r.ticker.Stop()
	}
	close(r.stopCh)
	r.mu.Unlock()

	r.wg.Wait()
	logging.Info(context.Background(), "Retention policy stopped")
}

func (r *RetentionPolicy) CleanupExpired(ctx context.Context) {
	now := time.Now()

	var expired []contracts.BackupRecord
	err := r.db.DB.Where("expires_at < ? AND status = ?", now, contracts.BackupStatusCompleted).
		Find(&expired).Error

	if err != nil {
		logging.Error(ctx, "Failed to query expired backups", zap.Error(err))
		return
	}

	for _, record := range expired {
		logging.Info(ctx, "Cleaning up expired backup", zap.String("backup_id", record.ID))

		providerName := "local"
		if p, ok := record.Metadata["provider"].(string); ok {
			providerName = p
		}

		if provider, err := r.providerReg.GetProvider(providerName); err == nil {
			_ = provider.Delete(ctx, record.Destination)
		}

		_ = r.db.DB.Delete(&record).Error
	}
}
