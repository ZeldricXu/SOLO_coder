package suppression

import (
	"context"
	"fmt"
	"regexp"
	"sync"
	"time"

	"notificationplatform/config"
	"notificationplatform/internal/common/cache"
	"notificationplatform/internal/common/database"
	"notificationplatform/internal/common/logger"
	"notificationplatform/internal/common/models"
	"notificationplatform/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type CheckResult struct {
	Suppressed      bool
	SuppressionType string
	Reason          string
	RuleID          string
}

type Manager struct {
	db          *gorm.DB
	cache       *cache.LocalCache
	rules       map[string]*models.SuppressionRule
	rulesMu     sync.RWMutex
	windowCache *cache.LocalCache
	rateCache   *cache.LocalCache
	dedupCache  *cache.LocalCache
}

var (
	instance *Manager
	once     sync.Once
)

func NewManager() *Manager {
	once.Do(func() {
		instance = &Manager{
			db:          database.GetDB(),
			cache:       cache.NewLocalCache(),
			rules:       make(map[string]*models.SuppressionRule),
			windowCache: cache.NewLocalCache(),
			rateCache:   cache.NewLocalCache(),
			dedupCache:  cache.NewLocalCache(),
		}
		instance.loadRules()
		go instance.cleanupLoop()
	})
	return instance
}

func (m *Manager) loadRules() {
	if m.db == nil {
		m.initDefaultRules()
		return
	}

	var rules []models.SuppressionRule
	if err := m.db.Where("enabled = ?", true).Find(&rules).Error; err != nil {
		logger.Get().Warn("failed to load suppression rules from DB", zap.Error(err))
		m.initDefaultRules()
		return
	}

	m.rulesMu.Lock()
	defer m.rulesMu.Unlock()

	m.rules = make(map[string]*models.SuppressionRule)
	for i := range rules {
		m.rules[rules[i].ID] = &rules[i]
	}

	if len(m.rules) == 0 {
		m.initDefaultRules()
	}
}

func (m *Manager) initDefaultRules() {
	defaultRules := []*models.SuppressionRule{
		{
			ID:              "rule_dedup_default",
			Name:            "Default Deduplication",
			Type:            string(models.SuppressionDedup),
			NotificationType: ".*",
			Channel:         ".*",
			DedupKeyPattern: "{{.Type}}_{{.Channel}}_{{.Recipient}}_{{.Content}}",
			WindowSeconds:   300,
			MaxCount:        1,
			Enabled:         true,
			CreatedAt:       time.Now(),
			UpdatedAt:       time.Now(),
		},
		{
			ID:              "rule_window_high",
			Name:            "High Priority Window Suppression",
			Type:            string(models.SuppressionWindow),
			NotificationType: ".*",
			Channel:         ".*",
			WindowSeconds:   60,
			MaxCount:        10,
			Enabled:         true,
			CreatedAt:       time.Now(),
			UpdatedAt:       time.Now(),
		},
		{
			ID:              "rule_ratelimit_email",
			Name:            "Email Rate Limit",
			Type:            string(models.SuppressionRateLimit),
			NotificationType: ".*",
			Channel:         "email",
			WindowSeconds:   60,
			MaxCount:        100,
			Enabled:         true,
			CreatedAt:       time.Now(),
			UpdatedAt:       time.Now(),
		},
		{
			ID:              "rule_ratelimit_sms",
			Name:            "SMS Rate Limit",
			Type:            string(models.SuppressionRateLimit),
			NotificationType: ".*",
			Channel:         "sms",
			WindowSeconds:   60,
			MaxCount:        50,
			Enabled:         true,
			CreatedAt:       time.Now(),
			UpdatedAt:       time.Now(),
		},
	}

	m.rulesMu.Lock()
	defer m.rulesMu.Unlock()

	for _, rule := range defaultRules {
		m.rules[rule.ID] = rule
	}
}

func (m *Manager) Check(ctx context.Context, notification *models.NotificationRecord) (*CheckResult, error) {
	log := logger.FromContext(ctx)

	if notification.Priority >= int(models.PriorityHigh) {
		log.Debug("high priority notification bypassing suppression",
			zap.Int("priority", notification.Priority),
		)
		return &CheckResult{Suppressed: false}, nil
	}

	m.rulesMu.RLock()
	rules := make([]*models.SuppressionRule, 0, len(m.rules))
	for _, rule := range m.rules {
		if rule.Enabled && m.matchesRule(rule, notification) {
			rules = append(rules, rule)
		}
	}
	m.rulesMu.RUnlock()

	for _, rule := range rules {
		result, err := m.checkRule(ctx, rule, notification)
		if err != nil {
			log.Warn("failed to check suppression rule",
				zap.String("rule_id", rule.ID),
				zap.Error(err),
			)
			continue
		}
		if result.Suppressed {
			log.Info("notification suppressed",
				zap.String("rule_id", rule.ID),
				zap.String("rule_type", rule.Type),
				zap.String("reason", result.Reason),
			)
			return result, nil
		}
	}

	return &CheckResult{Suppressed: false}, nil
}

func (m *Manager) matchesRule(rule *models.SuppressionRule, notification *models.NotificationRecord) bool {
	if !rule.Enabled {
		return false
	}

	typeMatch, _ := regexp.MatchString(rule.NotificationType, notification.Type)
	channelMatch, _ := regexp.MatchString(rule.Channel, notification.Channel)

	return typeMatch && channelMatch
}

func (m *Manager) checkRule(ctx context.Context, rule *models.SuppressionRule, notification *models.NotificationRecord) (*CheckResult, error) {
	switch models.SuppressionType(rule.Type) {
	case models.SuppressionDedup:
		return m.checkDedup(ctx, rule, notification)
	case models.SuppressionWindow:
		return m.checkWindow(ctx, rule, notification)
	case models.SuppressionRateLimit:
		return m.checkRateLimit(ctx, rule, notification)
	default:
		return &CheckResult{Suppressed: false}, nil
	}
}

func (m *Manager) checkDedup(ctx context.Context, rule *models.SuppressionRule, notification *models.NotificationRecord) (*CheckResult, error) {
	dedupKey := notification.DedupKey
	if dedupKey == "" {
		dedupKey = utils.GenerateDedupKey(notification.Type, notification.Channel, notification.Recipient, notification.Content)
	}

	cacheKey := fmt.Sprintf("dedup:%s", dedupKey)
	_, exists := m.dedupCache.Get(cacheKey)
	if exists {
		return &CheckResult{
			Suppressed:      true,
			SuppressionType: string(models.SuppressionDedup),
			Reason:          fmt.Sprintf("duplicate notification within %d seconds window", rule.WindowSeconds),
			RuleID:          rule.ID,
		}, nil
	}

	return &CheckResult{Suppressed: false}, nil
}

func (m *Manager) checkWindow(ctx context.Context, rule *models.SuppressionRule, notification *models.NotificationRecord) (*CheckResult, error) {
	windowKey := fmt.Sprintf("window:%s:%s:%s", rule.ID, notification.Channel, notification.Recipient)
	window := time.Duration(rule.WindowSeconds) * time.Second

	countVal, exists := m.windowCache.Get(windowKey)
	var count int
	if exists {
		count = countVal.(int)
	}

	count++

	if count > rule.MaxCount {
		return &CheckResult{
			Suppressed:      true,
			SuppressionType: string(models.SuppressionWindow),
			Reason:          fmt.Sprintf("exceeded %d notifications in %d seconds window", rule.MaxCount, rule.WindowSeconds),
			RuleID:          rule.ID,
		}, nil
	}

	m.windowCache.Set(windowKey, count, window)
	return &CheckResult{Suppressed: false}, nil
}

func (m *Manager) checkRateLimit(ctx context.Context, rule *models.SuppressionRule, notification *models.NotificationRecord) (*CheckResult, error) {
	rateKey := fmt.Sprintf("ratelimit:%s:%s", rule.ID, notification.Channel)
	window := time.Duration(rule.WindowSeconds) * time.Second

	countVal, exists := m.rateCache.Get(rateKey)
	var count int
	if exists {
		count = countVal.(int)
	}

	count++

	if count > rule.MaxCount {
		return &CheckResult{
			Suppressed:      true,
			SuppressionType: string(models.SuppressionRateLimit),
			Reason:          fmt.Sprintf("channel rate limit exceeded: %d/%ds", rule.MaxCount, rule.WindowSeconds),
			RuleID:          rule.ID,
		}, nil
	}

	m.rateCache.Set(rateKey, count, window)
	return &CheckResult{Suppressed: false}, nil
}

func (m *Manager) RecordSent(ctx context.Context, notification *models.NotificationRecord) {
	dedupKey := notification.DedupKey
	if dedupKey == "" {
		dedupKey = utils.GenerateDedupKey(notification.Type, notification.Channel, notification.Recipient, notification.Content)
	}

	cacheKey := fmt.Sprintf("dedup:%s", dedupKey)
	m.dedupCache.Set(cacheKey, true, config.DefaultSuppressionWindow)
}

func (m *Manager) AddRule(ctx context.Context, rule *models.SuppressionRule) error {
	m.rulesMu.Lock()
	defer m.rulesMu.Unlock()

	rule.ID = utils.NewID("rule")
	rule.CreatedAt = time.Now()
	rule.UpdatedAt = time.Now()

	if m.db != nil {
		if err := m.db.Create(rule).Error; err != nil {
			return err
		}
	}

	m.rules[rule.ID] = rule
	return nil
}

func (m *Manager) UpdateRule(ctx context.Context, id string, rule *models.SuppressionRule) error {
	m.rulesMu.Lock()
	defer m.rulesMu.Unlock()

	existing, exists := m.rules[id]
	if !exists {
		return fmt.Errorf("rule not found: %s", id)
	}

	rule.ID = id
	rule.CreatedAt = existing.CreatedAt
	rule.UpdatedAt = time.Now()

	if m.db != nil {
		if err := m.db.Save(rule).Error; err != nil {
			return err
		}
	}

	m.rules[id] = rule
	return nil
}

func (m *Manager) DeleteRule(ctx context.Context, id string) error {
	m.rulesMu.Lock()
	defer m.rulesMu.Unlock()

	if _, exists := m.rules[id]; !exists {
		return fmt.Errorf("rule not found: %s", id)
	}

	if m.db != nil {
		if err := m.db.Delete(&models.SuppressionRule{}, "id = ?", id).Error; err != nil {
			return err
		}
	}

	delete(m.rules, id)
	return nil
}

func (m *Manager) GetRules(ctx context.Context) []*models.SuppressionRule {
	m.rulesMu.RLock()
	defer m.rulesMu.RUnlock()

	rules := make([]*models.SuppressionRule, 0, len(m.rules))
	for _, rule := range m.rules {
		rules = append(rules, rule)
	}
	return rules
}

func (m *Manager) GetRule(ctx context.Context, id string) (*models.SuppressionRule, error) {
	m.rulesMu.RLock()
	defer m.rulesMu.RUnlock()

	rule, exists := m.rules[id]
	if !exists {
		return nil, fmt.Errorf("rule not found: %s", id)
	}
	return rule, nil
}

func (m *Manager) ResetCounters() {
	m.dedupCache = cache.NewLocalCache()
	m.windowCache = cache.NewLocalCache()
	m.rateCache = cache.NewLocalCache()
}

func (m *Manager) cleanupLoop() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		m.dedupCache.Cleanup()
		m.windowCache.Cleanup()
		m.rateCache.Cleanup()
		m.cache.Cleanup()
	}
}
