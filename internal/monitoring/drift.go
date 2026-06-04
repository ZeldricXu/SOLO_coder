package monitoring

import (
	"context"
	"encoding/json"
	"fmt"
	"math"
	"model-inference-platform/internal/notification"
	"model-inference-platform/internal/pkg/database"
	"sync"
	"time"

	"github.com/google/uuid"
	"gonum.org/v1/gonum/stat"
	"go.uber.org/zap"
)

type DistributionType string

const (
	DistClassification DistributionType = "classification"
	DistRegression     DistributionType = "regression"
)

type DriftAlert struct {
	ID             string    `json:"id"`
	ModelName      string    `json:"model_name"`
	Version        string    `json:"version"`
	Namespace      string    `json:"namespace"`
	DriftScore     float64   `json:"drift_score"`
	PSIScore       float64   `json:"psi_score,omitempty"`
	Threshold      float64   `json:"threshold"`
	DetectedAt     time.Time `json:"detected_at"`
	Message        string    `json:"message"`
	DriftType      string    `json:"drift_type"`
	Notified       bool      `json:"notified"`
}

type DriftNotificationConfig struct {
	Enabled          bool          `json:"enabled"`
	NotificationCoolDown time.Duration `json:"notification_cooldown"`
	OwnerEmails      []string      `json:"owner_emails"`
	OwnerDingtalk    string        `json:"owner_dingtalk"`
	OwnerWechat      string        `json:"owner_wechat"`
}

type PredictionDistMonitor struct {
	db          *database.Database
	logger      *zap.Logger
	notifier    *notification.NotificationManager

	trainingDistributions map[string]map[string]*DistributionProfile
	profileMu sync.RWMutex

	predictionBuffers map[string]*PredictionBuffer
	bufferMu    sync.RWMutex

	notificationConfigs map[string]*DriftNotificationConfig
	configMu    sync.RWMutex

	lastNotification map[string]time.Time
	lastNotifyMu sync.RWMutex

	driftThreshold float64
	psiThreshold   float64
	windowSize     time.Duration
	notificationCoolDown time.Duration

	stopCh chan struct{}
	wg     sync.WaitGroup
}

type DistributionProfile struct {
	Type         DistributionType  `json:"type"`
	LabelCounts  map[string]int64  `json:"label_counts"`
	Probabilities map[string]float64 `json:"probabilities"`
	Mean         float64           `json:"mean,omitempty"`
	StdDev       float64           `json:"std_dev,omitempty"`
	Min          float64           `json:"min,omitempty"`
	Max          float64           `json:"max,omitempty"`
}

type PredictionBuffer struct {
	ModelName   string
	Version     string
	Namespace   string
	Predictions []PredictionSample
	WindowStart time.Time
}

type PredictionSample struct {
	Label      string    `json:"label"`
	Value      float64   `json:"value,omitempty"`
	Timestamp  time.Time `json:"timestamp"`
	Confidence float64   `json:"confidence"`
}

func NewPredictionDistMonitor(db *database.Database, notifier *notification.NotificationManager,
	logger *zap.Logger) *PredictionDistMonitor {
	return &PredictionDistMonitor{
		db:                    db,
		logger:                logger,
		notifier:              notifier,
		trainingDistributions: make(map[string]map[string]*DistributionProfile),
		predictionBuffers:     make(map[string]*PredictionBuffer),
		notificationConfigs:   make(map[string]*DriftNotificationConfig),
		lastNotification:      make(map[string]time.Time),
		driftThreshold:        0.3,
		psiThreshold:          0.25,
		windowSize:            1 * time.Hour,
		notificationCoolDown:  1 * time.Hour,
		stopCh:                make(chan struct{}),
	}
}

func (m *PredictionDistMonitor) SetNotifier(notifier *notification.NotificationManager) {
	m.notifier = notifier
}

func (m *PredictionDistMonitor) SetNotificationConfig(namespace, modelName, version string,
	config *DriftNotificationConfig) {
	key := m.getKey(namespace, modelName, version)
	m.configMu.Lock()
	m.notificationConfigs[key] = config
	m.configMu.Unlock()
}

func (m *PredictionDistMonitor) GetNotificationConfig(namespace, modelName, version string) *DriftNotificationConfig {
	key := m.getKey(namespace, modelName, version)
	m.configMu.RLock()
	defer m.configMu.RUnlock()
	return m.notificationConfigs[key]
}

func (m *PredictionDistMonitor) Start(ctx context.Context) error {
	if err := m.loadTrainingDistributions(ctx); err != nil {
		m.logger.Warn("Failed to load training distributions", zap.Error(err))
	}

	m.wg.Add(2)
	go m.driftDetectionLoop(ctx)
	go m.bufferRotationLoop(ctx)

	m.logger.Info("Prediction distribution monitor started")
	return nil
}

func (m *PredictionDistMonitor) Stop() {
	close(m.stopCh)
	m.wg.Wait()
	m.logger.Info("Prediction distribution monitor stopped")
}

func (m *PredictionDistMonitor) loadTrainingDistributions(ctx context.Context) error {
	query := `
		SELECT mv.id, m.name, mv.version, m.namespace, mv.metadata
		FROM model_versions mv
		JOIN models m ON mv.model_id = m.id
		WHERE mv.metadata ? 'training_distribution'
	`

	rows, err := m.db.Query(ctx, query)
	if err != nil {
		return err
	}
	defer rows.Close()

	for rows.Next() {
		var versionID, modelName, version, namespace string
		var metadataJSON []byte

		err := rows.Scan(&versionID, &modelName, &version, &namespace, &metadataJSON)
		if err != nil {
			continue
		}

		var metadata map[string]interface{}
		json.Unmarshal(metadataJSON, &metadata)

		if dist, ok := metadata["training_distribution"]; ok {
			distJSON, _ := json.Marshal(dist)
			var profile DistributionProfile
			if json.Unmarshal(distJSON, &profile) == nil {
				key := m.getKey(namespace, modelName, version)
				m.profileMu.Lock()
				if _, ok := m.trainingDistributions[key]; !ok {
					m.trainingDistributions[key] = make(map[string]*DistributionProfile)
				}
				m.trainingDistributions[key][version] = &profile
				m.profileMu.Unlock()
			}
		}
	}

	return nil
}

func (m *PredictionDistMonitor) RegisterTrainingDistribution(namespace, modelName, version string, profile *DistributionProfile) {
	key := m.getKey(namespace, modelName, version)
	m.profileMu.Lock()
	if _, ok := m.trainingDistributions[key]; !ok {
		m.trainingDistributions[key] = make(map[string]*DistributionProfile)
	}
	m.trainingDistributions[key][version] = profile
	m.profileMu.Unlock()
}

func (m *PredictionDistMonitor) RecordPrediction(namespace, modelName, version, label string, value float64, confidence float64) {
	key := m.getKey(namespace, modelName, version)

	m.bufferMu.Lock()
	buffer, ok := m.predictionBuffers[key]
	if !ok {
		buffer = &PredictionBuffer{
			ModelName:   modelName,
			Version:     version,
			Namespace:   namespace,
			Predictions: make([]PredictionSample, 0),
			WindowStart: time.Now(),
		}
		m.predictionBuffers[key] = buffer
	}

	buffer.Predictions = append(buffer.Predictions, PredictionSample{
		Label:      label,
		Value:      value,
		Timestamp:  time.Now(),
		Confidence: confidence,
	})
	m.bufferMu.Unlock()
}

func (m *PredictionDistMonitor) driftDetectionLoop(ctx context.Context) {
	defer m.wg.Done()

	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-m.stopCh:
			return
		case <-ticker.C:
			m.checkAllDrifts(ctx)
		}
	}
}

func (m *PredictionDistMonitor) bufferRotationLoop(ctx context.Context) {
	defer m.wg.Done()

	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-m.stopCh:
			return
		case <-ticker.C:
			m.rotateBuffers(ctx)
		}
	}
}

func (m *PredictionDistMonitor) checkAllDrifts(ctx context.Context) {
	m.bufferMu.RLock()
	buffers := make([]*PredictionBuffer, 0, len(m.predictionBuffers))
	for _, buf := range m.predictionBuffers {
		buffers = append(buffers, buf)
	}
	m.bufferMu.RUnlock()

	for _, buf := range buffers {
		if len(buf.Predictions) < 100 {
			continue
		}

		m.checkDrift(ctx, buf)
	}
}

func (m *PredictionDistMonitor) checkDrift(ctx context.Context, buf *PredictionBuffer) {
	key := m.getKey(buf.Namespace, buf.ModelName, buf.Version)

	m.profileMu.RLock()
	profile, hasProfile := m.trainingDistributions[key][buf.Version]
	m.profileMu.RUnlock()

	if !hasProfile {
		return
	}

	var driftScore float64
	var psiScore float64
	var driftType string

	if profile.Type == DistClassification {
		currentDist := m.buildClassificationDistribution(buf.Predictions)
		driftScore = m.calculateKLDivergence(profile.Probabilities, currentDist)
		psiScore = m.calculatePSI(profile.Probabilities, currentDist)
		driftType = "classification_kl_divergence"
	} else {
		currentMean, currentStd := m.calculateRegressionStats(buf.Predictions)
		driftScore = m.calculateKSStatistic(profile, currentMean, currentStd)
		driftType = "regression_ks_statistic"
	}

	isAlert := driftScore > m.driftThreshold
	hasSignificantPSI := psiScore > m.psiThreshold

	if isAlert || hasSignificantPSI {
		m.persistDistribution(ctx, buf, driftScore, isAlert)

		alertMsg := fmt.Sprintf("Concept drift detected for model %s:%s", buf.ModelName, buf.Version)
		if profile.Type == DistClassification {
			alertMsg += fmt.Sprintf(". KL散度: %.4f, PSI: %.4f, 阈值: %.4f",
				driftScore, psiScore, m.driftThreshold)
		} else {
			alertMsg += fmt.Sprintf(". KS统计量: %.4f, 阈值: %.4f", driftScore, m.driftThreshold)
		}

		m.logger.Warn("Concept drift detected",
			zap.String("model", buf.ModelName),
			zap.String("version", buf.Version),
			zap.String("namespace", buf.Namespace),
			zap.Float64("drift_score", driftScore),
			zap.Float64("psi_score", psiScore),
			zap.String("drift_type", driftType))

		alert := m.createAlert(ctx, buf, driftScore, driftType, psiScore)

		if m.shouldSendNotification(key) {
			m.sendDriftNotification(ctx, alert)
		}
	}
}

func (m *PredictionDistMonitor) buildClassificationDistribution(samples []PredictionSample) map[string]float64 {
	counts := make(map[string]int64)
	total := int64(len(samples))

	for _, s := range samples {
		counts[s.Label]++
	}

	probs := make(map[string]float64)
	for label, count := range counts {
		probs[label] = float64(count) / float64(total)
	}

	return probs
}

func (m *PredictionDistMonitor) calculateRegressionStats(samples []PredictionSample) (float64, float64) {
	values := make([]float64, len(samples))
	for i, s := range samples {
		values[i] = s.Value
	}

	return stat.Mean(values, nil), stat.StdDev(values, nil)
}

func (m *PredictionDistMonitor) calculateKLDivergence(p, q map[string]float64) float64 {
	var klDiv float64

	allLabels := make(map[string]bool)
	for label := range p {
		allLabels[label] = true
	}
	for label := range q {
		allLabels[label] = true
	}

	epsilon := 1e-10

	for label := range allLabels {
		pVal := p[label]
		qVal := q[label]

		if pVal == 0 {
			pVal = epsilon
		}
		if qVal == 0 {
			qVal = epsilon
		}

		klDiv += pVal * math.Log(pVal/qVal)
	}

	return klDiv
}

func (m *PredictionDistMonitor) calculateKSStatistic(profile *DistributionProfile, currentMean, currentStd float64) float64 {
	meanShift := math.Abs(currentMean-profile.Mean) / profile.StdDev
	stdShift := math.Abs(currentStd-profile.StdDev) / profile.StdDev

	return (meanShift + stdShift) / 2.0
}

func (m *PredictionDistMonitor) persistDistribution(ctx context.Context, buf *PredictionBuffer, driftScore float64, isAlert bool) {
	labelDist := m.buildLabelDistribution(buf.Predictions)
	distJSON, _ := json.Marshal(labelDist)

	query := `
		INSERT INTO prediction_distributions (model_name, version, namespace, window_start,
			window_end, label_distribution, total_samples, drift_score, is_alert)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
		ON CONFLICT (model_name, version, window_start) DO UPDATE
		SET label_distribution = $6, total_samples = $7, drift_score = $8, is_alert = $9
	`

	_, err := m.db.Exec(ctx, query, buf.ModelName, buf.Version, buf.Namespace,
		buf.WindowStart, time.Now(), distJSON, len(buf.Predictions), driftScore, isAlert)
	if err != nil {
		m.logger.Warn("Failed to persist distribution", zap.Error(err))
	}
}

func (m *PredictionDistMonitor) buildLabelDistribution(samples []PredictionSample) map[string]int64 {
	counts := make(map[string]int64)
	for _, s := range samples {
		counts[s.Label]++
	}
	return counts
}

func (m *PredictionDistMonitor) createAlert(ctx context.Context, buf *PredictionBuffer,
	driftScore float64, driftType string, psiScore float64) *DriftAlert {

	alert := &DriftAlert{
		ID:         uuid.New().String(),
		ModelName:  buf.ModelName,
		Version:    buf.Version,
		Namespace:  buf.Namespace,
		DriftScore: driftScore,
		PSIScore:   psiScore,
		Threshold:  m.driftThreshold,
		DetectedAt: time.Now(),
		Message:    "Concept drift detected - prediction distribution differs significantly from training distribution",
		DriftType:  driftType,
	}

	m.logger.Warn("Drift alert created",
		zap.String("model", alert.ModelName),
		zap.String("type", alert.DriftType),
		zap.Float64("kl_divergence", alert.DriftScore),
		zap.Float64("psi", alert.PSIScore))

	return alert
}

func (m *PredictionDistMonitor) calculatePSI(expected, actual map[string]float64) float64 {
	const epsilon = 0.0001

	allLabels := make(map[string]bool)
	for label := range expected {
		allLabels[label] = true
	}
	for label := range actual {
		allLabels[label] = true
	}

	var psi float64
	for label := range allLabels {
		pVal := expected[label]
		qVal := actual[label]

		if pVal == 0 {
			pVal = epsilon
		}
		if qVal == 0 {
			qVal = epsilon
		}

		psi += (qVal - pVal) * math.Log(qVal/pVal)
	}

	return psi
}

func (m *PredictionDistMonitor) shouldSendNotification(key string) bool {
	if m.notifier == nil {
		return false
	}

	m.configMu.RLock()
	config, hasConfig := m.notificationConfigs[key]
	m.configMu.RUnlock()

	if hasConfig && !config.Enabled {
		return false
	}

	m.lastNotifyMu.RLock()
	lastSent, exists := m.lastNotification[key]
	m.lastNotifyMu.RUnlock()

	coolDown := m.notificationCoolDown
	if hasConfig && config.NotificationCoolDown > 0 {
		coolDown = config.NotificationCoolDown
	}

	if exists && time.Since(lastSent) < coolDown {
		return false
	}

	return true
}

func (m *PredictionDistMonitor) sendDriftNotification(ctx context.Context, alert *DriftAlert) {
	if m.notifier == nil {
		return
	}

	key := m.getKey(alert.Namespace, alert.ModelName, alert.Version)

	var recipientEmails []string
	m.configMu.RLock()
	config, hasConfig := m.notificationConfigs[key]
	m.configMu.RUnlock()

	if hasConfig {
		recipientEmails = config.OwnerEmails
	}

	err := m.notifier.SendDriftAlert(ctx,
		alert.ModelName, alert.ID, alert.Namespace,
		alert.DriftScore, alert.PSIScore, m.driftThreshold,
		recipientEmails)

	if err != nil {
		m.logger.Warn("Failed to send drift notification",
			zap.String("model", alert.ModelName),
			zap.Error(err))
	} else {
		m.lastNotifyMu.Lock()
		m.lastNotification[key] = time.Now()
		m.lastNotifyMu.Unlock()

		alert.Notified = true
		m.logger.Info("Drift notification sent successfully",
			zap.String("model", alert.ModelName),
			zap.String("version", alert.Version))
	}
}

func (m *PredictionDistMonitor) GetDriftThreshold() float64 {
	return m.driftThreshold
}

func (m *PredictionDistMonitor) SetDriftThreshold(threshold float64) {
	m.driftThreshold = threshold
}

func (m *PredictionDistMonitor) GetPSIThreshold() float64 {
	return m.psiThreshold
}

func (m *PredictionDistMonitor) SetPSIThreshold(threshold float64) {
	m.psiThreshold = threshold
}

func (m *PredictionDistMonitor) CalculatePSIForModel(ctx context.Context, namespace, modelName, version string) (float64, error) {
	key := m.getKey(namespace, modelName, version)

	m.profileMu.RLock()
	profile, hasProfile := m.trainingDistributions[key][version]
	m.profileMu.RUnlock()

	if !hasProfile {
		return 0, fmt.Errorf("no training distribution found for model %s:%s", modelName, version)
	}

	m.bufferMu.RLock()
	buf, hasBuf := m.predictionBuffers[key]
	m.bufferMu.RUnlock()

	if !hasBuf || len(buf.Predictions) < 100 {
		return 0, fmt.Errorf("insufficient prediction data for model %s:%s", modelName, version)
	}

	currentDist := m.buildClassificationDistribution(buf.Predictions)
	return m.calculatePSI(profile.Probabilities, currentDist), nil
}

func (m *PredictionDistMonitor) rotateBuffers(ctx context.Context) {
	now := time.Now()

	m.bufferMu.Lock()
	for key, buf := range m.predictionBuffers {
		if now.Sub(buf.WindowStart) >= m.windowSize {
			if len(buf.Predictions) >= 100 {
				go m.checkDrift(ctx, buf)
			}

			m.predictionBuffers[key] = &PredictionBuffer{
				ModelName:   buf.ModelName,
				Version:     buf.Version,
				Namespace:   buf.Namespace,
				Predictions: make([]PredictionSample, 0),
				WindowStart: now,
			}
		}
	}
	m.bufferMu.Unlock()
}

func (m *PredictionDistMonitor) getKey(namespace, modelName, version string) string {
	return namespace + ":" + modelName + ":" + version
}

func (m *PredictionDistMonitor) GetRecentAlerts(namespace string, limit int) []*DriftAlert {
	query := `
		SELECT model_name, version, namespace, drift_score, detected_at, drift_type
		FROM prediction_distributions
		WHERE is_alert = true AND namespace = $1
		ORDER BY detected_at DESC
		LIMIT $2
	`

	rows, err := m.db.Query(context.Background(), query, namespace, limit)
	if err != nil {
		return nil
	}
	defer rows.Close()

	var alerts []*DriftAlert
	for rows.Next() {
		alert := &DriftAlert{}
		rows.Scan(&alert.ModelName, &alert.Version, &alert.Namespace,
			&alert.DriftScore, &alert.DetectedAt, &alert.DriftType)
		alerts = append(alerts, alert)
	}

	return alerts
}

type RegressionMonitor struct {
	db          *database.Database
	logger      *zap.Logger

	predictionValues map[string][]float64
	valuesMu         sync.RWMutex

	maWindowSize  int
	alertThreshold float64

	stopCh chan struct{}
	wg     sync.WaitGroup
}

func NewRegressionMonitor(db *database.Database, logger *zap.Logger) *RegressionMonitor {
	return &RegressionMonitor{
		db:               db,
		logger:           logger,
		predictionValues: make(map[string][]float64),
		maWindowSize:     1000,
		alertThreshold:   0.5,
		stopCh:           make(chan struct{}),
	}
}

func (r *RegressionMonitor) Start(ctx context.Context) error {
	r.wg.Add(1)
	go r.monitoringLoop(ctx)
	r.logger.Info("Regression monitor started")
	return nil
}

func (r *RegressionMonitor) Stop() {
	close(r.stopCh)
	r.wg.Wait()
	r.logger.Info("Regression monitor stopped")
}

func (r *RegressionMonitor) RecordPrediction(namespace, modelName, version string, value float64) {
	key := namespace + ":" + modelName + ":" + version

	r.valuesMu.Lock()
	r.predictionValues[key] = append(r.predictionValues[key], value)
	if len(r.predictionValues[key]) > r.maWindowSize*2 {
		r.predictionValues[key] = r.predictionValues[key][len(r.predictionValues[key])-r.maWindowSize:]
	}
	r.valuesMu.Unlock()
}

func (r *RegressionMonitor) monitoringLoop(ctx context.Context) {
	defer r.wg.Done()

	ticker := time.NewTicker(15 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-r.stopCh:
			return
		case <-ticker.C:
			r.checkRegressionTrends(ctx)
		}
	}
}

func (r *RegressionMonitor) checkRegressionTrends(ctx context.Context) {
	r.valuesMu.RLock()
	keys := make([]string, 0, len(r.predictionValues))
	for k := range r.predictionValues {
		keys = append(keys, k)
	}
	r.valuesMu.RUnlock()

	for _, key := range keys {
		r.analyzeTrend(ctx, key)
	}
}

func (r *RegressionMonitor) analyzeTrend(ctx context.Context, key string) {
	r.valuesMu.RLock()
	values, ok := r.predictionValues[key]
	r.valuesMu.RUnlock()

	if !ok || len(values) < r.maWindowSize {
		return
	}

	recent := values[len(values)-r.maWindowSize:]
	older := values[max(0, len(values)-2*r.maWindowSize):max(0, len(values)-r.maWindowSize)]

	if len(older) < 10 {
		return
	}

	recentMA := stat.Mean(recent, nil)
	olderMA := stat.Mean(older, nil)
	recentStd := stat.StdDev(recent, nil)

	zScore := 0.0
	if recentStd > 0 {
		zScore = math.Abs(recentMA-olderMA) / recentStd
	}

	trend := "stable"
	isAlert := false

	if recentMA > olderMA*1.1 {
		trend = "increasing"
	} else if recentMA < olderMA*0.9 {
		trend = "decreasing"
	}

	if zScore > r.alertThreshold {
		isAlert = true
		r.logger.Warn("Regression trend alert",
			zap.String("key", key),
			zap.String("trend", trend),
			zap.Float64("z_score", zScore),
			zap.Float64("recent_ma", recentMA),
			zap.Float64("older_ma", olderMA))
	}

	r.persistMA(ctx, key, recentMA, recentStd, trend, isAlert)
}

func (r *RegressionMonitor) persistMA(ctx context.Context, key string, ma, std float64, trend string, isAlert bool) {
	var namespace, modelName, version string
	fmt.Sscanf(key, "%s:%s:%s", &namespace, &modelName, &version)

	windowEnd := time.Now()
	windowStart := windowEnd.Add(-15 * time.Minute)

	query := `
		INSERT INTO prediction_ma_history (model_name, version, namespace, window_start,
			window_end, moving_avg, std_dev, trend_direction, is_alert)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	`

	_, err := r.db.Exec(ctx, query, modelName, version, namespace, windowStart,
		windowEnd, ma, std, trend, isAlert)
	if err != nil {
		r.logger.Warn("Failed to persist MA history", zap.Error(err))
	}
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}
