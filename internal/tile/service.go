package tile

import (
	"bytes"
	"compress/gzip"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"pointcloud-platform/config"
	"pointcloud-platform/internal/cache"
	"pointcloud-platform/internal/octree"
	"pointcloud-platform/internal/parser"
	"pointcloud-platform/pkg/math3d"
	"strconv"
	"strings"
	"sync"
)

type TileService struct {
	cfg          *config.Config
	octreeSvc    *octree.OctreeService
	transfers    map[string]*TransferSession
	transfersMu  sync.RWMutex
}

type TransferSession struct {
	ID         string
	DatasetID  string
	TileKey    string
	FileSize   int64
	BytesSent  int64
	StartTime  int64
	Completed  bool
}

type TileRequest struct {
	DatasetID string
	LOD       int
	X, Y, Z   int64
}

type TileResponse struct {
	Key         string                 `json:"key"`
	LOD         int                    `json:"lod"`
	X           int64                  `json:"x"`
	Y           int64                  `json:"y"`
	Z           int64                  `json:"z"`
	PointCount  int                    `json:"point_count"`
	Bounds      [6]float64             `json:"bounds"`
	Center      [3]float64             `json:"center"`
	Compressed  bool                   `json:"compressed"`
	Size        int                    `json:"size"`
	ParentKey   string                 `json:"parent_key,omitempty"`
	ChildKeys   []string               `json:"child_keys,omitempty"`
}

type BinaryTileFormat struct {
	Magic       [4]byte
	Version     uint16
	LOD         uint16
	X           uint32
	Y           uint32
	Z           uint32
	PointCount  uint32
	MinX        float64
	MinY        float64
	MinZ        float64
	MaxX        float64
	MaxY        float64
	MaxZ        float64
	CenterX     float64
	CenterY     float64
	CenterZ     float64
}

func NewTileService(cfg *config.Config, octreeSvc *octree.OctreeService) *TileService {
	return &TileService{
		cfg:       cfg,
		octreeSvc: octreeSvc,
		transfers: make(map[string]*TransferSession),
	}
}

func (s *TileService) GetTile(req TileRequest) (*TileResponse, []byte, error) {
	tileKey := cache.TileKey(req.DatasetID, req.LOD, req.X, req.Y, req.Z)

	exists, _ := cache.Exists(tileKey)
	if exists {
		if cachedData, err := cache.GetBytes(tileKey); err == nil {
			_ = cache.IncrementTileHit(req.DatasetID, req.LOD, req.X, req.Y, req.Z)
			tile, points, err := s.decodeCachedTile(cachedData)
			if err == nil {
				return s.buildResponse(req, tile, points, true)
			}
		}
	}

	tile, err := s.octreeSvc.GetTile(req.DatasetID, req.LOD, req.X, req.Y, req.Z)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to get tile: %w", err)
	}

	_ = cache.IncrementTileHit(req.DatasetID, req.LOD, req.X, req.Y, req.Z)

	encodedPoints := s.encodePointsBinary(tile.Points)

	cachedData := s.encodeCachedTile(tile, encodedPoints)
	_ = cache.SetBytes(tileKey, cachedData, s.cfg.Redis.CacheTTL)

	return s.buildResponse(req, tile, encodedPoints, false)
}

func (s *TileService) GetTileRange(req TileRequest, w http.ResponseWriter, r *http.Request) error {
	tile, err := s.octreeSvc.GetTile(req.DatasetID, req.LOD, req.X, req.Y, req.Z)
	if err != nil {
		return err
	}

	encodedPoints := s.encodePointsBinary(tile.Points)

	rangeHeader := r.Header.Get("Range")
	if rangeHeader == "" {
		return s.serveFullTile(w, r, req, tile, encodedPoints)
	}

	return s.serveRangeTile(w, r, req, tile, encodedPoints, rangeHeader)
}

func (s *TileService) serveFullTile(w http.ResponseWriter, r *http.Request, req TileRequest, tile *octree.Tile, data []byte) error {
	acceptEncoding := r.Header.Get("Accept-Encoding")
	useGzip := strings.Contains(acceptEncoding, "gzip")

	var finalData []byte
	var contentEncoding string

	if useGzip && len(data) > 1024 {
		var buf bytes.Buffer
		gz := gzip.NewWriter(&buf)
		if _, err := gz.Write(data); err != nil {
			return err
		}
		if err := gz.Close(); err != nil {
			return err
		}
		finalData = buf.Bytes()
		contentEncoding = "gzip"
	} else {
		finalData = data
	}

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(len(finalData)))
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("X-Tile-Point-Count", strconv.Itoa(tile.PointCount))
	w.Header().Set("X-Tile-LOD", strconv.Itoa(tile.LOD))

	if contentEncoding != "" {
		w.Header().Set("Content-Encoding", contentEncoding)
	}

	w.WriteHeader(http.StatusOK)
	_, err := w.Write(finalData)
	return err
}

func (s *TileService) serveRangeTile(w http.ResponseWriter, r *http.Request, req TileRequest, tile *octree.Tile, data []byte, rangeHeader string) error {
	parts := strings.SplitN(rangeHeader, "=", 2)
	if len(parts) != 2 || parts[0] != "bytes" {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", len(data)))
		return fmt.Errorf("invalid range header")
	}

	rangeParts := strings.Split(parts[1], ",")
	if len(rangeParts) > 1 {
		return fmt.Errorf("multiple ranges not supported")
	}

	rangeSpec := strings.SplitN(rangeParts[0], "-", 2)
	if len(rangeSpec) != 2 {
		return fmt.Errorf("invalid range spec")
	}

	var start, end int64
	var err error

	if rangeSpec[0] == "" {
		suffixLen, err := strconv.ParseInt(rangeSpec[1], 10, 64)
		if err != nil {
			return err
		}
		start = int64(len(data)) - suffixLen
		end = int64(len(data)) - 1
	} else {
		start, err = strconv.ParseInt(rangeSpec[0], 10, 64)
		if err != nil {
			return err
		}
		if rangeSpec[1] == "" {
			end = int64(len(data)) - 1
		} else {
			end, err = strconv.ParseInt(rangeSpec[1], 10, 64)
			if err != nil {
				return err
			}
		}
	}

	if start < 0 || end >= int64(len(data)) || start > end {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", len(data)))
		w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
		return fmt.Errorf("range not satisfiable")
	}

	chunkSize := end - start + 1

	acceptEncoding := r.Header.Get("Accept-Encoding")
	useGzip := strings.Contains(acceptEncoding, "gzip")

	var finalData []byte
	var contentEncoding string

	chunk := data[start : end+1]

	if useGzip && chunkSize > 1024 {
		var buf bytes.Buffer
		gz := gzip.NewWriter(&buf)
		if _, err := gz.Write(chunk); err != nil {
			return err
		}
		if err := gz.Close(); err != nil {
			return err
		}
		finalData = buf.Bytes()
		contentEncoding = "gzip"
	} else {
		finalData = chunk
	}

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(len(finalData)))
	w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, len(data)))
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("X-Tile-Point-Count", strconv.Itoa(tile.PointCount))

	if contentEncoding != "" {
		w.Header().Set("Content-Encoding", contentEncoding)
	}

	w.WriteHeader(http.StatusPartialContent)
	_, err = w.Write(finalData)
	return err
}

func (s *TileService) encodePointsBinary(points []parser.Point) []byte {
	buf := make([]byte, 0, len(points)*16)

	for _, p := range points {
		x := float32(p.X)
		y := float32(p.Y)
		z := float32(p.Z)

		xBytes := make([]byte, 4)
		yBytes := make([]byte, 4)
		zBytes := make([]byte, 4)
		binary.LittleEndian.PutUint32(xBytes, uint32(math.Float32bits(x)))
		binary.LittleEndian.PutUint32(yBytes, uint32(math.Float32bits(y)))
		binary.LittleEndian.PutUint32(zBytes, uint32(math.Float32bits(z)))

		buf = append(buf, xBytes...)
		buf = append(buf, yBytes...)
		buf = append(buf, zBytes...)

		buf = append(buf, p.R, p.G, p.B, 255)
	}

	return buf
}

func (s *TileService) encodePointsJSON(points []parser.Point) []byte {
	type PointJSON struct {
		X float32 `json:"x"`
		Y float32 `json:"y"`
		Z float32 `json:"z"`
		R uint8   `json:"r"`
		G uint8   `json:"g"`
		B uint8   `json:"b"`
	}

	jsonPoints := make([]PointJSON, 0, len(points))
	for _, p := range points {
		jsonPoints = append(jsonPoints, PointJSON{
			X: float32(p.X),
			Y: float32(p.Y),
			Z: float32(p.Z),
			R: p.R,
			G: p.G,
			B: p.B,
		})
	}

	data, _ := json.Marshal(jsonPoints)
	return data
}

func (s *TileService) encodeCachedTile(tile *octree.Tile, pointsData []byte) []byte {
	header := BinaryTileFormat{
		Magic:      [4]byte{'P', 'T', 'L', 'E'},
		Version:    1,
		LOD:        uint16(tile.LOD),
		X:          uint32(tile.X),
		Y:          uint32(tile.Y),
		Z:          uint32(tile.Z),
		PointCount: uint32(tile.PointCount),
		MinX:       tile.Bounds.Min.X,
		MinY:       tile.Bounds.Min.Y,
		MinZ:       tile.Bounds.Min.Z,
		MaxX:       tile.Bounds.Max.X,
		MaxY:       tile.Bounds.Max.Y,
		MaxZ:       tile.Bounds.Max.Z,
		CenterX:    tile.Center.X,
		CenterY:    tile.Center.Y,
		CenterZ:    tile.Center.Z,
	}

	buf := new(bytes.Buffer)
	binary.Write(buf, binary.LittleEndian, header)
	buf.Write(pointsData)

	return buf.Bytes()
}

func (s *TileService) decodeCachedTile(data []byte) (*octree.Tile, []byte, error) {
	if len(data) < binary.Size(BinaryTileFormat{}) {
		return nil, nil, fmt.Errorf("invalid cached tile data")
	}

	var header BinaryTileFormat
	buf := bytes.NewReader(data)
	if err := binary.Read(buf, binary.LittleEndian, &header); err != nil {
		return nil, nil, err
	}

	if string(header.Magic[:]) != "PTLE" {
		return nil, nil, fmt.Errorf("invalid tile magic")
	}

	pointsData := data[binary.Size(header):]

	tile := &octree.Tile{
		LOD:        int(header.LOD),
		X:          int64(header.X),
		Y:          int64(header.Y),
		Z:          int64(header.Z),
		PointCount: int(header.PointCount),
		Bounds: math3d.AABB{
			Min: math3d.Vec3{X: header.MinX, Y: header.MinY, Z: header.MinZ},
			Max: math3d.Vec3{X: header.MaxX, Y: header.MaxY, Z: header.MaxZ},
		},
		Center: math3d.Vec3{X: header.CenterX, Y: header.CenterY, Z: header.CenterZ},
	}

	return tile, pointsData, nil
}

func (s *TileService) buildResponse(req TileRequest, tile *octree.Tile, points []byte, fromCache bool) (*TileResponse, []byte, error) {
	resp := &TileResponse{
		Key:        cache.TileKey(req.DatasetID, req.LOD, req.X, req.Y, req.Z),
		LOD:        tile.LOD,
		X:          tile.X,
		Y:          tile.Y,
		Z:          tile.Z,
		PointCount: tile.PointCount,
		Bounds: [6]float64{
			tile.Bounds.Min.X, tile.Bounds.Min.Y, tile.Bounds.Min.Z,
			tile.Bounds.Max.X, tile.Bounds.Max.Y, tile.Bounds.Max.Z,
		},
		Center:     [3]float64{tile.Center.X, tile.Center.Y, tile.Center.Z},
		Compressed: false,
		Size:       len(points),
		ParentKey:  tile.ParentKey,
		ChildKeys:  tile.ChildKeys,
	}

	return resp, points, nil
}

func (s *TileService) GetTileIndex(datasetID string) (*octree.DatasetTileIndex, error) {
	return s.octreeSvc.GetTileIndex(datasetID)
}

func (s *TileService) GetHotTiles(datasetID string, count int) ([]string, error) {
	return cache.GetHotTiles(datasetID, count)
}

func (s *TileService) PreloadHotTiles(datasetID string, count int) error {
	hotTiles, err := s.GetHotTiles(datasetID, count)
	if err != nil {
		return err
	}

	for _, tileKey := range hotTiles {
		parts := strings.Split(tileKey, ":")
		if len(parts) < 5 {
			continue
		}

		lod, _ := strconv.Atoi(parts[1])
		x, _ := strconv.ParseInt(parts[2], 10, 64)
		y, _ := strconv.ParseInt(parts[3], 10, 64)
		z, _ := strconv.ParseInt(parts[4], 10, 64)

		cacheKey := cache.TileKey(datasetID, lod, x, y, z)
		exists, _ := cache.Exists(cacheKey)
		if !exists {
			tile, err := s.octreeSvc.GetTile(datasetID, lod, x, y, z)
			if err == nil {
				encodedPoints := s.encodePointsBinary(tile.Points)
				cachedData := s.encodeCachedTile(tile, encodedPoints)
				_ = cache.SetBytes(cacheKey, cachedData, s.cfg.Redis.CacheTTL)
			}
		}
	}

	return nil
}

func (s *TileService) GetTileFile(datasetID string, lod int, x, y, z int64) (string, error) {
	filename := fmt.Sprintf("%s_tile_%d_%d_%d_%d.gob", datasetID, lod, x, y, z)
	filepath := filepath.Join(s.cfg.Storage.TileDir, datasetID, filename)

	if _, err := os.Stat(filepath); err != nil {
		return "", fmt.Errorf("tile file not found: %w", err)
	}

	return filepath, nil
}

func (s *TileService) StreamTile(w io.Writer, datasetID string, lod int, x, y, z int64, compress bool) error {
	tile, err := s.octreeSvc.GetTile(datasetID, lod, x, y, z)
	if err != nil {
		return err
	}

	data := s.encodePointsBinary(tile.Points)

	if compress {
		gz := gzip.NewWriter(w)
		defer gz.Close()
		_, err = gz.Write(data)
		return err
	}

	_, err = w.Write(data)
	return err
}

type IncrementalUpdateResponse struct {
	DatasetID       string `json:"dataset_id"`
	InsertedPoints  int    `json:"inserted_points"`
	UpdatedNodes    int    `json:"updated_nodes"`
	UpdatedTiles    int    `json:"updated_tiles"`
	NewTotalPoints  uint64 `json:"new_total_points"`
	RebuildRequired bool   `json:"rebuild_required"`
}

func (s *TileService) IncrementalUpdate(datasetID string, points []parser.Point, filePath string) (*IncrementalUpdateResponse, error) {
	req := &octree.IncrementalUpdateRequest{
		DatasetID:   datasetID,
		Points:      points,
		SourceFile:  filePath,
	}

	var resp *octree.IncrementalUpdateResponse
	var err error

	if filePath != "" {
		resp, err = s.octreeSvc.IncrementalUpdateFromFile(req)
	} else if len(points) > 0 {
		resp, err = s.octreeSvc.IncrementalUpdateFromPoints(datasetID, points)
	} else {
		return nil, fmt.Errorf("either points or source_file must be provided")
	}

	if err != nil {
		return nil, err
	}

	if resp.RebuildRequired {
		_ = cache.DeleteByPrefix(fmt.Sprintf("tile:%s:", datasetID))
		_ = cache.DeleteByPrefix(fmt.Sprintf("hot_tiles:%s", datasetID))
	} else {
		for _, tile := range resp.AffectedTiles {
			tileKey := cache.TileKey(datasetID, tile.LOD, tile.X, tile.Y, tile.Z)
			_ = cache.Delete(tileKey)
		}
	}

	result := &IncrementalUpdateResponse{
		DatasetID:       resp.DatasetID,
		InsertedPoints:  resp.InsertedPoints,
		UpdatedNodes:    resp.UpdatedNodes,
		UpdatedTiles:    resp.UpdatedTiles,
		NewTotalPoints:  resp.NewTotalPoints,
		RebuildRequired: resp.RebuildRequired,
	}

	return result, nil
}
