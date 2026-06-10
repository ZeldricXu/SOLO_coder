package room

import (
	"sync"
	"time"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
)

type Manager struct {
	rooms        map[common.RoomID]*Room
	inviteIndex  map[string]common.RoomID
	userRoomMap  map[common.UserID]common.RoomID

	mu           sync.RWMutex
	cleanupTicker *time.Ticker
}

func NewManager() *Manager {
	m := &Manager{
		rooms:       make(map[common.RoomID]*Room),
		inviteIndex: make(map[string]common.RoomID),
		userRoomMap: make(map[common.UserID]common.RoomID),
	}
	go m.startCleanup()
	return m
}

func (m *Manager) startCleanup() {
	m.cleanupTicker = time.NewTicker(5 * time.Minute)
	defer m.cleanupTicker.Stop()

	for range m.cleanupTicker.C {
		m.cleanupDisbanded()
	}
}

func (m *Manager) cleanupDisbanded() {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()
	for id, r := range m.rooms {
		if (r.State == common.StateDisbanded || r.State == common.StateFinished) &&
			now.Sub(r.UpdatedAt) > 30*time.Minute {
			if r.Config.InviteCode != "" {
				delete(m.inviteIndex, r.Config.InviteCode)
			}
			for uid := range r.Players {
				if m.userRoomMap[uid] == id {
					delete(m.userRoomMap, uid)
				}
			}
			delete(m.rooms, id)
			common.LogInfo("cleaned up room %s", id)
		}
	}
}

func (m *Manager) CreateRoom(config *common.RoomConfig, host *common.Player) (*Room, error) {
	rule, err := game.GetRule(config.GameType)
	if err != nil {
		return nil, err
	}

	roomID := common.GenerateRoomID()
	r := NewRoom(roomID, config, rule)

	if err := r.AddPlayer(host); err != nil {
		return nil, err
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	m.rooms[roomID] = r
	m.userRoomMap[host.UserID] = roomID
	if config.InviteCode != "" {
		m.inviteIndex[config.InviteCode] = roomID
	}

	common.LogInfo("room created: %s, type=%s, host=%s", roomID, config.GameType, host.UserID)
	return r, nil
}

func (m *Manager) GetRoom(roomID common.RoomID) (*Room, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	r, ok := m.rooms[roomID]
	return r, ok
}

func (m *Manager) GetRoomByInvite(code string) (*Room, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	roomID, ok := m.inviteIndex[code]
	if !ok {
		return nil, false
	}
	r, ok := m.rooms[roomID]
	return r, ok
}

func (m *Manager) GetUserRoom(userID common.UserID) (*Room, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	roomID, ok := m.userRoomMap[userID]
	if !ok {
		return nil, false
	}
	r, ok := m.rooms[roomID]
	return r, ok
}

func (m *Manager) JoinRoom(roomID common.RoomID, player *common.Player) (*Room, error) {
	m.mu.Lock()
	r, ok := m.rooms[roomID]
	m.mu.Unlock()

	if !ok {
		return nil, common.ErrRoomNotFound
	}

	if err := r.AddPlayer(player); err != nil {
		return nil, err
	}

	m.mu.Lock()
	m.userRoomMap[player.UserID] = roomID
	m.mu.Unlock()

	return r, nil
}

func (m *Manager) JoinRoomByInvite(code string, player *common.Player) (*Room, error) {
	m.mu.RLock()
	roomID, ok := m.inviteIndex[code]
	m.mu.RUnlock()

	if !ok {
		return nil, common.ErrInvalidInviteCode
	}
	return m.JoinRoom(roomID, player)
}

func (m *Manager) LeaveRoom(roomID common.RoomID, userID common.UserID) error {
	m.mu.RLock()
	r, ok := m.rooms[roomID]
	m.mu.RUnlock()

	if !ok {
		return common.ErrRoomNotFound
	}

	_, err := r.RemovePlayer(userID, true)
	if err != nil {
		return err
	}

	m.mu.Lock()
	if curRoom, exists := m.userRoomMap[userID]; exists && curRoom == roomID {
		delete(m.userRoomMap, userID)
	}
	m.mu.Unlock()

	common.LogInfo("player %s left room %s", userID, roomID)
	return nil
}

func (m *Manager) DisbandRoom(roomID common.RoomID, userID common.UserID) error {
	m.mu.RLock()
	r, ok := m.rooms[roomID]
	m.mu.RUnlock()

	if !ok {
		return common.ErrRoomNotFound
	}
	if r.HostID != userID {
		return common.ErrNotHost
	}

	r.Disband("host disbanded")

	m.mu.Lock()
	if r.Config.InviteCode != "" {
		delete(m.inviteIndex, r.Config.InviteCode)
	}
	for uid := range r.Players {
		if m.userRoomMap[uid] == roomID {
			delete(m.userRoomMap, uid)
		}
	}
	m.mu.Unlock()

	common.LogInfo("room %s disbanded by %s", roomID, userID)
	return nil
}

func (m *Manager) ListRooms(gameType common.GameType) []*Room {
	m.mu.RLock()
	defer m.mu.RUnlock()

	result := make([]*Room, 0)
	for _, r := range m.rooms {
		if gameType != "" && r.Config.GameType != gameType {
			continue
		}
		if r.State == common.StateWaiting {
			result = append(result, r)
		}
	}
	return result
}

func (m *Manager) Count() int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.rooms)
}
