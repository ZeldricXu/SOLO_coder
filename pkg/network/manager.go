package network

import (
	"sync"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/protocol"
)

type ConnectionManager struct {
	connections map[common.UserID]*Connection
	roomUsers   map[common.RoomID]map[common.UserID]bool

	mu sync.RWMutex
}

func NewConnectionManager() *ConnectionManager {
	return &ConnectionManager{
		connections: make(map[common.UserID]*Connection),
		roomUsers:   make(map[common.RoomID]map[common.UserID]bool),
	}
}

func (cm *ConnectionManager) Add(conn *Connection) {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	if old, ok := cm.connections[conn.UserID]; ok {
		old.Close()
	}
	cm.connections[conn.UserID] = conn

	if conn.RoomID != "" {
		if _, ok := cm.roomUsers[conn.RoomID]; !ok {
			cm.roomUsers[conn.RoomID] = make(map[common.UserID]bool)
		}
		cm.roomUsers[conn.RoomID][conn.UserID] = true
	}
}

func (cm *ConnectionManager) Remove(userID common.UserID) {
	cm.mu.Lock()
	defer cm.mu.Unlock()

	if conn, ok := cm.connections[userID]; ok {
		if conn.RoomID != "" {
			if users, ok := cm.roomUsers[conn.RoomID]; ok {
				delete(users, userID)
				if len(users) == 0 {
					delete(cm.roomUsers, conn.RoomID)
				}
			}
		}
		conn.Close()
		delete(cm.connections, userID)
	}
}

func (cm *ConnectionManager) Get(userID common.UserID) (*Connection, bool) {
	cm.mu.RLock()
	defer cm.mu.RUnlock()
	conn, ok := cm.connections[userID]
	return conn, ok
}

func (cm *ConnectionManager) BroadcastToRoom(roomID common.RoomID, msg *protocol.Message) {
	cm.mu.RLock()
	users, ok := cm.roomUsers[roomID]
	conns := make([]*Connection, 0, len(users))
	if ok {
		for uid := range users {
			if c, exists := cm.connections[uid]; exists {
				conns = append(conns, c)
			}
		}
	}
	cm.mu.RUnlock()

	for _, c := range conns {
		c.Send(msg)
	}
}

func (cm *ConnectionManager) BroadcastToPlayers(roomID common.RoomID, msg *protocol.Message) {
	cm.mu.RLock()
	users, ok := cm.roomUsers[roomID]
	conns := make([]*Connection, 0)
	if ok {
		for uid := range users {
			if c, exists := cm.connections[uid]; exists && !c.IsObserver {
				conns = append(conns, c)
			}
		}
	}
	cm.mu.RUnlock()

	for _, c := range conns {
		c.Send(msg)
	}
}

func (cm *ConnectionManager) BroadcastToObservers(roomID common.RoomID, msg *protocol.Message) {
	cm.mu.RLock()
	users, ok := cm.roomUsers[roomID]
	conns := make([]*Connection, 0)
	if ok {
		for uid := range users {
			if c, exists := cm.connections[uid]; exists && c.IsObserver {
				conns = append(conns, c)
			}
		}
	}
	cm.mu.RUnlock()

	for _, c := range conns {
		c.Send(msg)
	}
}

func (cm *ConnectionManager) SendToUser(userID common.UserID, msg *protocol.Message) bool {
	cm.mu.RLock()
	conn, ok := cm.connections[userID]
	cm.mu.RUnlock()

	if ok {
		conn.Send(msg)
		return true
	}
	return false
}

func (cm *ConnectionManager) CountInRoom(roomID common.RoomID) int {
	cm.mu.RLock()
	defer cm.mu.RUnlock()
	if users, ok := cm.roomUsers[roomID]; ok {
		return len(users)
	}
	return 0
}

func (cm *ConnectionManager) TotalCount() int {
	cm.mu.RLock()
	defer cm.mu.RUnlock()
	return len(cm.connections)
}
