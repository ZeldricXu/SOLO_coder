package api

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/match"
	"github.com/studio/gameroom/pkg/observer"
	"github.com/studio/gameroom/pkg/room"
	"github.com/studio/gameroom/pkg/storage"
)

type Server struct {
	RoomManager    *room.Manager
	MatchService   *match.Service
	ObserverMgr    *observer.Manager
	InteractionMgr *observer.InteractionManager
	MongoStore     *storage.MongoStore
	RedisStore     *storage.RedisStore
	StatsAgg       *storage.StatsAggregator
}

type APIResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

func writeJSON(w http.ResponseWriter, status int, resp APIResponse) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(resp)
}

func (s *Server) CreateRoom(w http.ResponseWriter, r *http.Request) {
	var req struct {
		GameType     common.GameType `json:"game_type"`
		IsFriendRoom bool           `json:"is_friend_room"`
		MaxPlayers   int            `json:"max_players"`
		MinPlayers   int            `json:"min_players"`
		BaseScore    int64          `json:"base_score"`
		UserID       common.UserID  `json:"user_id"`
		Nickname     string         `json:"nickname"`
		Avatar       string         `json:"avatar"`
		Level        int            `json:"level"`
		Elo          float64        `json:"elo"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: "invalid request: " + err.Error()})
		return
	}

	if req.MaxPlayers <= 0 {
		switch req.GameType {
		case common.GameTypeMahjong:
			req.MaxPlayers = 4
			req.MinPlayers = 4
		case common.GameTypeLandlord:
			req.MaxPlayers = 3
			req.MinPlayers = 3
		case common.GameTypeTexas:
			req.MaxPlayers = 9
			req.MinPlayers = 2
		default:
			req.MaxPlayers = 4
			req.MinPlayers = 2
		}
	}
	if req.MinPlayers <= 0 {
		req.MinPlayers = 2
	}

	config := &common.RoomConfig{
		GameType:        req.GameType,
		MaxPlayers:      req.MaxPlayers,
		MinPlayers:      req.MinPlayers,
		IsFriendRoom:    req.IsFriendRoom,
		BaseScore:       req.BaseScore,
		TurnTimeoutSec:  15,
		ReadyTimeoutSec: 60,
		AllowObserver:   true,
		PlaybackEnabled: true,
	}

	host := &common.Player{
		UserID:   req.UserID,
		Nickname: req.Nickname,
		Avatar:   req.Avatar,
		Level:    req.Level,
		Elo:      req.Elo,
	}

	rm, err := s.RoomManager.CreateRoom(config, host)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, APIResponse{Code: 500, Message: err.Error()})
		return
	}

	if s.RedisStore != nil {
		s.RedisStore.SetOnline(req.UserID, rm.ID, 5*time.Minute)
	}

	writeJSON(w, http.StatusOK, APIResponse{
		Code:    0,
		Message: "success",
		Data: map[string]interface{}{
			"room_id":     rm.ID,
			"invite_code": rm.Config.InviteCode,
			"state":       rm.State,
		},
	})
}

func (s *Server) JoinRoom(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RoomID   common.RoomID `json:"room_id"`
		UserID   common.UserID `json:"user_id"`
		Nickname string        `json:"nickname"`
		Avatar   string        `json:"avatar"`
		Level    int           `json:"level"`
		Elo      float64       `json:"elo"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: "invalid request"})
		return
	}

	player := &common.Player{
		UserID:   req.UserID,
		Nickname: req.Nickname,
		Avatar:   req.Avatar,
		Level:    req.Level,
		Elo:      req.Elo,
	}

	rm, err := s.RoomManager.JoinRoom(req.RoomID, player)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: err.Error()})
		return
	}

	if s.RedisStore != nil {
		s.RedisStore.SetOnline(req.UserID, rm.ID, 5*time.Minute)
	}

	writeJSON(w, http.StatusOK, APIResponse{
		Code:    0,
		Message: "success",
		Data: map[string]interface{}{
			"room_id": rm.ID,
			"state":   rm.State,
		},
	})
}

func (s *Server) JoinByInvite(w http.ResponseWriter, r *http.Request) {
	var req struct {
		InviteCode string         `json:"invite_code"`
		UserID     common.UserID  `json:"user_id"`
		Nickname   string         `json:"nickname"`
		Avatar     string         `json:"avatar"`
		Level      int            `json:"level"`
		Elo        float64        `json:"elo"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: "invalid request"})
		return
	}

	player := &common.Player{
		UserID:   req.UserID,
		Nickname: req.Nickname,
		Avatar:   req.Avatar,
		Level:    req.Level,
		Elo:      req.Elo,
	}

	rm, err := s.RoomManager.JoinRoomByInvite(req.InviteCode, player)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, APIResponse{
		Code:    0,
		Message: "success",
		Data: map[string]interface{}{
			"room_id": rm.ID,
			"state":   rm.State,
		},
	})
}

func (s *Server) LeaveRoom(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RoomID common.RoomID `json:"room_id"`
		UserID common.UserID `json:"user_id"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: "invalid request"})
		return
	}

	if err := s.RoomManager.LeaveRoom(req.RoomID, req.UserID); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: err.Error()})
		return
	}

	if s.RedisStore != nil {
		s.RedisStore.SetOffline(req.UserID)
	}

	writeJSON(w, http.StatusOK, APIResponse{Code: 0, Message: "success"})
}

func (s *Server) SetReady(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RoomID  common.RoomID `json:"room_id"`
		UserID  common.UserID `json:"user_id"`
		IsReady bool          `json:"is_ready"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: "invalid request"})
		return
	}

	rm, ok := s.RoomManager.GetRoom(req.RoomID)
	if !ok {
		writeJSON(w, http.StatusNotFound, APIResponse{Code: 404, Message: "room not found"})
		return
	}

	if err := rm.SetReady(req.UserID, req.IsReady); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: err.Error()})
		return
	}

	if rm.AllReady() {
		if err := rm.StartGame(); err != nil {
			common.LogWarn("auto start game failed: %v", err)
		}
	}

	writeJSON(w, http.StatusOK, APIResponse{Code: 0, Message: "success"})
}

func (s *Server) StartGame(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RoomID common.RoomID `json:"room_id"`
		UserID common.UserID `json:"user_id"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: "invalid request"})
		return
	}

	rm, ok := s.RoomManager.GetRoom(req.RoomID)
	if !ok {
		writeJSON(w, http.StatusNotFound, APIResponse{Code: 404, Message: "room not found"})
		return
	}
	if rm.HostID != req.UserID {
		writeJSON(w, http.StatusForbidden, APIResponse{Code: 403, Message: "not host"})
		return
	}

	if err := rm.StartGame(); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, APIResponse{Code: 0, Message: "success"})
}

func (s *Server) DisbandRoom(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RoomID common.RoomID `json:"room_id"`
		UserID common.UserID `json:"user_id"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: "invalid request"})
		return
	}

	if err := s.RoomManager.DisbandRoom(req.RoomID, req.UserID); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, APIResponse{Code: 0, Message: "success"})
}

func (s *Server) GetRoomInfo(w http.ResponseWriter, r *http.Request) {
	roomID := common.RoomID(r.URL.Query().Get("room_id"))
	rm, ok := s.RoomManager.GetRoom(roomID)
	if !ok {
		writeJSON(w, http.StatusNotFound, APIResponse{Code: 404, Message: "room not found"})
		return
	}

	writeJSON(w, http.StatusOK, APIResponse{
		Code:    0,
		Message: "success",
		Data: map[string]interface{}{
			"room_id":    rm.ID,
			"config":     rm.Config,
			"state":      rm.State,
			"players":    rm.GetPlayers(),
			"host_id":    rm.HostID,
			"observers":  s.ObserverMgr.ObserverCount(rm.ID),
			"created_at": rm.CreatedAt,
		},
	})
}

func (s *Server) RequestMatch(w http.ResponseWriter, r *http.Request) {
	var req struct {
		UserID   common.UserID  `json:"user_id"`
		GameType common.GameType `json:"game_type"`
		Elo      float64        `json:"elo"`
		Level    int            `json:"level"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: "invalid request"})
		return
	}

	matchReq := &common.MatchRequest{
		UserID:   req.UserID,
		GameType: req.GameType,
		Elo:      req.Elo,
		Level:    req.Level,
	}

	if err := s.MatchService.RequestMatch(matchReq); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, APIResponse{
		Code:    0,
		Message: "matching",
		Data: map[string]interface{}{
			"pool_size": s.MatchService.GetPoolSize(req.GameType),
		},
	})
}

func (s *Server) CancelMatch(w http.ResponseWriter, r *http.Request) {
	var req struct {
		UserID   common.UserID  `json:"user_id"`
		GameType common.GameType `json:"game_type"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, APIResponse{Code: 400, Message: "invalid request"})
		return
	}

	s.MatchService.CancelMatch(req.UserID, req.GameType)
	writeJSON(w, http.StatusOK, APIResponse{Code: 0, Message: "success"})
}

func (s *Server) GetPlayerStats(w http.ResponseWriter, r *http.Request) {
	userID := common.UserID(r.URL.Query().Get("user_id"))
	gameType := common.GameType(r.URL.Query().Get("game_type"))

	if s.MongoStore == nil {
		writeJSON(w, http.StatusServiceUnavailable, APIResponse{Code: 503, Message: "storage not available"})
		return
	}

	stats, err := s.MongoStore.GetPlayerStats(userID, gameType)
	if err != nil {
		writeJSON(w, http.StatusNotFound, APIResponse{Code: 404, Message: err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, APIResponse{Code: 0, Message: "success", Data: stats})
}

func (s *Server) GetDailyTrend(w http.ResponseWriter, r *http.Request) {
	userID := common.UserID(r.URL.Query().Get("user_id"))
	gameType := common.GameType(r.URL.Query().Get("game_type"))

	if s.MongoStore == nil {
		writeJSON(w, http.StatusServiceUnavailable, APIResponse{Code: 503, Message: "storage not available"})
		return
	}

	trend, err := s.MongoStore.GetDailyTrend(userID, gameType, 30)
	if err != nil {
		writeJSON(w, http.StatusNotFound, APIResponse{Code: 404, Message: err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, APIResponse{Code: 0, Message: "success", Data: trend})
}

func (s *Server) ListRooms(w http.ResponseWriter, r *http.Request) {
	gameType := common.GameType(r.URL.Query().Get("game_type"))
	rooms := s.RoomManager.ListRooms(gameType)
	result := make([]map[string]interface{}, 0, len(rooms))
	for _, rm := range rooms {
		result = append(result, map[string]interface{}{
			"room_id":       rm.ID,
			"game_type":     rm.Config.GameType,
			"player_count":  len(rm.GetPlayers()),
			"max_players":   rm.Config.MaxPlayers,
			"state":         rm.State,
			"is_friend_room": rm.Config.IsFriendRoom,
		})
	}
	writeJSON(w, http.StatusOK, APIResponse{Code: 0, Message: "success", Data: result})
}

func (s *Server) HealthCheck(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, APIResponse{
		Code:    0,
		Message: "ok",
		Data: map[string]interface{}{
			"rooms":       s.RoomManager.Count(),
			"online":      0,
			"game_types":  matchListGames(),
		},
	})
}

func matchListGames() []common.GameType {
	result := make([]common.GameType, 0)
	result = append(result, common.GameTypeMahjong)
	result = append(result, common.GameTypeLandlord)
	result = append(result, common.GameTypeTexas)
	return result
}
