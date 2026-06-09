package tile

import (
	"bytes"
	"compress/gzip"
	"encoding/binary"
	"fmt"
	"io"
	"math"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"pointcloud-platform/config"
	"pointcloud-platform/internal/cache"
	"pointcloud-platform/internal/octree"
	"pointcloud-platform/internal/parser"
	"pointcloud-platform/internal/testutil"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

type MockRedisClient struct {
	data     map[string][]byte
	hotness  map[string]float64
	mu       sync.RWMutex
	failMode bool
}

func NewMockRedisClient() *MockRedisClient {
	return &MockRedisClient{
		data:    make(map[string][]byte),
		hotness: make(map[string]float64),
	}
}

func (m *MockRedisClient) SetFailMode(fail bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.failMode = fail
}

func setupTestTileService(t *testing.T, pointCount int) (*TileService, *octree.OctreeService, string, func()) {
	assert := testutil.NewAssert(t)

	tmpDir, cleanup, err := testutil.TempDir("tile-test")
	assert.NoError(err)

	cfg := &config.Config{
		Storage: config.StorageConfig{
			DataDir: tmpDir,
			TileDir: tmpDir,
		},
		Octree: config.OctreeConfig{
			MaxPointsPerNode: 1000,
			MaxDepth:         6,
			LODLevels:        4,
		},
		Redis: config.RedisConfig{
			CacheTTL: 3600,
		},
	}

	octreeService := octree.NewOctreeService(&cfg.Octree, &cfg.Storage)
	tileService := NewTileService(cfg, octreeService)

	fixture := testutil.NewPointCloudFixture(pointCount, 42)
	pc := &parser.PointCloud{
		Points: fixture.Points,
		Bounds: fixture.Bounds,
		Header: parser.PointCloudHeader{
			PointCount: uint64(len(fixture.Points)),
		},
	}

	datasetID := "test-dataset-" + strconv.FormatInt(time.Now().UnixNano(), 10)
	_, err = octreeService.BuildFromPointCloud(datasetID, pc)
	assert.NoError(err)

	return tileService, octreeService, datasetID, cleanup
}

func findTestTile(t *testing.T, octreeService *octree.OctreeService, datasetID string, targetLOD int) *octree.TileMetadata {
	t.Helper()
	index, err := octreeService.GetTileIndex(datasetID)
	if err != nil {
		t.Fatalf("failed to get tile index: %v", err)
	}
	if len(index.Tiles) == 0 {
		t.Fatal("no tiles found in index")
	}

	for _, tile := range index.Tiles {
		if tile.LOD == targetLOD && tile.PointCount > 0 {
			return &tile
		}
	}
	for _, tile := range index.Tiles {
		if tile.PointCount > 0 {
			return &tile
		}
	}
	t.Fatal("no tiles with points found")
	return nil
}

func TestTileService_RangeRequest_DataIntegrity(t *testing.T) {
	assert := testutil.NewAssert(t)

	tileService, octreeService, datasetID, cleanup := setupTestTileService(t, 5000)
	defer cleanup()

	targetTile := findTestTile(t, octreeService, datasetID, 2)
	t.Logf("Using tile: LOD=%d, X=%d, Y=%d, Z=%d, points=%d",
		targetTile.LOD, targetTile.X, targetTile.Y, targetTile.Z, targetTile.PointCount)

	tileReq := TileRequest{
		DatasetID: datasetID,
		LOD:       targetTile.LOD,
		X:         targetTile.X,
		Y:         targetTile.Y,
		Z:         targetTile.Z,
	}

	resp, tileData, err := tileService.GetTile(tileReq)
	assert.NoError(err)
	assert.NotNil(resp)
	assert.NotEmpty(tileData)

	chunkSize := 1024
	var reassembled []byte
	totalSize := len(tileData)

	for start := 0; start < totalSize; start += chunkSize {
		end := start + chunkSize - 1
		if end >= totalSize {
			end = totalSize - 1
		}

		req := httptest.NewRequest("GET", fmt.Sprintf("/tiles/%s/%d/%d/%d/%d/range",
			datasetID, targetTile.LOD, targetTile.X, targetTile.Y, targetTile.Z), nil)
		req.Header.Set("Range", "bytes="+strconv.Itoa(start)+"-"+strconv.Itoa(end))
		w := httptest.NewRecorder()

		c, _ := gin.CreateTestContext(w)
		c.Request = req
		c.Set("datasetId", datasetID)
		c.Set("lod", strconv.Itoa(targetTile.LOD))
		c.Set("x", strconv.FormatInt(targetTile.X, 10))
		c.Set("y", strconv.FormatInt(targetTile.Y, 10))
		c.Set("z", strconv.FormatInt(targetTile.Z, 10))

		tile := &octree.Tile{
			LOD:        targetTile.LOD,
			X:          targetTile.X,
			Y:          targetTile.Y,
			Z:          targetTile.Z,
			PointCount: resp.PointCount,
		}

		err := tileService.serveRangeTile(w, req, tileReq, tile, tileData, req.Header.Get("Range"))
		assert.NoError(err)

		assert.Equal(http.StatusPartialContent, w.Code, "should return 206 Partial Content")
		assert.Equal("bytes", w.Header().Get("Accept-Ranges"), "should have Accept-Ranges header")

		contentRange := w.Header().Get("Content-Range")
		expectedRange := "bytes " + strconv.Itoa(start) + "-" + strconv.Itoa(end) + "/" + strconv.Itoa(totalSize)
		assert.Equal(expectedRange, contentRange, "Content-Range should match")

		chunk := w.Body.Bytes()
		assert.Equal(end-start+1, len(chunk), "chunk size should match")
		reassembled = append(reassembled, chunk...)
	}

	assert.Equal(totalSize, len(reassembled), "reassembled data should have same length as original")
	assert.True(bytes.Equal(tileData, reassembled), "reassembled data should be identical to original")
}

func TestTileService_GzipCompression(t *testing.T) {
	assert := testutil.NewAssert(t)

	tileService, octreeService, datasetID, cleanup := setupTestTileService(t, 5000)
	defer cleanup()

	targetTile := findTestTile(t, octreeService, datasetID, 1)
	t.Logf("Gzip test using tile: LOD=%d, X=%d, Y=%d, Z=%d, points=%d",
		targetTile.LOD, targetTile.X, targetTile.Y, targetTile.Z, targetTile.PointCount)

	tileReq := TileRequest{
		DatasetID: datasetID,
		LOD:       targetTile.LOD,
		X:         targetTile.X,
		Y:         targetTile.Y,
		Z:         targetTile.Z,
	}

	resp, tileData, err := tileService.GetTile(tileReq)
	assert.NoError(err)
	assert.NotNil(resp)

	req := httptest.NewRequest("GET", fmt.Sprintf("/tiles/%s/%d/%d/%d/%d",
		datasetID, targetTile.LOD, targetTile.X, targetTile.Y, targetTile.Z), nil)
	req.Header.Set("Accept-Encoding", "gzip")
	w := httptest.NewRecorder()

	tile := &octree.Tile{
		LOD:        targetTile.LOD,
		X:          targetTile.X,
		Y:          targetTile.Y,
		Z:          targetTile.Z,
		PointCount: resp.PointCount,
	}

	err = tileService.serveFullTile(w, req, tileReq, tile, tileData)
	assert.NoError(err)

	assert.Equal(http.StatusOK, w.Code)
	assert.Equal("gzip", w.Header().Get("Content-Encoding"))

	compressed := w.Body.Bytes()
	assert.Less(float64(len(compressed)), float64(len(tileData)), "compressed data should be smaller")

	gr, err := gzip.NewReader(bytes.NewReader(compressed))
	assert.NoError(err)
	defer gr.Close()

	decompressed, err := io.ReadAll(gr)
	assert.NoError(err)

	assert.True(bytes.Equal(tileData, decompressed), "decompressed data should match original")
}

func TestTileService_RedisFailure_FallbackToDisk(t *testing.T) {
	assert := testutil.NewAssert(t)

	tileService, octreeService, datasetID, cleanup := setupTestTileService(t, 2000)
	defer cleanup()

	targetTile := findTestTile(t, octreeService, datasetID, 1)
	t.Logf("Redis failover test using tile: LOD=%d, X=%d, Y=%d, Z=%d, points=%d",
		targetTile.LOD, targetTile.X, targetTile.Y, targetTile.Z, targetTile.PointCount)

	tileReq := TileRequest{
		DatasetID: datasetID,
		LOD:       targetTile.LOD,
		X:         targetTile.X,
		Y:         targetTile.Y,
		Z:         targetTile.Z,
	}

	resp1, data1, err := tileService.GetTile(tileReq)
	assert.NoError(err)
	assert.NotNil(resp1)
	assert.NotEmpty(data1)

	originalClient := cache.Client
	defer func() { cache.Client = originalClient }()

	cache.Client = nil

	start := time.Now()
	resp2, data2, err := tileService.GetTile(tileReq)
	elapsed := time.Since(start)

	assert.NoError(err, "should not return error when Redis fails")
	assert.NotNil(resp2, "should still return response")
	assert.Equal(len(data1), len(data2), "should return correct data size")
	assert.True(bytes.Equal(data1, data2), "data should be identical")

	t.Logf("Fallback response time: %v", elapsed)
	assert.Less(float64(elapsed.Seconds()), 5.0, "fallback should not take excessive time")
}

func TestTileService_ConcurrentRequests_StressTest(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping stress test in short mode")
	}

	assert := testutil.NewAssert(t)

	tileService, octreeService, datasetID, cleanup := setupTestTileService(t, 10000)
	defer cleanup()

	index, err := octreeService.GetTileIndex(datasetID)
	assert.NoError(err)

	var availableTiles []octree.TileMetadata
	for _, tile := range index.Tiles {
		if tile.PointCount > 0 {
			availableTiles = append(availableTiles, tile)
		}
	}
	assert.True(len(availableTiles) > 0, "should have available tiles")
	t.Logf("Found %d available tiles for stress test", len(availableTiles))

	concurrentClients := 50
	requestsPerClient := 20

	var wg sync.WaitGroup
	var successCount int64
	var errorCount int64

	start := time.Now()
	rng := rand.New(rand.NewSource(time.Now().UnixNano()))

	for client := 0; client < concurrentClients; client++ {
		wg.Add(1)
		go func(clientID int) {
			defer wg.Done()

			for req := 0; req < requestsPerClient; req++ {
				tileIdx := rng.Intn(len(availableTiles))
				tile := availableTiles[tileIdx]

				tileReq := TileRequest{
					DatasetID: datasetID,
					LOD:       tile.LOD,
					X:         tile.X,
					Y:         tile.Y,
					Z:         tile.Z,
				}

				_, _, err := tileService.GetTile(tileReq)

				if err == nil {
					atomic.AddInt64(&successCount, 1)
				} else {
					atomic.AddInt64(&errorCount, 1)
				}

				time.Sleep(time.Millisecond * 2)
			}
		}(client)
	}

	wg.Wait()
	elapsed := time.Since(start)

	totalRequests := concurrentClients * requestsPerClient
	successRate := float64(successCount) / float64(totalRequests) * 100
	throughput := float64(totalRequests) / elapsed.Seconds()

	t.Logf("Concurrent stress test results:")
	t.Logf("  Total requests: %d", totalRequests)
	t.Logf("  Success: %d (%.2f%%)", successCount, successRate)
	t.Logf("  Errors: %d", errorCount)
	t.Logf("  Total time: %v", elapsed)
	t.Logf("  Throughput: %.2f req/sec", throughput)

	assert.Greater(successRate, 95.0, "success rate should be > 95%")
	assert.Greater(throughput, 50.0, "throughput should be > 50 req/sec")
}

func TestTileService_NotFound(t *testing.T) {
	assert := testutil.NewAssert(t)

	tmpDir, cleanup, err := testutil.TempDir("tile-404-test")
	assert.NoError(err)
	defer cleanup()

	cfg := &config.Config{
		Storage: config.StorageConfig{
			DataDir: tmpDir,
			TileDir: tmpDir,
		},
		Octree: config.OctreeConfig{
			MaxPointsPerNode: 1000,
			MaxDepth:         6,
			LODLevels:        4,
		},
		Redis: config.RedisConfig{
			CacheTTL: 3600,
		},
	}

	octreeService := octree.NewOctreeService(&cfg.Octree, &cfg.Storage)
	tileService := NewTileService(cfg, octreeService)

	tileReq := TileRequest{
		DatasetID: "nonexistent",
		LOD:       0,
		X:         0,
		Y:         0,
		Z:         0,
	}

	_, _, err = tileService.GetTile(tileReq)
	assert.Error(err, "should return error for non-existent dataset")
	assert.Contains(err.Error(), "not found", "error should mention not found")
}

func TestParser_CorruptedLASHeader_NoPanic(t *testing.T) {
	assert := testutil.NewAssert(t)

	tmpDir, cleanup, err := testutil.TempDir("corrupted-las-test")
	assert.NoError(err)
	defer cleanup()

	fixture := testutil.NewPointCloudFixture(100, 1)
	corruptedPath := filepath.Join(tmpDir, "corrupted.las")
	err = fixture.ToCorruptedLASFile(corruptedPath)
	assert.NoError(err)

	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("Parser panicked on corrupted LAS: %v", r)
		}
	}()

	parseService := parser.NewParseService(4)
	_, err = parseService.ParseFileHeader(corruptedPath)

	assert.Error(err, "should return error for corrupted file")
	assert.Contains(err.Error(), "error", "error should indicate parsing failure")
}

func TestTileService_BinaryEncoding(t *testing.T) {
	assert := testutil.NewAssert(t)

	tileService, _, _, cleanup := setupTestTileService(t, 100)
	defer cleanup()

	fixture := testutil.NewPointCloudFixture(10, 99)
	encoded := tileService.encodePointsBinary(fixture.Points)

	expectedSize := len(fixture.Points) * 16
	assert.Equal(expectedSize, len(encoded), "encoded size should be 16 bytes per point")

	for i, p := range fixture.Points {
		offset := i * 16

		x := math.Float32frombits(binary.LittleEndian.Uint32(encoded[offset : offset+4]))
		y := math.Float32frombits(binary.LittleEndian.Uint32(encoded[offset+4 : offset+8]))
		z := math.Float32frombits(binary.LittleEndian.Uint32(encoded[offset+8 : offset+12]))
		r := encoded[offset+12]
		g := encoded[offset+13]
		b := encoded[offset+14]

		assert.InDelta(float64(p.X), float64(x), 0.001, "X coordinate should match")
		assert.InDelta(float64(p.Y), float64(y), 0.001, "Y coordinate should match")
		assert.InDelta(float64(p.Z), float64(z), 0.001, "Z coordinate should match")
		assert.Equal(p.R, r, "R should match")
		assert.Equal(p.G, g, "G should match")
		assert.Equal(p.B, b, "B should match")
	}

	t.Logf("Binary encoding verified: %d points, %d bytes", len(fixture.Points), len(encoded))
}

func TestTileService_CacheEncoding(t *testing.T) {
	assert := testutil.NewAssert(t)

	tileService, _, _, cleanup := setupTestTileService(t, 100)
	defer cleanup()

	fixture := testutil.NewPointCloudFixture(50, 123)
	encodedPoints := tileService.encodePointsBinary(fixture.Points)

	tile := &octree.Tile{
		LOD:        2,
		X:          1,
		Y:          2,
		Z:          3,
		PointCount: len(fixture.Points),
		Bounds:     fixture.Bounds,
		Center:     fixture.Bounds.Center(),
	}

	cachedData := tileService.encodeCachedTile(tile, encodedPoints)

	decodedTile, decodedPoints, err := tileService.decodeCachedTile(cachedData)
	assert.NoError(err)
	assert.NotNil(decodedTile)
	assert.Equal(len(encodedPoints), len(decodedPoints), "decoded points size should match")
	assert.True(bytes.Equal(encodedPoints, decodedPoints), "decoded points should match")

	assert.Equal(tile.LOD, decodedTile.LOD, "LOD should match")
	assert.Equal(tile.X, decodedTile.X, "X should match")
	assert.Equal(tile.Y, decodedTile.Y, "Y should match")
	assert.Equal(tile.Z, decodedTile.Z, "Z should match")
	assert.Equal(tile.PointCount, decodedTile.PointCount, "PointCount should match")
	assert.InDelta(tile.Bounds.Min.X, decodedTile.Bounds.Min.X, 0.001, "MinX should match")
	assert.InDelta(tile.Bounds.Max.X, decodedTile.Bounds.Max.X, 0.001, "MaxX should match")
	assert.InDelta(tile.Center.X, decodedTile.Center.X, 0.001, "CenterX should match")

	t.Log("Cache encoding/decoding verified successfully")
}

func TestTileService_InvalidRangeHeader(t *testing.T) {
	assert := testutil.NewAssert(t)

	tileService, _, datasetID, cleanup := setupTestTileService(t, 1000)
	defer cleanup()

	tileReq := TileRequest{
		DatasetID: datasetID,
		LOD:       1,
		X:         0,
		Y:         0,
		Z:         0,
	}

	resp, tileData, err := tileService.GetTile(tileReq)
	assert.NoError(err)

	tile := &octree.Tile{
		LOD:        1,
		X:          0,
		Y:          0,
		Z:          0,
		PointCount: resp.PointCount,
	}

	invalidRanges := []string{
		"invalid",
		"bytes=",
		"bytes=abc-def",
		"bytes=10-5",
		"bytes=" + strconv.Itoa(len(tileData)+100) + "-" + strconv.Itoa(len(tileData)+200),
	}

	for _, invalidRange := range invalidRanges {
		req := httptest.NewRequest("GET", "/tiles/"+datasetID+"/1/0/0/0/range", nil)
		req.Header.Set("Range", invalidRange)
		w := httptest.NewRecorder()

		err := tileService.serveRangeTile(w, req, tileReq, tile, tileData, invalidRange)
		assert.Error(err, "should return error for invalid range: %s", invalidRange)
		t.Logf("Invalid range '%s' correctly rejected: %v", invalidRange, err)
	}
}
