package network

import (
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/olahol/melody"
	"pixelrealm/pkg/models"
)

type HandlerFunc func(session *melody.Session, data json.RawMessage, server *WebSocketServer)

type MessageRouter struct {
	handlers     map[string]HandlerFunc
	handlerMutex sync.RWMutex
	onDisconnect func(playerID models.PlayerID)
}

func NewMessageRouter() *MessageRouter {
	return &MessageRouter{
		handlers: make(map[string]HandlerFunc),
	}
}

func (r *MessageRouter) Register(action string, handler HandlerFunc) {
	r.handlerMutex.Lock()
	defer r.handlerMutex.Unlock()
	r.handlers[action] = handler
}

func (r *MessageRouter) SetOnDisconnect(handler func(playerID models.PlayerID)) {
	r.onDisconnect = handler
}

func (r *MessageRouter) HandleDisconnect(playerID models.PlayerID) {
	if r.onDisconnect != nil {
		r.onDisconnect(playerID)
	}
}

func (r *MessageRouter) Route(session *melody.Session, req *models.Request, server *WebSocketServer) {
	r.handlerMutex.RLock()
	handler, exists := r.handlers[req.Action]
	r.handlerMutex.RUnlock()
	
	if !exists {
		server.SendError(session, 404, fmt.Sprintf("Unknown action: %s", req.Action))
		return
	}
	
	handler(session, req.Data, server)
}

func (r *MessageRouter) DefaultHandlers() {
	r.Register(models.ActionHeartbeat, handleHeartbeat)
}

func handleHeartbeat(session *melody.Session, data json.RawMessage, server *WebSocketServer) {
	var req models.HeartbeatRequest
	if err := json.Unmarshal(data, &req); err != nil {
		server.SendError(session, 400, "Invalid heartbeat request")
		return
	}
	
	resp := models.NewResponse(models.EventHeartbeat, models.HeartbeatResponseData{
		ServerTimestamp: time.Now().Unix(),
	})
	
	respData, _ := json.Marshal(resp)
	session.Write(respData)
	
	playerID := server.GetPlayerID(session)
	if playerID != nil {
		server.heartbeat.UpdateLastSeen(*playerID)
	}
}

func (r *MessageRouter) Respond(session *melody.Session, event string, data interface{}) {
	resp := models.NewResponse(event, data)
	respData, _ := json.Marshal(resp)
	session.Write(respData)
}

func (r *MessageRouter) RespondError(session *melody.Session, code int, message string) {
	resp := models.NewErrorResponse(code, message)
	respData, _ := json.Marshal(resp)
	session.Write(respData)
}
