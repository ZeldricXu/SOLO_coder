package anomaly

import (
	"context"
	"math"
	"math/rand"
	"sort"
	"sync"
	"time"

	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
	"log-pipeline/pkg/utils"
)

type AnomalyDetector struct {
	config       *config.AnomalyConfig
	movingAvg    map[string]*MovingAverageStats
	baselines    map[string]*BaselineStats
	iforest      *IsolationForest
	anomalyChan  chan *models.AnomalyResult
	mu           sync.RWMutex
	ctx          context.Context
	cancel       context.CancelFunc
	wg           sync.WaitGroup
}

type BaselineStats struct {
	Mean   float64
	StdDev float64
}

type MovingAverageStats struct {
	values   []float64
	sum      float64
	sumSq    float64
	index    int
	filled   bool
}

type IsolationForest struct {
	trees        []*IsolationTree
	sampleSize   int
	maxDepth     int
	contamination float64
}

type IsolationTree struct {
	root *Node
}

type Node struct {
	splitAttr   int
	splitValue  float64
	left        *Node
	right       *Node
	size        int
	isLeaf      bool
}

func NewAnomalyDetector(cfg *config.AnomalyConfig) *AnomalyDetector {
	ctx, cancel := context.WithCancel(context.Background())

	detector := &AnomalyDetector{
		config:      cfg,
		movingAvg:   make(map[string]*MovingAverageStats),
		baselines:   make(map[string]*BaselineStats),
		anomalyChan: make(chan *models.AnomalyResult, 100),
		ctx:         ctx,
		cancel:      cancel,
	}

	detector.iforest = &IsolationForest{
		trees:        make([]*IsolationTree, cfg.IsolationForest.Trees),
		sampleSize:   cfg.IsolationForest.SampleSize,
		maxDepth:     int(math.Log2(float64(cfg.IsolationForest.SampleSize))),
		contamination: cfg.IsolationForest.Contamination,
	}

	return detector
}

func (ad *AnomalyDetector) Start(aggChan <-chan *models.WindowAggregate) {
	ad.wg.Add(1)
	go ad.processAggregates(aggChan)
}

func (ad *AnomalyDetector) Stop() {
	ad.cancel()
	ad.wg.Wait()
	close(ad.anomalyChan)
}

func (ad *AnomalyDetector) Anomalies() <-chan *models.AnomalyResult {
	return ad.anomalyChan
}

func (ad *AnomalyDetector) processAggregates(aggChan <-chan *models.WindowAggregate) {
	defer ad.wg.Done()

	for {
		select {
		case <-ad.ctx.Done():
			return
		case agg, ok := <-aggChan:
			if !ok {
				return
			}
			ad.detectMovingAverage(agg)
			ad.detectIsolationForest(agg)
		}
	}
}

func (ad *AnomalyDetector) detectMovingAverage(agg *models.WindowAggregate) {
	ad.mu.Lock()
	defer ad.mu.Unlock()

	metrics := ad.extractFeatures(agg)
	allDeviations := make(map[string]float64)

	for metricName, value := range metrics {
		stats, exists := ad.movingAvg[metricName]
		if !exists {
			stats = &MovingAverageStats{
				values: make([]float64, ad.config.MovingAverageWindow),
			}
			ad.movingAvg[metricName] = stats
		}

		stats.sum -= stats.values[stats.index]
		stats.sumSq -= stats.values[stats.index] * stats.values[stats.index]

		stats.values[stats.index] = value
		stats.sum += value
		stats.sumSq += value * value

		stats.index = (stats.index + 1) % ad.config.MovingAverageWindow
		if stats.index == 0 {
			stats.filled = true
		}

		if !stats.filled {
			continue
		}

		n := float64(ad.config.MovingAverageWindow)
		mean := stats.sum / n
		variance := (stats.sumSq / n) - (mean * mean)
		stdDev := math.Sqrt(math.Abs(variance))

		ad.baselines[metricName] = &BaselineStats{Mean: mean, StdDev: stdDev}

		zScore := 0.0
		if stdDev > 0 {
			zScore = math.Abs(value-mean) / stdDev
		}
		threshold := ad.config.StdDevThreshold * stdDev

		if mean > 0 {
			allDeviations[metricName] = math.Abs(value-mean) / mean * 100
		}

		if zScore > ad.config.StdDevThreshold {
			score := ad.zScoreToScore(zScore)
			topContributors := ad.computeTopContributors(metrics, 3)

			result := &models.AnomalyResult{
				ID:           utils.GenerateID(),
				Timestamp:    time.Now(),
				MetricName:   metricName,
				AnomalyScore: zScore,
				IsAnomaly:    true,
				Method:       "moving_avg_stddev",
				Threshold:    mean + threshold,
				Value:        value,
				Features: map[string]float64{
					"mean":    mean,
					"stddev":  stdDev,
					"z_score": zScore,
				},
				Score:            score,
				TopContributors:  topContributors,
				DeviationPercent: ad.computeDeviationPercent(metricName, value),
			}

			select {
			case ad.anomalyChan <- result:
			default:
			}
		}
	}
}

func (ad *AnomalyDetector) DetectMovingAverage(agg *models.WindowAggregate) []*models.AnomalyResult {
	ad.mu.Lock()
	defer ad.mu.Unlock()

	var results []*models.AnomalyResult
	metrics := ad.extractFeatures(agg)

	for metricName, value := range metrics {
		stats, exists := ad.movingAvg[metricName]
		if !exists {
			stats = &MovingAverageStats{
				values: make([]float64, ad.config.MovingAverageWindow),
			}
			ad.movingAvg[metricName] = stats
		}

		stats.sum -= stats.values[stats.index]
		stats.sumSq -= stats.values[stats.index] * stats.values[stats.index]

		stats.values[stats.index] = value
		stats.sum += value
		stats.sumSq += value * value

		stats.index = (stats.index + 1) % ad.config.MovingAverageWindow
		if stats.index == 0 {
			stats.filled = true
		}

		if !stats.filled {
			continue
		}

		n := float64(ad.config.MovingAverageWindow)
		mean := stats.sum / n
		variance := (stats.sumSq / n) - (mean * mean)
		stdDev := math.Sqrt(math.Abs(variance))

		ad.baselines[metricName] = &BaselineStats{Mean: mean, StdDev: stdDev}

		zScore := 0.0
		if stdDev > 0 {
			zScore = math.Abs(value-mean) / stdDev
		}
		threshold := ad.config.StdDevThreshold * stdDev

		if zScore > ad.config.StdDevThreshold {
			score := ad.zScoreToScore(zScore)
			topContributors := ad.computeTopContributors(metrics, 3)

			results = append(results, &models.AnomalyResult{
				ID:           utils.GenerateID(),
				Timestamp:    time.Now(),
				MetricName:   metricName,
				AnomalyScore: zScore,
				IsAnomaly:    true,
				Method:       "moving_avg_stddev",
				Threshold:    mean + threshold,
				Value:        value,
				Features: map[string]float64{
					"mean":    mean,
					"stddev":  stdDev,
					"z_score": zScore,
				},
				Score:            score,
				TopContributors:  topContributors,
				DeviationPercent: ad.computeDeviationPercent(metricName, value),
			})
		}
	}

	return results
}

func (ad *AnomalyDetector) detectIsolationForest(agg *models.WindowAggregate) {
	features := ad.extractFeatureVector(agg)

	if len(ad.iforest.trees) == 0 || ad.iforest.trees[0] == nil {
		return
	}

	score := ad.iforest.AnomalyScore(features)
	threshold := 0.6

	if score > threshold {
		anomalyScore := ad.iforestScoreToScore(score)
		topContributors := ad.computeIsolationForestContributors(features, 3)
		deviationPercent := ad.computeIsolationForestDeviation(features)

		result := &models.AnomalyResult{
			ID:           utils.GenerateID(),
			Timestamp:    time.Now(),
			MetricName:   "isolation_forest",
			AnomalyScore: score,
			IsAnomaly:    true,
			Method:       "isolation_forest",
			Threshold:    threshold,
			Value:        score,
			Features:     features,
			Score:            anomalyScore,
			TopContributors:  topContributors,
			DeviationPercent: deviationPercent,
		}

		select {
		case ad.anomalyChan <- result:
		default:
		}
	}
}

func (ad *AnomalyDetector) DetectIsolationForest(agg *models.WindowAggregate) []*models.AnomalyResult {
	features := ad.extractFeatureVector(agg)

	if len(ad.iforest.trees) == 0 || ad.iforest.trees[0] == nil {
		return nil
	}

	score := ad.iforest.AnomalyScore(features)
	threshold := 0.6

	if score > threshold {
		anomalyScore := ad.iforestScoreToScore(score)
		topContributors := ad.computeIsolationForestContributors(features, 3)
		deviationPercent := ad.computeIsolationForestDeviation(features)

		return []*models.AnomalyResult{
			{
				ID:           utils.GenerateID(),
				Timestamp:    time.Now(),
				MetricName:   "isolation_forest",
				AnomalyScore: score,
				IsAnomaly:    true,
				Method:       "isolation_forest",
				Threshold:    threshold,
				Value:        score,
				Features:     features,
				Score:            anomalyScore,
				TopContributors:  topContributors,
				DeviationPercent: deviationPercent,
			},
		}
	}

	return nil
}

func (ad *AnomalyDetector) extractFeatures(agg *models.WindowAggregate) map[string]float64 {
	features := make(map[string]float64)

	features["log_count"] = float64(agg.Count)
	
	for level, count := range agg.LevelCounts {
		features["level_"+level] = float64(count)
	}

	if agg.LevelCounts["ERROR"] > 0 {
		features["error_ratio"] = float64(agg.LevelCounts["ERROR"]) / float64(agg.Count)
	} else {
		features["error_ratio"] = 0
	}

	return features
}

func (ad *AnomalyDetector) extractFeatureVector(agg *models.WindowAggregate) map[string]float64 {
	features := make(map[string]float64)
	features["log_count"] = float64(agg.Count)
	features["error_count"] = float64(agg.LevelCounts["ERROR"])
	features["warn_count"] = float64(agg.LevelCounts["WARN"])
	features["info_count"] = float64(agg.LevelCounts["INFO"])
	
	if agg.Count > 0 {
		features["error_ratio"] = float64(agg.LevelCounts["ERROR"]) / float64(agg.Count)
	}

	return features
}

func (ad *AnomalyDetector) TrainIsolationForest(trainingData []map[string]float64) {
	ad.iforest.Train(trainingData)
}

func (f *IsolationForest) Train(data []map[string]float64) {
	f.trees = make([]*IsolationTree, len(f.trees))
	
	for i := range f.trees {
		sample := f.sampleData(data)
		f.trees[i] = f.buildTree(sample, 0)
	}
}

func (f *IsolationForest) sampleData(data []map[string]float64) []map[string]float64 {
	sampleSize := f.sampleSize
	if len(data) < sampleSize {
		sampleSize = len(data)
	}

	indices := rand.Perm(len(data))[:sampleSize]
	sample := make([]map[string]float64, sampleSize)
	for i, idx := range indices {
		sample[i] = data[idx]
	}

	return sample
}

func (f *IsolationForest) buildTree(data []map[string]float64, depth int) *IsolationTree {
	node := &Node{size: len(data)}

	if len(data) <= 1 || depth >= f.maxDepth {
		node.isLeaf = true
		return &IsolationTree{root: node}
	}

	attrList := make([]string, 0, len(data[0]))
	for k := range data[0] {
		attrList = append(attrList, k)
	}
	splitAttr := attrList[rand.Intn(len(attrList))]

	values := make([]float64, len(data))
	for i, d := range data {
		values[i] = d[splitAttr]
	}
	sort.Float64s(values)
	
	minVal := values[0]
	maxVal := values[len(values)-1]
	if minVal == maxVal {
		node.isLeaf = true
		return &IsolationTree{root: node}
	}

	splitValue := minVal + rand.Float64()*(maxVal-minVal)

	var leftData, rightData []map[string]float64
	for _, d := range data {
		if d[splitAttr] < splitValue {
			leftData = append(leftData, d)
		} else {
			rightData = append(rightData, d)
		}
	}

	node.splitAttr = -1
	for i, attr := range attrList {
		if attr == splitAttr {
			node.splitAttr = i
			break
		}
	}
	node.splitValue = splitValue

	leftTree := f.buildTree(leftData, depth+1)
	rightTree := f.buildTree(rightData, depth+1)
	node.left = leftTree.root
	node.right = rightTree.root

	return &IsolationTree{root: node}
}

func (f *IsolationForest) AnomalyScore(features map[string]float64) float64 {
	if len(f.trees) == 0 {
		return 0
	}

	featureList := make([]float64, 0, len(features))
	for _, v := range features {
		featureList = append(featureList, v)
	}

	totalPathLength := 0.0
	for _, tree := range f.trees {
		if tree != nil && tree.root != nil {
			totalPathLength += f.pathLength(tree.root, featureList, 0)
		}
	}

	avgPathLength := totalPathLength / float64(len(f.trees))
	expectedPath := f.expectedPathLength(float64(f.sampleSize))
	
	score := math.Pow(2, -avgPathLength/expectedPath)
	return score
}

func (f *IsolationForest) pathLength(node *Node, features []float64, depth int) float64 {
	if node.isLeaf {
		if node.size <= 1 {
			return float64(depth)
		}
		return float64(depth) + f.expectedPathLength(float64(node.size))
	}

	if node.splitAttr >= 0 && node.splitAttr < len(features) {
		if features[node.splitAttr] < node.splitValue {
			return f.pathLength(node.left, features, depth+1)
		}
		return f.pathLength(node.right, features, depth+1)
	}

	return float64(depth)
}

func (f *IsolationForest) expectedPathLength(n float64) float64 {
	if n <= 1 {
		return 0
	}
	return 2*(math.Log(n-1)+0.5772156649) - 2*(n-1)/n
}

func (ad *AnomalyDetector) zScoreToScore(zScore float64) float64 {
	score := (zScore - ad.config.StdDevThreshold) / (10.0 - ad.config.StdDevThreshold) * 100.0
	if score < 0 {
		score = 0
	}
	if score > 100 {
		score = 100
	}
	return math.Round(score*100) / 100
}

func (ad *AnomalyDetector) iforestScoreToScore(iforestScore float64) float64 {
	score := (iforestScore - 0.5) / 0.5 * 100.0
	if score < 0 {
		score = 0
	}
	if score > 100 {
		score = 100
	}
	return math.Round(score*100) / 100
}

func (ad *AnomalyDetector) computeTopContributors(metrics map[string]float64, topN int) []models.ContributingFeature {
	type deviationEntry struct {
		name       string
		deviation  float64
		value      float64
		baseline   float64
	}

	var entries []deviationEntry
	for name, value := range metrics {
		baseline, exists := ad.baselines[name]
		if !exists || baseline.Mean == 0 {
			continue
		}
		deviation := math.Abs(value-baseline.Mean) / baseline.Mean
		entries = append(entries, deviationEntry{
			name:      name,
			deviation: deviation,
			value:     value,
			baseline:  baseline.Mean,
		})
	}

	sort.Slice(entries, func(i, j int) bool {
		return entries[i].deviation > entries[j].deviation
	})

	if len(entries) > topN {
		entries = entries[:topN]
	}

	totalDeviation := 0.0
	for _, e := range entries {
		totalDeviation += e.deviation
	}

	contributors := make([]models.ContributingFeature, 0, len(entries))
	for _, e := range entries {
		contribution := 0.0
		if totalDeviation > 0 {
			contribution = e.deviation / totalDeviation * 100
		}
		contributors = append(contributors, models.ContributingFeature{
			Name:        e.name,
			Value:       e.value,
			Baseline:    e.baseline,
			Deviation:   e.deviation * 100,
			Contribution: math.Round(contribution*100) / 100,
		})
	}

	return contributors
}

func (ad *AnomalyDetector) computeDeviationPercent(metricName string, value float64) float64 {
	baseline, exists := ad.baselines[metricName]
	if !exists || baseline.Mean == 0 {
		return 0
	}
	deviation := math.Abs(value-baseline.Mean) / baseline.Mean * 100
	return math.Round(deviation*100) / 100
}

func (ad *AnomalyDetector) computeIsolationForestContributors(features map[string]float64, topN int) []models.ContributingFeature {
	type featureDev struct {
		name       string
		value      float64
		baseline   float64
		deviation  float64
	}

	var entries []featureDev
	for name, value := range features {
		baseline, exists := ad.baselines[name]
		if !exists || baseline.Mean == 0 {
			continue
		}
		deviation := math.Abs(value-baseline.Mean) / baseline.Mean
		entries = append(entries, featureDev{
			name:      name,
			value:     value,
			baseline:  baseline.Mean,
			deviation: deviation,
		})
	}

	sort.Slice(entries, func(i, j int) bool {
		return entries[i].deviation > entries[j].deviation
	})

	if len(entries) > topN {
		entries = entries[:topN]
	}

	totalDeviation := 0.0
	for _, e := range entries {
		totalDeviation += e.deviation
	}

	contributors := make([]models.ContributingFeature, 0, len(entries))
	for _, e := range entries {
		contribution := 0.0
		if totalDeviation > 0 {
			contribution = e.deviation / totalDeviation * 100
		}
		contributors = append(contributors, models.ContributingFeature{
			Name:        e.name,
			Value:       e.value,
			Baseline:    e.baseline,
			Deviation:   e.deviation * 100,
			Contribution: math.Round(contribution*100) / 100,
		})
	}

	return contributors
}

func (ad *AnomalyDetector) computeIsolationForestDeviation(features map[string]float64) float64 {
	totalDeviation := 0.0
	count := 0
	for name, value := range features {
		baseline, exists := ad.baselines[name]
		if !exists || baseline.Mean == 0 {
			continue
		}
		totalDeviation += math.Abs(value-baseline.Mean) / baseline.Mean * 100
		count++
	}
	if count == 0 {
		return 0
	}
	avg := totalDeviation / float64(count)
	return math.Round(avg*100) / 100
}

func (ad *AnomalyDetector) GetBaselines() map[string]*BaselineStats {
	ad.mu.RLock()
	defer ad.mu.RUnlock()
	result := make(map[string]*BaselineStats, len(ad.baselines))
	for k, v := range ad.baselines {
		result[k] = v
	}
	return result
}
