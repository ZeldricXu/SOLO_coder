package collaboration

import (
	"encoding/json"
	"net/http"
	"pointcloud-platform/pkg/math3d"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

type Handler struct {
	service *CollaborationService
}

func NewHandler(service *CollaborationService) *Handler {
	return &Handler{service: service}
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	collab := r.Group("/collaboration")
	{
		collab.GET("/rooms", h.ListRooms)
		collab.POST("/rooms", h.CreateRoom)
		collab.GET("/rooms/:id", h.GetRoom)
		collab.GET("/rooms/:id/users", h.GetRoomUsers)
		collab.GET("/rooms/:id/state", h.GetRoomState)
		collab.PUT("/rooms/:id/users/:userId/frustum", h.UpdateUserFrustum)
		collab.POST("/rooms/:id/annotations/fetch", h.FetchAnnotationsInRegion)
		collab.GET("/rooms/:id/users/:userId/visible-annotations", h.GetVisibleAnnotations)
		collab.GET("/ws/:roomId/:userId/:username", h.WebSocketHandler)
	}

	go h.startCleanupRoutine()
}

func (h *Handler) startCleanupRoutine() {
	ticker := time.NewTicker(time.Duration(h.service.cfg.PingInterval) * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		h.service.CleanupInactiveUsers()
	}
}

func (h *Handler) ListRooms(c *gin.Context) {
	rooms := h.service.ListRooms()

	type RoomInfo struct {
		ID         string `json:"id"`
		Name       string `json:"name"`
		DatasetID  string `json:"dataset_id"`
		UserCount  int    `json:"user_count"`
		MaxUsers   int    `json:"max_users"`
	}

	roomInfos := make([]RoomInfo, 0, len(rooms))
	for _, room := range rooms {
		room.mu.RLock()
		roomInfos = append(roomInfos, RoomInfo{
			ID:        room.ID,
			Name:      room.Name,
			DatasetID: room.DatasetID,
			UserCount: len(room.Users),
			MaxUsers:  room.maxUsers,
		})
		room.mu.RUnlock()
	}

	c.JSON(http.StatusOK, gin.H{
		"rooms": roomInfos,
		"count": len(roomInfos),
	})
}

func (h *Handler) CreateRoom(c *gin.Context) {
	var req struct {
		ID        string `json:"id"`
		Name      string `json:"name"`
		DatasetID string `json:"dataset_id"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	if req.ID == "" {
		req.ID = "room-" + time.Now().Format("20060102-150405")
	}
	if req.Name == "" {
		req.Name = "Collaboration Room"
	}

	room, err := h.service.CreateRoom(req.ID, req.Name, req.DatasetID)
	if err != nil {
		c.JSON(http.StatusConflict, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"id":         room.ID,
		"name":       room.Name,
		"dataset_id": room.DatasetID,
		"max_users":  room.maxUsers,
		"created_at": time.Now().Unix(),
	})
}

func (h *Handler) GetRoom(c *gin.Context) {
	roomID := c.Param("id")

	room, exists := h.service.GetRoom(roomID)
	if !exists {
		c.JSON(http.StatusNotFound, gin.H{"error": "room not found"})
		return
	}

	room.mu.RLock()
	defer room.mu.RUnlock()

	users := make([]map[string]interface{}, 0, len(room.Users))
	for _, user := range room.Users {
		users = append(users, map[string]interface{}{
			"id":        user.ID,
			"username":  user.Username,
			"color":     user.Color,
			"joined_at": user.JoinedAt,
			"is_active": user.IsActive,
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"id":         room.ID,
		"name":       room.Name,
		"dataset_id": room.DatasetID,
		"users":      users,
		"user_count": len(room.Users),
		"max_users":  room.maxUsers,
		"state":      room.State,
	})
}

func (h *Handler) GetRoomUsers(c *gin.Context) {
	roomID := c.Param("id")

	users, err := h.service.GetRoomUsers(roomID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	userInfos := make([]map[string]interface{}, 0, len(users))
	for _, user := range users {
		userInfos = append(userInfos, map[string]interface{}{
			"id":        user.ID,
			"username":  user.Username,
			"color":     user.Color,
			"joined_at": user.JoinedAt,
			"last_seen": user.LastSeen,
			"is_active": user.IsActive,
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"users": userInfos,
		"count": len(userInfos),
	})
}

func (h *Handler) GetRoomState(c *gin.Context) {
	roomID := c.Param("id")

	state, err := h.service.GetRoomState(roomID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, state)
}

func (h *Handler) WebSocketHandler(c *gin.Context) {
	roomID := c.Param("roomId")
	userID := c.Param("userId")
	username := c.Param("username")

	conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to upgrade connection"})
		return
	}

	user, room, err := h.service.JoinRoom(roomID, userID, username, conn)
	if err != nil {
		conn.Close()
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	defer func() {
		h.service.LeaveRoom(roomID, userID)
	}()

	go h.writePump(conn, user, room)
	h.readPump(conn, user, room)
}

func (h *Handler) readPump(conn *websocket.Conn, user *User, room *Room) {
	defer conn.Close()

	conn.SetReadDeadline(time.Now().Add(time.Duration(h.service.cfg.PingInterval*3) * time.Second))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(time.Duration(h.service.cfg.PingInterval*3) * time.Second))
		user.LastSeen = time.Now().Unix()
		return nil
	})

	for {
		var msg Message
		err := conn.ReadJSON(&msg)
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
			}
			break
		}

		msg.UserID = user.ID
		msg.RoomID = room.ID

		if err := h.service.HandleMessage(room.ID, user.ID, msg); err != nil {
		}
	}
}

func (h *Handler) writePump(conn *websocket.Conn, user *User, room *Room) {
	ticker := time.NewTicker(time.Duration(h.service.cfg.PingInterval) * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		heartbeat := Message{
			Type:      MessageTypeHeartbeat,
			UserID:    user.ID,
			RoomID:    room.ID,
			Timestamp: time.Now().Unix(),
		}

		conn.SetWriteDeadline(time.Now().Add(time.Duration(h.service.cfg.PingInterval) * time.Second))
		if err := conn.WriteJSON(heartbeat); err != nil {
			return
		}
	}
}

func (h *Handler) UpdateUserFrustum(c *gin.Context) {
	roomID := c.Param("id")
	userID := c.Param("userId")

	var frustumState FrustumState
	if err := c.ShouldBindJSON(&frustumState); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	annotations, err := h.service.UpdateUserFrustum(roomID, userID, frustumState)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	if annotations == nil {
		annotations = []json.RawMessage{}
	}

	c.JSON(http.StatusOK, gin.H{
		"newly_visible": annotations,
		"count":         len(annotations),
	})
}

func (h *Handler) FetchAnnotationsInRegion(c *gin.Context) {
	roomID := c.Param("id")

	var req struct {
		Min math3d.Vec3 `json:"min"`
		Max math3d.Vec3 `json:"max"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	region := math3d.AABB{Min: req.Min, Max: req.Max}

	annotations, err := h.service.FetchAnnotationsInRegion(roomID, region)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	if annotations == nil {
		annotations = []json.RawMessage{}
	}

	c.JSON(http.StatusOK, gin.H{
		"annotations": annotations,
		"count":       len(annotations),
	})
}

func (h *Handler) GetVisibleAnnotations(c *gin.Context) {
	roomID := c.Param("id")
	userID := c.Param("userId")

	visibleIDs, err := h.service.GetVisibleAnnotationsForUser(roomID, userID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
		return
	}

	if visibleIDs == nil {
		visibleIDs = []string{}
	}

	c.JSON(http.StatusOK, gin.H{
		"annotation_ids": visibleIDs,
		"count":          len(visibleIDs),
	})
}
