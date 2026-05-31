package aggregation

import (
	"context"
	"time"

	"github.com/edgevision/edgevision/internal/domain/model"
	"gorm.io/gorm"
)

type dataStreamRepository struct {
	db *gorm.DB
}

func NewDataStreamRepository(db *gorm.DB) *dataStreamRepository {
	return &dataStreamRepository{db: db}
}

func (r *dataStreamRepository) Create(ctx context.Context, stream *model.DataStream) error {
	return r.db.WithContext(ctx).Create(stream).Error
}

func (r *dataStreamRepository) GetByID(ctx context.Context, id string) (*model.DataStream, error) {
	var stream model.DataStream
	if err := r.db.WithContext(ctx).First(&stream, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &stream, nil
}

func (r *dataStreamRepository) ListByDeviceID(ctx context.Context, deviceID string, page, pageSize int) ([]model.DataStream, int64, error) {
	var streams []model.DataStream
	var total int64

	query := r.db.WithContext(ctx).Model(&model.DataStream{})
	if deviceID != "" {
		query = query.Where("device_id = ?", deviceID)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&streams).Error; err != nil {
		return nil, 0, err
	}

	return streams, total, nil
}

func (r *dataStreamRepository) Update(ctx context.Context, stream *model.DataStream) error {
	return r.db.WithContext(ctx).Save(stream).Error
}

func (r *dataStreamRepository) Delete(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Delete(&model.DataStream{}, "id = ?", id).Error
}

type rawDataPointRepository struct {
	db *gorm.DB
}

func NewRawDataPointRepository(db *gorm.DB) *rawDataPointRepository {
	return &rawDataPointRepository{db: db}
}

func (r *rawDataPointRepository) Create(ctx context.Context, point *model.RawDataPoint) error {
	return r.db.WithContext(ctx).Create(point).Error
}

func (r *rawDataPointRepository) CreateBatch(ctx context.Context, points []model.RawDataPoint) error {
	if len(points) == 0 {
		return nil
	}
	return r.db.WithContext(ctx).Create(&points).Error
}

func (r *rawDataPointRepository) ListByStreamAndTimeRange(ctx context.Context, streamID string, startTime, endTime time.Time) ([]model.RawDataPoint, error) {
	var points []model.RawDataPoint
	err := r.db.WithContext(ctx).Where("stream_id = ? AND timestamp >= ? AND timestamp < ?", streamID, startTime, endTime).
		Order("timestamp ASC").Find(&points).Error
	return points, err
}

func (r *rawDataPointRepository) DeleteByTimeRange(ctx context.Context, streamID string, beforeTime time.Time) error {
	return r.db.WithContext(ctx).Where("stream_id = ? AND timestamp < ?", streamID, beforeTime).
		Delete(&model.RawDataPoint{}).Error
}

type aggregatedDataRepository struct {
	db *gorm.DB
}

func NewAggregatedDataRepository(db *gorm.DB) *aggregatedDataRepository {
	return &aggregatedDataRepository{db: db}
}

func (r *aggregatedDataRepository) Create(ctx context.Context, data *model.AggregatedData) error {
	return r.db.WithContext(ctx).Create(data).Error
}

func (r *aggregatedDataRepository) ListByStreamID(ctx context.Context, streamID string, startTime, endTime time.Time, page, pageSize int) ([]model.AggregatedData, int64, error) {
	var data []model.AggregatedData
	var total int64

	query := r.db.WithContext(ctx).Model(&model.AggregatedData{}).Where("stream_id = ?", streamID)
	if !startTime.IsZero() {
		query = query.Where("window_start >= ?", startTime)
	}
	if !endTime.IsZero() {
		query = query.Where("window_end < ?", endTime)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("window_start DESC").Find(&data).Error; err != nil {
		return nil, 0, err
	}

	return data, total, nil
}

func (r *aggregatedDataRepository) GetLatest(ctx context.Context, streamID, metric string) (*model.AggregatedData, error) {
	var data model.AggregatedData
	query := r.db.WithContext(ctx).Where("stream_id = ?", streamID)
	if metric != "" {
		query = query.Where("metric = ?", metric)
	}
	err := query.Order("window_end DESC").First(&data).Error
	if err != nil {
		return nil, err
	}
	return &data, nil
}
