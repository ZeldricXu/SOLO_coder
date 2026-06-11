package observer

import (
	"sync"
	"time"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/protocol"
)

type DelayedMessage struct {
	Message    *protocol.Message
	Timestamp  int64
	TargetUser common.UserID
}

type DelayBuffer struct {
	roomID    common.RoomID
	delaySec  int
	buffer    []DelayedMessage
	mu        sync.Mutex
	observers map[common.UserID]bool
}

func NewDelayBuffer(roomID common.RoomID, delaySec int) *DelayBuffer {
	if delaySec <= 0 {
		delaySec = 5
	}
	return &DelayBuffer{
		roomID:    roomID,
		delaySec:  delaySec,
		buffer:    make([]DelayedMessage, 0),
		observers: make(map[common.UserID]bool),
	}
}

func (db *DelayBuffer) Enqueue(msg *protocol.Message, targetUser common.UserID) {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.buffer = append(db.buffer, DelayedMessage{
		Message:    msg,
		Timestamp:  common.NowMs(),
		TargetUser: targetUser,
	})
}

func (db *DelayBuffer) DrainReady() []DelayedMessage {
	db.mu.Lock()
	defer db.mu.Unlock()

	threshold := common.NowMs() - int64(db.delaySec*1000)
	ready := make([]DelayedMessage, 0)
	remaining := make([]DelayedMessage, 0, len(db.buffer))

	for _, dm := range db.buffer {
		if dm.Timestamp <= threshold {
			ready = append(ready, dm)
		} else {
			remaining = append(remaining, dm)
		}
	}
	db.buffer = remaining
	return ready
}

func (db *DelayBuffer) AddObserver(userID common.UserID) {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.observers[userID] = true
}

func (db *DelayBuffer) RemoveObserver(userID common.UserID) {
	db.mu.Lock()
	defer db.mu.Unlock()
	delete(db.observers, userID)
}

func (db *DelayBuffer) ObserverCount() int {
	db.mu.Lock()
	defer db.mu.Unlock()
	return len(db.observers)
}

type Manager struct {
	buffers    map[common.RoomID]*DelayBuffer
	delaySec   int
	mu         sync.RWMutex
	onMessage  func(common.UserID, *protocol.Message)
	runningMu  sync.Mutex
	running    bool
	stopCh     chan struct{}
}

func NewManager(delaySec int) *Manager {
	if delaySec <= 0 {
		delaySec = 5
	}
	return &Manager{
		buffers:  make(map[common.RoomID]*DelayBuffer),
		delaySec: delaySec,
	}
}

func (m *Manager) SetMessageHandler(fn func(common.UserID, *protocol.Message)) {
	m.onMessage = fn
}

func (m *Manager) getOrCreateBuffer(roomID common.RoomID) *DelayBuffer {
	m.mu.Lock()
	defer m.mu.Unlock()

	if buf, ok := m.buffers[roomID]; ok {
		return buf
	}
	buf := NewDelayBuffer(roomID, m.delaySec)
	m.buffers[roomID] = buf
	return buf
}

func (m *Manager) BroadcastToObservers(roomID common.RoomID, msg *protocol.Message) {
	buf := m.getOrCreateBuffer(roomID)
	buf.Enqueue(msg, "")
}

func (m *Manager) SendToObserver(roomID common.RoomID, userID common.UserID, msg *protocol.Message) {
	buf := m.getOrCreateBuffer(roomID)
	buf.Enqueue(msg, userID)
}

func (m *Manager) AddObserver(roomID common.RoomID, userID common.UserID) {
	buf := m.getOrCreateBuffer(roomID)
	buf.AddObserver(userID)
}

func (m *Manager) RemoveObserver(roomID common.RoomID, userID common.UserID) {
	m.mu.RLock()
	buf, ok := m.buffers[roomID]
	m.mu.RUnlock()
	if ok {
		buf.RemoveObserver(userID)
	}
}

func (m *Manager) StartDrainLoop() {
	m.runningMu.Lock()
	if m.running {
		m.runningMu.Unlock()
		return
	}
	m.running = true
	m.stopCh = make(chan struct{})
	stop := m.stopCh
	m.runningMu.Unlock()

	go func() {
		ticker := time.NewTicker(500 * time.Millisecond)
		defer ticker.Stop()

		for {
			select {
			case <-stop:
				return
			case <-ticker.C:
				m.drainAll()
			}
		}
	}()
}

func (m *Manager) StopDrainLoop() {
	m.runningMu.Lock()
	defer m.runningMu.Unlock()
	if !m.running {
		return
	}
	m.running = false
	if m.stopCh != nil {
		close(m.stopCh)
		m.stopCh = nil
	}
}

func (m *Manager) drainAll() {
	m.mu.RLock()
	rooms := make([]common.RoomID, 0, len(m.buffers))
	buffers := make([]*DelayBuffer, 0, len(m.buffers))
	for id, buf := range m.buffers {
		rooms = append(rooms, id)
		buffers = append(buffers, buf)
	}
	m.mu.RUnlock()

	for i, buf := range buffers {
		ready := buf.DrainReady()
		for _, dm := range ready {
			if m.onMessage != nil {
				if dm.TargetUser != "" {
					m.onMessage(dm.TargetUser, dm.Message)
				} else {
					buf.mu.Lock()
					for uid := range buf.observers {
						m.onMessage(uid, dm.Message)
					}
					buf.mu.Unlock()
				}
			}
		}
		_ = i
	}
}

func (m *Manager) ObserverCount(roomID common.RoomID) int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	if buf, ok := m.buffers[roomID]; ok {
		return buf.ObserverCount()
	}
	return 0
}

type Danmaku struct {
	ID        string
	RoomID    common.RoomID
	UserID    common.UserID
	Nickname  string
	Content   string
	Color     string
	Timestamp int64
}

type Gift struct {
	ID        string
	RoomID    common.RoomID
	UserID    common.UserID
	Nickname  string
	GiftID    string
	GiftName  string
	Count     int
	Value     int64
	Timestamp int64
}

type InteractionManager struct {
	danmakus    map[common.RoomID][]Danmaku
	gifts       map[common.RoomID][]Gift
	maxDanmaku  int
	maxGift     int
	mu          sync.RWMutex
}

func NewInteractionManager() *InteractionManager {
	return &InteractionManager{
		danmakus:   make(map[common.RoomID][]Danmaku),
		gifts:      make(map[common.RoomID][]Gift),
		maxDanmaku: 200,
		maxGift:    100,
	}
}

func (im *InteractionManager) AddDanmaku(d Danmaku) {
	im.mu.Lock()
	defer im.mu.Unlock()

	list := im.danmakus[d.RoomID]
	list = append(list, d)
	if len(list) > im.maxDanmaku {
		list = list[len(list)-im.maxDanmaku:]
	}
	im.danmakus[d.RoomID] = list
}

func (im *InteractionManager) AddGift(g Gift) {
	im.mu.Lock()
	defer im.mu.Unlock()

	list := im.gifts[g.RoomID]
	list = append(list, g)
	if len(list) > im.maxGift {
		list = list[len(list)-im.maxGift:]
	}
	im.gifts[g.RoomID] = list
}

func (im *InteractionManager) GetRecentDanmakus(roomID common.RoomID, limit int) []Danmaku {
	im.mu.RLock()
	defer im.mu.RUnlock()

	list, ok := im.danmakus[roomID]
	if !ok {
		return nil
	}
	if limit <= 0 || limit > len(list) {
		limit = len(list)
	}
	return list[len(list)-limit:]
}

func (im *InteractionManager) GetRecentGifts(roomID common.RoomID, limit int) []Gift {
	im.mu.RLock()
	defer im.mu.RUnlock()

	list, ok := im.gifts[roomID]
	if !ok {
		return nil
	}
	if limit <= 0 || limit > len(list) {
		limit = len(list)
	}
	return list[len(list)-limit:]
}
