package vectorindex

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/google/uuid"
	"streamsql/internal/common/config"
	"streamsql/internal/common/logger"
)

type VectorIndexService struct {
	indices map[string]VectorIndex
	config  config.VectorIndexConfig
	mu      sync.RWMutex
}

func NewVectorIndexService(cfg config.VectorIndexConfig) *VectorIndexService {
	svc := &VectorIndexService{
		indices: make(map[string]VectorIndex),
		config:  cfg,
	}

	if err := os.MkdirAll(cfg.IndexPath, 0755); err != nil {
		logger.Sugar().Errorf("Failed to create index directory: %v", err)
	}

	logger.Sugar().Info("Vector index service initialized")
	return svc
}

func (s *VectorIndexService) CreateIndex(name string, indexType string, dimensions int, metric DistanceMetric, params map[string]interface{}) (VectorIndex, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.indices[name]; exists {
		return nil, fmt.Errorf("index already exists: %s", name)
	}

	var index VectorIndex
	switch indexType {
	case "flat":
		index = NewFlatIndex(dimensions, metric)
	case "hnsw":
		M := 16
		efConstruction := 100
		if m, ok := params["M"].(int); ok {
			M = m
		}
		if ef, ok := params["ef_construction"].(int); ok {
			efConstruction = ef
		}
		index = NewHNSWIndex(dimensions, metric, M, efConstruction)
	case "ivf":
		nlist := 100
		if n, ok := params["nlist"].(int); ok {
			nlist = n
		}
		index = NewIVFIndex(dimensions, metric, nlist)
	default:
		return nil, fmt.Errorf("unknown index type: %s", indexType)
	}

	s.indices[name] = index
	logger.Sugar().Infof("Created %s index: %s (dimensions: %d, metric: %s)", indexType, name, dimensions, metric)
	return index, nil
}

func (s *VectorIndexService) GetIndex(name string) (VectorIndex, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	index, exists := s.indices[name]
	if !exists {
		return nil, fmt.Errorf("index not found: %s", name)
	}
	return index, nil
}

func (s *VectorIndexService) DeleteIndex(name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.indices[name]; !exists {
		return fmt.Errorf("index not found: %s", name)
	}

	indexPath := filepath.Join(s.config.IndexPath, name+".json")
	_ = os.Remove(indexPath)

	delete(s.indices, name)
	logger.Sugar().Infof("Deleted index: %s", name)
	return nil
}

func (s *VectorIndexService) ListIndices() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	names := make([]string, 0, len(s.indices))
	for name := range s.indices {
		names = append(names, name)
	}
	return names
}

func (s *VectorIndexService) BuildIndex(name string, vectors []Vector) error {
	index, err := s.GetIndex(name)
	if err != nil {
		return err
	}

	for i := range vectors {
		if vectors[i].ID == "" {
			vectors[i].ID = uuid.New().String()
		}
	}

	start := time.Now()
	err = index.Build(vectors)
	if err != nil {
		return err
	}

	logger.Sugar().Infof("Built index %s with %d vectors in %v", name, len(vectors), time.Since(start))
	return nil
}

func (s *VectorIndexService) AddVector(name string, vector Vector) error {
	index, err := s.GetIndex(name)
	if err != nil {
		return err
	}

	if vector.ID == "" {
		vector.ID = uuid.New().String()
	}

	return index.Add(vector)
}

func (s *VectorIndexService) AddVectorsBatch(name string, vectors []Vector) error {
	index, err := s.GetIndex(name)
	if err != nil {
		return err
	}

	for i := range vectors {
		if vectors[i].ID == "" {
			vectors[i].ID = uuid.New().String()
		}
	}

	return index.AddBatch(vectors)
}

func (s *VectorIndexService) Search(name string, query []float32, k int, efSearch int) ([]SearchResult, error) {
	index, err := s.GetIndex(name)
	if err != nil {
		return nil, err
	}

	if efSearch <= 0 {
		efSearch = 50
	}

	return index.Search(query, k, efSearch)
}

func (s *VectorIndexService) DeleteVector(name string, vectorID string) error {
	index, err := s.GetIndex(name)
	if err != nil {
		return err
	}

	return index.Delete(vectorID)
}

func (s *VectorIndexService) SaveIndex(name string) error {
	index, err := s.GetIndex(name)
	if err != nil {
		return err
	}

	indexPath := filepath.Join(s.config.IndexPath, name+".json")
	return index.Save(indexPath)
}

func (s *VectorIndexService) LoadIndex(name string, indexType string, dimensions int, metric DistanceMetric, params map[string]interface{}) (VectorIndex, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.indices[name]; exists {
		return nil, fmt.Errorf("index already exists: %s", name)
	}

	var index VectorIndex
	switch indexType {
	case "flat":
		index = NewFlatIndex(dimensions, metric)
	case "hnsw":
		M := 16
		efConstruction := 100
		if m, ok := params["M"].(int); ok {
			M = m
		}
		if ef, ok := params["ef_construction"].(int); ok {
			efConstruction = ef
		}
		index = NewHNSWIndex(dimensions, metric, M, efConstruction)
	case "ivf":
		nlist := 100
		if n, ok := params["nlist"].(int); ok {
			nlist = n
		}
		index = NewIVFIndex(dimensions, metric, nlist)
	default:
		return nil, fmt.Errorf("unknown index type: %s", indexType)
	}

	indexPath := filepath.Join(s.config.IndexPath, name+".json")
	if err := index.Load(indexPath); err != nil {
		return nil, err
	}

	s.indices[name] = index
	logger.Sugar().Infof("Loaded %s index: %s", indexType, name)
	return index, nil
}

func (s *VectorIndexService) GetStats(name string) (map[string]interface{}, error) {
	index, err := s.GetIndex(name)
	if err != nil {
		return nil, err
	}

	return map[string]interface{}{
		"name":  name,
		"type":  index.Name(),
		"size":  index.Size(),
	}, nil
}

func (s *VectorIndexService) GetAllStats() []map[string]interface{} {
	s.mu.RLock()
	defer s.mu.RUnlock()

	stats := make([]map[string]interface{}, 0, len(s.indices))
	for name, index := range s.indices {
		stats = append(stats, map[string]interface{}{
			"name": name,
			"type": index.Name(),
			"size": index.Size(),
		})
	}
	return stats
}
