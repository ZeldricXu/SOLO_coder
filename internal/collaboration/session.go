package collaboration

import (
	"encoding/json"
	"fmt"
	"pointcloud-platform/config"
	"pointcloud-platform/pkg/math3d"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/google/uuid"
)

type MessageType string
type ConflictResolution string

const (
	MessageTypeViewSync    MessageType = "view_sync"
	MessageTypeAnnotation  MessageType = "annotation"
	MessageTypeCursor      MessageType = "cursor"
	MessageTypeChat        MessageType = "chat"
	MessageTypeSelection   MessageType = "selection"
	MessageTypeJoin        MessageType = "join"
	MessageTypeLeave       MessageType = "leave"
	MessageTypeHeartbeat   MessageType = "heartbeat"

	ConflictLastWriteWins ConflictResolution = "last-write-wins"
	ConflictMerge         ConflictResolution = "merge"
	ConflictReject        ConflictResolution = "reject"
)

type ViewState struct {
	Position math3d.Vec3 `json:"position"`
	Target   math3d.Vec3 `json:"target"`
	Up       math3d.Vec3 `json:"up"`
	Fov      float64    `json:"fov"`
}

type CursorPosition struct {
	ScreenX  int        `json:"screen_x"`
	ScreenY  int        `json:"screen_y"`
	WorldPos math3d.Vec3 `json:"world_pos"`
}

type Message struct {
	ID        string          `json:"id"`
	Type      MessageType     `json:"type"`
	UserID    string          `json:"user_id"`
	Username  string          `json:"username,omitempty"`
	RoomID    string          `json:"room_id"`
	Timestamp int64           `json:"timestamp"`
	Version   int64           `json:"version"`
	Payload   json.RawMessage `json:"payload"`
}

type User struct {
	ID       string          `json:"id"`
	Username string          `json:"username"`
	Color    string          `json:"color"`
	Conn     *websocket.Conn `json:"-"`
	connMu   sync.Mutex      `json:"-"`
	JoinedAt int64           `json:"joined_at"`
	LastSeen int64           `json:"last_seen"`
	IsActive bool            `json:"is_active"`
}

type RoomState struct {
	ViewState  *ViewState `json:"view_state,omitempty"`
	ViewVersion int64     `json:"view_version"`
	AnnotationVersion int64 `json:"annotation_version"`
	LastUpdated int64    `json:"last_updated"`
}

type Operation struct {
	ID        string          `json:"id"`
	Type      string          `json:"type"`
	EntityID  string          `json:"entity_id"`
	UserID    string          `json:"user_id"`
	Timestamp int64           `json:"timestamp"`
	Data      json.RawMessage `json:"data"`
	Previous  json.RawMessage `json:"previous,omitempty"`
}

type OperationLog struct {
	operations []Operation
	mu         sync.RWMutex
}

type Room struct {
	ID         string
	Name       string
	DatasetID  string
	Users      map[string]*User
	State      RoomState
	operations *OperationLog
	mu         sync.RWMutex
	maxUsers   int
}

type CollaborationService struct {
	cfg              *config.CollaborationConfig
	rooms            map[string]*Room
	roomsMu          sync.RWMutex
	userColors       []string
	nextColorIndex   int
}

func NewCollaborationService(cfg *config.CollaborationConfig) *CollaborationService {
	return &CollaborationService{
		cfg:    cfg,
		rooms:  make(map[string]*Room),
		userColors: []string{
			"#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4",
			"#FFEAA7", "#DDA0DD", "#98D8C8", "#F7DC6F",
			"#BB8FCE", "#85C1E9", "#F8B500", "#00CED1",
		},
	}
}

func (s *CollaborationService) CreateRoom(roomID, name, datasetID string) (*Room, error) {
	s.roomsMu.Lock()
	defer s.roomsMu.Unlock()

	if _, exists := s.rooms[roomID]; exists {
		return nil, fmt.Errorf("room %s already exists", roomID)
	}

	room := &Room{
		ID:         roomID,
		Name:       name,
		DatasetID:  datasetID,
		Users:      make(map[string]*User),
		maxUsers:   s.cfg.MaxConnectionsPerRoom,
		operations: &OperationLog{},
		State: RoomState{
			ViewVersion:       0,
			AnnotationVersion: 0,
			LastUpdated:       time.Now().Unix(),
		},
	}

	s.rooms[roomID] = room
	return room, nil
}

func (s *CollaborationService) GetRoom(roomID string) (*Room, bool) {
	s.roomsMu.RLock()
	defer s.roomsMu.RUnlock()
	room, exists := s.rooms[roomID]
	return room, exists
}

func (s *CollaborationService) JoinRoom(roomID, userID, username string, conn *websocket.Conn) (*User, *Room, error) {
	s.roomsMu.Lock()
	room, exists := s.rooms[roomID]
	s.roomsMu.Unlock()

	if !exists {
		var err error
		room, err = s.CreateRoom(roomID, "Collaboration Room", "")
		if err != nil {
			return nil, nil, err
		}
	}

	room.mu.Lock()
	defer room.mu.Unlock()

	if len(room.Users) >= room.maxUsers {
		return nil, nil, fmt.Errorf("room is full (max %d users)", room.maxUsers)
	}

	color := s.userColors[s.nextColorIndex]
	s.nextColorIndex = (s.nextColorIndex + 1) % len(s.userColors)

	now := time.Now().Unix()
	user := &User{
		ID:       userID,
		Username: username,
		Color:    color,
		Conn:     conn,
		JoinedAt: now,
		LastSeen: now,
		IsActive: true,
	}

	if existing, exists := room.Users[userID]; exists {
		if existing.Conn != nil {
			existing.Conn.Close()
		}
	}

	room.Users[userID] = user

	joinMsg := Message{
		ID:        uuid.New().String(),
		Type:      MessageTypeJoin,
		UserID:    userID,
		Username:  username,
		RoomID:    roomID,
		Timestamp: now,
		Version:   1,
	}
	joinMsg.Payload, _ = json.Marshal(user)

	s.broadcast(room, joinMsg, userID)

	return user, room, nil
}

func (s *CollaborationService) LeaveRoom(roomID, userID string) {
	s.roomsMu.RLock()
	room, exists := s.rooms[roomID]
	s.roomsMu.RUnlock()

	if !exists {
		return
	}

	room.mu.Lock()
	defer room.mu.Unlock()

	user, exists := room.Users[userID]
	if !exists {
		return
	}

	if user.Conn != nil {
		user.Conn.Close()
	}

	delete(room.Users, userID)

	leaveMsg := Message{
		ID:        uuid.New().String(),
		Type:      MessageTypeLeave,
		UserID:    userID,
		Username:  user.Username,
		RoomID:    roomID,
		Timestamp: time.Now().Unix(),
		Version:   1,
	}
	leaveMsg.Payload, _ = json.Marshal(map[string]string{"user_id": userID})

	s.broadcast(room, leaveMsg, "")

	if len(room.Users) == 0 {
		s.roomsMu.Lock()
		delete(s.rooms, roomID)
		s.roomsMu.Unlock()
	}
}

func (s *CollaborationService) HandleMessage(roomID, userID string, msg Message) error {
	s.roomsMu.RLock()
	room, exists := s.rooms[roomID]
	s.roomsMu.RUnlock()

	if !exists {
		return fmt.Errorf("room %s not found", roomID)
	}

	room.mu.Lock()
	user, userExists := room.Users[userID]
	room.mu.Unlock()

	if !userExists {
		return fmt.Errorf("user %s not in room", userID)
	}

	user.LastSeen = time.Now().Unix()
	msg.Timestamp = user.LastSeen

	switch msg.Type {
	case MessageTypeViewSync:
		return s.handleViewSync(room, user, msg)
	case MessageTypeAnnotation:
		return s.handleAnnotation(room, user, msg)
	case MessageTypeCursor:
		return s.handleCursor(room, user, msg)
	case MessageTypeHeartbeat:
		return nil
	default:
		s.broadcast(room, msg, userID)
	}

	return nil
}

func (s *CollaborationService) handleViewSync(room *Room, user *User, msg Message) error {
	var viewState ViewState
	if err := json.Unmarshal(msg.Payload, &viewState); err != nil {
		return err
	}

	room.mu.Lock()
	defer room.mu.Unlock()

	resolution := ConflictResolution(s.cfg.ConflictResolution)

	if room.State.ViewState == nil || resolution == ConflictLastWriteWins {
		room.State.ViewState = &viewState
		room.State.ViewVersion++
		room.State.LastUpdated = time.Now().Unix()
		msg.Version = room.State.ViewVersion

		op := Operation{
			ID:        msg.ID,
			Type:      "view_update",
			EntityID:  "view",
			UserID:    user.ID,
			Timestamp: msg.Timestamp,
			Data:      msg.Payload,
		}
		room.operations.Add(op)

		s.broadcast(room, msg, user.ID)
	}

	return nil
}

func (s *CollaborationService) handleAnnotation(room *Room, user *User, msg Message) error {
	var annotationData map[string]interface{}
	if err := json.Unmarshal(msg.Payload, &annotationData); err != nil {
		return err
	}

	room.mu.Lock()
	defer room.mu.Unlock()

	resolution := ConflictResolution(s.cfg.ConflictResolution)
	entityID, _ := annotationData["id"].(string)

	existingOp, hasConflict := room.operations.FindLatest(entityID)

	if hasConflict && resolution == ConflictReject {
		return fmt.Errorf("conflict detected, annotation update rejected")
	}

	if hasConflict && resolution == ConflictMerge {
		var existingData map[string]interface{}
		json.Unmarshal(existingOp.Data, &existingData)
		merged := s.mergeAnnotations(existingData, annotationData)
		mergedJSON, _ := json.Marshal(merged)
		msg.Payload = mergedJSON
	}

	room.State.AnnotationVersion++
	room.State.LastUpdated = time.Now().Unix()
	msg.Version = room.State.AnnotationVersion

	op := Operation{
		ID:        msg.ID,
		Type:      "annotation_update",
		EntityID:  entityID,
		UserID:    user.ID,
		Timestamp: msg.Timestamp,
		Data:      msg.Payload,
		Previous:  existingOp.Data,
	}
	room.operations.Add(op)

	s.broadcast(room, msg, user.ID)

	return nil
}

func (s *CollaborationService) handleCursor(room *Room, user *User, msg Message) error {
	var cursor CursorPosition
	if err := json.Unmarshal(msg.Payload, &cursor); err != nil {
		return err
	}

	msg.Payload, _ = json.Marshal(map[string]interface{}{
		"user_id":   user.ID,
		"username":  user.Username,
		"color":     user.Color,
		"cursor":    cursor,
	})

	s.broadcast(room, msg, user.ID)
	return nil
}

func (s *CollaborationService) mergeAnnotations(existing, incoming map[string]interface{}) map[string]interface{} {
	merged := make(map[string]interface{})

	for k, v := range existing {
		merged[k] = v
	}

	for k, v := range incoming {
		merged[k] = v
	}

	return merged
}

func (s *CollaborationService) broadcast(room *Room, msg Message, excludeUserID string) {
	room.mu.RLock()
	defer room.mu.RUnlock()

	data, err := json.Marshal(msg)
	if err != nil {
		return
	}

	for userID, user := range room.Users {
		if userID == excludeUserID || user.Conn == nil {
			continue
		}

		go func(u *User, data []byte) {
			u.connMu.Lock()
			defer u.connMu.Unlock()
			if u.Conn != nil {
				_ = u.Conn.WriteMessage(websocket.TextMessage, data)
			}
		}(user, data)
	}
}

func (s *CollaborationService) GetRoomUsers(roomID string) ([]*User, error) {
	s.roomsMu.RLock()
	room, exists := s.rooms[roomID]
	s.roomsMu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("room %s not found", roomID)
	}

	room.mu.RLock()
	defer room.mu.RUnlock()

	users := make([]*User, 0, len(room.Users))
	for _, user := range room.Users {
		users = append(users, user)
	}

	return users, nil
}

func (s *CollaborationService) GetRoomState(roomID string) (*RoomState, error) {
	s.roomsMu.RLock()
	room, exists := s.rooms[roomID]
	s.roomsMu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("room %s not found", roomID)
	}

	room.mu.RLock()
	defer room.mu.RUnlock()

	state := room.State
	return &state, nil
}

func (s *CollaborationService) ListRooms() []*Room {
	s.roomsMu.RLock()
	defer s.roomsMu.RUnlock()

	rooms := make([]*Room, 0, len(s.rooms))
	for _, room := range s.rooms {
		rooms = append(rooms, room)
	}
	return rooms
}

func (s *CollaborationService) CleanupInactiveUsers() {
	s.roomsMu.RLock()
	rooms := make([]*Room, 0, len(s.rooms))
	for _, room := range s.rooms {
		rooms = append(rooms, room)
	}
	s.roomsMu.RUnlock()

	now := time.Now().Unix()
	timeout := int64(s.cfg.PingInterval * 3)

	for _, room := range rooms {
		room.mu.Lock()
		inactiveUsers := make([]string, 0)

		for userID, user := range room.Users {
			if now-user.LastSeen > timeout {
				inactiveUsers = append(inactiveUsers, userID)
			}
		}

		for _, userID := range inactiveUsers {
			user := room.Users[userID]
			if user.Conn != nil {
				user.Conn.Close()
			}
			delete(room.Users, userID)
		}

		room.mu.Unlock()

		if len(inactiveUsers) > 0 {
			room.mu.RLock()
			userCount := len(room.Users)
			room.mu.RUnlock()

			if userCount == 0 {
				s.roomsMu.Lock()
				delete(s.rooms, room.ID)
				s.roomsMu.Unlock()
			}
		}
	}
}

func (log *OperationLog) Add(op Operation) {
	log.mu.Lock()
	defer log.mu.Unlock()
	log.operations = append(log.operations, op)
}

func (log *OperationLog) FindLatest(entityID string) (Operation, bool) {
	log.mu.RLock()
	defer log.mu.RUnlock()

	for i := len(log.operations) - 1; i >= 0; i-- {
		if log.operations[i].EntityID == entityID {
			return log.operations[i], true
		}
	}
	return Operation{}, false
}

func (log *OperationLog) GetHistory(entityID string) []Operation {
	log.mu.RLock()
	defer log.mu.RUnlock()

	var history []Operation
	for _, op := range log.operations {
		if op.EntityID == entityID {
			history = append(history, op)
		}
	}
	return history
}


