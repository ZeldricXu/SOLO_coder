package notify

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type SessionExpiredEvent struct {
	EventType string    `json:"event_type"`
	SessionID string    `json:"session_id"`
	UserID    string    `json:"user_id"`
	Reason    string    `json:"reason"`
	Timestamp time.Time `json:"timestamp"`
}

type SessionNotify struct {
	sessions    map[string]*websocket.Conn
	userSessions map[string][]string
	mu          sync.RWMutex
	upgrader    websocket.Upgrader
}

func NewSessionNotify() *SessionNotify {
	return &SessionNotify{
		sessions:     make(map[string]*websocket.Conn),
		userSessions: make(map[string][]string),
		upgrader: websocket.Upgrader{
			ReadBufferSize:  1024,
			WriteBufferSize: 1024,
			CheckOrigin: func(r *http.Request) bool {
				return true
			},
		},
	}
}

func (sn *SessionNotify) Register(sessionID, userID string, conn *websocket.Conn) {
	sn.mu.Lock()
	defer sn.mu.Unlock()

	if oldConn, exists := sn.sessions[sessionID]; exists {
		oldConn.Close()
	}

	sn.sessions[sessionID] = conn

	if _, exists := sn.userSessions[userID]; !exists {
		sn.userSessions[userID] = make([]string, 0)
	}

	for _, id := range sn.userSessions[userID] {
		if id == sessionID {
			return
		}
	}
	sn.userSessions[userID] = append(sn.userSessions[userID], sessionID)
}

func (sn *SessionNotify) Unregister(sessionID string) {
	sn.mu.Lock()
	defer sn.mu.Unlock()

	if conn, exists := sn.sessions[sessionID]; exists {
		conn.Close()
		delete(sn.sessions, sessionID)
	}

	for userID, sessionIDs := range sn.userSessions {
		newSessionIDs := make([]string, 0, len(sessionIDs))
		for _, id := range sessionIDs {
			if id != sessionID {
				newSessionIDs = append(newSessionIDs, id)
			}
		}
		if len(newSessionIDs) == 0 {
			delete(sn.userSessions, userID)
		} else {
			sn.userSessions[userID] = newSessionIDs
		}
	}
}

func (sn *SessionNotify) NotifySessionExpired(sessionID, userID, reason string) {
	event := SessionExpiredEvent{
		EventType: "session_expired",
		SessionID: sessionID,
		UserID:    userID,
		Reason:    reason,
		Timestamp: time.Now(),
	}

	sn.mu.RLock()
	conn, exists := sn.sessions[sessionID]
	sn.mu.RUnlock()

	if exists {
		data, err := json.Marshal(event)
		if err != nil {
			log.Printf("Failed to marshal session expired event: %v", err)
			return
		}

		err = conn.WriteMessage(websocket.TextMessage, data)
		if err != nil {
			log.Printf("Failed to send session expired notification: %v", err)
			sn.Unregister(sessionID)
		}
	}
}

func (sn *SessionNotify) NotifyUserSessionsExpired(userID, reason string) {
	sn.mu.RLock()
	sessionIDs, exists := sn.userSessions[userID]
	sn.mu.RUnlock()

	if !exists {
		return
	}

	for _, sessionID := range sessionIDs {
		sn.NotifySessionExpired(sessionID, userID, reason)
	}
}

func (sn *SessionNotify) HandleWebSocket(w http.ResponseWriter, r *http.Request) {
	sessionID := r.URL.Query().Get("session_id")
	userID := r.URL.Query().Get("user_id")

	if sessionID == "" || userID == "" {
		http.Error(w, "session_id and user_id are required", http.StatusBadRequest)
		return
	}

	conn, err := sn.upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("Failed to upgrade websocket: %v", err)
		return
	}

	sn.Register(sessionID, userID, conn)

	go func() {
		defer sn.Unregister(sessionID)
		for {
			_, _, err := conn.ReadMessage()
			if err != nil {
				return
			}
		}
	}()
}

type SessionNotifier interface {
	NotifySessionExpired(sessionID, userID, reason string)
	NotifyUserSessionsExpired(userID, reason string)
}
