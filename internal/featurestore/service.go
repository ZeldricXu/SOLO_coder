package featurestore

import (
	"context"
	"fmt"
	"math"
	"sync"
	"time"

	"go.uber.org/zap"
	"gorm.io/gorm"
	errors "session133/pkg/errors"
	"session133/pkg/utils"
)

type FeatureStoreService struct {
	db          *gorm.DB
	logger      *zap.Logger
	onlineCache map[string]map[string]*FeatureValue
	cacheMu     sync.RWMutex
}

func NewFeatureStoreService(db *gorm.DB, logger *zap.Logger) *FeatureStoreService {
	s := &FeatureStoreService{
		db:          db,
		logger:      logger,
		onlineCache: make(map[string]map[string]*FeatureValue),
	}

	if err := db.AutoMigrate(&FeatureGroup{}, &FeatureValue{}, &FeatureView{}, &TrainingDataset{}); err != nil {
		logger.Error("Failed to migrate feature store tables", zap.Error(err))
	}

	return s
}

func (s *FeatureStoreService) CreateFeatureGroup(ctx context.Context, req *CreateFeatureGroupRequest, userID string) (*FeatureGroup, error) {
	var existing FeatureGroup
	if err := s.db.WithContext(ctx).Where("name = ?", req.Name).First(&existing).Error; err == nil {
		return nil, errors.Conflict("特征组名称已存在")
	}

	fg := &FeatureGroup{
		ID:          utils.GenerateID("fg"),
		Name:        req.Name,
		Description: req.Description,
		EntityType:  req.EntityType,
		Version:     1,
		Features:    req.Features,
		Labels:      req.Labels,
		Online:      req.Online,
		CreatedBy:   userID,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(fg).Error; err != nil {
		return nil, errors.InternalError(err.Error())
	}

	return fg, nil
}

func (s *FeatureStoreService) GetFeatureGroup(ctx context.Context, nameOrID string) (*FeatureGroup, error) {
	var fg FeatureGroup
	err := s.db.WithContext(ctx).Where("id = ? OR name = ?", nameOrID, nameOrID).First(&fg).Error
	if err != nil {
		return nil, errors.NotFound("特征组不存在")
	}
	return &fg, nil
}

func (s *FeatureStoreService) ListFeatureGroups(ctx context.Context, entityType string, page, pageSize int) ([]*FeatureGroup, int64, error) {
	var fgs []*FeatureGroup
	var total int64

	query := s.db.WithContext(ctx).Model(&FeatureGroup{})
	if entityType != "" {
		query = query.Where("entity_type = ?", entityType)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, errors.InternalError(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at desc").Find(&fgs).Error; err != nil {
		return nil, 0, errors.InternalError(err.Error())
	}

	return fgs, total, nil
}

func (s *FeatureStoreService) InsertFeatureValues(ctx context.Context, groupID string, req *InsertFeatureValuesRequest) error {
	fg, err := s.GetFeatureGroup(ctx, groupID)
	if err != nil {
		return err
	}

	featureMap := make(map[string]FeatureSchema)
	for _, f := range fg.Features {
		featureMap[f.Name] = f
	}

	for name := range req.Values {
		if _, ok := featureMap[name]; !ok {
			return errors.InvalidParams(fmt.Sprintf("特征 %s 不存在于特征组 %s", name, fg.Name))
		}
	}

	eventTime := req.EventTime
	if eventTime.IsZero() {
		eventTime = time.Now()
	}

	tx := s.db.WithContext(ctx).Begin()
	if tx.Error != nil {
		return errors.InternalError(tx.Error.Error())
	}

	for name, value := range req.Values {
		fv := &FeatureValue{
			ID:             utils.GenerateID("fv"),
			FeatureGroupID: fg.ID,
			EntityID:       req.EntityID,
			FeatureName:    name,
			Value:          value,
			Timestamp:      time.Now(),
			EventTime:      eventTime,
		}

		if err := tx.Create(fv).Error; err != nil {
			tx.Rollback()
			return errors.InternalError(err.Error())
		}

		if fg.Online {
			s.updateOnlineCache(fg.ID, req.EntityID, fv)
		}
	}

	return tx.Commit().Error
}

func (s *FeatureStoreService) updateOnlineCache(groupID, entityID string, fv *FeatureValue) {
	s.cacheMu.Lock()
	defer s.cacheMu.Unlock()

	key := groupID + ":" + entityID
	if _, ok := s.onlineCache[key]; !ok {
		s.onlineCache[key] = make(map[string]*FeatureValue)
	}
	s.onlineCache[key][fv.FeatureName] = fv
}

func (s *FeatureStoreService) GetOnlineFeatures(ctx context.Context, req *GetOnlineFeaturesRequest) (map[string]map[string]interface{}, error) {
	var fv FeatureView
	if err := s.db.WithContext(ctx).Where("name = ? OR id = ?", req.FeatureView, req.FeatureView).First(&fv).Error; err != nil {
		return nil, errors.NotFound("特征视图不存在")
	}

	result := make(map[string]map[string]interface{})

	for _, entityID := range req.EntityIDs {
		result[entityID] = make(map[string]interface{})

		for _, groupID := range fv.FeatureGroups {
			s.cacheMu.RLock()
			cacheKey := groupID + ":" + entityID
			if features, ok := s.onlineCache[cacheKey]; ok {
				for name, fval := range features {
					if len(req.Features) == 0 || contains(req.Features, name) {
						result[entityID][name] = fval.Value
					}
				}
			}
			s.cacheMu.RUnlock()
		}
	}

	return result, nil
}

func (s *FeatureStoreService) GetOfflineFeatures(ctx context.Context, featureViewID string, entityIDs []string, startTime, endTime time.Time) ([]map[string]interface{}, error) {
	var fv FeatureView
	if err := s.db.WithContext(ctx).Where("id = ? OR name = ?", featureViewID, featureViewID).First(&fv).Error; err != nil {
		return nil, errors.NotFound("特征视图不存在")
	}

	var values []*FeatureValue
	query := s.db.WithContext(ctx).
		Where("feature_group_id IN ?", fv.FeatureGroups).
		Where("entity_id IN ?", entityIDs).
		Where("event_time BETWEEN ? AND ?", startTime, endTime)

	if len(fv.Features) > 0 {
		query = query.Where("feature_name IN ?", fv.Features)
	}

	if err := query.Order("event_time desc").Find(&values).Error; err != nil {
		return nil, errors.InternalError(err.Error())
	}

	resultMap := make(map[string]map[string]interface{})
	for _, v := range values {
		key := v.EntityID + ":" + v.EventTime.Format(time.RFC3339)
		if _, ok := resultMap[key]; !ok {
			resultMap[key] = make(map[string]interface{})
			resultMap[key]["entity_id"] = v.EntityID
			resultMap[key]["event_time"] = v.EventTime
		}
		resultMap[key][v.FeatureName] = v.Value
	}

	result := make([]map[string]interface{}, 0, len(resultMap))
	for _, v := range resultMap {
		result = append(result, v)
	}

	return result, nil
}

func (s *FeatureStoreService) CreateFeatureView(ctx context.Context, name, description string, featureGroups, features []string, labels map[string]string, online bool, ttl time.Duration, userID string) (*FeatureView, error) {
	var existing FeatureView
	if err := s.db.WithContext(ctx).Where("name = ?", name).First(&existing).Error; err == nil {
		return nil, errors.Conflict("特征视图名称已存在")
	}

	fv := &FeatureView{
		ID:            utils.GenerateID("fv"),
		Name:          name,
		Description:   description,
		FeatureGroups: featureGroups,
		Features:      features,
		Labels:        labels,
		Online:        online,
		TTL:           ttl,
		CreatedBy:     userID,
		CreatedAt:     time.Now(),
		UpdatedAt:     time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(fv).Error; err != nil {
		return nil, errors.InternalError(err.Error())
	}

	return fv, nil
}

func (s *FeatureStoreService) GetFeatureView(ctx context.Context, nameOrID string) (*FeatureView, error) {
	var fv FeatureView
	err := s.db.WithContext(ctx).Where("id = ? OR name = ?", nameOrID, nameOrID).First(&fv).Error
	if err != nil {
		return nil, errors.NotFound("特征视图不存在")
	}
	return &fv, nil
}

func (s *FeatureStoreService) ListFeatureViews(ctx context.Context, page, pageSize int) ([]*FeatureView, int64, error) {
	var fvs []*FeatureView
	var total int64

	if err := s.db.WithContext(ctx).Model(&FeatureView{}).Count(&total).Error; err != nil {
		return nil, 0, errors.InternalError(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := s.db.WithContext(ctx).Offset(offset).Limit(pageSize).Order("created_at desc").Find(&fvs).Error; err != nil {
		return nil, 0, errors.InternalError(err.Error())
	}

	return fvs, total, nil
}

func (s *FeatureStoreService) CreateTrainingDataset(ctx context.Context, name, description, featureViewID string, startTime, endTime time.Time, entityIDs []string, labels map[string]string, userID string) (*TrainingDataset, error) {
	ds := &TrainingDataset{
		ID:            utils.GenerateID("ds"),
		Name:          name,
		Description:   description,
		FeatureViewID: featureViewID,
		StartTime:     startTime,
		EndTime:       endTime,
		EntityIDs:     entityIDs,
		Labels:        labels,
		Status:        "creating",
		CreatedBy:     userID,
		CreatedAt:     time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(ds).Error; err != nil {
		return nil, errors.InternalError(err.Error())
	}

	go s.generateTrainingDataset(ds)

	return ds, nil
}

func (s *FeatureStoreService) generateTrainingDataset(ds *TrainingDataset) {
	features, err := s.GetOfflineFeatures(context.Background(), ds.FeatureViewID, ds.EntityIDs, ds.StartTime, ds.EndTime)
	if err != nil {
		ds.Status = "failed"
		s.logger.Error("Failed to generate training dataset", zap.Error(err))
	} else {
		ds.Status = "ready"
		ds.RowCount = int64(len(features))
		ds.StoragePath = fmt.Sprintf("/datasets/%s.parquet", ds.ID)
	}

	s.db.Save(ds)
}

func (s *FeatureStoreService) GetTrainingDataset(ctx context.Context, datasetID string) (*TrainingDataset, error) {
	var ds TrainingDataset
	if err := s.db.WithContext(ctx).Where("id = ?", datasetID).First(&ds).Error; err != nil {
		return nil, errors.NotFound("数据集不存在")
	}
	return &ds, nil
}

func (s *FeatureStoreService) ListTrainingDatasets(ctx context.Context, featureViewID string, page, pageSize int) ([]*TrainingDataset, int64, error) {
	var datasets []*TrainingDataset
	var total int64

	query := s.db.WithContext(ctx).Model(&TrainingDataset{})
	if featureViewID != "" {
		query = query.Where("feature_view_id = ?", featureViewID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, errors.InternalError(err.Error())
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at desc").Find(&datasets).Error; err != nil {
		return nil, 0, errors.InternalError(err.Error())
	}

	return datasets, total, nil
}

func (s *FeatureStoreService) ComputeFeatureStatistics(ctx context.Context, groupID, featureName string, startTime, endTime time.Time) (map[string]interface{}, error) {
	var values []*FeatureValue
	err := s.db.WithContext(ctx).
		Where("feature_group_id = ?", groupID).
		Where("feature_name = ?", featureName).
		Where("event_time BETWEEN ? AND ?", startTime, endTime).
		Find(&values).Error

	if err != nil {
		return nil, errors.InternalError(err.Error())
	}

	if len(values) == 0 {
		return nil, errors.NotFound("没有找到特征数据")
	}

	nums := make([]float64, 0, len(values))
	for _, v := range values {
		if num, ok := v.Value.(float64); ok {
			nums = append(nums, num)
		} else if num, ok := v.Value.(int); ok {
			nums = append(nums, float64(num))
		} else if num, ok := v.Value.(int64); ok {
			nums = append(nums, float64(num))
		}
	}

	if len(nums) == 0 {
		return map[string]interface{}{
			"count":     len(values),
			"non_numeric": true,
		}, nil
	}

	sum := 0.0
	min := nums[0]
	max := nums[0]
	for _, n := range nums {
		sum += n
		if n < min {
			min = n
		}
		if n > max {
			max = n
		}
	}

	mean := sum / float64(len(nums))
	variance := 0.0
	for _, n := range nums {
		variance += math.Pow(n-mean, 2)
	}
	variance /= float64(len(nums))
	std := math.Sqrt(variance)

	return map[string]interface{}{
		"count":     len(nums),
		"mean":      mean,
		"min":       min,
		"max":       max,
		"std":       std,
		"variance":  variance,
	}, nil
}

func contains(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}
