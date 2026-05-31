package handler

import (
	"time"

	"github.com/edgevision/edgevision/internal/domain/aggregation"
	"github.com/edgevision/edgevision/internal/domain/offline"
	"github.com/edgevision/edgevision/pkg/errors"
	"github.com/gin-gonic/gin"
)

type OfflineCacheHandler struct {
	cacheService offline.OfflineService
}

func NewOfflineCacheHandler(cacheService offline.OfflineService) *OfflineCacheHandler {
	return &OfflineCacheHandler{
		cacheService: cacheService,
	}
}

func (h *OfflineCacheHandler) CacheData(c *gin.Context) {
	var req offline.CacheDataRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	record, err := h.cacheService.CacheData(c.Request.Context(), &req)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, record)
}

func (h *OfflineCacheHandler) GetPendingRecords(c *gin.Context) {
	deviceID := c.Query("device_id")
	limit, _ := 100, 100

	records, err := h.cacheService.GetPendingRecords(c.Request.Context(), deviceID, limit)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, records)
}

func (h *OfflineCacheHandler) SyncData(c *gin.Context) {
	deviceID := c.Param("device_id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	session, err := h.cacheService.SyncData(c.Request.Context(), deviceID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, session)
}

func (h *OfflineCacheHandler) NetworkRestored(c *gin.Context) {
	deviceID := c.Param("device_id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	h.cacheService.NetworkRestored(c.Request.Context(), deviceID)
	Success(c, gin.H{"message": "Network restored, sync started"})
}

func (h *OfflineCacheHandler) ListRecords(c *gin.Context) {
	deviceID := c.Query("device_id")
	status := c.Query("status")
	page, pageSize := GetPagination(c)

	records, total, err := h.cacheService.ListRecords(c.Request.Context(), deviceID, status, page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, records, total, page, pageSize)
}

func (h *OfflineCacheHandler) GetSyncSessions(c *gin.Context) {
	deviceID := c.Query("device_id")
	page, pageSize := GetPagination(c)

	sessions, total, err := h.cacheService.GetSyncSessions(c.Request.Context(), deviceID, page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, sessions, total, page, pageSize)
}

func (h *OfflineCacheHandler) GetStats(c *gin.Context) {
	deviceID := c.Query("device_id")

	stats, err := h.cacheService.GetStats(c.Request.Context(), deviceID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, stats)
}

type DataAggregationHandler struct {
	aggService aggregation.DataAggregationService
}

func NewDataAggregationHandler(aggService aggregation.DataAggregationService) *DataAggregationHandler {
	return &DataAggregationHandler{
		aggService: aggService,
	}
}

func (h *DataAggregationHandler) CreateStream(c *gin.Context) {
	var req aggregation.CreateDataStreamRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	stream, err := h.aggService.CreateStream(c.Request.Context(), &req)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, stream)
}

func (h *DataAggregationHandler) GetStream(c *gin.Context) {
	streamID := c.Param("id")
	if streamID == "" {
		Error(c, errors.BadRequest("Stream ID is required"))
		return
	}

	stream, err := h.aggService.GetStream(c.Request.Context(), streamID)
	if err != nil {
		Error(c, errors.NotFound("Stream not found"))
		return
	}

	Success(c, stream)
}

func (h *DataAggregationHandler) ListStreams(c *gin.Context) {
	deviceID := c.Query("device_id")
	page, pageSize := GetPagination(c)

	streams, total, err := h.aggService.ListStreams(c.Request.Context(), deviceID, page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, streams, total, page, pageSize)
}

func (h *DataAggregationHandler) IngestDataPoint(c *gin.Context) {
	var req aggregation.IngestDataPointRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	if err := h.aggService.IngestDataPoint(c.Request.Context(), &req); err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, gin.H{"message": "Data point ingested successfully"})
}

func (h *DataAggregationHandler) AggregateData(c *gin.Context) {
	streamID := c.Param("id")
	if streamID == "" {
		Error(c, errors.BadRequest("Stream ID is required"))
		return
	}

	data, err := h.aggService.AggregateData(c.Request.Context(), streamID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, data)
}

func (h *DataAggregationHandler) GetAggregatedData(c *gin.Context) {
	streamID := c.Param("id")
	if streamID == "" {
		Error(c, errors.BadRequest("Stream ID is required"))
		return
	}

	page, pageSize := GetPagination(c)

	data, total, err := h.aggService.GetAggregatedData(c.Request.Context(), streamID, time.Time{}, time.Time{}, page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, data, total, page, pageSize)
}

func (h *DataAggregationHandler) GetLatestAggregatedData(c *gin.Context) {
	streamID := c.Param("id")
	if streamID == "" {
		Error(c, errors.BadRequest("Stream ID is required"))
		return
	}

	metric := c.Query("metric")

	data, err := h.aggService.GetLatestAggregatedData(c.Request.Context(), streamID, metric)
	if err != nil {
		Error(c, errors.NotFound("No aggregated data found"))
		return
	}

	Success(c, data)
}

func (h *DataAggregationHandler) GetStats(c *gin.Context) {
	streamID := c.Param("id")
	if streamID == "" {
		Error(c, errors.BadRequest("Stream ID is required"))
		return
	}

	stats, err := h.aggService.GetStreamStats(c.Request.Context(), streamID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, stats)
}
