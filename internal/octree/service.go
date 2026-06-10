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

type IncrementalUpdateRequest struct {
	DatasetID   string
	Points      []parser.Point
	SourceFile  string
	SourceFormat string
}

type IncrementalUpdateResponse struct {
	DatasetID       string
	InsertedPoints  int
	UpdatedNodes    int
	UpdatedTiles    int
	UpdatedLODs     []int
	AffectedTiles   []TileMetadata
	NewBounds       *math3d.AABB
	NewTotalPoints  uint64
	RebuildRequired bool
	Error           error
}

func (s *OctreeService) IncrementalUpdateFromPointCloud(req *IncrementalUpdateRequest) (*IncrementalUpdateResponse, error) {
	resp := &IncrementalUpdateResponse{
		DatasetID: req.DatasetID,
	}

	s.mu.RLock()
	octree, octreeExists := s.octrees[req.DatasetID]
	builder, builderExists := s.builders[req.DatasetID]
	s.mu.RUnlock()

	if !octreeExists || !builderExists {
		resp.Error = fmt.Errorf("dataset %s not found or not built", req.DatasetID)
		return resp, resp.Error
	}

	oldBounds := octree.GlobalBounds

	result := octree.InsertPoints(req.Points)
	resp.InsertedPoints = result.InsertedPoints
	resp.UpdatedNodes = result.UpdatedNodes
	resp.NewBounds = result.NewBounds
	resp.NewTotalPoints = octree.TotalPoints

	if result.NewBounds != nil {
		newOctree := NewOctree(s.cfg, *result.NewBounds)
		var allPoints []parser.Point
		collectAllPoints(octree.Root, &allPoints)
		allPoints = append(allPoints, req.Points...)
		newOctree.BuildFromPoints(allPoints)

		s.mu.Lock()
		s.octrees[req.DatasetID] = newOctree
		s.mu.Unlock()

		newBuilder := NewLODBuilder(newOctree, req.DatasetID, s.cfg, s.storageCfg)
		s.mu.Lock()
		s.builders[req.DatasetID] = newBuilder
		s.mu.Unlock()

		octree = newOctree
		builder = newBuilder
	}

	affectedTiles, affectedLODs := octree.GetAffectedTiles(builder.LODLevels - 1)
	resp.UpdatedLODs = affectedLODs

	lodResult, err := builder.UpdateAffectedTiles(affectedTiles, octree.TotalPoints)
	if err != nil {
		resp.Error = fmt.Errorf("failed to update LOD tiles: %w", err)
		return resp, resp.Error
	}

	resp.UpdatedTiles = len(lodResult.UpdatedTiles)
	resp.AffectedTiles = lodResult.UpdatedTiles
	resp.RebuildRequired = lodResult.RebuildRequired

	if resp.RebuildRequired {
		newOctree := NewOctree(s.cfg, octree.GlobalBounds)
		var allPoints []parser.Point
		collectAllPoints(octree.Root, &allPoints)
		newOctree.BuildFromPoints(allPoints)

		s.mu.Lock()
		s.octrees[req.DatasetID] = newOctree
		s.mu.Unlock()

		newBuilder := NewLODBuilder(newOctree, req.DatasetID, s.cfg, s.storageCfg)
		s.mu.Lock()
		s.builders[req.DatasetID] = newBuilder
		s.mu.Unlock()
	}

	octree.ClearDirty()

	_ = oldBounds
	return resp, nil
}

func collectAllPoints(node *OctreeNode, points *[]parser.Point) {
	if node == nil {
		return
	}
	if len(node.Points) > 0 {
		*points = append(*points, node.Points...)
	}
	if !node.IsLeaf {
		for _, child := range node.Children {
			collectAllPoints(child, points)
		}
	}
}

func (s *OctreeService) IncrementalUpdateFromFile(req *IncrementalUpdateRequest) (*IncrementalUpdateResponse, error) {
	if req.SourceFile == "" {
		return nil, fmt.Errorf("source file path is required")
	}

	parseService := parser.NewParseService(4)
	pc, err := parseService.ParseFile(req.SourceFile)
	if err != nil {
		return &IncrementalUpdateResponse{
			DatasetID: req.DatasetID,
			Error:     fmt.Errorf("failed to parse incremental file: %w", err),
		}, err
	}

	req.Points = pc.Points
	return s.IncrementalUpdateFromPointCloud(req)
}

func (s *OctreeService) IncrementalUpdateAsync(req *IncrementalUpdateRequest) <-chan *IncrementalUpdateResponse {
	resultChan := make(chan *IncrementalUpdateResponse, 1)

	go func() {
		defer close(resultChan)
		var resp *IncrementalUpdateResponse
		var err error

		if req.SourceFile != "" {
			resp, err = s.IncrementalUpdateFromFile(req)
		} else {
			resp, err = s.IncrementalUpdateFromPointCloud(req)
		}

		if err != nil {
			resultChan <- &IncrementalUpdateResponse{
				DatasetID: req.DatasetID,
				Error:     err,
			}
			return
		}
		resultChan <- resp
	}()

	return resultChan
}

func (s *OctreeService) IncrementalUpdateFromPoints(datasetID string, points []parser.Point) (*IncrementalUpdateResponse, error) {
	req := &IncrementalUpdateRequest{
		DatasetID: datasetID,
		Points:    points,
	}
	return s.IncrementalUpdateFromPointCloud(req)
}
