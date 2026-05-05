package push

import (
	"GameLeaderboard/internal/config"
	"encoding/json"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type BroadcastMessage struct {
	GameID    string                 `json:"game_id"`
	SeasonID  string                 `json:"season_id,omitempty"`
	Type      string                 `json:"type"`
	Timestamp time.Time              `json:"timestamp"`
	Data      map[string]interface{} `json:"data"`
}

type Client struct {
	conn     *websocket.Conn
	gameID   string
	playerID string
	send     chan []byte
}

type PushService struct {
	config        *config.WebSocketConfig
	clients       map[string]map[*Client]bool
	games         map[string]map[*Client]bool
	broadcast     chan *BroadcastMessage
	register      chan *Client
	unregister    chan *Client
	mu            sync.RWMutex
	upgrader      websocket.Upgrader
}

func NewPushService(cfg *config.WebSocketConfig) *PushService {
	if cfg == nil {
		cfg = &config.WebSocketConfig{
			ReadBufferSize:  1024,
			WriteBufferSize: 1024,
			WriteWait:       10,
			PongWait:        60,
			PingPeriod:      54,
		}
	}

	service := &PushService{
		config:     cfg,
		clients:    make(map[string]map[*Client]bool),
		games:      make(map[string]map[*Client]bool),
		broadcast:  make(chan *BroadcastMessage, 1000),
		register:   make(chan *Client),
		unregister: make(chan *Client),
		upgrader: websocket.Upgrader{
			ReadBufferSize:  cfg.ReadBufferSize,
			WriteBufferSize: cfg.WriteBufferSize,
			CheckOrigin: func(r *http.Request) bool {
				return true
			},
		},
	}

	go service.run()

	return service
}

func (s *PushService) run() {
	for {
		select {
		case client := <-s.register:
			s.mu.Lock()
			if s.clients[client.playerID] == nil {
				s.clients[client.playerID] = make(map[*Client]bool)
			}
			s.clients[client.playerID][client] = true

			if s.games[client.gameID] == nil {
				s.games[client.gameID] = make(map[*Client]bool)
			}
			s.games[client.gameID][client] = true
			s.mu.Unlock()

		case client := <-s.unregister:
			s.mu.Lock()
			if s.clients[client.playerID] != nil {
				if _, ok := s.clients[client.playerID][client]; ok {
					delete(s.clients[client.playerID], client)
					close(client.send)
					if len(s.clients[client.playerID]) == 0 {
						delete(s.clients, client.playerID)
					}
				}
			}

			if s.games[client.gameID] != nil {
				if _, ok := s.games[client.gameID][client]; ok {
					delete(s.games[client.gameID], client)
					if len(s.games[client.gameID]) == 0 {
						delete(s.games, client.gameID)
					}
				}
			}
			s.mu.Unlock()

		case msg := <-s.broadcast:
			s.broadcastMessage(msg)
		}
	}
}

func (s *PushService) broadcastMessage(msg *BroadcastMessage) {
	data, err := json.Marshal(msg)
	if err != nil {
		return
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	if msg.GameID != "" {
		if clients, ok := s.games[msg.GameID]; ok {
			for client := range clients {
				select {
				case client.send <- data:
				default:
					close(client.send)
					delete(s.clients[client.playerID], client)
					if len(s.clients[client.playerID]) == 0 {
						delete(s.clients, client.playerID)
					}
					delete(s.games[client.gameID], client)
					if len(s.games[client.gameID]) == 0 {
						delete(s.games, client.gameID)
					}
				}
			}
		}
	}
}

func (s *PushService) HandleWebSocket(w http.ResponseWriter, r *http.Request) {
	gameID := r.URL.Query().Get("game_id")
	playerID := r.URL.Query().Get("player_id")

	if gameID == "" {
		http.Error(w, "game_id is required", http.StatusBadRequest)
		return
	}
	if playerID == "" {
		http.Error(w, "player_id is required", http.StatusBadRequest)
		return
	}

	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	client := &Client{
		conn:     conn,
		gameID:   gameID,
		playerID: playerID,
		send:     make(chan []byte, 256),
	}

	s.register <- client

	go s.writePump(client)
	go s.readPump(client)
}

func (s *PushService) readPump(client *Client) {
	defer func() {
		s.unregister <- client
		client.conn.Close()
	}()

	client.conn.SetReadLimit(512)
	client.conn.SetReadDeadline(time.Now().Add(time.Duration(s.config.PongWait) * time.Second))
	client.conn.SetPongHandler(func(string) error {
		client.conn.SetReadDeadline(time.Now().Add(time.Duration(s.config.PongWait) * time.Second))
		return nil
	})

	for {
		_, _, err := client.conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
			}
			break
		}
	}
}

func (s *PushService) writePump(client *Client) {
	ticker := time.NewTicker(time.Duration(s.config.PingPeriod) * time.Second)
	defer func() {
		ticker.Stop()
		client.conn.Close()
	}()

	for {
		select {
		case message, ok := <-client.send:
			client.conn.SetWriteDeadline(time.Now().Add(time.Duration(s.config.WriteWait) * time.Second))
			if !ok {
				client.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}

			w, err := client.conn.NextWriter(websocket.TextMessage)
			if err != nil {
				return
			}
			w.Write(message)

			n := len(client.send)
			for i := 0; i < n; i++ {
				w.Write([]byte{'\n'})
				w.Write(<-client.send)
			}

			if err := w.Close(); err != nil {
				return
			}

		case <-ticker.C:
			client.conn.SetWriteDeadline(time.Now().Add(time.Duration(s.config.WriteWait) * time.Second))
			if err := client.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

func (s *PushService) BroadcastToGame(gameID string, msg *BroadcastMessage) {
	if msg.GameID == "" {
		msg.GameID = gameID
	}
	s.broadcast <- msg
}

func (s *PushService) BroadcastToPlayer(playerID string, msg *BroadcastMessage) {
	data, err := json.Marshal(msg)
	if err != nil {
		return
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	if clients, ok := s.clients[playerID]; ok {
		for client := range clients {
			select {
			case client.send <- data:
			default:
				close(client.send)
				delete(s.clients[playerID], client)
				if len(s.clients[playerID]) == 0 {
					delete(s.clients, playerID)
				}
				delete(s.games[client.gameID], client)
				if len(s.games[client.gameID]) == 0 {
					delete(s.games, client.gameID)
				}
			}
		}
	}
}

func (s *PushService) GetGameClientCount(gameID string) int {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if clients, ok := s.games[gameID]; ok {
		return len(clients)
	}
	return 0
}

func (s *PushService) GetPlayerClientCount(playerID string) int {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if clients, ok := s.clients[playerID]; ok {
		return len(clients)
	}
	return 0
}

func (s *PushService) GetTotalClientCount() int {
	s.mu.RLock()
	defer s.mu.RUnlock()

	count := 0
	for _, clients := range s.clients {
		count += len(clients)
	}
	return count
}
