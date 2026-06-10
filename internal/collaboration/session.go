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
	MessageTypeViewSync         MessageType = "view_sync"
	MessageTypeAnnotation       MessageType = "annotation"
	MessageTypeCursor           MessageType = "cursor"
	MessageTypeChat             MessageType = "chat"
	MessageTypeSelection        MessageType = "selection"
	MessageTypeJoin             MessageType = "join"
	MessageTypeLeave            MessageType = "leave"
	MessageTypeHeartbeat        MessageType = "heartbeat"
	MessageTypeFrustumUpdate    MessageType = "frustum_update"
	MessageTypeAnnotationFetch  MessageType = "annotation_fetch"
	MessageTypeAnnotationVisible MessageType = "annotation_visible"
	MessageTypeAnnotationGone   MessageType = "annotation_gone"
	MessageTypeAnnotationBatch  MessageType = "annotation_batch"

	ConflictLastWriteWins ConflictResolution = "last-write-wins"
	ConflictMerge         ConflictResolution = "merge"
	ConflictReject        ConflictResolution = "reject"
)

type FrustumState struct {
	Position math3d.Vec3    `json:"position"`
	Target   math3d.Vec3    `json:"target"`
	Up       math3d.Vec3    `json:"up"`
	Fov      float64        `json:"fov"`
	Near     float64        `json:"near"`
	Far      float64        `json:"far"`
	Aspect   float64        `json:"aspect"`
	Frustum  *math3d.Frustum `json:"-"`
	UpdatedAt int64         `json:"updated_at"`
}

type VisibleAnnotation struct {
	ID         string
	FirstSeen  int64
	LastSeen   int64
	IsVisible  bool
}

type CachedAnnotation struct {
	ID        string
	Data      json.RawMessage
	ExpiresAt int64
}

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
	ID                string                      `json:"id"`
	Username          string                      `json:"username"`
	Color             string                      `json:"color"`
	Conn              *websocket.Conn             `json:"-"`
	connMu            sync.Mutex                `json:"-"`
	JoinedAt          int64                       `json:"joined_at"`
	LastSeen          int64                       `json:"last_seen"`
	IsActive          bool                        `json:"is_active"`
	Frustum           *FrustumState             `json:"frustum,omitempty"`
	VisibleAnnotations map[string]*VisibleAnnotation `json:"-"`
	AnnotationCache   map[string]*CachedAnnotation `json:"-"`
	hasSpatialSync    bool                        `json:"-"`
	visibleMu          sync.RWMutex              `json:"-"`
	cacheMu           sync.RWMutex              `json:"-"`
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

	if len(room.Users) >= room.maxUsers {
		room.mu.Unlock()
		return nil, nil, fmt.Errorf("room is full (max %d users)", room.maxUsers)
	}

	color := s.userColors[s.nextColorIndex]
	s.nextColorIndex = (s.nextColorIndex + 1) % len(s.userColors)

	now := time.Now().Unix()
	user := &User{
		ID:                 userID,
		Username:           username,
		Color:              color,
		Conn:               conn,
		JoinedAt:           now,
		LastSeen:           now,
		IsActive:           true,
		VisibleAnnotations: make(map[string]*VisibleAnnotation),
		AnnotationCache:    make(map[string]*CachedAnnotation),
		hasSpatialSync:     false,
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

	room.mu.Unlock()

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

	user, exists := room.Users[userID]
	if !exists {
		room.mu.Unlock()
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

	isEmpty := len(room.Users) == 0

	room.mu.Unlock()

	s.broadcast(room, leaveMsg, "")

	if isEmpty {
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
	case MessageTypeFrustumUpdate:
		return s.handleFrustumUpdate(room, user, msg)
	case MessageTypeAnnotationFetch:
		return s.handleAnnotationFetch(room, user, msg)
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

	resolution := ConflictResolution(s.cfg.ConflictResolution)

	shouldBroadcast := false
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
		shouldBroadcast = true
	}

	room.mu.Unlock()

	if shouldBroadcast {
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

	resolution := ConflictResolution(s.cfg.ConflictResolution)
	entityID, _ := annotationData["id"].(string)

	existingOp, hasConflict := room.operations.FindLatest(entityID)

	if hasConflict && resolution == ConflictReject {
		room.mu.Unlock()
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

	room.mu.Unlock()

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

		if msg.Type == MessageTypeAnnotationGone || msg.Type == MessageTypeAnnotationVisible {
			go func(u *User, data []byte) {
				u.connMu.Lock()
				defer u.connMu.Unlock()
				if u.Conn != nil {
					_ = u.Conn.WriteMessage(websocket.TextMessage, data)
				}
			}(user, data)
			continue
		}

		if msg.Type == MessageTypeAnnotation {
			if user.hasSpatialSync && user.Frustum != nil && user.Frustum.Frustum != nil {
				annotationAABB := s.extractAnnotationAABB(msg.Payload)
				if annotationAABB != nil {
					if !user.Frustum.Frustum.IntersectsAABB(*annotationAABB) {
						s.cacheAnnotationForUser(user, msg.ID, data)
						continue
					}
				}
			}
			s.updateUserAnnotationVisibility(user, msg.ID, true)
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

func (s *CollaborationService) GetVisibleAnnotationsForUser(roomID, userID string) ([]string, error) {
	s.roomsMu.RLock()
	room, exists := s.rooms[roomID]
	s.roomsMu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("room %s not found", roomID)
	}

	room.mu.RLock()
	user, userExists := room.Users[userID]
	room.mu.RUnlock()

	if !userExists {
		return nil, fmt.Errorf("user %s not in room", userID)
	}

	user.visibleMu.RLock()
	defer user.visibleMu.RUnlock()

	var visibleIDs []string
	for id, va := range user.VisibleAnnotations {
		if va.IsVisible {
			visibleIDs = append(visibleIDs, id)
		}
	}

	return visibleIDs, nil
}

func (s *CollaborationService) UpdateUserFrustum(roomID, userID string, frustumState FrustumState) ([]json.RawMessage, error) {
	s.roomsMu.RLock()
	room, exists := s.rooms[roomID]
	s.roomsMu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("room %s not found", roomID)
	}

	room.mu.RLock()
	user, userExists := room.Users[userID]
	room.mu.RUnlock()

	if !userExists {
		return nil, fmt.Errorf("user %s not in room", userID)
	}

	proj := math3d.Perspective(frustumState.Fov, frustumState.Aspect, frustumState.Near, frustumState.Far)
	view := math3d.LookAt(frustumState.Position, frustumState.Target, frustumState.Up)
	frustum := math3d.ExtractFrustum(proj.Mul(view))

	frustumState.Frustum = frustum
	frustumState.UpdatedAt = time.Now().Unix()

	user.visibleMu.Lock()
	user.Frustum = &frustumState
	user.hasSpatialSync = true
	user.visibleMu.Unlock()

	newlyVisible := s.checkVisibleAnnotations(user, room)

	var annotations []json.RawMessage
	if len(newlyVisible) > 0 {
		user.cacheMu.RLock()
		for _, id := range newlyVisible {
			if cached, ok := user.AnnotationCache[id]; ok {
				annotations = append(annotations, cached.Data)
			}
		}
		user.cacheMu.RUnlock()
	}

	return annotations, nil
}

func (s *CollaborationService) FetchAnnotationsInRegion(roomID string, region math3d.AABB) ([]json.RawMessage, error) {
	s.roomsMu.RLock()
	room, exists := s.rooms[roomID]
	s.roomsMu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("room %s not found", roomID)
	}

	room.mu.RLock()
	defer room.mu.RUnlock()

	seen := make(map[string]bool)
	var result []json.RawMessage

	for _, user := range room.Users {
		user.cacheMu.RLock()
		for id, cached := range user.AnnotationCache {
			if seen[id] {
				continue
			}
			seen[id] = true

			aabb := s.extractAnnotationAABB(cached.Data)
			if aabb != nil && region.Intersects(*aabb) {
				result = append(result, cached.Data)
			}
		}
		user.cacheMu.RUnlock()
	}

	room.operations.mu.RLock()
	for _, op := range room.operations.operations {
		if op.Type != "annotation_update" {
			continue
		}
		if seen[op.EntityID] {
			continue
		}
		seen[op.EntityID] = true

		aabb := s.extractAnnotationAABB(op.Data)
		if aabb != nil && region.Intersects(*aabb) {
			result = append(result, op.Data)
		}
	}
	room.operations.mu.RUnlock()

	return result, nil
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

func (s *CollaborationService) handleFrustumUpdate(room *Room, user *User, msg Message) error {
	var frustumState FrustumState
	if err := json.Unmarshal(msg.Payload, &frustumState); err != nil {
		return err
	}

	proj := math3d.Perspective(frustumState.Fov, frustumState.Aspect, frustumState.Near, frustumState.Far)
	view := math3d.LookAt(frustumState.Position, frustumState.Target, frustumState.Up)
	frustum := math3d.ExtractFrustum(proj.Mul(view))

	frustumState.Frustum = frustum
	frustumState.UpdatedAt = time.Now().Unix()

	user.visibleMu.Lock()
	user.Frustum = &frustumState
	user.hasSpatialSync = true
	user.visibleMu.Unlock()

	newlyVisible := s.checkVisibleAnnotations(user, room)
	if len(newlyVisible) > 0 {
		s.sendNewlyVisibleAnnotations(user, room, newlyVisible)
	}

	go s.cleanupInvisibleAnnotations(user, room)

	return nil
}

func (s *CollaborationService) handleAnnotationFetch(room *Room, user *User, msg Message) error {
	var fetchRequest struct {
		AnnotationIDs []string `json:"annotation_ids"`
	}
	if err := json.Unmarshal(msg.Payload, &fetchRequest); err != nil {
		return err
	}

	user.cacheMu.RLock()
	defer user.cacheMu.RUnlock()

	var annotations []json.RawMessage
	for _, id := range fetchRequest.AnnotationIDs {
		if cached, ok := user.AnnotationCache[id]; ok && cached.ExpiresAt > time.Now().Unix() {
			annotations = append(annotations, cached.Data)
		}
	}

	if len(annotations) > 0 {
		batchMsg := Message{
			ID:        uuid.New().String(),
			Type:      MessageTypeAnnotationBatch,
			UserID:    "system",
			RoomID:    room.ID,
			Timestamp: time.Now().Unix(),
			Version:   1,
		}
		batchMsg.Payload, _ = json.Marshal(map[string]interface{}{
			"annotations": annotations,
		})

		data, _ := json.Marshal(batchMsg)
		user.connMu.Lock()
		if user.Conn != nil {
			_ = user.Conn.WriteMessage(websocket.TextMessage, data)
		}
		user.connMu.Unlock()
	}

	return nil
}

func (s *CollaborationService) extractAnnotationAABB(payload json.RawMessage) *math3d.AABB {
	var geometry struct {
		Center   math3d.Vec3 `json:"center"`
		Size     math3d.Vec3 `json:"size"`
		Position math3d.Vec3 `json:"position"`
		Points   []math3d.Vec3 `json:"points"`
	}

	if err := json.Unmarshal(payload, &geometry); err != nil {
		return nil
	}

	if !geometry.Center.IsZero() && !geometry.Size.IsZero() {
		return &math3d.AABB{
			Min: geometry.Center.Sub(geometry.Size.Mul(0.5)),
			Max: geometry.Center.Add(geometry.Size.Mul(0.5)),
		}
	}

	if !geometry.Position.IsZero() {
		size := math3d.Vec3{X: 0.5, Y: 0.5, Z: 0.5}
		return &math3d.AABB{
			Min: geometry.Position.Sub(size),
			Max: geometry.Position.Add(size),
		}
	}

	if len(geometry.Points) > 0 {
		min := geometry.Points[0]
		max := geometry.Points[0]
		for _, p := range geometry.Points {
			min = min.Min(p)
			max = max.Max(p)
		}
		return &math3d.AABB{Min: min, Max: max}
	}

	return nil
}

func (s *CollaborationService) cacheAnnotationForUser(user *User, annotationID string, data []byte) {
	user.cacheMu.Lock()
	defer user.cacheMu.Unlock()

	ttl := int64(300)
	user.AnnotationCache[annotationID] = &CachedAnnotation{
		ID:        annotationID,
		Data:      data,
		ExpiresAt: time.Now().Unix() + ttl,
	}

	user.visibleMu.Lock()
	defer user.visibleMu.Unlock()

	if va, ok := user.VisibleAnnotations[annotationID]; ok {
		va.IsVisible = false
		va.LastSeen = time.Now().Unix()
	} else {
		user.VisibleAnnotations[annotationID] = &VisibleAnnotation{
			ID:        annotationID,
			FirstSeen: time.Now().Unix(),
			LastSeen:  time.Now().Unix(),
			IsVisible: false,
		}
	}
}

func (s *CollaborationService) updateUserAnnotationVisibility(user *User, annotationID string, visible bool) {
	user.visibleMu.Lock()
	defer user.visibleMu.Unlock()

	now := time.Now().Unix()
	if va, ok := user.VisibleAnnotations[annotationID]; ok {
		va.IsVisible = visible
		va.LastSeen = now
	} else {
		user.VisibleAnnotations[annotationID] = &VisibleAnnotation{
			ID:        annotationID,
			FirstSeen: now,
			LastSeen:  now,
			IsVisible: visible,
		}
	}
}

func (s *CollaborationService) checkVisibleAnnotations(user *User, room *Room) []string {
	user.visibleMu.RLock()
	frustum := user.Frustum
	user.visibleMu.RUnlock()

	if frustum == nil || frustum.Frustum == nil {
		return nil
	}

	var newlyVisible []string
	now := time.Now().Unix()

	user.cacheMu.RLock()
	user.visibleMu.Lock()
	defer user.cacheMu.RUnlock()
	defer user.visibleMu.Unlock()

	for id, cached := range user.AnnotationCache {
		if cached.ExpiresAt <= now {
			continue
		}

		va, exists := user.VisibleAnnotations[id]
		if exists && va.IsVisible {
			continue
		}

		aabb := s.extractAnnotationAABB(cached.Data)
		if aabb != nil && frustum.Frustum.IntersectsAABB(*aabb) {
			newlyVisible = append(newlyVisible, id)
			if va != nil {
				va.IsVisible = true
				va.LastSeen = now
			} else {
				user.VisibleAnnotations[id] = &VisibleAnnotation{
					ID:        id,
					FirstSeen: now,
					LastSeen:  now,
					IsVisible: true,
				}
			}
		}
	}

	return newlyVisible
}

func (s *CollaborationService) sendNewlyVisibleAnnotations(user *User, room *Room, annotationIDs []string) {
	user.cacheMu.RLock()
	defer user.cacheMu.RUnlock()

	var annotations []json.RawMessage
	for _, id := range annotationIDs {
		if cached, ok := user.AnnotationCache[id]; ok {
			annotations = append(annotations, cached.Data)
		}
	}

	if len(annotations) > 0 {
		batchMsg := Message{
			ID:        uuid.New().String(),
			Type:      MessageTypeAnnotationVisible,
			UserID:    "system",
			RoomID:    room.ID,
			Timestamp: time.Now().Unix(),
			Version:   1,
		}
		batchMsg.Payload, _ = json.Marshal(map[string]interface{}{
			"annotations": annotations,
		})

		data, _ := json.Marshal(batchMsg)
		user.connMu.Lock()
		if user.Conn != nil {
			_ = user.Conn.WriteMessage(websocket.TextMessage, data)
		}
		user.connMu.Unlock()
	}
}

func (s *CollaborationService) cleanupInvisibleAnnotations(user *User, room *Room) {
	time.Sleep(time.Second * 5)

	threshold := int64(60)
	now := time.Now().Unix()

	user.cacheMu.Lock()
	user.visibleMu.Lock()
	defer user.cacheMu.Unlock()
	defer user.visibleMu.Unlock()

	var goneIDs []string

	for id, va := range user.VisibleAnnotations {
		if !va.IsVisible && now-va.LastSeen > threshold {
			if cached, ok := user.AnnotationCache[id]; ok && cached.ExpiresAt <= now {
				goneIDs = append(goneIDs, id)
				delete(user.AnnotationCache, id)
				delete(user.VisibleAnnotations, id)
			}
		}
	}

	if len(goneIDs) > 0 {
		goneMsg := Message{
			ID:        uuid.New().String(),
			Type:      MessageTypeAnnotationGone,
			UserID:    "system",
			RoomID:    room.ID,
			Timestamp: now,
			Version:   1,
		}
		goneMsg.Payload, _ = json.Marshal(map[string]interface{}{
			"annotation_ids": goneIDs,
		})

		data, _ := json.Marshal(goneMsg)
		user.connMu.Lock()
		if user.Conn != nil {
			_ = user.Conn.WriteMessage(websocket.TextMessage, data)
		}
		user.connMu.Unlock()
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


