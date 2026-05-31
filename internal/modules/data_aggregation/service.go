package data_aggregation

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"

	"edgescheduler/internal/common/database"
	"edgescheduler/internal/common/eventbus"
	"edgescheduler/internal/common/logger"
	"edgescheduler/pkg/utils"
)

type AggregationService interface {
	CreateRule(ctx context.Context, rule *AggregationRule) (*AggregationRule, error)
	GetRule(ctx context.Context, ruleID string) (*AggregationRule, error)
	ListRules(ctx context.Context, deviceID string, offset, limit int) ([]AggregationRule, int64, error)
	UpdateRule(ctx context.Context, ruleID string, updates map[string]interface{}) error
	DeleteRule(ctx context.Context, ruleID string) error
	IngestData(ctx context.Context, data *RawDataPoint) error
	GetAggregationResults(ctx context.Context, ruleID string, startTime, endTime time.Time, offset, limit int) ([]AggregationResult, int64, error)
	StartAggregationWorker(ctx context.Context, workerCount int)
	UploadResults(ctx context.Context, deviceID string) (int64, error)
}

type aggregationServiceImpl struct {
	db        *gorm.DB
	eventBus  eventbus.EventBus
	dataCh    chan *RawDataPoint
	ruleCache map[string]*AggregationRule
	cacheMu   sync.RWMutex
}

func NewAggregationService() AggregationService {
	return &aggregationServiceImpl{
		db:        database.GetDB(),
		eventBus:  eventbus.GetEventBus(),
		dataCh:    make(chan *RawDataPoint, 10000),
		ruleCache: make(map[string]*AggregationRule),
	}
}

func (s *aggregationServiceImpl) CreateRule(ctx context.Context, rule *AggregationRule) (*AggregationRule, error) {
	rule.RuleID = utils.GenerateID("rule")

	if err := s.db.Create(rule).Error; err != nil {
		return nil, fmt.Errorf("failed to create aggregation rule: %w", err)
	}

	s.cacheMu.Lock()
	s.ruleCache[rule.RuleID] = rule
	s.cacheMu.Unlock()

	logger.Info("Aggregation rule created",
		zap.String("rule_id", rule.RuleID),
		zap.String("name", rule.Name),
	)

	return rule, nil
}

func (s *aggregationServiceImpl) GetRule(ctx context.Context, ruleID string) (*AggregationRule, error) {
	var rule AggregationRule
	if err := s.db.Where("rule_id = ?", ruleID).First(&rule).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("rule not found")
		}
		return nil, err
	}
	return &rule, nil
}

func (s *aggregationServiceImpl) ListRules(ctx context.Context, deviceID string, offset, limit int) ([]AggregationRule, int64, error) {
	var rules []AggregationRule
	var total int64

	query := s.db.Model(&AggregationRule{})
	if deviceID != "" {
		query = query.Where("device_id = ?", deviceID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Offset(offset).Limit(limit).Find(&rules).Error; err != nil {
		return nil, 0, err
	}

	return rules, total, nil
}

func (s *aggregationServiceImpl) UpdateRule(ctx context.Context, ruleID string, updates map[string]interface{}) error {
	result := s.db.Model(&AggregationRule{}).Where("rule_id = ?", ruleID).Updates(updates)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.New("rule not found")
	}
	return nil
}

func (s *aggregationServiceImpl) DeleteRule(ctx context.Context, ruleID string) error {
	result := s.db.Where("rule_id = ?", ruleID).Delete(&AggregationRule{})
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.New("rule not found")
	}

	s.cacheMu.Lock()
	delete(s.ruleCache, ruleID)
	s.cacheMu.Unlock()

	return nil
}

func (s *aggregationServiceImpl) IngestData(ctx context.Context, data *RawDataPoint) error {
	select {
	case s.dataCh <- data:
		return nil
	default:
		return errors.New("aggregation queue is full")
	}
}

func (s *aggregationServiceImpl) processDataPoint(ctx context.Context, data *RawDataPoint) {
	s.cacheMu.RLock()
	defer s.cacheMu.RUnlock()

	for _, rule := range s.ruleCache {
		if !rule.Enabled {
			continue
		}
		if rule.DeviceID != data.DeviceID {
			continue
		}
		if rule.DataSource != data.DataSource {
			continue
		}

		windowStart := data.Timestamp.Truncate(time.Duration(rule.WindowSeconds) * time.Second)
		windowEnd := windowStart.Add(time.Duration(rule.WindowSeconds) * time.Second)

		fieldValue, ok := data.Data[rule.Field]
		if !ok {
			continue
		}

		numericValue, ok := s.toFloat64(fieldValue)
		if !ok {
			continue
		}

		groupValues := make(map[string]string)
		for _, gb := range rule.GroupBy {
			if v, exists := data.Data[gb]; exists {
				groupValues[gb] = fmt.Sprintf("%v", v)
			}
		}

		s.updateAggregation(ctx, rule, data.DeviceID, windowStart, windowEnd, numericValue, groupValues)
	}
}

func (s *aggregationServiceImpl) toFloat64(v interface{}) (float64, bool) {
	switch val := v.(type) {
	case float64:
		return val, true
	case int:
		return float64(val), true
	case int64:
		return float64(val), true
	case float32:
		return float64(val), true
	default:
		return 0, false
	}
}

func (s *aggregationServiceImpl) updateAggregation(ctx context.Context, rule *AggregationRule, deviceID string, windowStart, windowEnd time.Time, value float64, groupValues map[string]string) {
	var result AggregationResult

	groupKey := utils.ToJSON(groupValues)

	err := s.db.Where("rule_id = ? AND device_id = ? AND window_start = ? AND window_end = ? AND group_values::text = ?",
		rule.RuleID, deviceID, windowStart, windowEnd, groupKey).First(&result).Error

	if errors.Is(err, gorm.ErrRecordNotFound) {
		result = AggregationResult{
			ResultID:    utils.GenerateID("agg"),
			RuleID:      rule.RuleID,
			DeviceID:    deviceID,
			WindowStart: windowStart,
			WindowEnd:   windowEnd,
			Value:       value,
			Count:       1,
			GroupValues: groupValues,
			Uploaded:    false,
		}
		s.db.Create(&result)
	} else {
		result.Count++
		switch rule.AggregationType {
		case AggregationTypeSum:
			result.Value += value
		case AggregationTypeAvg:
			result.Value = (result.Value*float64(result.Count-1) + value) / float64(result.Count)
		case AggregationTypeMin:
			if value < result.Value {
				result.Value = value
			}
		case AggregationTypeMax:
			if value > result.Value {
				result.Value = value
			}
		}
		s.db.Save(&result)
	}

	s.eventBus.Publish(ctx, eventbus.EventDataAggregated, map[string]interface{}{
		"result_id":   result.ResultID,
		"rule_id":     rule.RuleID,
		"device_id":   deviceID,
		"value":       result.Value,
		"count":       result.Count,
		"window_start": windowStart,
	}, "data_aggregation")
}

func (s *aggregationServiceImpl) GetAggregationResults(ctx context.Context, ruleID string, startTime, endTime time.Time, offset, limit int) ([]AggregationResult, int64, error) {
	var results []AggregationResult
	var total int64

	query := s.db.Model(&AggregationResult{}).Where("rule_id = ?", ruleID)
	if !startTime.IsZero() {
		query = query.Where("window_start >= ?", startTime)
	}
	if !endTime.IsZero() {
		query = query.Where("window_end <= ?", endTime)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if err := query.Order("window_start DESC").Offset(offset).Limit(limit).Find(&results).Error; err != nil {
		return nil, 0, err
	}

	return results, total, nil
}

func (s *aggregationServiceImpl) StartAggregationWorker(ctx context.Context, workerCount int) {
	logger.Info("Starting aggregation workers", zap.Int("count", workerCount))

	s.loadRulesIntoCache()

	for i := 0; i < workerCount; i++ {
		go func(workerID int) {
			logger.Debug("Aggregation worker started", zap.Int("worker_id", workerID))
			for {
				select {
				case <-ctx.Done():
					logger.Debug("Aggregation worker stopped", zap.Int("worker_id", workerID))
					return
				case data := <-s.dataCh:
					s.processDataPoint(ctx, data)
				}
			}
		}(i)
	}
}

func (s *aggregationServiceImpl) loadRulesIntoCache() {
	var rules []AggregationRule
	s.db.Where("enabled = ?", true).Find(&rules)

	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()

	for i := range rules {
		s.ruleCache[rules[i].RuleID] = &rules[i]
	}

	logger.Info("Loaded aggregation rules into cache", zap.Int("count", len(rules)))
}

func (s *aggregationServiceImpl) UploadResults(ctx context.Context, deviceID string) (int64, error) {
	now := time.Now().UTC()
	result := s.db.Model(&AggregationResult{}).
		Where("device_id = ? AND uploaded = ?", deviceID, false).
		Updates(map[string]interface{}{
			"uploaded":    true,
			"uploaded_at": now,
		})

	if result.Error != nil {
		return 0, result.Error
	}

	logger.Info("Aggregation results uploaded",
		zap.String("device_id", deviceID),
		zap.Int64("count", result.RowsAffected),
	)

	return result.RowsAffected, nil
}
