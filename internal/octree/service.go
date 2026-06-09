package octree

import (
	"fmt"
	"path/filepath"
	"pointcloud-platform/config"
	"pointcloud-platform/internal/parser"
	"pointcloud-platform/pkg/math3d"
	"sync"
)

type OctreeService struct {
	cfg        *config.OctreeConfig
	storageCfg *config.StorageConfig
	octrees    map[string]*Octree
	builders   map[string]*LODBuilder
	mu         sync.RWMutex
}

type BuildRequest struct {
	DatasetID string
	FilePath  string
	Format    string
}

type BuildResult struct {
	DatasetID   string
	TotalPoints uint64
	NodeCount   uint64
	LeafCount   uint64
	LODLevels   int
	TileCount   int
	Error       error
}

func NewOctreeService(cfg *config.OctreeConfig, storageCfg *config.StorageConfig) *OctreeService {
	return &OctreeService{
		cfg:        cfg,
		storageCfg: storageCfg,
		octrees:    make(map[string]*Octree),
		builders:   make(map[string]*LODBuilder),
	}
}

func (s *OctreeService) BuildFromPointCloud(datasetID string, pc *parser.PointCloud) (*BuildResult, error) {
	result := &BuildResult{
		DatasetID: datasetID,
	}

	octree := NewOctree(s.cfg, pc.Bounds)
	octree.BuildFromPoints(pc.Points)

	result.TotalPoints = octree.TotalPoints
	result.NodeCount = octree.NodeCount + 1
	result.LeafCount = octree.LeafCount + 1

	s.mu.Lock()
	s.octrees[datasetID] = octree
	s.mu.Unlock()

	builder := NewLODBuilder(octree, datasetID, s.cfg, s.storageCfg)
	index, err := builder.Build()
	if err != nil {
		result.Error = err
		return result, err
	}

	result.LODLevels = len(index.Levels)
	for _, level := range index.Levels {
		result.TileCount += level.TileCount
	}

	s.mu.Lock()
	s.builders[datasetID] = builder
	s.mu.Unlock()

	return result, nil
}

func (s *OctreeService) BuildFromFile(datasetID string, filePath string, format string) (*BuildResult, error) {
	parseService := parser.NewParseService(4)
	pc, err := parseService.ParseFile(filePath)
	if err != nil {
		return &BuildResult{
			DatasetID: datasetID,
			Error:     fmt.Errorf("failed to parse file: %w", err),
		}, err
	}

	return s.BuildFromPointCloud(datasetID, pc)
}

func (s *OctreeService) BuildAsync(req BuildRequest) <-chan *BuildResult {
	resultChan := make(chan *BuildResult, 1)

	go func() {
		defer close(resultChan)
		result, err := s.BuildFromFile(req.DatasetID, req.FilePath, req.Format)
		if err != nil {
			resultChan <- &BuildResult{
				DatasetID: req.DatasetID,
				Error:     err,
			}
			return
		}
		resultChan <- result
	}()

	return resultChan
}

func (s *OctreeService) QueryTiles(datasetID string, frustum *math3d.Frustum, maxTiles int) ([]TileMetadata, error) {
	s.mu.RLock()
	builder, exists := s.builders[datasetID]
	s.mu.RUnlock()

	if !exists {
		var err error
		builder, err = s.loadBuilder(datasetID)
		if err != nil {
			return nil, fmt.Errorf("dataset not built: %w", err)
		}
	}

	return builder.GetTilesForView(frustum, 1.0, maxTiles), nil
}

func (s *OctreeService) GetTile(datasetID string, lod int, x, y, z int64) (*Tile, error) {
	s.mu.RLock()
	builder, exists := s.builders[datasetID]
	s.mu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("builder not found for dataset %s", datasetID)
	}

	return builder.LoadTile(lod, x, y, z)
}

func (s *OctreeService) GetTileIndex(datasetID string) (*DatasetTileIndex, error) {
	s.mu.RLock()
	builder, exists := s.builders[datasetID]
	s.mu.RUnlock()

	if !exists {
		builder, err := s.loadBuilder(datasetID)
		if err != nil {
			return nil, err
		}
		return builder.LoadIndex()
	}

	return builder.LoadIndex()
}

func (s *OctreeService) loadBuilder(datasetID string) (*LODBuilder, error) {
	indexPath := filepath.Join(s.storageCfg.TileDir, datasetID, "index.json")

	var index DatasetTileIndex

	_ = indexPath

	octree := NewOctree(s.cfg, index.Bounds)

	builder := NewLODBuilder(octree, datasetID, s.cfg, s.storageCfg)

	s.mu.Lock()
	s.builders[datasetID] = builder
	s.octrees[datasetID] = octree
	s.mu.Unlock()

	return builder, nil
}

func (s *OctreeService) GetOctree(datasetID string) (*Octree, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	octree, exists := s.octrees[datasetID]
	return octree, exists
}
