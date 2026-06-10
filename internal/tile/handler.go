package tile

import (
	"net/http"
	"pointcloud-platform/internal/parser"
	"pointcloud-platform/pkg/math3d"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
)

type Handler struct {
	service *TileService
}

func NewHandler(service *TileService) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	tile := r.Group("/tiles")
	{
		tile.GET("/:datasetId/:lod/:x/:y/:z", h.GetTile)
		tile.GET("/:datasetId/:lod/:x/:y/:z/stream", h.StreamTile)
		tile.GET("/:datasetId/:lod/:x/:y/:z/range", h.GetTileRange)
		tile.GET("/:datasetId/index", h.GetTileIndex)
		tile.GET("/:datasetId/hot", h.GetHotTiles)
		tile.POST("/:datasetId/preload", h.PreloadHotTiles)
		tile.POST("/:datasetId/query", h.QueryTiles)
		tile.POST("/:datasetId/incremental", h.IncrementalUpdate)
	}
}

func (h *Handler) parseTileRequest(c *gin.Context) (TileRequest, bool) {
	datasetID := c.Param("datasetId")
	lod, err := strconv.Atoi(c.Param("lod"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid LOD"})
		return TileRequest{}, false
	}

	x, err := strconv.ParseInt(c.Param("x"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid x coordinate"})
		return TileRequest{}, false
	}

	y, err := strconv.ParseInt(c.Param("y"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid y coordinate"})
		return TileRequest{}, false
	}

	z, err := strconv.ParseInt(c.Param("z"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid z coordinate"})
		return TileRequest{}, false
	}

	return TileRequest{
		DatasetID: datasetID,
		LOD:       lod,
		X:         x,
		Y:         y,
		Z:         z,
	}, true
}

func (h *Handler) GetTile(c *gin.Context) {
	req, ok := h.parseTileRequest(c)
	if !ok {
		return
	}

	format := c.DefaultQuery("format", "binary")

	if format == "range" {
		if err := h.service.GetTileRange(req, c.Writer, c.Request); err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		}
		return
	}

	resp, data, err := h.service.GetTile(req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	if format == "json" {
		c.JSON(http.StatusOK, gin.H{
			"metadata": resp,
			"data":     data,
		})
		return
	}

	c.Header("Content-Type", "application/octet-stream")
	c.Header("X-Tile-Lod", strconv.Itoa(resp.LOD))
	c.Header("X-Tile-Point-Count", strconv.Itoa(resp.PointCount))
	c.Header("X-Tile-Key", resp.Key)
	c.Data(http.StatusOK, "application/octet-stream", data)
}

func (h *Handler) StreamTile(c *gin.Context) {
	req, ok := h.parseTileRequest(c)
	if !ok {
		return
	}

	compress := c.DefaultQuery("compress", "true") == "true"

	c.Header("Content-Type", "application/octet-stream")
	c.Header("Transfer-Encoding", "chunked")
	c.Header("X-Tile-Dataset", req.DatasetID)

	if compress {
		c.Header("Content-Encoding", "gzip")
	}

	c.Writer.WriteHeader(http.StatusOK)

	if err := h.service.StreamTile(c.Writer, req.DatasetID, req.LOD, req.X, req.Y, req.Z, compress); err != nil {
		return
	}
}

func (h *Handler) GetTileRange(c *gin.Context) {
	req, ok := h.parseTileRequest(c)
	if !ok {
		return
	}

	if err := h.service.GetTileRange(req, c.Writer, c.Request); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
	}
}

func (h *Handler) GetTileIndex(c *gin.Context) {
	datasetID := c.Param("datasetId")

	index, err := h.service.GetTileIndex(datasetID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, index)
}

func (h *Handler) GetHotTiles(c *gin.Context) {
	datasetID := c.Param("datasetId")
	count, _ := strconv.Atoi(c.DefaultQuery("count", "50"))

	tiles, err := h.service.GetHotTiles(datasetID, count)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"dataset_id": datasetID,
		"count":      len(tiles),
		"tiles":      tiles,
	})
}

func (h *Handler) PreloadHotTiles(c *gin.Context) {
	datasetID := c.Param("datasetId")

	var req struct {
		Count int `json:"count"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		req.Count = 100
	}

	if err := h.service.PreloadHotTiles(datasetID, req.Count); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"status":  "preloaded",
		"dataset": datasetID,
		"count":   req.Count,
	})
}

func (h *Handler) QueryTiles(c *gin.Context) {
	datasetID := c.Param("datasetId")

	var req struct {
		Frustum   [24]float64 `json:"frustum"`
		MaxTiles  int         `json:"max_tiles"`
		LODBias   float64     `json:"lod_bias"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.MaxTiles == 0 {
		req.MaxTiles = 100
	}
	if req.LODBias == 0 {
		req.LODBias = 1.0
	}

	var frustum math3d.Frustum
	for i := 0; i < 6; i++ {
		frustum.Planes[i] = math3d.Vec4{
			X: req.Frustum[i*4],
			Y: req.Frustum[i*4+1],
			Z: req.Frustum[i*4+2],
			W: req.Frustum[i*4+3],
		}
	}

	tiles, err := h.service.octreeSvc.QueryTiles(datasetID, &frustum, req.MaxTiles)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"dataset_id": datasetID,
		"count":      len(tiles),
		"tiles":      tiles,
	})
}

func ParseRangeHeader(header string, fileSize int64) (start, end int64, err error) {
	if header == "" {
		return 0, fileSize - 1, nil
	}

	parts := strings.SplitN(header, "=", 2)
	if len(parts) != 2 || parts[0] != "bytes" {
		return 0, 0, nil
	}

	rangeParts := strings.Split(parts[1], ",")
	if len(rangeParts) > 1 {
		return 0, 0, nil
	}

	rangeSpec := strings.SplitN(rangeParts[0], "-", 2)
	if len(rangeSpec) != 2 {
		return 0, 0, nil
	}

	if rangeSpec[0] == "" {
		suffixLen, err := strconv.ParseInt(rangeSpec[1], 10, 64)
		if err != nil {
			return 0, 0, err
		}
		return fileSize - suffixLen, fileSize - 1, nil
	}

	start, err = strconv.ParseInt(rangeSpec[0], 10, 64)
	if err != nil {
		return 0, 0, err
	}

	if rangeSpec[1] == "" {
		return start, fileSize - 1, nil
	}

	end, err = strconv.ParseInt(rangeSpec[1], 10, 64)
	if err != nil {
		return 0, 0, err
	}

	return start, end, nil
}

func (h *Handler) IncrementalUpdate(c *gin.Context) {
	datasetID := c.Param("datasetId")

	var req struct {
		SourceFile string `json:"source_file"`
		Points     []struct {
			X float64 `json:"x"`
			Y float64 `json:"y"`
			Z float64 `json:"z"`
			R uint8   `json:"r"`
			G uint8   `json:"g"`
			B uint8   `json:"b"`
		} `json:"points"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var points []parser.Point
	for _, p := range req.Points {
		points = append(points, parser.Point{
			X: p.X,
			Y: p.Y,
			Z: p.Z,
			R: p.R,
			G: p.G,
			B: p.B,
		})
	}

	result, err := h.service.IncrementalUpdate(datasetID, points, req.SourceFile)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, result)
}
