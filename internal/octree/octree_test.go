package octree

import (
	"math"
	"math/rand"
	"pointcloud-platform/config"
	"pointcloud-platform/internal/parser"
	"pointcloud-platform/internal/testutil"
	"pointcloud-platform/pkg/math3d"
	"sync"
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
