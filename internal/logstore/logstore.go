package logstore

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/logger"
	"github.com/solocoder/cloudci/internal/models"
	"github.com/solocoder/cloudci/internal/storage"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

const (
	LevelInfo  = "INFO"
	LevelWarn  = "WARN"
	LevelError = "ERROR"

	StreamStdout = "stdout"
	StreamStderr = "stderr"
)

type LogStore struct {
	cfg         *config.LogStoreConfig
	db          *gorm.DB
	redis       *storage.RedisClient
	lineCounter sync.Map
}

func NewLogStore(cfg *config.LogStoreConfig, db *gorm.DB, redis *storage.RedisClient) *LogStore {
	logger.Info("initializing log store",
		zap.Bool("enable_postgres", cfg.EnablePostgres),
		zap.Bool("enable_redis", cfg.EnableRedis),
		zap.String("redis_channel", cfg.RedisChannel),
		zap.Int("retention_days", cfg.RetentionDays),
	)

	return &LogStore{
		cfg:   cfg,
		db:    db,
		redis: redis,
	}
}

func (ls *LogStore) AppendLog(executionID, stageID types.ID, level, message, stream string) {
	if level == "" {
		level = LevelInfo
	}
	if stream == "" {
		stream = StreamStdout
	}

	now := time.Now()
	lineKey := fmt.Sprintf("%s:%s", executionID, stageID)
	lineNum, _ := ls.lineCounter.LoadOrStore(lineKey, int64(0))
	currentLine := lineNum.(int64) + 1
	ls.lineCounter.Store(lineKey, currentLine)

	record := &models.LogRecord{
		ID:          types.NewID(),
		ExecutionID: executionID,
		StageID:     stageID,
		Timestamp:   now,
		Level:       level,
		Message:     message,
		Stream:      stream,
		LineNumber:  currentLine,
		CreatedAt:   now,
	}

	if ls.cfg.EnablePostgres {
		if err := ls.db.Create(record).Error; err != nil {
			logger.Error("failed to save log to postgres",
				zap.String("execution_id", string(executionID)),
				zap.String("stage_id", string(stageID)),
				zap.Error(err),
			)
		}
	}

	if ls.cfg.EnableRedis && ls.redis != nil {
		channel := ls.getChannel(executionID, stageID)
		payload, err := json.Marshal(record)
		if err != nil {
			logger.Error("failed to marshal log record",
				zap.String("execution_id", string(executionID)),
				zap.String("stage_id", string(stageID)),
				zap.Error(err),
			)
			return
		}

		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()

		if err := ls.redis.Publish(ctx, channel, string(payload)); err != nil {
			logger.Error("failed to publish log to redis",
				zap.String("execution_id", string(executionID)),
				zap.String("stage_id", string(stageID)),
				zap.String("channel", channel),
				zap.Error(err),
			)
		}
	}
}

func (ls *LogStore) GetLogs(executionID types.ID, stageID types.ID, tail int) ([]models.LogRecord, error) {
	var logs []models.LogRecord

	query := ls.db.Model(&models.LogRecord{}).Where("execution_id = ?", executionID)

	if stageID != "" {
		query = query.Where("stage_id = ?", stageID)
	}

	query = query.Order("timestamp ASC, line_number ASC")

	if tail > 0 {
		subQuery := ls.db.Model(&models.LogRecord{}).
			Where("execution_id = ?", executionID)
		if stageID != "" {
			subQuery = subQuery.Where("stage_id = ?", stageID)
		}
		subQuery = subQuery.Order("timestamp DESC, line_number DESC").Limit(tail).Select("id")

		query = ls.db.Model(&models.LogRecord{}).
			Where("id IN (?)", subQuery).
			Order("timestamp ASC, line_number ASC")
	}

	if err := query.Find(&logs).Error; err != nil {
		return nil, fmt.Errorf("failed to query logs: %w", err)
	}

	return logs, nil
}

func (ls *LogStore) CleanupExpired(ctx context.Context) (int64, error) {
	if !ls.cfg.EnablePostgres {
		return 0, nil
	}

	retentionDays := ls.cfg.RetentionDays
	if retentionDays <= 0 {
		retentionDays = 90
	}

	cutoffTime := time.Now().AddDate(0, 0, -retentionDays)

	logger.Info("cleaning up expired logs",
		zap.Time("cutoff_time", cutoffTime),
		zap.Int("retention_days", retentionDays),
	)

	result := ls.db.WithContext(ctx).
		Where("timestamp < ?", cutoffTime).
		Delete(&models.LogRecord{})

	if result.Error != nil {
		return 0, fmt.Errorf("failed to cleanup expired logs: %w", result.Error)
	}

	logger.Info("expired logs cleaned up",
		zap.Int64("deleted_count", result.RowsAffected),
	)

	return result.RowsAffected, nil
}

func (ls *LogStore) getChannel(executionID, stageID types.ID) string {
	if stageID != "" {
		return fmt.Sprintf("%s:%s:%s", ls.cfg.RedisChannel, executionID, stageID)
	}
	return fmt.Sprintf("%s:%s", ls.cfg.RedisChannel, executionID)
}
