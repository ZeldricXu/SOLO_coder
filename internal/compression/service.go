package compression

import (
	"errors"
	"sync"
	"time"

	"streamsql/internal/common/config"
	"streamsql/internal/common/logger"
	"streamsql/internal/common/models"
)

type CompressionService struct {
	algorithms    map[string]CompressionAlgorithm
	downsamplers  map[string]DownsamplingStrategy
	multiResStore *MultiResolutionStore
	mu            sync.RWMutex
	config        config.CompressionConfig
}

func NewCompressionService(cfg config.CompressionConfig) *CompressionService {
	svc := &CompressionService{
		algorithms:   make(map[string]CompressionAlgorithm),
		downsamplers: make(map[string]DownsamplingStrategy),
		config:       cfg,
	}

	svc.algorithms["gorilla"] = NewGorillaCompressor()
	svc.algorithms["delta"] = NewDeltaCompressor()
	svc.algorithms["simple8b"] = NewSimple8bCompressor()

	svc.downsamplers["lttb"] = NewLTTBStrategy()
	svc.downsamplers["average"] = NewAverageStrategy()
	svc.downsamplers["minmax"] = NewMinMaxStrategy()
	svc.downsamplers["firstlast"] = NewFirstLastStrategy()
	svc.downsamplers["timebucket"] = NewTimeBucketStrategy(time.Minute * 5)

	levels := []ResolutionLevel{
		{Name: "raw", Interval: time.Second, Retention: time.Hour * 24},
		{Name: "1m", Interval: time.Minute, Retention: time.Hour * 24 * 7},
		{Name: "5m", Interval: time.Minute * 5, Retention: time.Hour * 24 * 30},
		{Name: "1h", Interval: time.Hour, Retention: time.Hour * 24 * 365},
		{Name: "1d", Interval: time.Hour * 24, Retention: time.Hour * 24 * 365 * 3},
	}

	var err error
	svc.multiResStore, err = NewMultiResolutionStore(levels, svc.algorithms[cfg.DefaultAlgorithm], svc.downsamplers["lttb"])
	if err != nil {
		logger.Sugar().Fatalf("Failed to create multi-resolution store: %v", err)
	}

	logger.Sugar().Info("Compression service initialized")
	return svc
}

func (s *CompressionService) Encode(algorithm string, points []float64) ([]byte, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	algo, ok := s.algorithms[algorithm]
	if !ok {
		return nil, errors.New("unknown compression algorithm")
	}

	start := time.Now()
	data, err := algo.Encode(points)
	if err != nil {
		return nil, err
	}

	logger.Sugar().Infof("Encoded %d points with %s in %v, ratio: %.2f%%",
		len(points), algorithm, time.Since(start),
		float64(len(data))/float64(len(points)*8)*100)

	return data, nil
}

func (s *CompressionService) Decode(algorithm string, data []byte) ([]float64, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	algo, ok := s.algorithms[algorithm]
	if !ok {
		return nil, errors.New("unknown compression algorithm")
	}

	start := time.Now()
	points, err := algo.Decode(data)
	if err != nil {
		return nil, err
	}

	logger.Sugar().Infof("Decoded to %d points with %s in %v", len(points), algorithm, time.Since(start))
	return points, nil
}

func (s *CompressionService) Downsample(strategy string, points []models.TimeSeriesPoint, targetSize int) ([]models.TimeSeriesPoint, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	ds, ok := s.downsamplers[strategy]
	if !ok {
		return nil, errors.New("unknown downsampling strategy")
	}

	start := time.Now()
	result := ds.Downsample(points, targetSize)

	logger.Sugar().Infof("Downsampled from %d to %d points using %s in %v",
		len(points), len(result), strategy, time.Since(start))

	return result, nil
}

func (s *CompressionService) WriteTimeSeries(metric string, point models.TimeSeriesPoint) error {
	return s.multiResStore.Write(metric, point)
}

func (s *CompressionService) WriteTimeSeriesBatch(metric string, points []models.TimeSeriesPoint) error {
	return s.multiResStore.WriteBatch(metric, points)
}

func (s *CompressionService) ReadTimeSeries(metric string, startTime, endTime time.Time, maxPoints int) ([]models.TimeSeriesPoint, error) {
	return s.multiResStore.Read(metric, startTime, endTime, maxPoints)
}

func (s *CompressionService) Compact() error {
	return s.multiResStore.Compact()
}

func (s *CompressionService) GetCompressionStats() map[string]interface{} {
	return s.multiResStore.GetStats()
}

func (s *CompressionService) ListAlgorithms() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	algos := make([]string, 0, len(s.algorithms))
	for name := range s.algorithms {
		algos = append(algos, name)
	}
	return algos
}

func (s *CompressionService) ListDownsamplers() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	ds := make([]string, 0, len(s.downsamplers))
	for name := range s.downsamplers {
		ds = append(ds, name)
	}
	return ds
}
