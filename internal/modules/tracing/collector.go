package tracing

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"session189/internal/domain"
	"session189/internal/infrastructure/database"
	"session189/internal/infrastructure/logger"
)

type SpanCollector struct {
	spanBuffer chan *domain.TraceSpan
	bufferSize   int
	flushInterval time.Duration
	sampler      *Sampler
	mu           sync.Mutex
	wg           sync.WaitGroup
	stopCh       chan struct{}
}

func NewSpanCollector(bufferSize int, flushInterval time.Duration, sampler *Sampler) *SpanCollector {
	if bufferSize <= 0 {
		bufferSize = 1000
	}
	if flushInterval <= 0 {
		flushInterval = 5 * time.Second
	}

	return &SpanCollector{
		spanBuffer:    make(chan *domain.TraceSpan, bufferSize),
		bufferSize:    bufferSize,
		flushInterval: flushInterval,
		sampler:       sampler,
		stopCh:        make(chan struct{}),
	}
}

func (c *SpanCollector) Start() {
	go c.flushWorker()
	logger.Info("Span collector started",
		zap.Int("buffer_size", c.bufferSize),
		zap.Duration("flush_interval", c.flushInterval))
}

func (c *SpanCollector) Stop() {
	close(c.stopCh)
	c.wg.Wait()
	c.flushBuffer()
	logger.Info("Span collector stopped")
}

func (c *SpanCollector) Collect(ctx context.Context, span *domain.TraceSpan) (bool, error) {
	if span == nil {
		return false, fmt.Errorf("span is nil")
	}

	if span.SpanID == "" {
		span.SpanID = generateSpanID()
	}
	if span.TraceID == "" {
		span.TraceID = generateTraceID()
	}
	if span.StartTime.IsZero() {
		span.StartTime = time.Now()
	}
	if span.EndTime.IsZero() {
		span.EndTime = time.Now()
	}
	if span.DurationNano == 0 {
		span.DurationNano = span.EndTime.Sub(span.StartTime).Nanoseconds()
	}

	sampled := true
	if c.sampler != nil {
		var err error
		sampled, err = c.sampler.ShouldSample(span)
		if err != nil {
			logger.Warn("Sampling error", zap.Error(err))
		}
	}

	span.Sampled = sampled
	span.CreatedAt = time.Now()

	if sampled {
		select {
		case c.spanBuffer <- span:
		default:
			logger.Warn("Span buffer full, dropping span",
				zap.String("trace_id", span.TraceID),
				zap.String("span_id", span.SpanID))
		}
	}

	return sampled, nil
}

func (c *SpanCollector) flushWorker() {
	ticker := time.NewTicker(c.flushInterval)
	defer ticker.Stop()

	for {
		select {
		case <-c.stopCh:
			return
		case <-ticker.C:
			c.flushBuffer()
		}
	}
}

func (c *SpanCollector) flushBuffer() {
	c.mu.Lock()
	defer c.mu.Unlock()

	var spans []*domain.TraceSpan

loop:
	for {
		select {
		case span := <-c.spanBuffer:
			spans = append(spans, span)
			if len(spans) >= 100 {
				break loop
			}
		default:
			break loop
		}
	}

	if len(spans) == 0 {
		return
	}

	if err := c.saveSpans(context.Background(), spans); err != nil {
		logger.Error("Failed to save spans", zap.Error(err))
		return
	}

	c.updateTraceSummaries(context.Background(), spans)

	logger.Debug("Flushed spans", zap.Int("count", len(spans)))
}

func (c *SpanCollector) saveSpans(ctx context.Context, spans []*domain.TraceSpan) error {
	if len(spans) == 0 {
		return nil
	}

	tx := database.DB.WithContext(ctx).Begin()
	if tx.Error != nil {
		return tx.Error
	}

	for _, span := range spans {
		if err := tx.Create(span).Error; err != nil {
			tx.Rollback()
			return fmt.Errorf("save span failed: %w", err)
		}
	}

	return tx.Commit().Error
}

func (c *SpanCollector) updateTraceSummaries(ctx context.Context, spans []*domain.TraceSpan) {
	traceMap := make(map[string][]*domain.TraceSpan)
	for _, span := range spans {
		traceMap[span.TraceID] = append(traceMap[span.TraceID], span)
	}

	for traceID, traceSpans := range traceMap {
		if err := c.updateTraceSummary(ctx, traceID, traceSpans); err != nil {
			logger.Error("Failed to update trace summary",
				zap.String("trace_id", traceID),
				zap.Error(err))
		}
	}
}

func (c *SpanCollector) updateTraceSummary(ctx context.Context, traceID string, spans []*domain.TraceSpan) error {
	var existingSummary domain.TraceSummary
	err := database.DB.WithContext(ctx).Where("trace_id = ?", traceID).First(&existingSummary).Error

	if err != nil {
		summary := &domain.TraceSummary{
			SummaryID: uuid.New().String(),
			TraceID:   traceID,
			SpanCount: int32(len(spans)),
		}

		services := make(map[string]bool)
		hasError := false
		var minStartTime, maxEndTime time.Time

		for _, span := range spans {
			services[span.ServiceName] = true
			if span.Status == domain.SpanStatusError {
				hasError = true
			}
			if minStartTime.IsZero() || span.StartTime.Before(minStartTime) {
				minStartTime = span.StartTime
			}
			if maxEndTime.IsZero() || span.EndTime.After(maxEndTime) {
				maxEndTime = span.EndTime
			}
			if span.ParentSpanID == "" {
				summary.RootService = span.ServiceName
				summary.RootOperation = span.Name
			}
		}

		summary.ServiceCount = int32(len(services))
		summary.HasError = hasError
		summary.TotalDurationNano = maxEndTime.Sub(minStartTime).Nanoseconds()
		summary.StartTime = minStartTime
		summary.EndTime = maxEndTime

		return database.DB.WithContext(ctx).Create(summary).Error
	}

	existingSummary.SpanCount += int32(len(spans))
	var maxDuration int64
	var latestEndTime time.Time
	for _, span := range spans {
		if span.Status == domain.SpanStatusError {
			existingSummary.HasError = true
		}
		duration := span.EndTime.Sub(span.StartTime).Nanoseconds()
		if duration > maxDuration {
			maxDuration = duration
		}
		if span.EndTime.After(latestEndTime) {
			latestEndTime = span.EndTime
		}
	}
	existingSummary.TotalDurationNano = max(existingSummary.TotalDurationNano, maxDuration)
	existingSummary.EndTime = maxTime(existingSummary.EndTime, latestEndTime)

	return database.DB.WithContext(ctx).Save(&existingSummary).Error
}

func (c *SpanCollector) GetSpan(ctx context.Context, spanID string) (*domain.TraceSpan, error) {
	var span domain.TraceSpan
	if err := database.DB.WithContext(ctx).Where("span_id = ?", spanID).First(&span).Error; err != nil {
		return nil, fmt.Errorf("get span failed: %w", err)
	}
	return &span, nil
}

func (c *SpanCollector) GetTrace(ctx context.Context, traceID string) ([]domain.TraceSpan, error) {
	var spans []domain.TraceSpan
	if err := database.DB.WithContext(ctx).
		Where("trace_id = ?", traceID).
		Order("start_time ASC").
		Find(&spans).Error; err != nil {
		return nil, fmt.Errorf("get trace failed: %w", err)
	}
	return spans, nil
}

func (c *SpanCollector) ListTraces(ctx context.Context, serviceName string, hasError *bool, offset, limit int) ([]domain.TraceSummary, int64, error) {
	var summaries []domain.TraceSummary
	var total int64

	query := database.DB.WithContext(ctx).Model(&domain.TraceSummary{})
	if serviceName != "" {
		query = query.Where("root_service = ?", serviceName)
	}
	if hasError != nil {
		query = query.Where("has_error = ?", *hasError)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count traces failed: %w", err)
	}

	if err := query.Order("start_time DESC").Offset(offset).Limit(limit).Find(&summaries).Error; err != nil {
		return nil, 0, fmt.Errorf("list traces failed: %w", err)
	}

	return summaries, total, nil
}

func (c *SpanCollector) GetTraceSummary(ctx context.Context, traceID string) (*domain.TraceSummary, error) {
	var summary domain.TraceSummary
	if err := database.DB.WithContext(ctx).Where("trace_id = ?", traceID).First(&summary).Error; err != nil {
		return nil, fmt.Errorf("get trace summary failed: %w", err)
	}
	return &summary, nil
}

func (c *SpanCollector) ListSpans(ctx context.Context, serviceName string, status domain.SpanStatus, offset, limit int) ([]domain.TraceSpan, int64, error) {
	var spans []domain.TraceSpan
	var total int64

	query := database.DB.WithContext(ctx).Model(&domain.TraceSpan{})
	if serviceName != "" {
		query = query.Where("service_name = ?", serviceName)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, fmt.Errorf("count spans failed: %w", err)
	}

	if err := query.Order("start_time DESC").Offset(offset).Limit(limit).Find(&spans).Error; err != nil {
		return nil, 0, fmt.Errorf("list spans failed: %w", err)
	}

	return spans, total, nil
}

func generateSpanID() string {
	return uuid.New().String()[:16]
}

func generateTraceID() string {
	return uuid.New().String()
}

func max(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

func maxTime(a, b time.Time) time.Time {
	if a.After(b) {
		return a
	}
	return b
}
