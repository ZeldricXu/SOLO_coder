package anomaly

import (
	"context"
	"fmt"
	"go.uber.org/zap"
	"math"
	"metricplatform/internal/models"
	"sort"
	"sync"
	"time"
)

type DetectionAlgorithm string

const (
	AlgorithmZScore        DetectionAlgorithm = "zscore"
	AlgorithmMovingAvg     DetectionAlgorithm = "moving_avg"
	AlgorithmIQR           DetectionAlgorithm = "iqr"
	AlgorithmSeasonal      DetectionAlgorithm = "seasonal"
	AlgorithmIsolationForest DetectionAlgorithm = "isolation_forest"
)

type DetectionPriority int

const (
	PriorityLow    DetectionPriority = 1
	PriorityMedium DetectionPriority = 2
	PriorityHigh   DetectionPriority = 3
	PriorityCritical DetectionPriority = 4
)

type DetectionTask struct {
	MetricName  string
	Value       float64
	Timestamp   time.Time
	Priority    DetectionPriority
	ResultChan  chan *DetectionResult
	Context     context.Context
	SubmittedAt time.Time
}

type DetectionResult struct {
	MetricName    string
	Timestamp     time.Time
	Value         float64
	ExpectedValue float64
	Deviation     float64
	Score         float64
	IsAnomaly     bool
	Algorithm     DetectionAlgorithm
	Severity      string
	Latency       time.Duration
	Error         error
}

type ScalingPolicy struct {
	MinWorkers      int
	MaxWorkers      int
	ScaleUpThreshold   float64
	ScaleDownThreshold float64
	ScaleUpFactor   float64
	ScaleDownFactor float64
	CooldownPeriod  time.Duration
	AvgWaitThreshold time.Duration
}

type Detector struct {
	history      map[string]*MetricHistory
	algorithms   map[DetectionAlgorithm]bool
	threshold    float64
	windowSize   int
	autoRecover  bool
	logger       *zap.Logger
	mu           sync.RWMutex

	taskQueue    chan *DetectionTask
	workers      int
	workerWg     sync.WaitGroup
	workerDone   chan struct{}
	scalingMu    sync.Mutex
	scalingPolicy ScalingPolicy
	lastScale    time.Time
	scaleStats   *ScalingStats

	ctx          context.Context
	cancel       context.CancelFunc
}

type MetricHistory struct {
	MetricName string
	DataPoints []models.MetricDataPoint
	Mean       float64
	StdDev     float64
	Median     float64
	UpdatedAt  time.Time
}

type ScalingStats struct {
	TotalTasks       uint64
	CompletedTasks   uint64
	FailedTasks      uint64
	AvgWaitTime      time.Duration
	AvgProcessTime   time.Duration
	QueueSizeHistory []int
	WorkerHistory    []int
	LastScaledAt     time.Time
	LastScaleAction  string
	mu               sync.RWMutex
}

type DetectorOption func(*Detector)

func WithWorkers(n int) DetectorOption {
	return func(d *Detector) {
		d.workers = n
	}
}

func WithQueueSize(size int) DetectorOption {
	return func(d *Detector) {
		d.taskQueue = make(chan *DetectionTask, size)
	}
}

func WithScalingPolicy(policy ScalingPolicy) DetectorOption {
	return func(d *Detector) {
		d.scalingPolicy = policy
	}
}

func NewDetector(algorithms []DetectionAlgorithm, threshold float64, windowSize int, autoRecover bool, logger *zap.Logger, opts ...DetectorOption) *Detector {
	ctx, cancel := context.WithCancel(context.Background())

	algoMap := make(map[DetectionAlgorithm]bool)
	for _, algo := range algorithms {
		algoMap[algo] = true
	}

	d := &Detector{
		history:     make(map[string]*MetricHistory),
		algorithms:  algoMap,
		threshold:   threshold,
		windowSize:  windowSize,
		autoRecover: autoRecover,
		logger:      logger,
		workers:     4,
		ctx:         ctx,
		cancel:      cancel,
		scalingPolicy: ScalingPolicy{
			MinWorkers:        2,
			MaxWorkers:        32,
			ScaleUpThreshold:  0.7,
			ScaleDownThreshold: 0.3,
			ScaleUpFactor:    1.5,
			ScaleDownFactor:  0.7,
			CooldownPeriod:   2 * time.Minute,
			AvgWaitThreshold: 500 * time.Millisecond,
		},
		scaleStats: &ScalingStats{
			QueueSizeHistory: make([]int, 0, 60),
			WorkerHistory:    make([]int, 0, 60),
		},
	}

	for _, opt := range opts {
		opt(d)
	}

	if d.taskQueue == nil {
		d.taskQueue = make(chan *DetectionTask, 10000)
	}

	return d
}

func (d *Detector) AddDataPoint(point models.MetricDataPoint) {
	d.mu.Lock()
	defer d.mu.Unlock()

	hist, exists := d.history[point.MetricName]
	if !exists {
		hist = &MetricHistory{
			MetricName: point.MetricName,
			DataPoints: make([]models.MetricDataPoint, 0, d.windowSize),
		}
		d.history[point.MetricName] = hist
	}

	hist.DataPoints = append(hist.DataPoints, point)
	if len(hist.DataPoints) > d.windowSize {
		hist.DataPoints = hist.DataPoints[1:]
	}

	d.updateStatistics(hist)
	hist.UpdatedAt = time.Now()
}

func (d *Detector) updateStatistics(hist *MetricHistory) {
	if len(hist.DataPoints) == 0 {
		return
	}

	values := make([]float64, len(hist.DataPoints))
	for i, dp := range hist.DataPoints {
		values[i] = dp.Value
	}

	hist.Mean = mean(values)
	hist.StdDev = stdDev(values, hist.Mean)
	hist.Median = median(values)
}

func (d *Detector) Start() {
	d.startWorkers()
	go d.autoScaler()
	go d.statsCollector()
	d.logger.Info("Anomaly detector started with elastic scaling",
		zap.Int("initial_workers", d.workers),
		zap.Int("min_workers", d.scalingPolicy.MinWorkers),
		zap.Int("max_workers", d.scalingPolicy.MaxWorkers),
		zap.Int("queue_size", cap(d.taskQueue)))
}

func (d *Detector) startWorkers() {
	d.workerWg.Add(d.workers)
	d.workerDone = make(chan struct{})

	for i := 0; i < d.workers; i++ {
		go d.detectionWorker(i)
	}
	d.logger.Debug("Started detection workers", zap.Int("count", d.workers))
}

func (d *Detector) Stop() {
	d.cancel()
	close(d.taskQueue)
	d.workerWg.Wait()
	if d.workerDone != nil {
		close(d.workerDone)
	}
	d.logger.Info("Anomaly detector stopped")
}

func (d *Detector) Detect(ctx context.Context, metricName string, value float64) ([]AnomalyResult, error) {
	task := &DetectionTask{
		MetricName:  metricName,
		Value:       value,
		Timestamp:   time.Now(),
		Priority:    PriorityMedium,
		Context:     ctx,
		SubmittedAt: time.Now(),
	}

	resultChan, err := d.SubmitTask(task)
	if err != nil {
		return nil, err
	}

	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case result := <-resultChan:
		if result != nil && result.Error != nil {
			return nil, result.Error
		}
		return []AnomalyResult{
			{
				MetricName:    result.MetricName,
				Timestamp:     result.Timestamp,
				Value:         result.Value,
				ExpectedValue: result.ExpectedValue,
				Deviation:     result.Deviation,
				Score:         result.Score,
				IsAnomaly:     result.IsAnomaly,
				Algorithm:     result.Algorithm,
				Severity:      result.Severity,
			},
		}, nil
	}
}

func (d *Detector) SubmitTask(task *DetectionTask) (chan *DetectionResult, error) {
	d.scaleStats.mu.Lock()
	d.scaleStats.TotalTasks++
	d.scaleStats.mu.Unlock()

	task.ResultChan = make(chan *DetectionResult, 1)

	select {
	case d.taskQueue <- task:
		return task.ResultChan, nil
	default:
		d.scaleStats.mu.Lock()
		d.scaleStats.FailedTasks++
		d.scaleStats.mu.Unlock()
		return nil, fmt.Errorf("detection queue full, task rejected")
	}
}

func (d *Detector) SubmitTaskAsync(task *DetectionTask, callback func(*DetectionResult)) error {
	d.scaleStats.mu.Lock()
	d.scaleStats.TotalTasks++
	d.scaleStats.mu.Unlock()

	task.ResultChan = make(chan *DetectionResult, 1)

	go func() {
		select {
		case d.taskQueue <- task:
			go func() {
				result := <-task.ResultChan
				if callback != nil {
					callback(result)
				}
			}()
		default:
			d.scaleStats.mu.Lock()
			d.scaleStats.FailedTasks++
			d.scaleStats.mu.Unlock()
			if callback != nil {
				callback(&DetectionResult{
					MetricName: task.MetricName,
					Value:      task.Value,
					Timestamp:  task.Timestamp,
					Error:      fmt.Errorf("queue full"),
				})
			}
		}
	}()

	return nil
}

func (d *Detector) detectionWorker(id int) {
	defer d.workerWg.Done()
	d.logger.Debug("Detection worker started", zap.Int("worker_id", id))

	for {
		select {
		case <-d.ctx.Done():
			d.logger.Debug("Detection worker stopping", zap.Int("worker_id", id))
			return
		case task, ok := <-d.taskQueue:
			if !ok {
				return
			}

			waitTime := time.Since(task.SubmittedAt)
			d.processTask(task, waitTime)

			d.scaleStats.mu.Lock()
			d.scaleStats.CompletedTasks++
			d.scaleStats.AvgWaitTime = (d.scaleStats.AvgWaitTime + waitTime) / 2
			d.scaleStats.mu.Unlock()
		}
	}
}

func (d *Detector) processTask(task *DetectionTask, waitTime time.Duration) {
	startTime := time.Now()
	ctx := task.Context
	if ctx == nil {
		ctx = d.ctx
	}

	d.mu.RLock()
	hist, exists := d.history[task.MetricName]
	d.mu.RUnlock()

	result := &DetectionResult{
		MetricName: task.MetricName,
		Value:      task.Value,
		Timestamp:  task.Timestamp,
	}

	if !exists || len(hist.DataPoints) < d.windowSize/2 {
		result.IsAnomaly = false
		result.Latency = time.Since(startTime)
		task.ResultChan <- result
		return
	}

	if d.algorithms[AlgorithmZScore] {
		if r, ok := d.detectZScore(hist, task.Value); ok {
			result.IsAnomaly = true
			result.Score = r.Score
			result.ExpectedValue = r.ExpectedValue
			result.Deviation = r.Deviation
			result.Algorithm = r.Algorithm
			result.Severity = r.Severity
		}
	}

	if !result.IsAnomaly && d.algorithms[AlgorithmMovingAvg] {
		if r, ok := d.detectMovingAvg(hist, task.Value); ok {
			result.IsAnomaly = true
			result.Score = r.Score
			result.ExpectedValue = r.ExpectedValue
			result.Deviation = r.Deviation
			result.Algorithm = r.Algorithm
			result.Severity = r.Severity
		}
	}

	if !result.IsAnomaly && d.algorithms[AlgorithmIQR] {
		if r, ok := d.detectIQR(hist, task.Value); ok {
			result.IsAnomaly = true
			result.Score = r.Score
			result.ExpectedValue = r.ExpectedValue
			result.Deviation = r.Deviation
			result.Algorithm = r.Algorithm
			result.Severity = r.Severity
		}
	}

	if !result.IsAnomaly && d.algorithms[AlgorithmSeasonal] {
		if r, ok := d.detectSeasonal(hist, task.Value); ok {
			result.IsAnomaly = true
			result.Score = r.Score
			result.ExpectedValue = r.ExpectedValue
			result.Deviation = r.Deviation
			result.Algorithm = r.Algorithm
			result.Severity = r.Severity
		}
	}

	result.Latency = time.Since(startTime)

	d.scaleStats.mu.Lock()
	d.scaleStats.AvgProcessTime = (d.scaleStats.AvgProcessTime + result.Latency) / 2
	d.scaleStats.mu.Unlock()

	if result.IsAnomaly {
		d.logger.Info("Anomaly detected",
			zap.String("metric", task.MetricName),
			zap.Float64("value", task.Value),
			zap.String("algorithm", string(result.Algorithm)),
			zap.Duration("wait_time", waitTime),
			zap.Duration("process_time", result.Latency))

		if d.autoRecover {
			d.autoRecoverAction(ctx, task.MetricName, []AnomalyResult{{
				Algorithm: result.Algorithm,
				Severity:  result.Severity,
			}})
		}
	}

	task.ResultChan <- result
}

func (d *Detector) autoScaler() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-d.ctx.Done():
			return
		case <-ticker.C:
			d.evaluateScaling()
		}
	}
}

func (d *Detector) evaluateScaling() {
	d.scalingMu.Lock()
	defer d.scalingMu.Unlock()

	queueSize := len(d.taskQueue)
	capacity := float64(cap(d.taskQueue))
	utilization := float64(queueSize) / capacity

	d.scaleStats.mu.RLock()
	avgWait := d.scaleStats.AvgWaitTime
	d.scaleStats.mu.RUnlock()

	cooldownElapsed := time.Since(d.lastScale) > d.scalingPolicy.CooldownPeriod

	d.logger.Debug("Evaluating scaling",
		zap.Int("queue_size", queueSize),
		zap.Int("workers", d.workers),
		zap.Float64("utilization", utilization),
		zap.Duration("avg_wait", avgWait),
		zap.Bool("cooldown_elapsed", cooldownElapsed))

	if !cooldownElapsed {
		return
	}

	shouldScaleUp := utilization > d.scalingPolicy.ScaleUpThreshold ||
		avgWait > d.scalingPolicy.AvgWaitThreshold
	shouldScaleDown := utilization < d.scalingPolicy.ScaleDownThreshold &&
		avgWait < d.scalingPolicy.AvgWaitThreshold/2

	if shouldScaleUp && d.workers < d.scalingPolicy.MaxWorkers {
		d.scaleUp()
	} else if shouldScaleDown && d.workers > d.scalingPolicy.MinWorkers {
		d.scaleDown()
	}

	d.scaleStats.mu.Lock()
	d.scaleStats.QueueSizeHistory = append(d.scaleStats.QueueSizeHistory, queueSize)
	d.scaleStats.WorkerHistory = append(d.scaleStats.WorkerHistory, d.workers)
	if len(d.scaleStats.QueueSizeHistory) > 60 {
		d.scaleStats.QueueSizeHistory = d.scaleStats.QueueSizeHistory[1:]
		d.scaleStats.WorkerHistory = d.scaleStats.WorkerHistory[1:]
	}
	d.scaleStats.mu.Unlock()
}

func (d *Detector) scaleUp() {
	desired := int(float64(d.workers) * d.scalingPolicy.ScaleUpFactor)
	if desired > d.scalingPolicy.MaxWorkers {
		desired = d.scalingPolicy.MaxWorkers
	}
	if desired == d.workers {
		desired++
	}

	newWorkers := desired - d.workers
	if newWorkers <= 0 {
		return
	}

	d.workerWg.Add(newWorkers)
	for i := 0; i < newWorkers; i++ {
		go d.detectionWorker(d.workers + i)
	}
	d.workers = desired
	d.lastScale = time.Now()

	d.scaleStats.mu.Lock()
	d.scaleStats.LastScaledAt = time.Now()
	d.scaleStats.LastScaleAction = fmt.Sprintf("scaled_up_to_%d", d.workers)
	d.scaleStats.mu.Unlock()

	d.logger.Info("Scaled up detection workers",
		zap.Int("new_count", d.workers),
		zap.Int("added", newWorkers))
}

func (d *Detector) scaleDown() {
	desired := int(float64(d.workers) * d.scalingPolicy.ScaleDownFactor)
	if desired < d.scalingPolicy.MinWorkers {
		desired = d.scalingPolicy.MinWorkers
	}
	if desired >= d.workers {
		return
	}

	removed := d.workers - desired
	d.workers = desired
	d.lastScale = time.Now()

	d.scaleStats.mu.Lock()
	d.scaleStats.LastScaledAt = time.Now()
	d.scaleStats.LastScaleAction = fmt.Sprintf("scaled_down_to_%d", d.workers)
	d.scaleStats.mu.Unlock()

	d.logger.Info("Scaled down detection workers",
		zap.Int("new_count", d.workers),
		zap.Int("removed", removed))
}

func (d *Detector) statsCollector() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-d.ctx.Done():
			return
		case <-ticker.C:
			d.logStats()
		}
	}
}

func (d *Detector) logStats() {
	d.scaleStats.mu.RLock()
	defer d.scaleStats.mu.RUnlock()

	d.logger.Info("Anomaly detector statistics",
		zap.Int("workers", d.workers),
		zap.Int("queue_size", len(d.taskQueue)),
		zap.Uint64("total_tasks", d.scaleStats.TotalTasks),
		zap.Uint64("completed_tasks", d.scaleStats.CompletedTasks),
		zap.Uint64("failed_tasks", d.scaleStats.FailedTasks),
		zap.Duration("avg_wait_time", d.scaleStats.AvgWaitTime),
		zap.Duration("avg_process_time", d.scaleStats.AvgProcessTime),
		zap.Time("last_scaled_at", d.scaleStats.LastScaledAt),
		zap.String("last_scale_action", d.scaleStats.LastScaleAction))
}

type AnomalyResult struct {
	MetricName    string
	Timestamp     time.Time
	Value         float64
	ExpectedValue float64
	Deviation     float64
	Score         float64
	IsAnomaly     bool
	Algorithm     DetectionAlgorithm
	Severity      string
}

func (d *Detector) detectZScore(hist *MetricHistory, value float64) (AnomalyResult, bool) {
	if hist.StdDev == 0 {
		return AnomalyResult{}, false
	}

	zScore := math.Abs(value-hist.Mean) / hist.StdDev
	result := AnomalyResult{
		MetricName:    hist.MetricName,
		Timestamp:     time.Now(),
		Value:         value,
		ExpectedValue: hist.Mean,
		Deviation:     value - hist.Mean,
		Score:         zScore,
		Algorithm:     AlgorithmZScore,
		IsAnomaly:     zScore > d.threshold,
	}

	if result.IsAnomaly {
		result.Severity = getSeverity(zScore, d.threshold)
	}

	return result, result.IsAnomaly
}

func (d *Detector) detectMovingAvg(hist *MetricHistory, value float64) (AnomalyResult, bool) {
	if len(hist.DataPoints) < 5 {
		return AnomalyResult{}, false
	}

	window := 5
	if len(hist.DataPoints) < window {
		window = len(hist.DataPoints)
	}

	recentValues := make([]float64, window)
	for i := 0; i < window; i++ {
		recentValues[i] = hist.DataPoints[len(hist.DataPoints)-window+i].Value
	}

	movingAvg := mean(recentValues)
	dev := math.Abs(value - movingAvg)

	result := AnomalyResult{
		MetricName:    hist.MetricName,
		Timestamp:     time.Now(),
		Value:         value,
		ExpectedValue: movingAvg,
		Deviation:     value - movingAvg,
		Score:         dev,
		Algorithm:     AlgorithmMovingAvg,
		IsAnomaly:     dev > d.threshold*hist.StdDev,
	}

	if result.IsAnomaly {
		result.Severity = getSeverity(dev, d.threshold*hist.StdDev)
	}

	return result, result.IsAnomaly
}

func (d *Detector) detectIQR(hist *MetricHistory, value float64) (AnomalyResult, bool) {
	values := make([]float64, len(hist.DataPoints))
	for i, dp := range hist.DataPoints {
		values[i] = dp.Value
	}
	sort.Float64s(values)

	q1 := percentile(values, 25)
	q3 := percentile(values, 75)
	iqr := q3 - q1

	lowerBound := q1 - 1.5*iqr
	upperBound := q3 + 1.5*iqr

	isAnomaly := value < lowerBound || value > upperBound
	score := 0.0
	if value < lowerBound {
		score = lowerBound - value
	} else if value > upperBound {
		score = value - upperBound
	}

	result := AnomalyResult{
		MetricName:    hist.MetricName,
		Timestamp:     time.Now(),
		Value:         value,
		ExpectedValue: hist.Median,
		Deviation:     value - hist.Median,
		Score:         score,
		Algorithm:     AlgorithmIQR,
		IsAnomaly:     isAnomaly,
	}

	if result.IsAnomaly {
		result.Severity = getSeverity(score, iqr)
	}

	return result, result.IsAnomaly
}

func (d *Detector) detectSeasonal(hist *MetricHistory, value float64) (AnomalyResult, bool) {
	if len(hist.DataPoints) < 24 {
		return AnomalyResult{}, false
	}

	seasonalPattern := make([]float64, 24)
	counts := make([]int, 24)

	for _, dp := range hist.DataPoints {
		hour := dp.Timestamp.Hour()
		seasonalPattern[hour] += dp.Value
		counts[hour]++
	}

	for i := range seasonalPattern {
		if counts[i] > 0 {
			seasonalPattern[i] /= float64(counts[i])
		}
	}

	currentHour := time.Now().Hour()
	expected := seasonalPattern[currentHour]
	dev := math.Abs(value - expected)

	result := AnomalyResult{
		MetricName:    hist.MetricName,
		Timestamp:     time.Now(),
		Value:         value,
		ExpectedValue: expected,
		Deviation:     value - expected,
		Score:         dev,
		Algorithm:     AlgorithmSeasonal,
		IsAnomaly:     dev > d.threshold*hist.StdDev,
	}

	if result.IsAnomaly {
		result.Severity = getSeverity(dev, d.threshold*hist.StdDev)
	}

	return result, result.IsAnomaly
}

func (d *Detector) autoRecoverAction(ctx context.Context, metricName string, results []AnomalyResult) {
	d.logger.Info("Auto-recovery triggered for anomaly",
		zap.String("metric", metricName),
		zap.Int("anomaly_count", len(results)))
}

func mean(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sum := 0.0
	for _, v := range values {
		sum += v
	}
	return sum / float64(len(values))
}

func stdDev(values []float64, mean float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sum := 0.0
	for _, v := range values {
		diff := v - mean
		sum += diff * diff
	}
	return math.Sqrt(sum / float64(len(values)))
}

func median(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)

	n := len(sorted)
	if n%2 == 0 {
		return (sorted[n/2-1] + sorted[n/2]) / 2
	}
	return sorted[n/2]
}

func percentile(values []float64, p float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)

	index := (p / 100) * float64(len(sorted)-1)
	lower := int(math.Floor(index))
	upper := int(math.Ceil(index))
	if lower == upper {
		return sorted[lower]
	}
	return sorted[lower] + (sorted[upper]-sorted[lower])*(index-float64(lower))
}

func getSeverity(score, threshold float64) string {
	ratio := score / threshold
	switch {
	case ratio >= 5:
		return "critical"
	case ratio >= 3:
		return "major"
	case ratio >= 1.5:
		return "minor"
	default:
		return "warning"
	}
}

func (d *Detector) GetHistory(metricName string) (*MetricHistory, bool) {
	d.mu.RLock()
	defer d.mu.RUnlock()
	hist, ok := d.history[metricName]
	return hist, ok
}

func (d *Detector) GetAllMetrics() []string {
	d.mu.RLock()
	defer d.mu.RUnlock()
	metrics := make([]string, 0, len(d.history))
	for name := range d.history {
		metrics = append(metrics, name)
	}
	return metrics
}

func (d *Detector) GetStats() map[string]interface{} {
	d.scaleStats.mu.RLock()
	defer d.scaleStats.mu.RUnlock()

	return map[string]interface{}{
		"workers":          d.workers,
		"min_workers":      d.scalingPolicy.MinWorkers,
		"max_workers":      d.scalingPolicy.MaxWorkers,
		"queue_size":       len(d.taskQueue),
		"queue_capacity":   cap(d.taskQueue),
		"total_tasks":      d.scaleStats.TotalTasks,
		"completed_tasks":  d.scaleStats.CompletedTasks,
		"failed_tasks":     d.scaleStats.FailedTasks,
		"avg_wait_time":    d.scaleStats.AvgWaitTime.String(),
		"avg_process_time": d.scaleStats.AvgProcessTime.String(),
		"last_scaled_at":   d.scaleStats.LastScaledAt,
		"last_scale_action": d.scaleStats.LastScaleAction,
		"metrics_tracked":  len(d.history),
	}
}


