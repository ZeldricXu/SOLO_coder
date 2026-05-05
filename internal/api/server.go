package api

import (
	"fmt"
	"strconv"
	"time"

	"GameLeaderboard/internal/config"
	"GameLeaderboard/internal/models"
	"GameLeaderboard/internal/push"
	"GameLeaderboard/internal/ranking"
	"GameLeaderboard/internal/score"
	"GameLeaderboard/internal/season"
	"GameLeaderboard/internal/storage"
	"net/http"

	"github.com/gin-gonic/gin"
)

type APIServer struct {
	config        *config.Config
	router        *gin.Engine
	scoreService  *score.ScoreService
	rankService   *ranking.RankingService
	seasonService *season.SeasonService
	pushService   *push.PushService
}

type APIResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
}

type ScoreReportRequest struct {
	PlayerID    string `json:"player_id" binding:"required"`
	GameID      string `json:"game_id" binding:"required"`
	ScoreChange int64  `json:"score_change" binding:"required,gt=0"`
	ScoreType   string `json:"score_type" binding:"required"`
}

type LeaderboardQueryRequest struct {
	GameID   string                 `form:"game_id" binding:"required"`
	SeasonID string                 `form:"season_id"`
	Type     models.LeaderboardType `form:"type"`
	PlayerID string                 `form:"player_id"`
	Limit    int64                  `form:"limit" binding:"min=1,max=100"`
	Offset   int64                  `form:"offset" binding:"min=0"`
}

type CreateSeasonRequest struct {
	GameID       string               `json:"game_id" binding:"required"`
	SeasonName   string               `json:"season_name" binding:"required"`
	StartTime    string               `json:"start_time" binding:"required"`
	EndTime      string               `json:"end_time" binding:"required"`
	RewardConfig *models.RewardConfig `json:"reward_config"`
}

type SwitchSeasonRequest struct {
	GameID      string `json:"game_id" binding:"required"`
	NewSeasonID string `json:"new_season_id" binding:"required"`
}

func NewAPIServer(
	cfg *config.Config,
	mysqlStore *storage.MySQLStore,
	redisStore *storage.RedisStore,
) *APIServer {
	if cfg.Server.Mode == "release" {
		gin.SetMode(gin.ReleaseMode)
	}

	pushService := push.NewPushService(&cfg.WebSocket)
	rankService := ranking.NewRankingServiceWithConfig(mysqlStore, redisStore, pushService, &cfg.Ranking)
	scoreService := score.NewScoreService(mysqlStore, redisStore, rankService)
	seasonService := season.NewSeasonServiceWithConfig(mysqlStore, redisStore, pushService, &cfg.Season)

	server := &APIServer{
		config:        cfg,
		scoreService:  scoreService,
		rankService:   rankService,
		seasonService: seasonService,
		pushService:   pushService,
	}

	server.setupRouter()

	return server
}

func (s *APIServer) setupRouter() {
	r := gin.New()

	r.Use(gin.Logger())
	r.Use(gin.Recovery())
	r.Use(CORSMiddleware())

	api := r.Group("/api/v1")
	{
		score := api.Group("/score")
		{
			score.POST("/report", s.handleScoreReport)
		}

		leaderboard := api.Group("/leaderboard")
		{
			leaderboard.GET("/query", s.handleLeaderboardQuery)
			leaderboard.GET("/historical", s.handleHistoricalLeaderboard)
		}

		season := api.Group("/season")
		{
			season.POST("/create", s.handleCreateSeason)
			season.POST("/switch", s.handleSwitchSeason)
			season.GET("/active", s.handleGetActiveSeason)
			season.GET("/list", s.handleListSeasons)
			season.GET("/:season_id", s.handleGetSeason)
			season.GET("/scheduler/status", s.handleGetSchedulerStatus)
			season.POST("/scheduler/start", s.handleStartScheduler)
			season.POST("/scheduler/stop", s.handleStopScheduler)
			season.GET("/archive/:season_id", s.handleGetSeasonArchive)
		}

		admin := api.Group("/admin")
		{
			admin.GET("/config", s.handleGetConfig)
			admin.POST("/snapshot", s.handleCreateSnapshot)
		}
	}

	r.GET("/ws", s.handleWebSocket)
	r.GET("/health", s.handleHealth)

	s.router = r
}

func (s *APIServer) handleScoreReport(c *gin.Context) {
	var req ScoreReportRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "Invalid request: " + err.Error(),
		})
		return
	}

	result, err := s.scoreService.ReportScore(&score.ScoreReportRequest{
		PlayerID:    req.PlayerID,
		GameID:      req.GameID,
		ScoreChange: req.ScoreChange,
		ScoreType:   req.ScoreType,
	})

	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to report score: " + err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, APIResponse{
		Code: 200,
		Data: result,
	})
}

func (s *APIServer) handleLeaderboardQuery(c *gin.Context) {
	var req LeaderboardQueryRequest
	if err := c.ShouldBindQuery(&req); err != nil {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "Invalid request: " + err.Error(),
		})
		return
	}

	if req.Limit == 0 {
		req.Limit = 100
	}

	result, err := s.rankService.QueryLeaderboard(&ranking.LeaderboardQueryRequest{
		GameID:   req.GameID,
		SeasonID: req.SeasonID,
		Type:     req.Type,
		PlayerID: req.PlayerID,
		Limit:    req.Limit,
		Offset:   req.Offset,
	})

	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to query leaderboard: " + err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, APIResponse{
		Code: 200,
		Data: result,
	})
}

func (s *APIServer) handleHistoricalLeaderboard(c *gin.Context) {
	gameID := c.Query("game_id")
	seasonID := c.Query("season_id")
	lbType := c.Query("type")
	limit := c.Query("limit")

	if gameID == "" {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "game_id is required",
		})
		return
	}
	if seasonID == "" {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "season_id is required",
		})
		return
	}

	lbTypeEnum := models.LeaderboardType(lbType)
	if lbTypeEnum == "" {
		lbTypeEnum = models.LeaderboardTypeTotal
	}

	limitNum := int64(100)
	if limit != "" {
		if l, err := strconv.ParseInt(limit, 10, 64); err == nil && l > 0 && l <= 1000 {
			limitNum = l
		}
	}

	lb, err := s.seasonService.QueryHistoricalLeaderboard(gameID, seasonID, lbTypeEnum, limitNum)
	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to query historical leaderboard: " + err.Error(),
		})
		return
	}

	entries, err := lb.GetEntries()
	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to parse leaderboard entries: " + err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, APIResponse{
		Code: 200,
		Data: gin.H{
			"leaderboard_id": lb.LeaderboardID,
			"game_id":        lb.GameID,
			"season_id":      lb.SeasonID,
			"type":           lb.Type,
			"entries":        entries,
			"total_players":  lb.TotalPlayers,
			"updated_at":     lb.UpdatedAt,
		},
	})
}

func (s *APIServer) handleCreateSeason(c *gin.Context) {
	var req CreateSeasonRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "Invalid request: " + err.Error(),
		})
		return
	}

	startTime, err := parseTime(req.StartTime)
	if err != nil {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "Invalid start_time format: " + err.Error(),
		})
		return
	}

	endTime, err := parseTime(req.EndTime)
	if err != nil {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "Invalid end_time format: " + err.Error(),
		})
		return
	}

	result, err := s.seasonService.CreateSeason(&season.CreateSeasonRequest{
		GameID:       req.GameID,
		SeasonName:   req.SeasonName,
		StartTime:    startTime,
		EndTime:      endTime,
		RewardConfig: req.RewardConfig,
	})

	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to create season: " + err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, APIResponse{
		Code: 200,
		Data: result,
	})
}

func (s *APIServer) handleSwitchSeason(c *gin.Context) {
	var req SwitchSeasonRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "Invalid request: " + err.Error(),
		})
		return
	}

	err := s.seasonService.SwitchSeason(&season.SwitchSeasonRequest{
		GameID:      req.GameID,
		NewSeasonID: req.NewSeasonID,
	})

	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to switch season: " + err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, APIResponse{
		Code:    200,
		Message: "Season switched successfully",
	})
}

func (s *APIServer) handleGetActiveSeason(c *gin.Context) {
	gameID := c.Query("game_id")
	if gameID == "" {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "game_id is required",
		})
		return
	}

	result, err := s.seasonService.GetActiveSeason(gameID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to get active season: " + err.Error(),
		})
		return
	}

	if result == nil {
		c.JSON(http.StatusOK, APIResponse{
			Code:    200,
			Message: "No active season found",
		})
		return
	}

	c.JSON(http.StatusOK, APIResponse{
		Code: 200,
		Data: result,
	})
}

func (s *APIServer) handleListSeasons(c *gin.Context) {
	gameID := c.Query("game_id")
	if gameID == "" {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "game_id is required",
		})
		return
	}

	result, err := s.seasonService.GetAllSeasons(gameID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to list seasons: " + err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, APIResponse{
		Code: 200,
		Data: result,
	})
}

func (s *APIServer) handleGetSeason(c *gin.Context) {
	seasonID := c.Param("season_id")
	if seasonID == "" {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "season_id is required",
		})
		return
	}

	result, err := s.seasonService.GetSeasonByID(seasonID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to get season: " + err.Error(),
		})
		return
	}

	if result == nil {
		c.JSON(http.StatusNotFound, APIResponse{
			Code:    404,
			Message: "Season not found",
		})
		return
	}

	c.JSON(http.StatusOK, APIResponse{
		Code: 200,
		Data: result,
	})
}

func (s *APIServer) handleWebSocket(c *gin.Context) {
	s.pushService.HandleWebSocket(c.Writer, c.Request)
}

func (s *APIServer) handleHealth(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status":    "healthy",
		"timestamp": time.Now().Format(time.RFC3339),
	})
}

func (s *APIServer) Run() error {
	addr := fmt.Sprintf(":%d", s.config.Server.Port)
	return s.router.Run(addr)
}

func (s *APIServer) GetRouter() *gin.Engine {
	return s.router
}

func CORSMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		origin := c.Request.Header.Get("Origin")
		if origin == "" {
			origin = "*"
		}

		c.Writer.Header().Set("Access-Control-Allow-Origin", origin)
		c.Writer.Header().Set("Access-Control-Allow-Credentials", "true")
		c.Writer.Header().Set("Access-Control-Allow-Headers", "Content-Type, Content-Length, Accept-Encoding, X-CSRF-Token, Authorization, accept, origin, Cache-Control, X-Requested-With")
		c.Writer.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS, GET, PUT, DELETE")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}

		c.Next()
	}
}

func parseTime(timeStr string) (time.Time, error) {
	formats := []string{
		time.RFC3339,
		"2006-01-02T15:04:05",
		"2006-01-02 15:04:05",
		"2006-01-02",
	}

	for _, format := range formats {
		if t, err := time.ParseInLocation(format, timeStr, time.Local); err == nil {
			return t, nil
		}
	}

	return time.Time{}, fmt.Errorf("unable to parse time: %s", timeStr)
}

func (s *APIServer) handleGetSchedulerStatus(c *gin.Context) {
	status := s.seasonService.GetSchedulerStatus()

	c.JSON(http.StatusOK, APIResponse{
		Code: 200,
		Data: status,
	})
}

func (s *APIServer) handleStartScheduler(c *gin.Context) {
	s.seasonService.StartScheduler()

	c.JSON(http.StatusOK, APIResponse{
		Code:    200,
		Message: "Season scheduler started",
	})
}

func (s *APIServer) handleStopScheduler(c *gin.Context) {
	s.seasonService.StopScheduler()

	c.JSON(http.StatusOK, APIResponse{
		Code:    200,
		Message: "Season scheduler stopped",
	})
}

func (s *APIServer) handleGetSeasonArchive(c *gin.Context) {
	seasonID := c.Param("season_id")
	if seasonID == "" {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "season_id is required",
		})
		return
	}

	archive, err := s.seasonService.GetSeasonArchive(seasonID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to get season archive: " + err.Error(),
		})
		return
	}

	if archive == nil {
		c.JSON(http.StatusNotFound, APIResponse{
			Code:    404,
			Message: "Archive not found",
		})
		return
	}

	archivedScores, err := archive.GetPlayerScores()
	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to parse archived scores: " + err.Error(),
		})
		return
	}

	archivedLBs, err := archive.GetLeaderboardData()
	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to parse archived leaderboards: " + err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, APIResponse{
		Code: 200,
		Data: gin.H{
			"archive_id":       archive.ArchiveID,
			"game_id":          archive.GameID,
			"season_id":        archive.SeasonID,
			"season_name":      archive.SeasonName,
			"start_time":       archive.StartTime,
			"end_time":         archive.EndTime,
			"archived_at":      archive.ArchivedAt,
			"player_count":     archive.PlayerCount,
			"total_score_sum":  archive.TotalScoreSum,
			"checksum":         archive.Checksum,
			"player_scores":    archivedScores,
			"leaderboards":     archivedLBs,
		},
	})
}

func (s *APIServer) handleGetConfig(c *gin.Context) {
	c.JSON(http.StatusOK, APIResponse{
		Code: 200,
		Data: gin.H{
			"server": gin.H{
				"port": s.config.Server.Port,
				"mode": s.config.Server.Mode,
			},
			"season": s.config.Season,
			"ranking": s.config.Ranking,
			"websocket": s.config.WebSocket,
		},
	})
}

func (s *APIServer) handleCreateSnapshot(c *gin.Context) {
	var req struct {
		GameID   string                  `json:"game_id" binding:"required"`
		SeasonID string                  `json:"season_id"`
		Type     models.LeaderboardType  `json:"type"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, APIResponse{
			Code:    400,
			Message: "Invalid request: " + err.Error(),
		})
		return
	}

	lbType := req.Type
	if lbType == "" {
		lbType = models.LeaderboardTypeTotal
	}

	err := s.rankService.CalculateLeaderboardSnapshot(req.GameID, req.SeasonID, lbType)
	if err != nil {
		c.JSON(http.StatusInternalServerError, APIResponse{
			Code:    500,
			Message: "Failed to create snapshot: " + err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, APIResponse{
		Code:    200,
		Message: "Leaderboard snapshot created successfully",
	})
}
