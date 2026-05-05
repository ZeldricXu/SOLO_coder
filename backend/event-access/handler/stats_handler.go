package handler

import (
	"net/http"
	"strconv"
	"time"

	"gamestats/event-access/model"
	"gamestats/event-access/storage"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

type StatsHandler struct {
	influxDB *storage.InfluxDBClient
	mysql    *storage.MySQLClient
	logger   *zap.Logger
}

func NewStatsHandler(
	influxDB *storage.InfluxDBClient,
	mysql *storage.MySQLClient,
	logger *zap.Logger,
) *StatsHandler {
	return &StatsHandler{
		influxDB: influxDB,
		mysql:    mysql,
		logger:   logger,
	}
}

func (h *StatsHandler) GetOnlineStats(c *gin.Context) {
	gameID := c.DefaultQuery("game_id", "game_mmorpg_01")
	
	durationStr := c.DefaultQuery("duration", "1h")
	duration, err := time.ParseDuration(durationStr)
	if err != nil {
		duration = time.Hour
	}

	end := time.Now()
	start := end.Add(-duration)

	stats, err := h.influxDB.QueryOnlineStats(c.Request.Context(), gameID, start, end)
	if err != nil {
		h.logger.Error("Failed to query online stats", 
			zap.String("game_id", gameID), 
			zap.Error(err))
		
		defaultStats := model.OnlineStats{
			StatID:             "online_" + time.Now().Format("20060102_1504"),
			GameID:             gameID,
			OnlineCount:        0,
			ServerDistribution: make(map[string]int),
			SampleTime:         time.Now(),
			PeakToday:          0,
		}
		
		c.JSON(http.StatusOK, model.NewSuccessResponse(defaultStats))
		return
	}

	if len(stats) == 0 {
		defaultStats := model.OnlineStats{
			StatID:             "online_" + time.Now().Format("20060102_1504"),
			GameID:             gameID,
			OnlineCount:        0,
			ServerDistribution: make(map[string]int),
			SampleTime:         time.Now(),
			PeakToday:          0,
		}
		
		c.JSON(http.StatusOK, model.NewSuccessResponse(defaultStats))
		return
	}

	latestStats := stats[len(stats)-1]
	c.JSON(http.StatusOK, model.NewSuccessResponse(latestStats))
}

func (h *StatsHandler) GetTrend(c *gin.Context) {
	gameID := c.DefaultQuery("game_id", "game_mmorpg_01")
	
	durationStr := c.DefaultQuery("duration", "1h")
	duration, err := time.ParseDuration(durationStr)
	if err != nil {
		duration = time.Hour
	}

	trend, err := h.influxDB.QueryTrend(c.Request.Context(), gameID, duration)
	if err != nil {
		h.logger.Error("Failed to query trend", 
			zap.String("game_id", gameID), 
			zap.Error(err))
		
		trendResponse := model.TrendResponse{
			GameID: gameID,
			Trend:  []model.TrendPoint{},
		}
		
		c.JSON(http.StatusOK, model.NewSuccessResponse(trendResponse))
		return
	}

	trendResponse := model.TrendResponse{
		GameID: gameID,
		Trend:  trend,
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(trendResponse))
}

func (h *StatsHandler) GetPlayerProfile(c *gin.Context) {
	playerID := c.Param("player_id")

	profile, err := h.mysql.GetPlayerProfile(c.Request.Context(), playerID)
	if err != nil {
		h.logger.Warn("Player profile not found", 
			zap.String("player_id", playerID), 
			zap.Error(err))
		c.JSON(http.StatusNotFound, model.NewErrorResponse(404, "Player profile not found"))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(profile))
}

func (h *StatsHandler) GetPlayerEvents(c *gin.Context) {
	playerID := c.Param("player_id")
	
	limitStr := c.DefaultQuery("limit", "100")
	limit, err := strconv.Atoi(limitStr)
	if err != nil || limit < 1 || limit > 1000 {
		limit = 100
	}

	events, err := h.mysql.GetPlayerEvents(c.Request.Context(), playerID, limit)
	if err != nil {
		h.logger.Error("Failed to get player events", 
			zap.String("player_id", playerID), 
			zap.Error(err))
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, "Failed to get player events"))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(events))
}

func (h *StatsHandler) GetAllProfiles(c *gin.Context) {
	profiles, err := h.mysql.GetAllProfiles(c.Request.Context())
	if err != nil {
		h.logger.Error("Failed to get all profiles", zap.Error(err))
		c.JSON(http.StatusInternalServerError, model.NewErrorResponse(500, "Failed to get profiles"))
		return
	}

	c.JSON(http.StatusOK, model.NewSuccessResponse(profiles))
}
