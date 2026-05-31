package dataclassify

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/apishield/apishield/internal/core/ports"
	"github.com/google/uuid"
)

var (
	ErrClassificationNotFound = errors.New("classification not found")
	ErrInvalidPolicy          = errors.New("invalid classification policy")
	ErrInvalidContent         = errors.New("invalid content")
)

var (
	phoneRegex     = regexp.MustCompile(`1[3-9]\d{9}`)
	emailRegex     = regexp.MustCompile(`[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}`)
	idCardRegex    = regexp.MustCompile(`[1-9]\d{5}(19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]`)
	bankCardRegex  = regexp.MustCompile(`\d{16,19}`)
)

type patternConfig struct {
	regex    *regexp.Regexp
	dataType ports.SensitiveDataType
	weight   float64
}

type classifierService struct {
	classifications  map[string]*ports.DataClassification
	policies         map[string]*ports.ClassificationPolicy
	patterns         []patternConfig
	logger           ports.Logger
	eventBus         ports.EventPublisher
	mu               sync.RWMutex
}

func NewDataClassifierService(logger ports.Logger, eventBus ports.EventPublisher) ports.DataClassifierPort {
	patterns := []patternConfig{
		{regex: phoneRegex, dataType: ports.SensitivePhone, weight: 0.8},
		{regex: emailRegex, dataType: ports.SensitiveEmail, weight: 0.6},
		{regex: idCardRegex, dataType: ports.SensitiveIDCard, weight: 1.0},
		{regex: bankCardRegex, dataType: ports.SensitiveBankCard, weight: 0.9},
	}

	return &classifierService{
		classifications: make(map[string]*ports.DataClassification),
		policies:        make(map[string]*ports.ClassificationPolicy),
		patterns:        patterns,
		logger:          logger,
		eventBus:        eventBus,
	}
}

func (s *classifierService) ScanData(ctx context.Context, req *ports.DataScanRequest) (*ports.DataClassification, error) {
	if req == nil {
		return nil, errors.New("scan request is nil")
	}
	if req.Content == "" {
		return nil, ErrInvalidContent
	}

	s.logger.Debug(ctx, "Starting data scan", map[string]any{
		"data_id":   req.DataID,
		"deep_scan": req.DeepScan,
	})

	matches := s.detectSensitiveData(req.Content)
	level := s.determineLevel(matches)
	confidence := s.calculateConfidence(matches)

	dataID := req.DataID
	if dataID == "" {
		dataID = uuid.New().String()
	}

	classification := &ports.DataClassification{
		DataID:     dataID,
		Level:      level,
		Matches:    matches,
		ScanTime:   time.Now().Unix(),
		Confidence: confidence,
	}

	s.mu.Lock()
	s.classifications[dataID] = classification
	s.mu.Unlock()

	s.publishEvent(ctx, "data.scanned", dataID, map[string]interface{}{
		"level":       level,
		"match_count": len(matches),
		"confidence":  confidence,
	})

	s.logger.Info(ctx, "Data scan completed", map[string]any{
		"data_id":      dataID,
		"level":        level,
		"match_count":  len(matches),
		"confidence":   confidence,
	})

	return classification, nil
}

func (s *classifierService) Classify(ctx context.Context, dataID string, content string) (*ports.DataClassification, error) {
	if content == "" {
		return nil, ErrInvalidContent
	}

	return s.ScanData(ctx, &ports.DataScanRequest{
		DataID:   dataID,
		Content:  content,
		DeepScan: true,
	})
}

func (s *classifierService) ApplyPolicy(ctx context.Context, classification *ports.DataClassification, policy *ports.ClassificationPolicy) error {
	if classification == nil {
		return errors.New("classification is nil")
	}
	if policy == nil {
		return errors.New("policy is nil")
	}

	if err := s.validatePolicy(policy); err != nil {
		return err
	}

	if !policy.IsActive {
		return fmt.Errorf("%w: policy is inactive", ErrInvalidPolicy)
	}

	s.mu.RLock()
	existing, exists := s.classifications[classification.DataID]
	s.mu.RUnlock()

	if !exists {
		return ErrClassificationNotFound
	}

	levelPriority := map[ports.DataLevel]int{
		ports.LevelPublic:       0,
		ports.LevelInternal:     1,
		ports.LevelConfidential: 2,
		ports.LevelTopSecret:    3,
	}

	classLevel := levelPriority[existing.Level]
	policyLevel := levelPriority[policy.Level]

	if classLevel >= policyLevel {
		existing.PolicyApplied = true
		existing.PolicyAction = policy.Action

		s.mu.Lock()
		s.classifications[classification.DataID] = existing
		s.policies[policy.ID] = policy
		s.mu.Unlock()

		s.publishEvent(ctx, "policy.applied", classification.DataID, map[string]interface{}{
			"policy_id": policy.ID,
			"action":    policy.Action,
			"level":     policy.Level,
		})

		s.logger.Info(ctx, "Policy applied successfully", map[string]any{
			"data_id":   classification.DataID,
			"policy_id": policy.ID,
			"action":    policy.Action,
		})

		return nil
	}

	return fmt.Errorf("classification level %s does not meet policy requirement %s",
		existing.Level, policy.Level)
}

func (s *classifierService) GetClassification(ctx context.Context, dataID string) (*ports.DataClassification, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	classification, exists := s.classifications[dataID]
	if !exists {
		return nil, ErrClassificationNotFound
	}

	result := &ports.DataClassification{
		DataID:        classification.DataID,
		Level:         classification.Level,
		Matches:       make([]*ports.SensitiveDataMatch, len(classification.Matches)),
		ScanTime:      classification.ScanTime,
		Confidence:    classification.Confidence,
		PolicyApplied: classification.PolicyApplied,
		PolicyAction:  classification.PolicyAction,
	}

	for i, m := range classification.Matches {
		match := *m
		match.Value = s.maskValue(m.Value, m.Type)
		result.Matches[i] = &match
	}

	return result, nil
}

func (s *classifierService) detectSensitiveData(content string) []*ports.SensitiveDataMatch {
	var matches []*ports.SensitiveDataMatch

	for _, pattern := range s.patterns {
		found := pattern.regex.FindAllStringIndex(content, -1)
		for _, idx := range found {
			value := content[idx[0]:idx[1]]

			if pattern.dataType == ports.SensitiveBankCard && !s.validateLuhn(value) {
				continue
			}

			if pattern.dataType == ports.SensitiveIDCard && !s.validateIDCard(value) {
				continue
			}

			matches = append(matches, &ports.SensitiveDataMatch{
				Type:     pattern.dataType,
				Value:    value,
				Position: idx[0],
				Length:   idx[1] - idx[0],
			})
		}
	}

	return matches
}

func (s *classifierService) determineLevel(matches []*ports.SensitiveDataMatch) ports.DataLevel {
	if len(matches) == 0 {
		return ports.LevelPublic
	}

	typeCount := make(map[ports.SensitiveDataType]int)
	for _, m := range matches {
		typeCount[m.Type]++
	}

	if typeCount[ports.SensitiveIDCard] > 0 || typeCount[ports.SensitiveBankCard] >= 2 {
		return ports.LevelTopSecret
	}

	if typeCount[ports.SensitiveBankCard] > 0 || typeCount[ports.SensitivePhone] >= 3 {
		return ports.LevelConfidential
	}

	if typeCount[ports.SensitivePhone] > 0 || typeCount[ports.SensitiveEmail] >= 2 {
		return ports.LevelInternal
	}

	if typeCount[ports.SensitiveEmail] > 0 {
		return ports.LevelInternal
	}

	return ports.LevelPublic
}

func (s *classifierService) calculateConfidence(matches []*ports.SensitiveDataMatch) float64 {
	if len(matches) == 0 {
		return 1.0
	}

	totalWeight := 0.0
	for _, pattern := range s.patterns {
		for _, m := range matches {
			if m.Type == pattern.dataType {
				totalWeight += pattern.weight
			}
		}
	}

	confidence := totalWeight / float64(len(matches))
	if confidence > 1.0 {
		confidence = 1.0
	}

	return confidence
}

func (s *classifierService) validateLuhn(number string) bool {
	var sum int
	alternate := false

	for i := len(number) - 1; i >= 0; i-- {
		digit, err := strconv.Atoi(string(number[i]))
		if err != nil {
			return false
		}

		if alternate {
			digit *= 2
			if digit > 9 {
				digit -= 9
			}
		}

		sum += digit
		alternate = !alternate
	}

	return sum%10 == 0
}

func (s *classifierService) validateIDCard(id string) bool {
	if len(id) != 18 {
		return false
	}

	weights := []int{7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2}
	codes := []string{"1", "0", "X", "9", "8", "7", "6", "5", "4", "3", "2"}

	sum := 0
	for i := 0; i < 17; i++ {
		digit, err := strconv.Atoi(string(id[i]))
		if err != nil {
			return false
		}
		sum += digit * weights[i]
	}

	checkCode := codes[sum%11]
	lastChar := strings.ToUpper(string(id[17]))

	return lastChar == checkCode
}

func (s *classifierService) maskValue(value string, dataType ports.SensitiveDataType) string {
	if len(value) <= 4 {
		return strings.Repeat("*", len(value))
	}

	switch dataType {
	case ports.SensitivePhone:
		return value[:3] + strings.Repeat("*", 4) + value[7:]
	case ports.SensitiveEmail:
		parts := strings.Split(value, "@")
		if len(parts[0]) > 2 {
			return parts[0][:2] + strings.Repeat("*", len(parts[0])-2) + "@" + parts[1]
		}
		return strings.Repeat("*", len(parts[0])) + "@" + parts[1]
	case ports.SensitiveIDCard:
		return value[:6] + strings.Repeat("*", 8) + value[14:]
	case ports.SensitiveBankCard:
		return value[:6] + strings.Repeat("*", len(value)-10) + value[len(value)-4:]
	default:
		return value[:2] + strings.Repeat("*", len(value)-4) + value[len(value)-2:]
	}
}

func (s *classifierService) validatePolicy(policy *ports.ClassificationPolicy) error {
	if policy.ID == "" {
		return fmt.Errorf("%w: policy ID is required", ErrInvalidPolicy)
	}
	if policy.Name == "" {
		return fmt.Errorf("%w: policy name is required", ErrInvalidPolicy)
	}
	if policy.Action == "" {
		return fmt.Errorf("%w: policy action is required", ErrInvalidPolicy)
	}

	switch policy.Level {
	case ports.LevelPublic, ports.LevelInternal, ports.LevelConfidential, ports.LevelTopSecret:
	default:
		return fmt.Errorf("%w: invalid data level %s", ErrInvalidPolicy, policy.Level)
	}

	return nil
}

func (s *classifierService) publishEvent(ctx context.Context, eventType, aggregateID string, payload map[string]interface{}) {
	if s.eventBus == nil {
		return
	}

	event := ports.Event{
		ID:        uuid.New(),
		Type:      eventType,
		Source:    "dataclassify-service",
		Payload:   payload,
		Timestamp: time.Now().Unix(),
	}

	if err := s.eventBus.Publish(ctx, event); err != nil {
		s.logger.Warn(ctx, "Failed to publish domain event", map[string]any{
			"event_type": eventType,
			"error":      err.Error(),
		})
	}
}

func (s *classifierService) RegisterPolicy(policy *ports.ClassificationPolicy) error {
	if err := s.validatePolicy(policy); err != nil {
		return err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	s.policies[policy.ID] = policy
	return nil
}

func (s *classifierService) GetPolicies() []*ports.ClassificationPolicy {
	s.mu.RLock()
	defer s.mu.RUnlock()

	result := make([]*ports.ClassificationPolicy, 0, len(s.policies))
	for _, p := range s.policies {
		policy := *p
		result = append(result, &policy)
	}

	return result
}
