package handler

import (
	"net/http"
	"sync"

	"gamestats/event-access/config"
	"gamestats/event-access/model"
	"gamestats/event-access/storage"

	"github.com/gin-gonic/gin"
	"github.com/go-playground/validator/v10"
	"go.uber.org/zap"
)

type EventHandler struct {
	influxDB   *storage.InfluxDBClient
	mysql      *storage.MySQLClient
	logger     *zap.Logger
	config     *config.Config
	validator  *validator.Validate
	eventQueue chan model.GameEvent
	wg         sync.WaitGroup
}

func NewEventHandler(
	influxDB *storage.InfluxDBClient,
	mysql *storage.MySQLClient,
	logger *zap.Logger,
	config *config.Config,
) *EventHandler {
	handler := &EventHandler{
		influxDB:   influxDB,
		mysql:      mysql,
		logger:     logger,
		config:     config,
		validator:  validator.New(),
		eventQueue: make(chan model.GameEvent, 10000),
	}

	handler.startEventProcessor()

	return handler
}

func (h *EventHandler) startEventProcessor() {
	for i := 0; i < 10; i++ {
		h.wg.Add(1)
		go func() {
			defer h.wg.Done()
			for event := range h.eventQueue {
				h.processEvent(event)
			}
		}()
	}
}

func (h *EventHandler) processEvent(event model.GameEvent) {
	if err := h.influxDB.WriteEvent(h.config.Context(), &event); err != nil {
		h.logger.Error("Failed to write event to InfluxDB", 
			zap.String("event_id", event.EventID), 
			zap.Error(err))
	}

	if err := h.mysql.SaveEvent(h.config.Context(), &event); err != nil {
		h.logger.Error("Failed to save event to MySQL", 
			zap.String("event_id", event.EventID), 
			zap.Error(err))
	}
}

func (h *EventHandler) ReportEvents(c *gin.Context) {
	var req model.EventReportRequest

	if err := c.ShouldBindJSON(&req); err != nil {
		h.logger.Warn("Invalid request body", zap.Error(err))
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, "Invalid request body"))
		return
	}

	if err := h.validator.Struct(req); err != nil {
		h.logger.Warn("Validation failed", zap.Error(err))
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	for _, event := range req.Events {
		select {
		case h.eventQueue <- event:
		default:
			h.logger.Warn("Event queue is full, dropping event", 
				zap.String("event_id", event.EventID))
		}
	}

	response := model.EventReportResponse{
		Code: 200,
		Data: model.EventReportData{
			ReceivedCount: len(req.Events),
		},
	}

	h.logger.Info("Events reported", zap.Int("count", len(req.Events)))
	c.JSON(http.StatusOK, response)
}

func (h *EventHandler) GetEvent(c *gin.Context) {
	eventID := c.Param("event_id")

	event, err := h.mysql.GetEvent(h.config.Context(), eventID)
	if err != nil {
		h.logger.Warn("Event not found", zap.String("event_id", eventID), zap.Error(err))
		c.JSON(http.StatusNotFound, model.NewErrorResponse(404, "Event not found"))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(event))
}

func (h *EventHandler) Heartbeat(c *gin.Context) {
	var payload model.HeartbeatPayload

	if err := c.ShouldBindJSON(&payload); err != nil {
		h.logger.Warn("Invalid heartbeat payload", zap.Error(err))
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, "Invalid payload"))
		return
	}

	if err := h.validator.Struct(payload); err != nil {
		h.logger.Warn("Heartbeat validation failed", zap.Error(err))
		c.JSON(http.StatusBadRequest, model.NewErrorResponse(400, err.Error()))
		return
	}

	h.logger.Debug("Heartbeat received", 
		zap.String("player_id", payload.PlayerID),
		zap.String("game_id", payload.GameID),
		zap.String("server_id", payload.ServerID))

	c.JSON(http.StatusOK, model.NewSuccessResponse(map[string]string{
		"status": "ok",
	}))
}

func (h *EventHandler) Shutdown() {
	close(h.eventQueue)
	h.wg.Wait()
	h.logger.Info("Event handler shutdown complete")
}
