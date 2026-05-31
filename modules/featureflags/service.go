package featureflags

import (
	"context"
	"crypto/sha256"
	"depguard/cache"
	"depguard/database"
	"depguard/logger"
	"depguard/utils"
	"encoding/binary"
	"fmt"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"math"
	"sort"
	"time"
)

type CacheClient interface {
	Del(ctx context.Context, keys ...string) error
}

type Service struct {
	db    *gorm.DB
	cache CacheClient
}

func NewService() *Service {
	return &Service{
		db:    database.Get(),
		cache: cache.Get(),
	}
}

func NewServiceWithDeps(db *gorm.DB, cache CacheClient) *Service {
	return &Service{
		db:    db,
		cache: cache,
	}
}

func (s *Service) CreateFlag(ctx context.Context, flag *FeatureFlag) (*FeatureFlag, error) {
	flag.ID = utils.GenerateID("ff")
	flag.CreatedAt = time.Now()
	flag.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(flag).Error; err != nil {
		return nil, err
	}

	s.invalidateCache(flag.Key)
	return flag, nil
}

func (s *Service) ListFlags(ctx context.Context) ([]FeatureFlag, error) {
	var flags []FeatureFlag
	if err := s.db.WithContext(ctx).Order("created_at DESC").Find(&flags).Error; err != nil {
		return nil, err
	}
	return flags, nil
}

func (s *Service) GetFlag(ctx context.Context, key string) (*FeatureFlag, error) {
	var flag FeatureFlag
	if err := s.db.WithContext(ctx).First(&flag, "key = ?", key).Error; err != nil {
		return nil, err
	}
	return &flag, nil
}

func (s *Service) UpdateFlag(ctx context.Context, key string, flag *FeatureFlag) (*FeatureFlag, error) {
	flag.UpdatedAt = time.Now()
	if err := s.db.WithContext(ctx).Where("key = ?", key).Save(flag).Error; err != nil {
		return nil, err
	}

	s.invalidateCache(key)
	return flag, nil
}

func (s *Service) DeleteFlag(ctx context.Context, key string) error {
	if err := s.db.WithContext(ctx).Delete(&FeatureFlag{}, "key = ?", key).Error; err != nil {
		return err
	}
	s.invalidateCache(key)
	return nil
}

func (s *Service) CreateSegment(ctx context.Context, segment *UserSegment) (*UserSegment, error) {
	segment.ID = utils.GenerateID("seg")
	segment.CreatedAt = time.Now()
	segment.UpdatedAt = time.Now()
	if err := s.db.WithContext(ctx).Create(segment).Error; err != nil {
		return nil, err
	}
	return segment, nil
}

func (s *Service) ListSegments(ctx context.Context) ([]UserSegment, error) {
	var segments []UserSegment
	if err := s.db.WithContext(ctx).Order("created_at DESC").Find(&segments).Error; err != nil {
		return nil, err
	}
	return segments, nil
}

func (s *Service) Evaluate(ctx context.Context, flagKey string, ctx2 *EvaluationContext) (*EvaluationResult, error) {
	flag, err := s.GetFlag(ctx, flagKey)
	if err != nil {
		return &EvaluationResult{
			Key:     flagKey,
			Enabled: false,
			Reason:  "flag_not_found",
		}, nil
	}

	if !flag.Enabled {
		return &EvaluationResult{
			Key:     flagKey,
			Enabled: false,
			Reason:  "flag_disabled",
		}, nil
	}

	if len(flag.Rules) == 0 {
		return &EvaluationResult{
			Key:     flagKey,
			Enabled: true,
			Reason:  "default_enabled",
		}, nil
	}

	sortedRules := make([]RolloutRule, len(flag.Rules))
	copy(sortedRules, flag.Rules)
	sort.Slice(sortedRules, func(i, j int) bool {
		return sortedRules[i].Priority > sortedRules[j].Priority
	})

	now := time.Now()
	for _, rule := range sortedRules {
		if !rule.Enabled {
			continue
		}
		if rule.StartAt != nil && now.Before(*rule.StartAt) {
			continue
		}
		if rule.EndAt != nil && now.After(*rule.EndAt) {
			continue
		}

		if s.matchesRule(ctx2, &rule) {
			enabled := s.shouldEnable(ctx2, &rule)
			event := &RolloutEvent{
				ID:        utils.GenerateID("evt"),
				FlagKey:   flagKey,
				UserID:    ctx2.UserID,
				Timestamp: time.Now(),
				Value:     rule.Value,
			}
			if enabled {
				event.Variation = "treatment"
			} else {
				event.Variation = "control"
			}
			go s.recordEvent(event)

			return &EvaluationResult{
				Key:     flagKey,
				Enabled: enabled,
				Value:   rule.Value,
				RuleID:  rule.ID,
				Reason:  "matched_rule",
			}, nil
		}
	}

	return &EvaluationResult{
		Key:     flagKey,
		Enabled: false,
		Reason:  "no_matching_rule",
	}, nil
}

func (s *Service) matchesRule(ctx *EvaluationContext, rule *RolloutRule) bool {
	if len(rule.Users) > 0 {
		for _, user := range rule.Users {
			if user == ctx.UserID {
				return true
			}
		}
	}

	if len(rule.Segments) > 0 {
		for _, segmentID := range rule.Segments {
			if s.isInSegment(ctx, segmentID) {
				return true
			}
		}
	}

	if len(rule.Conditions) > 0 {
		allMatch := true
		for _, cond := range rule.Conditions {
			if !s.evaluateCondition(ctx.Attributes, &cond) {
				allMatch = false
				break
			}
		}
		if allMatch {
			return true
		}
	}

	if rule.Type == "percentage" && rule.Percentage > 0 {
		return true
	}

	return false
}

func (s *Service) isInSegment(ctx *EvaluationContext, segmentID string) bool {
	for _, seg := range ctx.Segments {
		if seg == segmentID {
			return true
		}
	}

	var segment UserSegment
	if err := s.db.First(&segment, "id = ?", segmentID).Error; err != nil {
		return false
	}

	for _, uid := range segment.UserIDs {
		if uid == ctx.UserID {
			return true
		}
	}

	if len(segment.Rules) > 0 {
		allMatch := true
		for _, rule := range segment.Rules {
			if !s.evaluateSegmentRule(ctx.Attributes, &rule) {
				allMatch = false
				break
			}
		}
		return allMatch
	}

	return false
}

func (s *Service) shouldEnable(ctx *EvaluationContext, rule *RolloutRule) bool {
	if len(rule.Users) > 0 {
		for _, user := range rule.Users {
			if user == ctx.UserID {
				return true
			}
		}
	}

	if rule.Percentage <= 0 {
		return false
	}
	if rule.Percentage >= 100 {
		return true
	}

	hash := s.computeHash(rule.ID, ctx.UserID)
	normalized := float64(hash) / float64(math.MaxUint64) * 100.0
	return normalized < rule.Percentage
}

func (s *Service) computeHash(ruleID, userID string) uint64 {
	h := sha256.New()
	h.Write([]byte(fmt.Sprintf("%s:%s", ruleID, userID)))
	sum := h.Sum(nil)
	return binary.BigEndian.Uint64(sum[:8])
}

func (s *Service) evaluateCondition(attrs map[string]interface{}, cond *RuleCondition) bool {
	val, ok := attrs[cond.Attribute]
	if !ok {
		return false
	}
	return compareValues(val, cond.Operator, cond.Values)
}

func (s *Service) evaluateSegmentRule(attrs map[string]interface{}, rule *SegmentRule) bool {
	val, ok := attrs[rule.Attribute]
	if !ok {
		return false
	}
	return compareValues(val, rule.Operator, rule.Values)
}

func compareValues(actual interface{}, op string, expected []interface{}) bool {
	switch op {
	case "eq", "equals":
		for _, v := range expected {
			if fmt.Sprintf("%v", actual) == fmt.Sprintf("%v", v) {
				return true
			}
		}
		return false
	case "neq", "not_equals":
		for _, v := range expected {
			if fmt.Sprintf("%v", actual) == fmt.Sprintf("%v", v) {
				return false
			}
		}
		return true
	case "in":
		for _, v := range expected {
			if fmt.Sprintf("%v", actual) == fmt.Sprintf("%v", v) {
				return true
			}
		}
		return false
	case "contains":
		actualStr := fmt.Sprintf("%v", actual)
		for _, v := range expected {
			if contains(actualStr, fmt.Sprintf("%v", v)) {
				return true
			}
		}
		return false
	case "starts_with":
		actualStr := fmt.Sprintf("%v", actual)
		for _, v := range expected {
			if startsWith(actualStr, fmt.Sprintf("%v", v)) {
				return true
			}
		}
		return false
	default:
		return false
	}
}

func contains(haystack, needle string) bool {
	return len(needle) > 0 && len(haystack) >= len(needle) && indexOf(haystack, needle) >= 0
}

func startsWith(str, prefix string) bool {
	return len(prefix) > 0 && len(str) >= len(prefix) && str[:len(prefix)] == prefix
}

func indexOf(haystack, needle string) int {
	for i := 0; i <= len(haystack)-len(needle); i++ {
		if haystack[i:i+len(needle)] == needle {
			return i
		}
	}
	return -1
}

func (s *Service) recordEvent(event *RolloutEvent) {
	if err := s.db.Create(event).Error; err != nil {
		logger.Get().Warn("failed to record rollout event", zap.Error(err))
	}
}

func (s *Service) GetExperimentStats(ctx context.Context, flagKey string) (*ExperimentStats, error) {
	var total int64
	s.db.Model(&RolloutEvent{}).Where("flag_key = ?", flagKey).Count(&total)

	var exposed int64
	s.db.Model(&RolloutEvent{}).Where("flag_key = ?", flagKey).Distinct("user_id").Count(&exposed)

	var control int64
	s.db.Model(&RolloutEvent{}).Where("flag_key = ? AND variation = ?", flagKey, "control").Distinct("user_id").Count(&control)

	var treatment int64
	s.db.Model(&RolloutEvent{}).Where("flag_key = ? AND variation = ?", flagKey, "treatment").Distinct("user_id").Count(&treatment)

	return &ExperimentStats{
		FlagKey:        flagKey,
		TotalUsers:     total,
		ExposedUsers:   exposed,
		ControlUsers:   control,
		TreatmentUsers: treatment,
	}, nil
}

func (s *Service) invalidateCache(key string) {
	_ = s.cache.Del(context.Background(), "ff:"+key)
}

func (s *Service) BatchEvaluate(ctx context.Context, flagKeys []string, evalCtx *EvaluationContext) ([]*EvaluationResult, error) {
	results := make([]*EvaluationResult, 0, len(flagKeys))
	for _, key := range flagKeys {
		result, err := s.Evaluate(ctx, key, evalCtx)
		if err != nil {
			logger.Get().Warn("failed to evaluate flag", zap.String("key", key), zap.Error(err))
			continue
		}
		results = append(results, result)
	}
	return results, nil
}
