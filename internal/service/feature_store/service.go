package feature_store

import (
	"errors"
	"fmt"
	"time"

	"gorm.io/gorm"

	"llmgateway/internal/domain/entity"
	"llmgateway/internal/infrastructure/database"
	"llmgateway/internal/infrastructure/logger"
	"llmgateway/pkg/utils"
)

type Service struct {
	db *gorm.DB
}

func NewService() *Service {
	return &Service{
		db: database.DB(),
	}
}

type RegisterFeatureRequest struct {
	Name        string                 `json:"name" binding:"required"`
	Description string                 `json:"description"`
	Type        string                 `json:"type" binding:"required"`
	ValueType   string                 `json:"value_type" binding:"required"`
	Dimensions  int                    `json:"dimensions"`
	Entity      string                 `json:"entity" binding:"required"`
	Source      string                 `json:"source"`
	Owner       string                 `json:"owner"`
	Tags        []string               `json:"tags"`
	Config      map[string]interface{} `json:"config"`
}

func (s *Service) RegisterFeature(req *RegisterFeatureRequest) (*entity.Feature, error) {
	var existing entity.Feature
	err := s.db.Where("name = ?", req.Name).First(&existing).Error
	if err == nil {
		return nil, errors.New("feature with this name already exists")
	}
	if !errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, fmt.Errorf("failed to check existing feature: %w", err)
	}

	now := utils.Now()
	feature := &entity.Feature{
		ID:          utils.GenerateID("feat"),
		Name:        req.Name,
		Description: req.Description,
		Type:        req.Type,
		ValueType:   req.ValueType,
		Dimensions:  req.Dimensions,
		Entity:      req.Entity,
		Source:      req.Source,
		Owner:       req.Owner,
		Tags:        req.Tags,
		Config:      req.Config,
		Status:      string(entity.FeatureStatusActive),
		Version:     1,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	if err := s.db.Create(feature).Error; err != nil {
		return nil, fmt.Errorf("failed to create feature: %w", err)
	}

	logger.Info("feature registered", "feature_id", feature.ID, "name", feature.Name)
	return feature, nil
}

func (s *Service) GetFeature(id string) (*entity.Feature, error) {
	var feature entity.Feature
	if err := s.db.Where("id = ?", id).First(&feature).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("feature not found")
		}
		return nil, fmt.Errorf("failed to get feature: %w", err)
	}
	return &feature, nil
}

func (s *Service) GetFeatureByName(name string) (*entity.Feature, error) {
	var feature entity.Feature
	if err := s.db.Where("name = ?", name).First(&feature).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("feature not found")
		}
		return nil, fmt.Errorf("failed to get feature: %w", err)
	}
	return &feature, nil
}

func (s *Service) ListFeatures(page, pageSize int, entityType, status string) ([]entity.Feature, int64, error) {
	var features []entity.Feature
	var total int64

	query := s.db.Model(&entity.Feature{})
	if entityType != "" {
		query = query.Where("entity = ?", entityType)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to count features: %w", err)
	}

	offset := (page - 1) * pageSize
	if err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&features).Error; err != nil {
		return nil, 0, fmt.Errorf("failed to list features: %w", err)
	}

	return features, total, nil
}

func (s *Service) UpdateFeature(id string, description *string, tags []string, config map[string]interface{}, status *string) (*entity.Feature, error) {
	feature, err := s.GetFeature(id)
	if err != nil {
		return nil, err
	}

	updates := make(map[string]interface{})
	if description != nil {
		updates["description"] = *description
	}
	if tags != nil {
		updates["tags"] = tags
	}
	if config != nil {
		updates["config"] = config
	}
	if status != nil {
		updates["status"] = *status
	}
	updates["version"] = feature.Version + 1
	updates["updated_at"] = utils.Now()

	if err := s.db.Model(feature).Updates(updates).Error; err != nil {
		return nil, fmt.Errorf("failed to update feature: %w", err)
	}

	return s.GetFeature(id)
}

func (s *Service) DeleteFeature(id string) error {
	result := s.db.Delete(&entity.Feature{}, "id = ?", id)
	if result.Error != nil {
		return fmt.Errorf("failed to delete feature: %w", result.Error)
	}
	if result.RowsAffected == 0 {
		return errors.New("feature not found")
	}
	return nil
}

type InsertFeatureValueRequest struct {
	FeatureID string      `json:"feature_id" binding:"required"`
	EntityKey string      `json:"entity_key" binding:"required"`
	Value     interface{} `json:"value" binding:"required"`
	TTL       *time.Time  `json:"ttl"`
}

func (s *Service) InsertFeatureValue(req *InsertFeatureValueRequest) (*entity.FeatureValue, error) {
	feature, err := s.GetFeature(req.FeatureID)
	if err != nil {
		return nil, err
	}

	now := utils.Now()
	value := &entity.FeatureValue{
		ID:        utils.GenerateID("fv"),
		FeatureID: req.FeatureID,
		EntityKey: req.EntityKey,
		Value:     req.Value,
		Version:   feature.Version,
		Timestamp: now,
		TTL:       req.TTL,
		CreatedAt: now,
	}

	if err := s.db.Create(value).Error; err != nil {
		return nil, fmt.Errorf("failed to insert feature value: %w", err)
	}

	return value, nil
}

func (s *Service) BatchInsertFeatureValues(values []InsertFeatureValueRequest) ([]entity.FeatureValue, error) {
	var results []entity.FeatureValue

	for _, req := range values {
		val, err := s.InsertFeatureValue(&req)
		if err != nil {
			return nil, err
		}
		results = append(results, *val)
	}

	return results, nil
}

func (s *Service) GetOnlineFeatureValue(featureName, entityKey string) (interface{}, error) {
	feature, err := s.GetFeatureByName(featureName)
	if err != nil {
		return nil, err
	}

	var value entity.FeatureValue
	if err := s.db.Where("feature_id = ? AND entity_key = ?", feature.ID, entityKey).
		Order("timestamp DESC").First(&value).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("failed to get feature value: %w", err)
	}

	return value.Value, nil
}

func (s *Service) BatchGetOnlineFeatureValues(featureNames []string, entityKey string) (map[string]interface{}, error) {
	result := make(map[string]interface{})

	for _, name := range featureNames {
		val, err := s.GetOnlineFeatureValue(name, entityKey)
		if err != nil {
			return nil, err
		}
		result[name] = val
	}

	return result, nil
}

func (s *Service) GetFeatureValues(featureID string, entityKey string, startTime, endTime time.Time) ([]entity.FeatureValue, error) {
	var values []entity.FeatureValue

	query := s.db.Where("feature_id = ? AND entity_key = ?", featureID, entityKey)
	if !startTime.IsZero() {
		query = query.Where("timestamp >= ?", startTime)
	}
	if !endTime.IsZero() {
		query = query.Where("timestamp <= ?", endTime)
	}

	if err := query.Order("timestamp DESC").Find(&values).Error; err != nil {
		return nil, fmt.Errorf("failed to get feature values: %w", err)
	}

	return values, nil
}

type CreateFeatureSetRequest struct {
	Name        string                 `json:"name" binding:"required"`
	Description string                 `json:"description"`
	Entity      string                 `json:"entity" binding:"required"`
	FeatureIDs  []string               `json:"feature_ids" binding:"required"`
	Config      map[string]interface{} `json:"config"`
	CreatedBy   string                 `json:"-"`
}

func (s *Service) CreateFeatureSet(req *CreateFeatureSetRequest) (*entity.FeatureSet, error) {
	now := utils.Now()
	featureSet := &entity.FeatureSet{
		ID:          utils.GenerateID("fs"),
		Name:        req.Name,
		Description: req.Description,
		Entity:      req.Entity,
		FeatureIDs:  req.FeatureIDs,
		Config:      req.Config,
		Status:      "active",
		CreatedBy:   req.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}

	if err := s.db.Create(featureSet).Error; err != nil {
		return nil, fmt.Errorf("failed to create feature set: %w", err)
	}

	logger.Info("feature set created", "feature_set_id", featureSet.ID, "name", featureSet.Name)
	return featureSet, nil
}

func (s *Service) GetFeatureSet(id string) (*entity.FeatureSet, error) {
	var featureSet entity.FeatureSet
	if err := s.db.Where("id = ?", id).First(&featureSet).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("feature set not found")
		}
		return nil, fmt.Errorf("failed to get feature set: %w", err)
	}
	return &featureSet, nil
}

func (s *Service) GetFeatureSetValues(featureSetID, entityKey string) (map[string]interface{}, error) {
	featureSet, err := s.GetFeatureSet(featureSetID)
	if err != nil {
		return nil, err
	}

	result := make(map[string]interface{})
	for _, fid := range featureSet.FeatureIDs {
		var feature entity.Feature
		if err := s.db.Where("id = ?", fid).First(&feature).Error; err == nil {
			val, _ := s.GetOnlineFeatureValue(feature.Name, entityKey)
			result[feature.Name] = val
		}
	}

	return result, nil
}

type CreateFeatureViewRequest struct {
	Name          string                 `json:"name" binding:"required"`
	Description   string                 `json:"description"`
	FeatureSetID  string                 `json:"feature_set_id" binding:"required"`
	QueryTemplate string                 `json:"query_template"`
	Materialized  bool                   `json:"materialized"`
	RefreshRate   string                 `json:"refresh_rate"`
	TTL           string                 `json:"ttl"`
	Config        map[string]interface{} `json:"config"`
	CreatedBy     string                 `json:"-"`
}

func (s *Service) CreateFeatureView(req *CreateFeatureViewRequest) (*entity.FeatureView, error) {
	now := utils.Now()
	view := &entity.FeatureView{
		ID:            utils.GenerateID("fv"),
		Name:          req.Name,
		Description:   req.Description,
		FeatureSetID:  req.FeatureSetID,
		QueryTemplate: req.QueryTemplate,
		Materialized:  req.Materialized,
		RefreshRate:   req.RefreshRate,
		TTL:           req.TTL,
		Config:        req.Config,
		Status:        "active",
		CreatedBy:     req.CreatedBy,
		CreatedAt:     now,
		UpdatedAt:     now,
	}

	if err := s.db.Create(view).Error; err != nil {
		return nil, fmt.Errorf("failed to create feature view: %w", err)
	}

	logger.Info("feature view created", "feature_view_id", view.ID, "name", view.Name)
	return view, nil
}

func (s *Service) GetFeatureView(id string) (*entity.FeatureView, error) {
	var view entity.FeatureView
	if err := s.db.Where("id = ?", id).First(&view).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, errors.New("feature view not found")
		}
		return nil, fmt.Errorf("failed to get feature view: %w", err)
	}
	return &view, nil
}

func (s *Service) QueryFeatureView(viewID string, params map[string]interface{}) (map[string]interface{}, error) {
	view, err := s.GetFeatureView(viewID)
	if err != nil {
		return nil, err
	}

	return s.GetFeatureSetValues(view.FeatureSetID, utils.SafeGetMapString(params, "entity_key"))
}

func (s *Service) GetOfflineFeatureValues(featureID string, entityKeys []string, startTime, endTime time.Time) (map[string][]entity.FeatureValue, error) {
	result := make(map[string][]entity.FeatureValue)

	for _, ek := range entityKeys {
		values, err := s.GetFeatureValues(featureID, ek, startTime, endTime)
		if err != nil {
			return nil, err
		}
		result[ek] = values
	}

	return result, nil
}

func (s *Service) CompareOnlineOffline(featureID string, entityKey string) (map[string]interface{}, error) {
	onlineVal, err := s.GetOnlineFeatureValue(featureID, entityKey)
	if err != nil {
		return nil, err
	}

	now := utils.Now()
	offlineVals, err := s.GetFeatureValues(featureID, entityKey, now.Add(-24*time.Hour), now)
	if err != nil {
		return nil, err
	}

	var offlineLatest interface{}
	if len(offlineVals) > 0 {
		offlineLatest = offlineVals[0].Value
	}

	consistent := fmt.Sprintf("%v", onlineVal) == fmt.Sprintf("%v", offlineLatest)

	return map[string]interface{}{
		"online_value":    onlineVal,
		"offline_latest":  offlineLatest,
		"consistent":      consistent,
		"offline_count":   len(offlineVals),
	}, nil
}
