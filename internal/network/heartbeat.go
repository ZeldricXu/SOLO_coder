package network

import (
	"sync"
	"time"

	"github.com/olahol/melody"
	"pixelrealm/pkg/config"
	"pixelrealm/pkg/models"
)

type HeartbeatManager struct {
	server      *WebSocketServer
	config      *config.WebSocketConfig
	lastSeen    map[models.PlayerID]time.Time
	lastSeenMu  sync.RWMutex
	timers      map[models.PlayerID]*time.Timer
	timersMu    sync.Mutex
}

func NewHeartbeatManager(server *WebSocketServer, cfg *config.WebSocketConfig) *HeartbeatManager {
	return &HeartbeatManager{
		server:   server,
		config:   cfg,
		lastSeen: make(map[models.PlayerID]time.Time),
		timers:   make(map[models.PlayerID]*time.Timer),
	}
}

func (h *HeartbeatManager) StartHeartbeat(playerID models.PlayerID, session *melody.Session) {
	h.lastSeenMu.Lock()
	h.lastSeen[playerID] = time.Now()
	h.lastSeenMu.Unlock()
	
	h.timersMu.Lock()
	if existingTimer, exists := h.timers[playerID]; exists {
		existingTimer.Stop()
	}
	h.timers[playerID] = time.AfterFunc(h.config.PingInterval, func() {
		h.sendPing(playerID, session)
	})
	h.timersMu.Unlock()
}

func (h *HeartbeatManager) StopHeartbeat(playerID models.PlayerID) {
	h.timersMu.Lock()
	if timer, exists := h.timers[playerID]; exists {
		timer.Stop()
		delete(h.timers, playerID)
	}
	h.timersMu.Unlock()
	
	h.lastSeenMu.Lock()
	delete(h.lastSeen, playerID)
	h.lastSeenMu.Unlock()
}

func (h *HeartbeatManager) UpdateLastSeen(playerID models.PlayerID) {
	h.lastSeenMu.Lock()
	h.lastSeen[playerID] = time.Now()
	h.lastSeenMu.Unlock()
}

func (h *HeartbeatManager) sendPing(playerID models.PlayerID, session *melody.Session) {
	h.lastSeenMu.RLock()
	lastSeenTime := h.lastSeen[playerID]
	h.lastSeenMu.RUnlock()
	
	timeSinceLastSeen := time.Since(lastSeenTime)
	
	if timeSinceLastSeen > h.config.PongWait {
		h.disconnectPlayer(playerID, session)
		return
	}
	
	pingMsg := models.NewResponse(models.EventHeartbeat, models.HeartbeatResponseData{
		ServerTimestamp: time.Now().Unix(),
	})
	
	data, _ := json.Marshal(pingMsg)
	session.Write(data)
	
	h.timersMu.Lock()
	if timer, exists := h.timers[playerID]; exists {
		timer.Stop()
	}
	h.timers[playerID] = time.AfterFunc(h.config.PingInterval, func() {
		h.sendPing(playerID, session)
	})
	h.timersMu.Unlock()
}

func (h *HeartbeatManager) disconnectPlayer(playerID models.PlayerID, session *melody.Session) {
	h.timersMu.Lock()
	if timer, exists := h.timers[playerID]; exists {
		timer.Stop()
		delete(h.timers, playerID)
	}
	h.timersMu.Unlock()
	
	h.lastSeenMu.Lock()
	delete(h.lastSeen, playerID)
	h.lastSeenMu.Unlock()
	
	session.Close()
}

func (h *HeartbeatManager) GetLastSeen(playerID models.PlayerID) time.Time {
	h.lastSeenMu.RLock()
	defer h.lastSeenMu.RUnlock()
	return h.lastSeen[playerID]
}

func (h *HeartbeatManager) IsActive(playerID models.PlayerID) bool {
	h.lastSeenMu.RLock()
	defer h.lastSeenMu.RUnlock()
	
	lastSeen, exists := h.lastSeen[playerID]
	if !exists {
		return false
	}
	
	return time.Since(lastSeen) < h.config.PongWait
}

func (h *HeartbeatManager) GetActivePlayers() []models.PlayerID {
	h.lastSeenMu.RLock()
	defer h.lastSeenMu.RUnlock()
	
	var active []models.PlayerID
	now := time.Now()
	
	for playerID, lastSeen := range h.lastSeen {
		if now.Sub(lastSeen) < h.config.PongWait {
			active = append(active, playerID)
		}
	}
	
	return active
}
