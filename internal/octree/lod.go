package octree

import (
	"encoding/gob"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"pointcloud-platform/config"
	"pointcloud-platform/internal/parser"
	"pointcloud-platform/pkg/math3d"
	"sort"
	"sync"
)

type LODBuilder struct {
	Octree      *Octree
	LODLevels   int
	TileDir     string
	DatasetID   string
	tiles       map[string]*Tile
	tilesMutex  sync.RWMutex
}

type LODLevel struct {
	Level       int
	GridSize    int64
	NodeSize    math3d.Vec3
	TileCount   int
	TotalPoints uint64
}

type TileMetadata struct {
	Key         string        `json:"key"`
	LOD         int           `json:"lod"`
	X           int64         `json:"x"`
	Y           int64         `json:"y"`
	Z           int64         `json:"z"`
	Bounds      math3d.AABB   `json:"bounds"`
	PointCount  int           `json:"point_count"`
	Center      math3d.Vec3   `json:"center"`
	ParentKey   string        `json:"parent_key,omitempty"`
	ChildKeys   []string      `json:"child_keys,omitempty"`
}

type DatasetTileIndex struct {
	DatasetID string             `json:"dataset_id"`
	Levels    []LODLevel         `json:"levels"`
	Tiles     []TileMetadata     `json:"tiles"`
	Bounds    math3d.AABB        `json:"bounds"`
	TotalPoints uint64           `json:"total_points"`
}

func NewLODBuilder(octree *Octree, datasetID string, cfg *config.OctreeConfig, storageCfg *config.StorageConfig) *LODBuilder {
	return &LODBuilder{
		Octree:    octree,
		LODLevels: cfg.LODLevels,
		TileDir:   storageCfg.TileDir,
		DatasetID: datasetID,
		tiles:     make(map[string]*Tile),
	}
}

func (b *LODBuilder) Build() (*DatasetTileIndex, error) {
	index := &DatasetTileIndex{
		DatasetID:   b.DatasetID,
		Bounds:      b.Octree.GlobalBounds,
		TotalPoints: b.Octree.TotalPoints,
	}

	tilesPath := filepath.Join(b.TileDir, b.DatasetID)
	if err := os.MkdirAll(tilesPath, 0755); err != nil {
		return nil, fmt.Errorf("failed to create tiles directory: %w", err)
	}

	for lod := 0; lod < b.LODLevels; lod++ {
		levelTiles, err := b.buildLODLevel(lod)
		if err != nil {
			return nil, fmt.Errorf("failed to build LOD level %d: %w", lod, err)
		}

		gridSize := int64(1 << uint(lod))
		nodeSize := b.Octree.GlobalBounds.Size().Div(float64(gridSize))

		level := LODLevel{
			Level:       lod,
			GridSize:    gridSize,
			NodeSize:    nodeSize,
			TileCount:   len(levelTiles),
		}

		totalPoints := uint64(0)
		for _, t := range levelTiles {
			totalPoints += uint64(t.PointCount)
			level.TotalPoints = totalPoints

			meta := TileMetadata{
				Key:        b.tileKey(t.LOD, t.X, t.Y, t.Z),
				LOD:        t.LOD,
				X:          t.X,
				Y:          t.Y,
				Z:          t.Z,
				Bounds:     t.Bounds,
				PointCount: t.PointCount,
				Center:     t.Center,
				ParentKey:  t.ParentKey,
				ChildKeys:  t.ChildKeys,
			}
			index.Tiles = append(index.Tiles, meta)
		}

		index.Levels = append(index.Levels, level)
	}

	if err := b.saveIndex(index); err != nil {
		return nil, err
	}

	return index, nil
}

func (b *LODBuilder) buildLODLevel(lod int) ([]*Tile, error) {
	var tiles []*Tile
	gridSize := int64(1 << uint(lod))
	bounds := b.Octree.GlobalBounds
	size := bounds.Size()
	nodeSize := size.Div(float64(gridSize))

	samplingRate := 1
	if lod > 0 {
		samplingRate = int(math.Pow(8, float64(lod)))
	}

	var wg sync.WaitGroup
	tileChan := make(chan *Tile, gridSize*gridSize*gridSize)
	limit := make(chan struct{}, 8)

	for x := int64(0); x < gridSize; x++ {
		for y := int64(0); y < gridSize; y++ {
			for z := int64(0); z < gridSize; z++ {
				x, y, z := x, y, z
				limit <- struct{}{}
				wg.Add(1)

				go func() {
					defer wg.Done()
					defer func() { <-limit }()

					tile := b.buildTile(lod, x, y, z, nodeSize, bounds, samplingRate)
					if tile != nil && tile.PointCount > 0 {
						tileChan <- tile
					}
				}()
			}
		}
	}

	go func() {
		wg.Wait()
		close(tileChan)
	}()

	for tile := range tileChan {
		b.tilesMutex.Lock()
		b.tiles[b.tileKey(tile.LOD, tile.X, tile.Y, tile.Z)] = tile
		b.tilesMutex.Unlock()
		tiles = append(tiles, tile)

		if err := b.saveTile(tile); err != nil {
			return nil, err
		}
	}

	b.buildHierarchy(tiles, lod, gridSize)

	return tiles, nil
}

func (b *LODBuilder) buildTile(lod int, x, y, z int64, nodeSize math3d.Vec3, bounds math3d.AABB, samplingRate int) *Tile {
	min := bounds.Min
	tileMin := math3d.Vec3{
		X: min.X + float64(x)*nodeSize.X,
		Y: min.Y + float64(y)*nodeSize.Y,
		Z: min.Z + float64(z)*nodeSize.Z,
	}
	tileMax := math3d.Vec3{
		X: tileMin.X + nodeSize.X,
		Y: tileMin.Y + nodeSize.Y,
		Z: tileMin.Z + nodeSize.Z,
	}
	tileAABB := math3d.NewAABB(tileMin, tileMax)

	nodes := b.Octree.QueryByAABB(tileAABB)
	if len(nodes) == 0 {
		return nil
	}

	var points []parser.Point
	for _, node := range nodes {
		for i, p := range node.Points {
			if samplingRate <= 1 || i%samplingRate == 0 {
				pv := math3d.Vec3{X: p.X, Y: p.Y, Z: p.Z}
				if tileAABB.Contains(pv) {
					points = append(points, p)
				}
			}
		}
	}

	if len(points) == 0 {
		return nil
	}

	return &Tile{
		DatasetID:  b.DatasetID,
		LOD:        lod,
		X:          x,
		Y:          y,
		Z:          z,
		Bounds:     tileAABB,
		PointCount: len(points),
		Points:     points,
		Center:     tileAABB.Center(),
	}
}

func (b *LODBuilder) buildHierarchy(tiles []*Tile, currentLOD int, gridSize int64) {
	if currentLOD == 0 {
		return
	}

	for _, tile := range tiles {
		parentX := tile.X / 2
		parentY := tile.Y / 2
		parentZ := tile.Z / 2
		parentKey := b.tileKey(currentLOD-1, parentX, parentY, parentZ)
		tile.ParentKey = parentKey

		b.tilesMutex.RLock()
		parentTile, exists := b.tiles[parentKey]
		b.tilesMutex.RUnlock()

		if exists {
			childKey := b.tileKey(tile.LOD, tile.X, tile.Y, tile.Z)
			parentTile.ChildKeys = append(parentTile.ChildKeys, childKey)
		}
	}
}

func (b *LODBuilder) tileKey(lod int, x, y, z int64) string {
	return fmt.Sprintf("%s:%d:%d:%d:%d", b.DatasetID, lod, x, y, z)
}

func (b *LODBuilder) saveTile(tile *Tile) error {
	filename := fmt.Sprintf("%s_tile_%d_%d_%d_%d.gob", b.DatasetID, tile.LOD, tile.X, tile.Y, tile.Z)
	filepath := filepath.Join(b.TileDir, b.DatasetID, filename)

	f, err := os.Create(filepath)
	if err != nil {
		return fmt.Errorf("failed to create tile file: %w", err)
	}
	defer f.Close()

	encoder := gob.NewEncoder(f)
	if err := encoder.Encode(tile); err != nil {
		return fmt.Errorf("failed to encode tile: %w", err)
	}

	return nil
}

func (b *LODBuilder) LoadTile(lod int, x, y, z int64) (*Tile, error) {
	key := b.tileKey(lod, x, y, z)

	b.tilesMutex.RLock()
	if tile, exists := b.tiles[key]; exists {
		b.tilesMutex.RUnlock()
		return tile, nil
	}
	b.tilesMutex.RUnlock()

	filename := fmt.Sprintf("%s_tile_%d_%d_%d_%d.gob", b.DatasetID, lod, x, y, z)
	filepath := filepath.Join(b.TileDir, b.DatasetID, filename)

	f, err := os.Open(filepath)
	if err != nil {
		return nil, fmt.Errorf("failed to open tile file: %w", err)
	}
	defer f.Close()

	var tile Tile
	decoder := gob.NewDecoder(f)
	if err := decoder.Decode(&tile); err != nil {
		return nil, fmt.Errorf("failed to decode tile: %w", err)
	}

	b.tilesMutex.Lock()
	b.tiles[key] = &tile
	b.tilesMutex.Unlock()

	return &tile, nil
}

func (b *LODBuilder) saveIndex(index *DatasetTileIndex) error {
	sort.Slice(index.Tiles, func(i, j int) bool {
		if index.Tiles[i].LOD != index.Tiles[j].LOD {
			return index.Tiles[i].LOD < index.Tiles[j].LOD
		}
		if index.Tiles[i].X != index.Tiles[j].X {
			return index.Tiles[i].X < index.Tiles[j].X
		}
		if index.Tiles[i].Y != index.Tiles[j].Y {
			return index.Tiles[i].Y < index.Tiles[j].Y
		}
		return index.Tiles[i].Z < index.Tiles[j].Z
	})

	indexPath := filepath.Join(b.TileDir, b.DatasetID, "index.json")
	data, err := json.MarshalIndent(index, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal index: %w", err)
	}

	if err := os.WriteFile(indexPath, data, 0644); err != nil {
		return fmt.Errorf("failed to write index file: %w", err)
	}

	return nil
}

func (b *LODBuilder) LoadIndex() (*DatasetTileIndex, error) {
	indexPath := filepath.Join(b.TileDir, b.DatasetID, "index.json")
	data, err := os.ReadFile(indexPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read index file: %w", err)
	}

	var index DatasetTileIndex
	if err := json.Unmarshal(data, &index); err != nil {
		return nil, fmt.Errorf("failed to unmarshal index: %w", err)
	}

	return &index, nil
}

func (b *LODBuilder) GetTilesForView(frustum *math3d.Frustum, lodBias float64, maxTiles int) []TileMetadata {
	index, err := b.LoadIndex()
	if err != nil {
		return nil
	}

	var visibleTiles []TileMetadata

	for _, tile := range index.Tiles {
		if !frustum.IntersectsAABB(tile.Bounds) {
			continue
		}

		if tile.LOD < int(float64(len(index.Levels)-1)*lodBias)+1 {
			visibleTiles = append(visibleTiles, tile)
		}

		if len(visibleTiles) >= maxTiles {
			break
		}
	}

	return visibleTiles
}
