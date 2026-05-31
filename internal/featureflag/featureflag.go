package featureflag

import (
	"fmt"
	"github.com/solocoder/tasktracker/internal/logger"
	"hash/fnv"
	"math/rand"
	"sync"
	"time"
)

type FlagStatus string

const (
	StatusEnabled  FlagStatus = "enabled"
	StatusDisabled FlagStatus = "disabled"
	StatusRollout  FlagStatus = "rollout"
)

type UserSegment struct {
	ID         string                 `json:"id"`
	Name       string                 `json:"name"`
	Conditions map[string]interface{} `json:"conditions"`
}

type FlagRule struct {
	ID          string                 `json:"id"`
	FlagID      string                 `json:"flag_id"`
	SegmentID   string                 `json:"segment_id"`
	Percentage  int                    `json:"percentage"`
	Value       interface{}            `json:"value"`
	StartTime   *time.Time             `json:"start_time,omitempty"`
	EndTime     *time.Time             `json:"end_time,omitempty"`
	Priority    int                    `json:"priority"`
}

type FeatureFlag struct {
	ID          string                 `json:"id"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	Status      FlagStatus             `json:"status"`
	DefaultValue interface{}            `json:"default_value"`
	ValueType   string                 `json:"value_type"`
	Tags        []string               `json:"tags"`
	CreatedAt   time.Time              `json:"created_at"`
	UpdatedAt   time.Time              `json:"updated_at"`
	Rules       []FlagRule             `json:"rules,omitempty"`
	Metadata    map[string]interface{} `json:"metadata,omitempty"`
}

type UserContext struct {
	UserID     string                 `json:"user_id"`
	Email      string                 `json:"email"`
	Attributes map[string]interface{} `json:"attributes"`
}

type EvaluationResult struct {
	FlagID   string      `json:"flag_id"`
	Value    interface{} `json:"value"`
	Enabled  bool        `json:"enabled"`
	RuleID   string      `json:"rule_id,omitempty"`
	Segment  string      `json:"segment,omitempty"`
	Reason   string      `json:"reason"`
}

type FeatureFlagManager struct {
	mu         sync.RWMutex
	flags      map[string]*FeatureFlag
	segments   map[string]*UserSegment
	evaluations map[string]int64
}

func NewFeatureFlagManager() *FeatureFlagManager {
	return &FeatureFlagManager{
		flags:       make(map[string]*FeatureFlag),
		segments:    make(map[string]*UserSegment),
		evaluations: make(map[string]int64),
	}
}

func (fm *FeatureFlagManager) CreateFlag(flag *FeatureFlag) error {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	if _, exists := fm.flags[flag.ID]; exists {
		return fmt.Errorf("flag already exists: %s", flag.ID)
	}

	now := time.Now()
	flag.CreatedAt = now
	flag.UpdatedAt = now

	fm.flags[flag.ID] = flag
	logger.Info("Feature flag created", logger.String("flag_id", flag.ID), logger.String("name", flag.Name))
	return nil
}

func (fm *FeatureFlagManager) UpdateFlag(flag *FeatureFlag) error {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	if _, exists := fm.flags[flag.ID]; !exists {
		return fmt.Errorf("flag not found: %s", flag.ID)
	}

	flag.UpdatedAt = time.Now()
	fm.flags[flag.ID] = flag
	logger.Info("Feature flag updated", logger.String("flag_id", flag.ID))
	return nil
}

func (fm *FeatureFlagManager) DeleteFlag(flagID string) error {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	if _, exists := fm.flags[flagID]; !exists {
		return fmt.Errorf("flag not found: %s", flagID)
	}

	delete(fm.flags, flagID)
	logger.Info("Feature flag deleted", logger.String("flag_id", flagID))
	return nil
}

func (fm *FeatureFlagManager) GetFlag(flagID string) (*FeatureFlag, error) {
	fm.mu.RLock()
	defer fm.mu.RUnlock()

	flag, exists := fm.flags[flagID]
	if !exists {
		return nil, fmt.Errorf("flag not found: %s", flagID)
	}
	return flag, nil
}

func (fm *FeatureFlagManager) ListFlags(tags []string) []*FeatureFlag {
	fm.mu.RLock()
	defer fm.mu.RUnlock()

	result := make([]*FeatureFlag, 0)
	for _, flag := range fm.flags {
		if len(tags) > 0 {
			matched := false
			for _, t := range tags {
				for _, ft := range flag.Tags {
					if t == ft {
						matched = true
						break
					}
				}
				if matched {
					break
				}
			}
			if !matched {
				continue
			}
		}
		result = append(result, flag)
	}
	return result
}

func (fm *FeatureFlagManager) AddSegment(segment *UserSegment) {
	fm.mu.Lock()
	defer fm.mu.Unlock()
	fm.segments[segment.ID] = segment
	logger.Info("User segment added", logger.String("segment_id", segment.ID), logger.String("name", segment.Name))
}

func (fm *FeatureFlagManager) RemoveSegment(segmentID string) {
	fm.mu.Lock()
	defer fm.mu.Unlock()
	delete(fm.segments, segmentID)
	logger.Info("User segment removed", logger.String("segment_id", segmentID))
}

func (fm *FeatureFlagManager) GetSegment(segmentID string) (*UserSegment, error) {
	fm.mu.RLock()
	defer fm.mu.RUnlock()

	segment, exists := fm.segments[segmentID]
	if !exists {
		return nil, fmt.Errorf("segment not found: %s", segmentID)
	}
	return segment, nil
}

func (fm *FeatureFlagManager) ListSegments() []*UserSegment {
	fm.mu.RLock()
	defer fm.mu.RUnlock()

	result := make([]*UserSegment, 0, len(fm.segments))
	for _, seg := range fm.segments {
		result = append(result, seg)
	}
	return result
}

func (fm *FeatureFlagManager) Evaluate(flagID string, user *UserContext) *EvaluationResult {
	fm.mu.RLock()
	flag, exists := fm.flags[flagID]
	fm.mu.RUnlock()

	if !exists {
		return &EvaluationResult{
			FlagID:  flagID,
			Value:   nil,
			Enabled: false,
			Reason:  "flag_not_found",
		}
	}

	fm.recordEvaluation(flagID)

	switch flag.Status {
	case StatusDisabled:
		return &EvaluationResult{
			FlagID:  flagID,
			Value:   flag.DefaultValue,
			Enabled: false,
			Reason:  "flag_disabled",
		}
	case StatusEnabled:
		return &EvaluationResult{
			FlagID:  flagID,
			Value:   flag.DefaultValue,
			Enabled: true,
			Reason:  "flag_enabled",
		}
	}

	return fm.evaluateRollout(flag, user)
}

func (fm *FeatureFlagManager) evaluateRollout(flag *FeatureFlag, user *UserContext) *EvaluationResult {
	now := time.Now()

	for _, rule := range flag.Rules {
		if rule.StartTime != nil && rule.StartTime.After(now) {
			continue
		}
		if rule.EndTime != nil && rule.EndTime.Before(now) {
			continue
		}

		if rule.SegmentID != "" && user != nil {
			if !fm.isUserInSegment(user, rule.SegmentID) {
				continue
			}
		}

		if rule.Percentage > 0 {
			if user != nil && user.UserID != "" {
				if !fm.isUserInPercentage(user.UserID, flag.ID, rule.Percentage) {
					continue
				}
			} else {
				if rand.Intn(100) >= rule.Percentage {
					continue
				}
			}
		}

		return &EvaluationResult{
			FlagID:  flag.ID,
			Value:   rule.Value,
			Enabled: true,
			RuleID:  rule.ID,
			Segment: rule.SegmentID,
			Reason:  "rule_matched",
		}
	}

	return &EvaluationResult{
		FlagID:  flag.ID,
		Value:   flag.DefaultValue,
		Enabled: false,
		Reason:  "no_rule_matched",
	}
}

func (fm *FeatureFlagManager) isUserInSegment(user *UserContext, segmentID string) bool {
	segment, exists := fm.segments[segmentID]
	if !exists {
		return false
	}

	for key, condition := range segment.Conditions {
		userVal, ok := user.Attributes[key]
		if !ok {
			return false
		}

		switch cond := condition.(type) {
		case []interface{}:
			found := false
			for _, v := range cond {
				if fmt.Sprintf("%v", userVal) == fmt.Sprintf("%v", v) {
					found = true
					break
				}
			}
			if !found {
				return false
			}
		default:
			if fmt.Sprintf("%v", userVal) != fmt.Sprintf("%v", cond) {
				return false
			}
		}
	}

	return true
}

func (fm *FeatureFlagManager) isUserInPercentage(userID, flagID string, percentage int) bool {
	if percentage >= 100 {
		return true
	}
	if percentage <= 0 {
		return false
	}

	hash := fm.hash(fmt.Sprintf("%s:%s", flagID, userID))
	bucket := hash % 100
	return bucket < uint32(percentage)
}

func (fm *FeatureFlagManager) hash(s string) uint32 {
	h := fnv.New32a()
	h.Write([]byte(s))
	return h.Sum32()
}

func (fm *FeatureFlagManager) recordEvaluation(flagID string) {
	fm.mu.Lock()
	defer fm.mu.Unlock()
	fm.evaluations[flagID]++
}

func (fm *FeatureFlagManager) GetEvaluationStats() map[string]int64 {
	fm.mu.RLock()
	defer fm.mu.RUnlock()

	stats := make(map[string]int64)
	for k, v := range fm.evaluations {
		stats[k] = v
	}
	return stats
}

func (fm *FeatureFlagManager) SetFlagStatus(flagID string, status FlagStatus) error {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	flag, exists := fm.flags[flagID]
	if !exists {
		return fmt.Errorf("flag not found: %s", flagID)
	}

	flag.Status = status
	flag.UpdatedAt = time.Now()
	logger.Info("Flag status updated", logger.String("flag_id", flagID), logger.String("status", string(status)))
	return nil
}

func (fm *FeatureFlagManager) AddRule(flagID string, rule *FlagRule) error {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	flag, exists := fm.flags[flagID]
	if !exists {
		return fmt.Errorf("flag not found: %s", flagID)
	}

	rule.ID = fmt.Sprintf("rule_%d", time.Now().UnixNano())
	rule.FlagID = flagID
	flag.Rules = append(flag.Rules, *rule)
	flag.UpdatedAt = time.Now()

	logger.Info("Rule added to flag", logger.String("flag_id", flagID), logger.String("rule_id", rule.ID))
	return nil
}

func (fm *FeatureFlagManager) RemoveRule(flagID, ruleID string) error {
	fm.mu.Lock()
	defer fm.mu.Unlock()

	flag, exists := fm.flags[flagID]
	if !exists {
		return fmt.Errorf("flag not found: %s", flagID)
	}

	newRules := make([]FlagRule, 0)
	for _, r := range flag.Rules {
		if r.ID != ruleID {
			newRules = append(newRules, r)
		}
	}
	flag.Rules = newRules
	flag.UpdatedAt = time.Now()

	logger.Info("Rule removed from flag", logger.String("flag_id", flagID), logger.String("rule_id", ruleID))
	return nil
}

func (fm *FeatureFlagManager) GetBoolean(flagID string, user *UserContext, defaultValue bool) bool {
	result := fm.Evaluate(flagID, user)
	if result.Value == nil {
		return defaultValue
	}
	if b, ok := result.Value.(bool); ok {
		return b
	}
	return defaultValue
}

func (fm *FeatureFlagManager) GetString(flagID string, user *UserContext, defaultValue string) string {
	result := fm.Evaluate(flagID, user)
	if result.Value == nil {
		return defaultValue
	}
	if s, ok := result.Value.(string); ok {
		return s
	}
	return defaultValue
}

func (fm *FeatureFlagManager) GetInt(flagID string, user *UserContext, defaultValue int) int {
	result := fm.Evaluate(flagID, user)
	if result.Value == nil {
		return defaultValue
	}
	switch v := result.Value.(type) {
	case int:
		return v
	case float64:
		return int(v)
	}
	return defaultValue
}
