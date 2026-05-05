package network

import (
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/olahol/melody"
	"pixelrealm/pkg/config"
	"pixelrealm/pkg/models"
)

type WebSocketServer struct {
	melody        *melody.Melody
	sessions      map[*melody.Session]*models.PlayerID
	sessionMutex  sync.RWMutex
	config        *config.WebSocketConfig
	messageRouter *MessageRouter
	heartbeat     *HeartbeatManager
}

func NewWebSocketServer(cfg *config.WebSocketConfig) *WebSocketServer {
	m := melody.New()
	m.Config.MaxMessageSize = cfg.MaxMessageSize
	
	server := &WebSocketServer{
		melody:       m,
		sessions:     make(map[*melody.Session]*models.PlayerID),
		config:       cfg,
		messageRouter: NewMessageRouter(),
	}
	
	server.heartbeat = NewHeartbeatManager(server, cfg)
	
	return server
}

func (s *WebSocketServer) SetMessageRouter(router *MessageRouter) {
	s.messageRouter = router
}

func (s *WebSocketServer) HandleRequest(w http.ResponseWriter, r *http.Request) {
	err := s.melody.HandleRequest(w, r)
	if err != nil {
		http.Error(w, "Failed to upgrade connection", http.StatusInternalServerError)
	}
}

func (s *WebSocketServer) RegisterHandlers() {
	s.melody.HandleConnect(s.handleConnect)
	s.melody.HandleDisconnect(s.handleDisconnect)
	s.melody.HandleMessage(s.handleMessage)
	s.melody.HandleMessageBinary(s.handleMessageBinary)
}

func (s *WebSocketServer) handleConnect(session *melody.Session) {
	session.Set("connected_at", time.Now().Unix())
}

func (s *WebSocketServer) handleDisconnect(session *melody.Session) {
	s.sessionMutex.Lock()
	playerID, exists := s.sessions[session]
	if exists {
		delete(s.sessions, session)
	}
	s.sessionMutex.Unlock()
	
	if exists && playerID != nil {
		s.heartbeat.StopHeartbeat(*playerID)
		if s.messageRouter != nil {
			s.messageRouter.HandleDisconnect(*playerID)
		}
	}
}

func (s *WebSocketServer) handleMessage(session *melody.Session, msg []byte) {
	var req models.Request
	if err := json.Unmarshal(msg, &req); err != nil {
		s.SendError(session, 400, "Invalid message format")
		return
	}
	
	if s.messageRouter != nil {
		s.messageRouter.Route(session, &req, s)
	}
}

func (s *WebSocketServer) handleMessageBinary(session *melody.Session, msg []byte) {
	s.handleMessage(session, msg)
}

func (s *WebSocketServer) BindSession(session *melody.Session, playerID models.PlayerID) {
	s.sessionMutex.Lock()
	s.sessions[session] = &playerID
	session.Set("player_id", playerID)
	s.sessionMutex.Unlock()
	
	s.heartbeat.StartHeartbeat(playerID, session)
}

func (s *WebSocketServer) UnbindSession(session *melody.Session) {
	s.sessionMutex.Lock()
	playerID, exists := s.sessions[session]
	if exists {
		delete(s.sessions, session)
		session.Set("player_id", nil)
	}
	s.sessionMutex.Unlock()
	
	if exists && playerID != nil {
		s.heartbeat.StopHeartbeat(*playerID)
	}
}

func (s *WebSocketServer) GetPlayerID(session *melody.Session) *models.PlayerID {
	s.sessionMutex.RLock()
	defer s.sessionMutex.RUnlock()
	
	if pid, exists := s.sessions[session]; exists {
		return pid
	}
	
	if val, exists := session.Get("player_id"); exists {
		if pid, ok := val.(models.PlayerID); ok {
			return &pid
		}
	}
	
	return nil
}

func (s *WebSocketServer) GetSession(playerID models.PlayerID) *melody.Session {
	s.sessionMutex.RLock()
	defer s.sessionMutex.RUnlock()
	
	for session, pid := range s.sessions {
		if pid != nil && *pid == playerID {
			return session
		}
	}
	return nil
}

func (s *WebSocketServer) SendToPlayer(playerID models.PlayerID, response *models.Response) error {
	session := s.GetSession(playerID)
	if session == nil {
		return nil
	}
	
	data, err := json.Marshal(response)
	if err != nil {
		return err
	}
	
	return session.Write(data)
}

func (s *WebSocketServer) SendToPlayers(playerIDs []models.PlayerID, response *models.Response) {
	data, err := json.Marshal(response)
	if err != nil {
		return
	}
	
	s.sessionMutex.RLock()
	defer s.sessionMutex.RUnlock()
	
	for _, targetID := range playerIDs {
		for session, pid := range s.sessions {
			if pid != nil && *pid == targetID {
				session.Write(data)
				break
			}
		}
	}
}

func (s *WebSocketServer) Broadcast(response *models.Response) {
	data, err := json.Marshal(response)
	if err != nil {
		return
	}
	s.melody.Broadcast(data)
}

func (s *WebSocketServer) BroadcastOthers(response *models.Response, excludePlayerID models.PlayerID) {
	data, err := json.Marshal(response)
	if err != nil {
		return
	}
	
	s.sessionMutex.RLock()
	defer s.sessionMutex.RUnlock()
	
	for session, pid := range s.sessions {
		if pid != nil && *pid != excludePlayerID {
			session.Write(data)
		}
	}
}

func (s *WebSocketServer) SendError(session *melody.Session, code int, message string) {
	resp := models.NewErrorResponse(code, message)
	data, _ := json.Marshal(resp)
	session.Write(data)
}

func (s *WebSocketServer) GetConnectedCount() int {
	s.sessionMutex.RLock()
	defer s.sessionMutex.RUnlock()
	return len(s.sessions)
}

func (s *WebSocketServer) GetMelody() *melody.Melody {
	return s.melody
}
