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

type IncrementalLODResult struct {
	UpdatedTiles    []TileMetadata
	UpdatedLevels   []int
	Reindexed       bool
	RebuildRequired bool
	NewTotalPoints  uint64
}

func (b *LODBuilder) UpdateAffectedTiles(affectedKeys []TileKey, newTotalPoints uint64) (*IncrementalLODResult, error) {
	result := &IncrementalLODResult{
		UpdatedTiles:   make([]TileMetadata, 0, len(affectedKeys)),
		UpdatedLevels:  make([]int, 0),
		NewTotalPoints: newTotalPoints,
	}

	levelMap := make(map[int]bool)

	bounds := b.Octree.GlobalBounds
	size := bounds.Size()

	for _, key := range affectedKeys {
		gridSize := int64(1 << uint(key.LOD))
		nodeSize := size.Div(float64(gridSize))

		maxCoord := gridSize
		if key.X < 0 || key.Y < 0 || key.Z < 0 || key.X >= maxCoord || key.Y >= maxCoord || key.Z >= maxCoord {
			result.RebuildRequired = true
			continue
		}

		samplingRate := 1
		if key.LOD > 0 {
			samplingRate = int(math.Pow(8, float64(key.LOD)))
		}

		tile := b.buildTile(key.LOD, key.X, key.Y, key.Z, nodeSize, bounds, samplingRate)

		if tile == nil {
			tile = &Tile{
				LOD:        key.LOD,
				X:          key.X,
				Y:          key.Y,
				Z:          key.Z,
				Bounds: math3d.NewAABB(
					math3d.Vec3{
						X: bounds.Min.X + float64(key.X)*nodeSize.X,
						Y: bounds.Min.Y + float64(key.Y)*nodeSize.Y,
						Z: bounds.Min.Z + float64(key.Z)*nodeSize.Z,
					},
					math3d.Vec3{
						X: bounds.Min.X + float64(key.X+1)*nodeSize.X,
						Y: bounds.Min.Y + float64(key.Y+1)*nodeSize.Y,
						Z: bounds.Min.Z + float64(key.Z+1)*nodeSize.Z,
					},
				),
				PointCount: 0,
				Center: math3d.Vec3{
					X: bounds.Min.X + float64(key.X)*nodeSize.X + nodeSize.X*0.5,
					Y: bounds.Min.Y + float64(key.Y)*nodeSize.Y + nodeSize.Y*0.5,
					Z: bounds.Min.Z + float64(key.Z)*nodeSize.Z + nodeSize.Z*0.5,
				},
			}
		}

		tileKey := b.tileKey(key.LOD, key.X, key.Y, key.Z)

		b.tilesMutex.Lock()
		b.tiles[tileKey] = tile
		b.tilesMutex.Unlock()

		if err := b.saveTile(tile); err != nil {
			return nil, fmt.Errorf("failed to save updated tile %s: %w", tileKey, err)
		}

		meta := TileMetadata{
			Key:        tileKey,
			LOD:        tile.LOD,
			X:          tile.X,
			Y:          tile.Y,
			Z:          tile.Z,
			Bounds:     tile.Bounds,
			PointCount: tile.PointCount,
			Center:     tile.Center,
		}

		result.UpdatedTiles = append(result.UpdatedTiles, meta)
		levelMap[key.LOD] = true
	}

	for level := range levelMap {
		result.UpdatedLevels = append(result.UpdatedLevels, level)
	}

	if !result.RebuildRequired {
		if err := b.updateIndex(result.UpdatedTiles, newTotalPoints); err != nil {
			return nil, fmt.Errorf("failed to update tile index: %w", err)
		}
		result.Reindexed = true
	}

	return result, nil
}

func (b *LODBuilder) updateIndex(updatedTiles []TileMetadata, newTotalPoints uint64) error {
	index, err := b.LoadIndex()
	if err != nil {
		return fmt.Errorf("failed to load existing index: %w", err)
	}

	index.TotalPoints = newTotalPoints

	updatedMap := make(map[string]TileMetadata)
	for _, t := range updatedTiles {
		updatedMap[t.Key] = t
	}

	newTiles := make([]TileMetadata, 0, len(index.Tiles))
	seenKeys := make(map[string]bool)

	for _, existing := range index.Tiles {
		if updated, ok := updatedMap[existing.Key]; ok {
			if updated.PointCount > 0 {
				newTiles = append(newTiles, updated)
			}
			seenKeys[existing.Key] = true
		} else {
			newTiles = append(newTiles, existing)
		}
	}

	for _, updated := range updatedTiles {
		if !seenKeys[updated.Key] && updated.PointCount > 0 {
			newTiles = append(newTiles, updated)
		}
	}

	index.Tiles = newTiles

	levelPointCounts := make(map[int]uint64)
	levelTileCounts := make(map[int]int)
	for _, t := range index.Tiles {
		levelPointCounts[t.LOD] += uint64(t.PointCount)
		levelTileCounts[t.LOD]++
	}

	for i, level := range index.Levels {
		if points, ok := levelPointCounts[level.Level]; ok {
			index.Levels[i].TotalPoints = points
		}
		if count, ok := levelTileCounts[level.Level]; ok {
			index.Levels[i].TileCount = count
		}
	}

	return b.saveIndex(index)
}
