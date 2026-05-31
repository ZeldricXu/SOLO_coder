package service

import (
	"context"
	"fmt"
	"hash/crc32"
	"math"
	"time"

	"projectservice/internal/infrastructure/logger"
	"projectservice/internal/infrastructure/monitor"
	"projectservice/internal/model"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

type FeatureFlagService struct {
	db      *gorm.DB
	logger  *logger.Logger
	metrics *monitor.Metrics
}

func NewFeatureFlagService(db *gorm.DB, log *logger.Logger, metrics *monitor.Metrics) *FeatureFlagService {
	return &FeatureFlagService{
		db:      db,
		logger:  log,
		metrics: metrics,
	}
}

func (s *FeatureFlagService) CreateFlag(ctx context.Context, req *model.CreateFeatureFlagRequest) (*model.FeatureFlag, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("featureflag", "create", "success", time.Since(start))
	}()

	flag := &model.FeatureFlag{
		ID:             uuid.New().String(),
		Name:           req.Name,
		Key:            req.Key,
		Description:    req.Description,
		Enabled:        req.Enabled,
		RolloutPercent: req.RolloutPercent,
		TargetUsers:    req.TargetUsers,
		TargetGroups:   req.TargetGroups,
		Segments:       req.Segments,
		Conditions:     req.Conditions,
		Variations:     req.Variations,
		DefaultValue:   req.DefaultValue,
		CreatedBy:      "system",
		StartAt:        req.StartAt,
		EndAt:          req.EndAt,
		CreatedAt:      time.Now(),
		UpdatedAt:      time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(flag).Error; err != nil {
		s.metrics.ObserveError("featureflag", "db_error")
		return nil, fmt.Errorf("failed to create feature flag: %w", err)
	}
	return flag, nil
}

func (s *FeatureFlagService) GetFlag(ctx context.Context, flagID string) (*model.FeatureFlag, error) {
	var flag model.FeatureFlag
	if err := s.db.WithContext(ctx).Where("id = ?", flagID).First(&flag).Error; err != nil {
		return nil, fmt.Errorf("flag not found: %w", err)
	}
	return &flag, nil
}

func (s *FeatureFlagService) GetFlagByKey(ctx context.Context, key string) (*model.FeatureFlag, error) {
	var flag model.FeatureFlag
	if err := s.db.WithContext(ctx).Where("key = ?", key).First(&flag).Error; err != nil {
		return nil, fmt.Errorf("flag not found: %w", err)
	}
	return &flag, nil
}

func (s *FeatureFlagService) ListFlags(ctx context.Context, enabled *bool, page, pageSize int) ([]model.FeatureFlag, int64, error) {
	var flags []model.FeatureFlag
	var total int64

	query := s.db.WithContext(ctx).Model(&model.FeatureFlag{})

	if enabled != nil {
		query = query.Where("enabled = ?", *enabled)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&flags).Error; err != nil {
		return nil, 0, err
	}

	return flags, total, nil
}

func (s *FeatureFlagService) UpdateFlag(ctx context.Context, flagID string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	result := s.db.WithContext(ctx).
		Model(&model.FeatureFlag{}).
		Where("id = ?", flagID).
		Updates(updates)

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("flag not found")
	}
	return nil
}

func (s *FeatureFlagService) UpdateRollout(ctx context.Context, flagID string, req *model.UpdateRolloutRequest) error {
	updates := map[string]interface{}{
		"rollout_percent": req.RolloutPercent,
		"segments":        req.Segments,
		"updated_at":      time.Now(),
	}
	return s.UpdateFlag(ctx, flagID, updates)
}

func (s *FeatureFlagService) DeleteFlag(ctx context.Context, flagID string) error {
	result := s.db.WithContext(ctx).Delete(&model.FeatureFlag{}, "id = ?", flagID)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("flag not found")
	}
	return nil
}

func (s *FeatureFlagService) EvaluateFlag(ctx context.Context, req *model.FlagEvaluationRequest) (*model.FlagEvaluationResult, error) {
	start := time.Now()
	defer func() {
		s.metrics.ObserveTaskDuration("featureflag", "evaluate", "success", time.Since(start))
	}()

	flag, err := s.GetFlagByKey(ctx, req.FlagKey)
	if err != nil {
		return &model.FlagEvaluationResult{
			FlagKey: req.FlagKey,
			Value:   "",
			Enabled: false,
			Reason:  "flag_not_found",
		}, nil
	}

	if !flag.Enabled {
		return &model.FlagEvaluationResult{
			FlagKey: req.FlagKey,
			Value:   flag.DefaultValue,
			Enabled: false,
			Reason:  "flag_disabled",
		}, nil
	}

	now := time.Now()
	if flag.StartAt != nil && now.Before(*flag.StartAt) {
		return &model.FlagEvaluationResult{
			FlagKey: req.FlagKey,
			Value:   flag.DefaultValue,
			Enabled: false,
			Reason:  "not_started",
		}, nil
	}
	if flag.EndAt != nil && now.After(*flag.EndAt) {
		return &model.FlagEvaluationResult{
			FlagKey: req.FlagKey,
			Value:   flag.DefaultValue,
			Enabled: false,
			Reason:  "expired",
		}, nil
	}

	for _, user := range flag.TargetUsers {
		if user == req.UserID {
			value := s.selectVariation(flag, req.UserID)
			return &model.FlagEvaluationResult{
				FlagKey: req.FlagKey,
				Value:   value,
				Enabled: true,
				Reason:  "target_user",
			}, nil
		}
	}

	if s.isInSegment(ctx, req.UserID, flag.Segments) {
		value := s.selectVariation(flag, req.UserID)
		return &model.FlagEvaluationResult{
			FlagKey: req.FlagKey,
			Value:   value,
			Enabled: true,
			Reason:  "in_segment",
		}, nil
	}

	if flag.RolloutPercent > 0 {
		hash := crc32.ChecksumIEEE([]byte(req.UserID + flag.Key))
		bucket := int(math.Mod(float64(hash), 100))
		if bucket < flag.RolloutPercent {
			value := s.selectVariation(flag, req.UserID)
			return &model.FlagEvaluationResult{
				FlagKey: req.FlagKey,
				Value:   value,
				Enabled: true,
				Reason:  "rollout_percent",
			}, nil
		}
	}

	return &model.FlagEvaluationResult{
		FlagKey: req.FlagKey,
		Value:   flag.DefaultValue,
		Enabled: false,
		Reason:  "not_enabled",
	}, nil
}

func (s *FeatureFlagService) selectVariation(flag *model.FeatureFlag, userID string) string {
	if len(flag.Variations) == 0 {
		return flag.DefaultValue
	}

	totalWeight := 0
	for _, v := range flag.Variations {
		totalWeight += v.Weight
	}

	if totalWeight == 0 {
		return flag.DefaultValue
	}

	hash := crc32.ChecksumIEEE([]byte(userID + flag.Key))
	bucket := int(math.Mod(float64(hash), float64(totalWeight)))

	current := 0
	for _, v := range flag.Variations {
		current += v.Weight
		if bucket < current {
			return v.Value
		}
	}

	return flag.DefaultValue
}

func (s *FeatureFlagService) isInSegment(ctx context.Context, userID string, segments []string) bool {
	if len(segments) == 0 {
		return false
	}

	var userSegments []model.UserSegment
	if err := s.db.WithContext(ctx).Where("id IN ?", segments).Find(&userSegments).Error; err != nil {
		return false
	}

	for _, seg := range userSegments {
		for _, user := range seg.Users {
			if user == userID {
				return true
			}
		}
	}

	return false
}

func (s *FeatureFlagService) CreateSegment(ctx context.Context, segment *model.UserSegment) (*model.UserSegment, error) {
	segment.ID = uuid.New().String()
	segment.CreatedAt = time.Now()
	segment.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(segment).Error; err != nil {
		return nil, fmt.Errorf("failed to create segment: %w", err)
	}
	return segment, nil
}

func (s *FeatureFlagService) GetSegment(ctx context.Context, segmentID string) (*model.UserSegment, error) {
	var segment model.UserSegment
	if err := s.db.WithContext(ctx).Where("id = ?", segmentID).First(&segment).Error; err != nil {
		return nil, fmt.Errorf("segment not found: %w", err)
	}
	return &segment, nil
}

func (s *FeatureFlagService) ListSegments(ctx context.Context, page, pageSize int) ([]model.UserSegment, int64, error) {
	var segments []model.UserSegment
	var total int64

	if err := s.db.WithContext(ctx).Model(&model.UserSegment{}).Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := s.db.WithContext(ctx).Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&segments).Error; err != nil {
		return nil, 0, err
	}

	return segments, total, nil
}

func (s *FeatureFlagService) DeleteSegment(ctx context.Context, segmentID string) error {
	result := s.db.WithContext(ctx).Delete(&model.UserSegment{}, "id = ?", segmentID)
	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return fmt.Errorf("segment not found")
	}
	return nil
}

func (s *FeatureFlagService) AddUserToSegment(ctx context.Context, segmentID, userID string) error {
	var segment model.UserSegment
	if err := s.db.WithContext(ctx).Where("id = ?", segmentID).First(&segment).Error; err != nil {
		return fmt.Errorf("segment not found: %w", err)
	}

	for _, u := range segment.Users {
		if u == userID {
			return nil
		}
	}

	segment.Users = append(segment.Users, userID)
	segment.UpdatedAt = time.Now()

	return s.db.WithContext(ctx).Save(&segment).Error
}

func (s *FeatureFlagService) RemoveUserFromSegment(ctx context.Context, segmentID, userID string) error {
	var segment model.UserSegment
	if err := s.db.WithContext(ctx).Where("id = ?", segmentID).First(&segment).Error; err != nil {
		return fmt.Errorf("segment not found: %w", err)
	}

	var users []string
	for _, u := range segment.Users {
		if u != userID {
			users = append(users, u)
		}
	}

	segment.Users = users
	segment.UpdatedAt = time.Now()

	return s.db.WithContext(ctx).Save(&segment).Error
}
