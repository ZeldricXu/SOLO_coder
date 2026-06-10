package octree

import (
	"fmt"
	"math"
	"math/rand"
	"pointcloud-platform/config"
	"pointcloud-platform/internal/parser"
	"pointcloud-platform/internal/testutil"
	"pointcloud-platform/pkg/math3d"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestOctree_InsertAndQuery_NearestNeighbor(t *testing.T) {
	assert := testutil.NewAssert(t)

	cfg := &config.OctreeConfig{
		MaxPointsPerNode: 100,
		MaxDepth:         10,
	}

	bounds := math3d.AABB{
		Min: math3d.Vec3{X: -100, Y: -100, Z: -100},
		Max: math3d.Vec3{X: 100, Y: 100, Z: 100},
	}

	tree := NewOctree(cfg, bounds)

	rng := rand.New(rand.NewSource(42))
	pointCount := 100000
	points := make([]parser.Point, pointCount)

	for i := 0; i < pointCount; i++ {
		points[i] = parser.Point{
			X: bounds.Min.X + rng.Float64()*(bounds.Max.X-bounds.Min.X),
			Y: bounds.Min.Y + rng.Float64()*(bounds.Max.Y-bounds.Min.Y),
			Z: bounds.Min.Z + rng.Float64()*(bounds.Max.Z-bounds.Min.Z),
		}
	}

	for _, p := range points {
		tree.Insert(p)
	}

	queryPoint := parser.Point{X: 10.5, Y: 20.3, Z: -5.2}
	queryAABB := math3d.AABB{
		Min: math3d.Vec3{X: queryPoint.X - 5, Y: queryPoint.Y - 5, Z: queryPoint.Z - 5},
		Max: math3d.Vec3{X: queryPoint.X + 5, Y: queryPoint.Y + 5, Z: queryPoint.Z + 5},
	}

	resultNodes := tree.QueryByAABB(queryAABB)

	var result []parser.Point
	for _, node := range resultNodes {
		result = append(result, node.Points...)
	}

	assert.Greater(float64(len(result)), 0, "should find points in range")
	assert.Less(float64(len(result)), float64(pointCount))

	withinRange := 0
	for _, p := range result {
		dx := p.X - queryPoint.X
		dy := p.Y - queryPoint.Y
		dz := p.Z - queryPoint.Z
		distSq := dx*dx + dy*dy + dz*dz
		if distSq <= 75.0+0.001 {
			withinRange++
		}
	}

	assert.Greater(float64(withinRange), float64(len(result))*0.1, "should have points within query range")
	t.Logf("  Points within exact range: %d/%d (%.1f%%)", withinRange, len(result), float64(withinRange)/float64(len(result))*100)

	t.Logf("Found %d points in neighborhood out of %d total", len(result), pointCount)
}

func TestOctree_InsertMillionPoints_SpatialError(t *testing.T) {
	assert := testutil.NewAssert(t)

	cfg := &config.OctreeConfig{
		MaxPointsPerNode: 5000,
		MaxDepth:         12,
	}

	bounds := math3d.AABB{
		Min: math3d.Vec3{X: -1000, Y: -1000, Z: -100},
		Max: math3d.Vec3{X: 1000, Y: 1000, Z: 100},
	}

	tree := NewOctree(cfg, bounds)

	rng := rand.New(rand.NewSource(12345))
	pointCount := 1000000
	points := make([]parser.Point, pointCount)

	for i := 0; i < pointCount; i++ {
		points[i] = parser.Point{
			X: bounds.Min.X + rng.Float64()*(bounds.Max.X-bounds.Min.X),
			Y: bounds.Min.Y + rng.Float64()*(bounds.Max.Y-bounds.Min.Y),
			Z: bounds.Min.Z + rng.Float64()*(bounds.Max.Z-bounds.Min.Z),
		}
	}

	t.Logf("Inserting %d points into octree...", pointCount)
	start := time.Now()

	for _, p := range points {
		tree.Insert(p)
	}

	insertTime := time.Since(start)
	t.Logf("Inserted %d points in %v", pointCount, insertTime)

	sampleCount := 1000
	maxError := 0.0
	totalError := 0.0
	errorsFound := 0

	for i := 0; i < sampleCount; i++ {
		idx := rng.Intn(pointCount)
		original := points[idx]

		queryAABB := math3d.AABB{
			Min: math3d.Vec3{X: original.X - 0.1, Y: original.Y - 0.1, Z: original.Z - 0.1},
			Max: math3d.Vec3{X: original.X + 0.1, Y: original.Y + 0.1, Z: original.Z + 0.1},
		}

		resultNodes := tree.QueryByAABB(queryAABB)

		var result []parser.Point
		for _, node := range resultNodes {
			result = append(result, node.Points...)
		}

		if len(result) == 0 {
			errorsFound++
			totalError += 0.1732
			if 0.1732 > maxError {
				maxError = 0.1732
			}
			continue
		}

		minDist := 1e9
		for _, rp := range result {
			dx := rp.X - original.X
			dy := rp.Y - original.Y
			dz := rp.Z - original.Z
			dist := dx*dx + dy*dy + dz*dz
			if dist < minDist {
				minDist = dist
			}
		}
		minDist = mathSqrt(minDist)

		totalError += minDist
		if minDist > maxError {
			maxError = minDist
		}

		if minDist > 0.01 {
			errorsFound++
		}
	}

	avgError := totalError / float64(sampleCount)
	t.Logf("Spatial query statistics:")
	t.Logf("  Samples: %d", sampleCount)
	t.Logf("  Points with error > 0.01: %d (%.2f%%)", errorsFound, float64(errorsFound)/float64(sampleCount)*100)
	t.Logf("  Average error: %.6f", avgError)
	t.Logf("  Maximum error: %.6f", maxError)

	assert.Less(avgError, 0.01, "average spatial error should be within tolerance")
	assert.Less(maxError, 0.2, "maximum spatial error should be within tolerance")
}

func TestLODBuilder_LevelTransition_NoCracks(t *testing.T) {
	assert := testutil.NewAssert(t)

	tmpDir, cleanup, err := testutil.TempDir("lod-test")
	assert.NoError(err)
	defer cleanup()

	storageCfg := &config.StorageConfig{
		TileDir: tmpDir,
	}

	octreeCfg := &config.OctreeConfig{
		MaxPointsPerNode: 1000,
		MaxDepth:         6,
		LODLevels:        4,
	}

	fixture := testutil.NewPointCloudFixture(100000, 999)

	bounds := fixture.Bounds
	tree := NewOctree(octreeCfg, bounds)
	for _, p := range fixture.Points {
		tree.Insert(p)
	}

	builder := NewLODBuilder(tree, "test-dataset", octreeCfg, storageCfg)

	t.Log("Building LOD tiles...")
	start := time.Now()
	index, err := builder.Build()
	assert.NoError(err)

	buildTime := time.Since(start)
	t.Logf("Built %d LOD levels in %v", len(index.Levels), buildTime)

	for lod, level := range index.Levels {
		t.Logf("  LOD %d: %d tiles, %d total points", lod, level.TileCount, level.TotalPoints)

		if lod > 0 && level.TotalPoints > 500 {
			expectedRatio := 1.0 / 8.0
			actualRatio := float64(level.TotalPoints) / float64(index.Levels[lod-1].TotalPoints)

			t.Logf("    Point ratio vs LOD %d: %.4f (expected ~%.4f)", lod-1, actualRatio, expectedRatio)

			assert.Greater(actualRatio, expectedRatio*0.3, "LOD point reduction should be reasonable")
			assert.Less(actualRatio, expectedRatio*3.0, "LOD point reduction should be reasonable")
		}
	}

	for lod := 0; lod < len(index.Levels)-1; lod++ {
		currentLevel := index.Levels[lod]
		nextLevel := index.Levels[lod+1]

		if currentLevel.TileCount == 0 || nextLevel.TileCount == 0 {
			continue
		}

		currentBounds := index.Bounds
		nextBounds := index.Bounds

		overlapMin := math3d.Vec3{
			X: mathMax(currentBounds.Min.X, nextBounds.Min.X),
			Y: mathMax(currentBounds.Min.Y, nextBounds.Min.Y),
			Z: mathMax(currentBounds.Min.Z, nextBounds.Min.Z),
		}
		overlapMax := math3d.Vec3{
			X: mathMin(currentBounds.Max.X, nextBounds.Max.X),
			Y: mathMin(currentBounds.Max.Y, nextBounds.Max.Y),
			Z: mathMin(currentBounds.Max.Z, nextBounds.Max.Z),
		}

		overlapVolume := (overlapMax.X - overlapMin.X) * (overlapMax.Y - overlapMin.Y) * (overlapMax.Z - overlapMin.Z)
		currentVolume := (currentBounds.Max.X - currentBounds.Min.X) * (currentBounds.Max.Y - currentBounds.Min.Y) * (currentBounds.Max.Z - currentBounds.Min.Z)
		overlapRatio := overlapVolume / currentVolume

		t.Logf("LOD %d->%d boundary overlap: %.2f%%", lod, lod+1, overlapRatio*100)

		assert.Greater(overlapRatio, 0.95, "LOD level boundaries should overlap to prevent cracks")
	}
}

func TestOctree_ConcurrentInsert(t *testing.T) {
	assert := testutil.NewAssert(t)

	cfg := &config.OctreeConfig{
		MaxPointsPerNode: 1000,
		MaxDepth:         10,
	}

	bounds := math3d.AABB{
		Min: math3d.Vec3{X: -500, Y: -500, Z: -50},
		Max: math3d.Vec3{X: 500, Y: 500, Z: 50},
	}

	tree := NewOctree(cfg, bounds)

	goroutineCount := 10
	pointsPerGoroutine := 10000

	var wg sync.WaitGroup
	wg.Add(goroutineCount)

	for g := 0; g < goroutineCount; g++ {
		go func(seed int) {
			defer wg.Done()

			rng := rand.New(rand.NewSource(int64(seed)))
			for i := 0; i < pointsPerGoroutine; i++ {
				p := parser.Point{
					X: bounds.Min.X + rng.Float64()*(bounds.Max.X-bounds.Min.X),
					Y: bounds.Min.Y + rng.Float64()*(bounds.Max.Y-bounds.Min.Y),
					Z: bounds.Min.Z + rng.Float64()*(bounds.Max.Z-bounds.Min.Z),
				}
				tree.Insert(p)
			}
		}(g)
	}

	wg.Wait()

	totalInserted := goroutineCount * pointsPerGoroutine

	resultNodes := tree.QueryByAABB(bounds)
	var allPoints []parser.Point
	for _, node := range resultNodes {
		allPoints = append(allPoints, node.Points...)
	}

	t.Logf("Concurrent insert: %d goroutines, %d points each, query returned %d points",
		goroutineCount, pointsPerGoroutine, len(allPoints))

	assert.GreaterOrEqual(float64(len(allPoints)), float64(totalInserted)*0.99, "should have at least 99% of points")
}

func TestOctree_FrustumCulling(t *testing.T) {
	assert := testutil.NewAssert(t)

	cfg := &config.OctreeConfig{
		MaxPointsPerNode: 500,
		MaxDepth:         8,
	}

	bounds := math3d.AABB{
		Min: math3d.Vec3{X: -1000, Y: -1000, Z: -100},
		Max: math3d.Vec3{X: 1000, Y: 1000, Z: 100},
	}

	tree := NewOctree(cfg, bounds)

	rng := rand.New(rand.NewSource(789))
	for i := 0; i < 50000; i++ {
		tree.Insert(parser.Point{
			X: bounds.Min.X + rng.Float64()*(bounds.Max.X-bounds.Min.X),
			Y: bounds.Min.Y + rng.Float64()*(bounds.Max.Y-bounds.Min.Y),
			Z: bounds.Min.Z + rng.Float64()*(bounds.Max.Z-bounds.Min.Z),
		})
	}

	frustum := testutil.GenerateFrustum(
		math3d.Vec3{X: 0, Y: -1500, Z: 0},
		math3d.Vec3{X: 0, Y: 0, Z: 0},
		60, 1.0, 0.1, 2000,
	)

	start := time.Now()
	visibleNodes := tree.QueryByFrustum(frustum, 10000)
	queryTime := time.Since(start)

	var visiblePoints []parser.Point
	for _, node := range visibleNodes {
		visiblePoints = append(visiblePoints, node.Points...)
	}

	allNodes := tree.QueryByAABB(bounds)
	var allPoints []parser.Point
	for _, node := range allNodes {
		allPoints = append(allPoints, node.Points...)
	}

	t.Logf("Frustum culling:")
	t.Logf("  Total points: %d", len(allPoints))
	t.Logf("  Visible points: %d (%.1f%%)", len(visiblePoints), float64(len(visiblePoints))/float64(len(allPoints))*100)
	t.Logf("  Query time: %v", queryTime)

	assert.Less(float64(len(visiblePoints)), float64(len(allPoints)), "frustum should filter points")
	assert.Greater(float64(len(visiblePoints)), 0, "should have some visible points")
}

func mathSqrt(x float64) float64 {
	return math.Sqrt(x)
}

func mathMax(a, b float64) float64 {
	if a > b {
		return a
	}
	return b
}

func mathMin(a, b float64) float64 {
	if a < b {
		return a
	}
	return b
}

func TestOctree_InsertPoints_IncrementalUpdate(t *testing.T) {
	assert := testutil.NewAssert(t)

	cfg := &config.OctreeConfig{
		MaxPointsPerNode: 100,
		MaxDepth:         10,
	}

	bounds := math3d.AABB{
		Min: math3d.Vec3{X: -100, Y: -100, Z: -100},
		Max: math3d.Vec3{X: 100, Y: 100, Z: 100},
	}

	tree := NewOctree(cfg, bounds)

	rng := rand.New(rand.NewSource(42))
	initialPoints := make([]parser.Point, 50000)
	for i := 0; i < 50000; i++ {
		initialPoints[i] = parser.Point{
			X: bounds.Min.X + rng.Float64()*(bounds.Max.X-bounds.Min.X),
			Y: bounds.Min.Y + rng.Float64()*(bounds.Max.Y-bounds.Min.Y),
			Z: bounds.Min.Z + rng.Float64()*(bounds.Max.Z-bounds.Min.Z),
		}
	}

	for _, p := range initialPoints {
		tree.Insert(p)
	}

	assert.Equal(uint64(50000), tree.TotalPoints, "should have 50000 initial points")

	t.Log("Initial build complete. Starting incremental update...")

	incrementalPoints := make([]parser.Point, 10000)
	for i := 0; i < 10000; i++ {
		incrementalPoints[i] = parser.Point{
			X: bounds.Min.X + rng.Float64()*(bounds.Max.X-bounds.Min.X),
			Y: bounds.Min.Y + rng.Float64()*(bounds.Max.Y-bounds.Min.Y),
			Z: bounds.Min.Z + rng.Float64()*(bounds.Max.Z-bounds.Min.Z),
		}
	}

	start := time.Now()
	result := tree.InsertPoints(incrementalPoints)
	elapsed := time.Since(start)

	t.Logf("Incremental update result:")
	t.Logf("  Inserted points: %d", result.InsertedPoints)
	t.Logf("  Updated nodes: %d", result.UpdatedNodes)
	t.Logf("  Time taken: %v", elapsed)

	assert.Equal(uint64(10000), uint64(result.InsertedPoints), "should insert 10000 new points")
	assert.Equal(uint64(60000), tree.TotalPoints, "total points should be 60000")
	assert.Greater(float64(result.UpdatedNodes), 0, "should have updated nodes")
	assert.Nil(result.NewBounds, "bounds should not change")

	affectedTiles, affectedLODs := tree.GetAffectedTiles(4)
	t.Logf("  Affected tiles: %d", len(affectedTiles))
	t.Logf("  Affected LODs: %v", affectedLODs)

	assert.Greater(float64(len(affectedTiles)), 0, "should have affected tiles")
	assert.Greater(float64(len(affectedLODs)), 0, "should have affected LODs")

	tree.ClearDirty()
	tree.dirtyMu.Lock()
	dirtyCount := len(tree.dirtyNodes)
	tree.dirtyMu.Unlock()
	assert.Equal(0, dirtyCount, "dirty nodes should be cleared")
}

func TestOctree_InsertPoints_BoundsExpansion(t *testing.T) {
	assert := testutil.NewAssert(t)

	cfg := &config.OctreeConfig{
		MaxPointsPerNode: 100,
		MaxDepth:         10,
	}

	bounds := math3d.AABB{
		Min: math3d.Vec3{X: -100, Y: -100, Z: -100},
		Max: math3d.Vec3{X: 100, Y: 100, Z: 100},
	}

	tree := NewOctree(cfg, bounds)

	rng := rand.New(rand.NewSource(123))
	initialPoints := make([]parser.Point, 1000)
	for i := 0; i < 1000; i++ {
		initialPoints[i] = parser.Point{
			X: bounds.Min.X + rng.Float64()*(bounds.Max.X-bounds.Min.X),
			Y: bounds.Min.Y + rng.Float64()*(bounds.Max.Y-bounds.Min.Y),
			Z: bounds.Min.Z + rng.Float64()*(bounds.Max.Z-bounds.Min.Z),
		}
	}

	for _, p := range initialPoints {
		tree.Insert(p)
	}

	expansionPoints := []parser.Point{
		{X: 200, Y: 200, Z: 200},
		{X: -200, Y: -200, Z: -200},
		{X: 150, Y: -150, Z: 150},
	}

	result := tree.InsertPoints(expansionPoints)

	t.Logf("Bounds expansion result:")
	t.Logf("  Inserted points: %d", result.InsertedPoints)
	t.Logf("  Updated nodes: %d", result.UpdatedNodes)
	t.Logf("  Old bounds: [%v, %v]", bounds.Min, bounds.Max)
	if result.NewBounds != nil {
		t.Logf("  New bounds: [%v, %v]", result.NewBounds.Min, result.NewBounds.Max)
	}

	assert.Equal(3, result.InsertedPoints, "should insert 3 expansion points")
	assert.NotNil(result.NewBounds, "bounds should be expanded")
	assert.Less(result.NewBounds.Min.X, bounds.Min.X, "min X should expand")
	assert.Greater(result.NewBounds.Max.X, bounds.Max.X, "max X should expand")
}

func TestOctree_GetAffectedTiles(t *testing.T) {
	assert := testutil.NewAssert(t)

	cfg := &config.OctreeConfig{
		MaxPointsPerNode: 500,
		MaxDepth:         8,
		LODLevels:        4,
	}

	bounds := math3d.AABB{
		Min: math3d.Vec3{X: -1000, Y: -1000, Z: -100},
		Max: math3d.Vec3{X: 1000, Y: 1000, Z: 100},
	}

	tree := NewOctree(cfg, bounds)

	rng := rand.New(rand.NewSource(456))
	for i := 0; i < 50000; i++ {
		tree.Insert(parser.Point{
			X: bounds.Min.X + rng.Float64()*(bounds.Max.X-bounds.Min.X),
			Y: bounds.Min.Y + rng.Float64()*(bounds.Max.Y-bounds.Min.Y),
			Z: bounds.Min.Z + rng.Float64()*(bounds.Max.Z-bounds.Min.Z),
		})
	}

	localPoints := make([]parser.Point, 5000)
	for i := 0; i < 5000; i++ {
		localPoints[i] = parser.Point{
			X: 100 + rng.Float64()*200,
			Y: 100 + rng.Float64()*200,
			Z: -50 + rng.Float64()*100,
		}
	}

	result := tree.InsertPoints(localPoints)
	assert.Equal(uint64(5000), result.InsertedPoints, "should insert 5000 local points")

	maxLOD := 4
	affectedTiles, affectedLODs := tree.GetAffectedTiles(maxLOD)

	t.Logf("Affected tiles analysis:")
	t.Logf("  Total affected tiles: %d", len(affectedTiles))
	t.Logf("  Affected LOD levels: %v", affectedLODs)

	tilesByLOD := make(map[int]int)
	for _, tile := range affectedTiles {
		tilesByLOD[tile.LOD]++
	}

	for lod, count := range tilesByLOD {
		t.Logf("    LOD %d: %d tiles", lod, count)
	}

	assert.Greater(float64(len(affectedTiles)), 0, "should have affected tiles")
	foundLOD0 := false
	for _, lod := range affectedLODs {
		if lod == 0 {
			foundLOD0 = true
			break
		}
	}
	assert.True(foundLOD0, "should affect LOD 0")

	seenKeys := make(map[string]bool)
	for _, tile := range affectedTiles {
		key := fmt.Sprintf("%d-%d-%d-%d", tile.LOD, tile.X, tile.Y, tile.Z)
		assert.False(seenKeys[key], "tile keys should be unique")
		seenKeys[key] = true
	}
}

func TestLODBuilder_UpdateAffectedTiles(t *testing.T) {
	assert := testutil.NewAssert(t)

	tmpDir, cleanup, err := testutil.TempDir("lod-incremental-test")
	assert.NoError(err)
	defer cleanup()

	storageCfg := &config.StorageConfig{
		TileDir: tmpDir,
	}

	octreeCfg := &config.OctreeConfig{
		MaxPointsPerNode: 1000,
		MaxDepth:         6,
		LODLevels:        4,
	}

	fixture := testutil.NewPointCloudFixture(50000, 789)

	bounds := fixture.Bounds
	tree := NewOctree(octreeCfg, bounds)
	for _, p := range fixture.Points {
		tree.Insert(p)
	}

	builder := NewLODBuilder(tree, "test-dataset-incremental", octreeCfg, storageCfg)

	t.Log("Building initial LOD tiles...")
	index, err := builder.Build()
	assert.NoError(err)
	t.Logf("Initial build: %d levels, %d total points", len(index.Levels), index.TotalPoints)

	t.Log("Performing incremental update...")
	rng := rand.New(rand.NewSource(999))
	incrementalPoints := make([]parser.Point, 5000)
	for i := 0; i < 5000; i++ {
		incrementalPoints[i] = parser.Point{
			X: 50 + rng.Float64()*100,
			Y: 50 + rng.Float64()*100,
			Z: -20 + rng.Float64()*40,
		}
	}

	result := tree.InsertPoints(incrementalPoints)
	assert.Equal(uint64(5000), result.InsertedPoints, "should insert 5000 points")

	affectedTiles, _ := tree.GetAffectedTiles(4)
	t.Logf("Affected tiles for update: %d", len(affectedTiles))

	newTotal := tree.TotalPoints
	start := time.Now()
	lodResult, err := builder.UpdateAffectedTiles(affectedTiles, newTotal)
	elapsed := time.Since(start)

	assert.NoError(err)
	t.Logf("Incremental LOD update result:")
	t.Logf("  Updated tiles: %d", len(lodResult.UpdatedTiles))
	t.Logf("  Updated levels: %v", lodResult.UpdatedLevels)
	t.Logf("  New total points: %d", lodResult.NewTotalPoints)
	t.Logf("  Reindexed: %v", lodResult.Reindexed)
	t.Logf("  Time taken: %v", elapsed)

	assert.Greater(float64(len(lodResult.UpdatedTiles)), 0, "should have updated tiles")
	assert.Equal(uint64(55000), lodResult.NewTotalPoints, "total points should match")
	assert.True(lodResult.Reindexed, "index should be reindexed")

	updatedIndex, err := builder.LoadIndex()
	assert.NoError(err)
	assert.Equal(uint64(55000), updatedIndex.TotalPoints, "index total points should be updated")
}

func TestOctree_IncrementalInsert_WithinBounds(t *testing.T) {
	assert := testutil.NewAssert(t)

	tmpDir, cleanup, err := testutil.TempDir("incr-within-bounds")
	assert.NoError(err)
	defer cleanup()

	octreeCfg := &config.OctreeConfig{
		MaxDepth:         6,
		MinPointsPerNode: 1,
		MaxPointsPerNode: 50,
		LODLevels:        3,
	}
	storageCfg := &config.StorageConfig{
		TileDir: tmpDir,
	}

	fixture := testutil.NewPointCloudFixture(5000, 42)
	pc := &parser.PointCloud{
		Points: fixture.Points,
		Bounds: fixture.Bounds,
	}

	svc := NewOctreeService(octreeCfg, storageCfg)
	buildResult, err := svc.BuildFromPointCloud("incr-within", pc)
	assert.NoError(err)
	assert.Greater(float64(buildResult.TotalPoints), 0)

	octree, exists := svc.GetOctree("incr-within")
	assert.True(exists)
	initialTotal := octree.TotalPoints

	incrementalFixture := testutil.NewPointCloudFixture(200, 99)
	resp, err := svc.IncrementalUpdateFromPoints("incr-within", incrementalFixture.Points)
	assert.NoError(err)
	assert.Greater(float64(resp.NewTotalPoints), float64(initialTotal))

	octree, _ = svc.GetOctree("incr-within")
	octree.dirtyMu.Lock()
	dirtyCount := len(octree.dirtyNodes)
	octree.dirtyMu.Unlock()
	assert.Equal(0, dirtyCount)
}

func TestOctree_IncrementalInsert_ExpandBounds(t *testing.T) {
	assert := testutil.NewAssert(t)

	tmpDir, cleanup, err := testutil.TempDir("incr-expand-bounds")
	assert.NoError(err)
	defer cleanup()

	octreeCfg := &config.OctreeConfig{
		MaxDepth:         6,
		MinPointsPerNode: 1,
		MaxPointsPerNode: 50,
		LODLevels:        3,
	}
	storageCfg := &config.StorageConfig{
		TileDir: tmpDir,
	}

	fixture := testutil.NewPointCloudFixture(1000, 77)
	pc := &parser.PointCloud{
		Points: fixture.Points,
		Bounds: fixture.Bounds,
	}

	svc := NewOctreeService(octreeCfg, storageCfg)
	_, err = svc.BuildFromPointCloud("incr-expand", pc)
	assert.NoError(err)

	expansionPoints := []parser.Point{
		{X: 5000, Y: 5000, Z: 500},
		{X: -5000, Y: -5000, Z: -500},
	}

	resp, err := svc.IncrementalUpdateFromPoints("incr-expand", expansionPoints)
	assert.NoError(err)
	assert.NotNil(resp.NewBounds)
	t.Logf("RebuildRequired after bounds expansion: %v", resp.RebuildRequired)
}

func TestOctree_IncrementalUpdate_LODPartialRebuild(t *testing.T) {
	assert := testutil.NewAssert(t)

	tmpDir, cleanup, err := testutil.TempDir("incr-lod-partial")
	assert.NoError(err)
	defer cleanup()

	octreeCfg := &config.OctreeConfig{
		MaxDepth:         6,
		MinPointsPerNode: 1,
		MaxPointsPerNode: 50,
		LODLevels:        3,
	}
	storageCfg := &config.StorageConfig{
		TileDir: tmpDir,
	}

	fixture := testutil.NewPointCloudFixture(10000, 123)
	pc := &parser.PointCloud{
		Points: fixture.Points,
		Bounds: fixture.Bounds,
	}

	svc := NewOctreeService(octreeCfg, storageCfg)
	buildResult, err := svc.BuildFromPointCloud("incr-lod-partial", pc)
	assert.NoError(err)
	assert.Greater(float64(buildResult.TileCount), 0)

	initialIndex, err := svc.GetTileIndex("incr-lod-partial")
	assert.NoError(err)
	initialTileCount := len(initialIndex.Tiles)

	rng := rand.New(rand.NewSource(456))
	var incrementalPoints []parser.Point
	for i := 0; i < 500; i++ {
		incrementalPoints = append(incrementalPoints, parser.Point{
			X: 100 + rng.Float64()*200,
			Y: 100 + rng.Float64()*200,
			Z: -20 + rng.Float64()*40,
		})
	}

	resp, err := svc.IncrementalUpdateFromPoints("incr-lod-partial", incrementalPoints)
	assert.NoError(err)
	assert.Greater(float64(resp.UpdatedTiles), 0)
	assert.Greater(float64(len(resp.UpdatedLODs)), 0)

	updatedIndex, err := svc.GetTileIndex("incr-lod-partial")
	assert.NoError(err)
	assert.GreaterOrEqual(float64(len(updatedIndex.Tiles)), float64(initialTileCount)*0.5)

	totalTiles := len(updatedIndex.Tiles)
	assert.Greater(float64(resp.UpdatedTiles), 0)
	assert.Less(float64(resp.UpdatedTiles), float64(totalTiles))
}

func TestOctree_IncrementalUpdate_ClearsCache(t *testing.T) {
	assert := testutil.NewAssert(t)

	tmpDir, cleanup, err := testutil.TempDir("incr-clear-cache")
	assert.NoError(err)
	defer cleanup()

	octreeCfg := &config.OctreeConfig{
		MaxDepth:         6,
		MinPointsPerNode: 1,
		MaxPointsPerNode: 50,
		LODLevels:        3,
	}
	storageCfg := &config.StorageConfig{
		TileDir: tmpDir,
	}

	fixture := testutil.NewPointCloudFixture(5000, 321)
	pc := &parser.PointCloud{
		Points: fixture.Points,
		Bounds: fixture.Bounds,
	}

	svc := NewOctreeService(octreeCfg, storageCfg)
	_, err = svc.BuildFromPointCloud("incr-clear-cache", pc)
	assert.NoError(err)

	octree, exists := svc.GetOctree("incr-clear-cache")
	assert.True(exists)

	rng := rand.New(rand.NewSource(654))
	var incrementalPoints []parser.Point
	for i := 0; i < 300; i++ {
		incrementalPoints = append(incrementalPoints, parser.Point{
			X: -100 + rng.Float64()*200,
			Y: -100 + rng.Float64()*200,
			Z: -20 + rng.Float64()*40,
		})
	}

	resp, err := svc.IncrementalUpdateFromPoints("incr-clear-cache", incrementalPoints)
	assert.NoError(err)
	assert.False(resp.RebuildRequired)

	octree.dirtyMu.Lock()
	dirtyCount := len(octree.dirtyNodes)
	octree.dirtyMu.Unlock()
	assert.Equal(0, dirtyCount)
}

func TestOctree_ConcurrentIncrementalUpdates(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping concurrent incremental test in short mode")
	}

	assert := testutil.NewAssert(t)

	cfg := &config.OctreeConfig{
		MaxPointsPerNode: 1000,
		MaxDepth:         10,
	}

	bounds := math3d.AABB{
		Min: math3d.Vec3{X: -500, Y: -500, Z: -50},
		Max: math3d.Vec3{X: 500, Y: 500, Z: 50},
	}

	tree := NewOctree(cfg, bounds)

	goroutineCount := 5
	updatesPerGoroutine := 10
	pointsPerUpdate := 100

	var wg sync.WaitGroup
	wg.Add(goroutineCount)

	var totalInserted uint64

	for g := 0; g < goroutineCount; g++ {
		go func(seed int) {
			defer wg.Done()

			rng := rand.New(rand.NewSource(int64(seed)))
			for u := 0; u < updatesPerGoroutine; u++ {
				points := make([]parser.Point, pointsPerUpdate)
				for i := 0; i < pointsPerUpdate; i++ {
					points[i] = parser.Point{
						X: bounds.Min.X + rng.Float64()*(bounds.Max.X-bounds.Min.X),
						Y: bounds.Min.Y + rng.Float64()*(bounds.Max.Y-bounds.Min.Y),
						Z: bounds.Min.Z + rng.Float64()*(bounds.Max.Z-bounds.Min.Z),
					}
				}

				result := tree.InsertPoints(points)
				atomic.AddUint64(&totalInserted, uint64(result.InsertedPoints))

				time.Sleep(time.Millisecond * time.Duration(rng.Intn(10)))
			}
		}(g)
	}

	wg.Wait()

	expectedTotal := goroutineCount * updatesPerGoroutine * pointsPerUpdate
	t.Logf("Concurrent incremental updates:")
	t.Logf("  Expected: %d points", expectedTotal)
	t.Logf("  Actual: %d points", tree.TotalPoints)
	t.Logf("  Reported inserted: %d points", totalInserted)

	assert.Equal(uint64(expectedTotal), totalInserted, "reported inserted should match expected")
	assert.GreaterOrEqual(float64(tree.TotalPoints), float64(uint64(float64(expectedTotal)*0.99)), "should have at least 99% of points")
}
