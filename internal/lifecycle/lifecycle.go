package lifecycle

import (
	"sync"
	"time"

	"github.com/datatransform/platform/pkg/logger"
	"go.uber.org/zap"
)

type DataTier string

const (
	TierHot      DataTier = "hot"
	TierWarm     DataTier = "warm"
	TierCold     DataTier = "cold"
	TierArchived DataTier = "archived"
)

type DataItem struct {
	ID          string
	Data        interface{}
	Tier        DataTier
	LastAccess  time.Time
	CreateTime  time.Time
	SizeBytes   int64
	TTLSeconds  int64
}

type MigrationPolicy struct {
	SourceTier   DataTier
	TargetTier   DataTier
	AgeThreshold time.Duration
	AccessThreshold time.Duration
}

type ArchivePolicy struct {
	ArchiveAfter  time.Duration
	DeleteAfter   time.Duration
	Enabled       bool
}

type DataLifecycleManager struct {
	items         map[string]*DataItem
	tierData      map[DataTier][]*DataItem
	migrationPolicies []*MigrationPolicy
	archivePolicy *ArchivePolicy
	running       bool
	stopChan      chan struct{}
	mu            sync.RWMutex
}

func NewDataLifecycleManager() *DataLifecycleManager {
	return &DataLifecycleManager{
		items:         make(map[string]*DataItem),
		tierData:      make(map[DataTier][]*DataItem),
		migrationPolicies: make([]*MigrationPolicy, 0),
		archivePolicy: &ArchivePolicy{
			ArchiveAfter: 30 * 24 * time.Hour,
			DeleteAfter:  90 * 24 * time.Hour,
			Enabled:      true,
		},
		stopChan: make(chan struct{}),
	}
}

func (d *DataLifecycleManager) AddMigrationPolicy(policy *MigrationPolicy) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.migrationPolicies = append(d.migrationPolicies, policy)
}

func (d *DataLifecycleManager) SetArchivePolicy(policy *ArchivePolicy) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.archivePolicy = policy
}

func (d *DataLifecycleManager) AddItem(item *DataItem) {
	d.mu.Lock()
	defer d.mu.Unlock()

	if item.Tier == "" {
		item.Tier = TierHot
	}

	d.items[item.ID] = item
	d.tierData[item.Tier] = append(d.tierData[item.Tier], item)

	logger.Info("item added to lifecycle manager",
		zap.String("id", item.ID),
		zap.String("tier", string(item.Tier)),
	)
}

func (d *DataLifecycleManager) GetItem(id string) (*DataItem, bool) {
	d.mu.RLock()
	defer d.mu.RUnlock()

	item, exists := d.items[id]
	if exists {
		item.LastAccess = time.Now()
	}
	return item, exists
}

func (d *DataLifecycleManager) RemoveItem(id string) {
	d.mu.Lock()
	defer d.mu.Unlock()

	if item, exists := d.items[id]; exists {
		d.removeFromTier(item.Tier, id)
		delete(d.items, id)
		logger.Info("item removed from lifecycle manager",
			zap.String("id", id),
		)
	}
}

func (d *DataLifecycleManager) removeFromTier(tier DataTier, id string) {
	items := d.tierData[tier]
	for i, item := range items {
		if item.ID == id {
			d.tierData[tier] = append(items[:i], items[i+1:]...)
			break
		}
	}
}

func (d *DataLifecycleManager) MigrateTier(id string, targetTier DataTier) error {
	d.mu.Lock()
	defer d.mu.Unlock()

	item, exists := d.items[id]
	if !exists {
		return nil
	}

	oldTier := item.Tier
	d.removeFromTier(oldTier, id)

	item.Tier = targetTier
	item.LastAccess = time.Now()
	d.tierData[targetTier] = append(d.tierData[targetTier], item)

	logger.Info("item migrated between tiers",
		zap.String("id", id),
		zap.String("from", string(oldTier)),
		zap.String("to", string(targetTier)),
	)

	return nil
}

func (d *DataLifecycleManager) Start() {
	d.mu.Lock()
	if d.running {
		d.mu.Unlock()
		return
	}
	d.running = true
	d.mu.Unlock()

	logger.Info("starting data lifecycle manager")
	go d.lifecycleLoop()
}

func (d *DataLifecycleManager) Stop() {
	d.mu.Lock()
	if !d.running {
		d.mu.Unlock()
		return
	}
	d.running = false
	close(d.stopChan)
	d.mu.Unlock()

	logger.Info("data lifecycle manager stopped")
}

func (d *DataLifecycleManager) lifecycleLoop() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-d.stopChan:
			return
		case <-ticker.C:
			d.processLifecycle()
		}
	}
}

func (d *DataLifecycleManager) processLifecycle() {
	d.processTierMigrations()
	if d.archivePolicy.Enabled {
		d.processArchiving()
		d.processExpiration()
	}
}

func (d *DataLifecycleManager) processTierMigrations() {
	d.mu.Lock()
	defer d.mu.Unlock()

	now := time.Now()

	for _, policy := range d.migrationPolicies {
		items := d.tierData[policy.SourceTier]
		for _, item := range items {
			shouldMigrate := false

			if policy.AgeThreshold > 0 {
				age := now.Sub(item.CreateTime)
				if age > policy.AgeThreshold {
					shouldMigrate = true
				}
			}

			if !shouldMigrate && policy.AccessThreshold > 0 {
				sinceAccess := now.Sub(item.LastAccess)
				if sinceAccess > policy.AccessThreshold {
					shouldMigrate = true
				}
			}

			if shouldMigrate {
				logger.Info("migrating item based on policy",
					zap.String("id", item.ID),
					zap.String("from", string(policy.SourceTier)),
					zap.String("to", string(policy.TargetTier)),
				)
				go d.MigrateTier(item.ID, policy.TargetTier)
			}
		}
	}
}

func (d *DataLifecycleManager) processArchiving() {
	d.mu.RLock()
	itemsToArchive := make([]string, 0)
	now := time.Now()

	for id, item := range d.items {
		age := now.Sub(item.CreateTime)
		if age > d.archivePolicy.ArchiveAfter && item.Tier != TierArchived {
			itemsToArchive = append(itemsToArchive, id)
		}
	}
	d.mu.RUnlock()

	for _, id := range itemsToArchive {
		logger.Info("archiving item", zap.String("id", id))
		d.MigrateTier(id, TierArchived)
	}
}

func (d *DataLifecycleManager) processExpiration() {
	d.mu.RLock()
	itemsToDelete := make([]string, 0)
	now := time.Now()

	for id, item := range d.items {
		if item.TTLSeconds > 0 {
			expiryTime := item.CreateTime.Add(time.Duration(item.TTLSeconds) * time.Second)
			if now.After(expiryTime) {
				itemsToDelete = append(itemsToDelete, id)
			}
		} else {
			age := now.Sub(item.CreateTime)
			if age > d.archivePolicy.DeleteAfter && item.Tier == TierArchived {
				itemsToDelete = append(itemsToDelete, id)
			}
		}
	}
	d.mu.RUnlock()

	for _, id := range itemsToDelete {
		logger.Info("deleting expired item", zap.String("id", id))
		d.RemoveItem(id)
	}
}

func (d *DataLifecycleManager) GetStats() map[DataTier]int {
	d.mu.RLock()
	defer d.mu.RUnlock()

	stats := make(map[DataTier]int)
	for tier, items := range d.tierData {
		stats[tier] = len(items)
	}
	stats[TierHot] += len(d.getTierItems(TierHot))
	stats[TierWarm] += len(d.getTierItems(TierWarm))
	stats[TierCold] += len(d.getTierItems(TierCold))
	stats[TierArchived] += len(d.getTierItems(TierArchived))

	return stats
}

func (d *DataLifecycleManager) getTierItems(tier DataTier) []*DataItem {
	items := make([]*DataItem, 0)
	for _, item := range d.items {
		if item.Tier == tier {
			items = append(items, item)
		}
	}
	return items
}

func (d *DataLifecycleManager) IsRunning() bool {
	d.mu.RLock()
	defer d.mu.RUnlock()
	return d.running
}
