package network

import (
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/protocol"
)

type ReactorConfig struct {
	ReadWorkers       int
	WriteWorkers      int
	BatchSize         int
	WriteBufferSize   int
	FlushIntervalMs   int
}

func DefaultReactorConfig() *ReactorConfig {
	return &ReactorConfig{
		ReadWorkers:       4,
		WriteWorkers:      8,
		BatchSize:         32,
		WriteBufferSize:   128,
		FlushIntervalMs:   10,
	}
}

type PendingMessage struct {
	Conn    *Connection
	Message *protocol.Message
}

type Reactor struct {
	config       *ReactorConfig
	connections  sync.Map
	writeQueue   chan PendingMessage
	readQueue    chan ReadEvent
	handler      func(common.UserID, common.RoomID, *protocol.Message)

	wg           sync.WaitGroup
	shutdownCh   chan struct{}
	running      atomic.Bool
	connCount    atomic.Int64
}

type ReadEvent struct {
	Conn    *Connection
	Message *protocol.Message
	Err     error
}

func NewReactor(config *ReactorConfig) *Reactor {
	if config == nil {
		config = DefaultReactorConfig()
	}

	r := &Reactor{
		config:     config,
		writeQueue: make(chan PendingMessage, 10000),
		readQueue:  make(chan ReadEvent, 10000),
		shutdownCh: make(chan struct{}),
	}

	return r
}

func (r *Reactor) Start(messageHandler func(common.UserID, common.RoomID, *protocol.Message)) {
	if !r.running.CompareAndSwap(false, true) {
		return
	}

	r.handler = messageHandler

	for i := 0; i < r.config.WriteWorkers; i++ {
		r.wg.Add(1)
		go r.writeWorker(i)
	}

	for i := 0; i < r.config.ReadWorkers; i++ {
		r.wg.Add(1)
		go r.readWorker(i)
	}

	common.LogInfo("connection reactor started: read_workers=%d, write_workers=%d",
		r.config.ReadWorkers, r.config.WriteWorkers)
}

func (r *Reactor) Stop() {
	if !r.running.CompareAndSwap(true, false) {
		return
	}

	close(r.shutdownCh)

	r.connections.Range(func(key, value interface{}) bool {
		if conn, ok := value.(*Connection); ok {
			conn.Close()
		}
		return true
	})

	r.wg.Wait()
	close(r.writeQueue)
	close(r.readQueue)

	common.LogInfo("connection reactor stopped: total_conns_processed=%d", r.connCount.Load())
}

func (r *Reactor) Register(conn *Connection) {
	if conn == nil {
		return
	}

	r.connections.Store(conn.UserID, conn)
	r.connCount.Add(1)

	r.wg.Add(1)
	go r.readLoop(conn)
}

func (r *Reactor) Unregister(userID common.UserID) {
	if value, ok := r.connections.LoadAndDelete(userID); ok {
		if conn, ok := value.(*Connection); ok {
			conn.Close()
		}
	}
}

func (r *Reactor) GetConnection(userID common.UserID) *Connection {
	if value, ok := r.connections.Load(userID); ok {
		if conn, ok := value.(*Connection); ok && !conn.IsClosed() {
			return conn
		}
	}
	return nil
}

func (r *Reactor) SendMessage(userID common.UserID, msg *protocol.Message) bool {
	conn := r.GetConnection(userID)
	if conn == nil {
		return false
	}
	r.EnqueueSend(conn, msg)
	return true
}

func (r *Reactor) EnqueueSend(conn *Connection, msg *protocol.Message) {
	if conn.IsClosed() {
		return
	}

	if msg.NeedAck {
		conn.ackMgr.Add(msg)
	}

	select {
	case r.writeQueue <- PendingMessage{Conn: conn, Message: msg}:
	default:
		common.LogWarn("reactor write queue full, dropping msg for user %s", conn.UserID)
	}
}

func (r *Reactor) BroadcastToRoom(roomID common.RoomID, msg *protocol.Message, excludeObservers bool) {
	count := 0
	r.connections.Range(func(key, value interface{}) bool {
		if conn, ok := value.(*Connection); ok {
			if conn.RoomID == roomID {
				if excludeObservers && conn.IsObserver {
					return true
				}
				if !conn.IsClosed() {
					r.EnqueueSend(conn, msg)
					count++
				}
			}
		}
		return true
	})
	common.LogDebug("broadcast to room %s: %d recipients", roomID, count)
}

func (r *Reactor) BroadcastToObservers(roomID common.RoomID, msg *protocol.Message) {
	r.connections.Range(func(key, value interface{}) bool {
		if conn, ok := value.(*Connection); ok {
			if conn.RoomID == roomID && conn.IsObserver && !conn.IsClosed() {
				r.EnqueueSend(conn, msg)
			}
		}
		return true
	})
}

func (r *Reactor) GetRoomConnections(roomID common.RoomID) []*Connection {
	result := make([]*Connection, 0)
	r.connections.Range(func(key, value interface{}) bool {
		if conn, ok := value.(*Connection); ok {
			if conn.RoomID == roomID && !conn.IsClosed() {
				result = append(result, conn)
			}
		}
		return true
	})
	return result
}

func (r *Reactor) ConnCount() int64 {
	return r.connCount.Load()
}

func (r *Reactor) readLoop(conn *Connection) {
	defer r.wg.Done()

	for {
		select {
		case <-r.shutdownCh:
			return
		default:
		}

		if conn.IsClosed() {
			r.connections.Delete(conn.UserID)
			return
		}

		var rawMsg protocol.Message
		conn.WSConn.SetReadDeadline(time.Now().Add(60 * time.Second))
		err := conn.WSConn.ReadJSON(&rawMsg)
		if err != nil {
			r.readQueue <- ReadEvent{Conn: conn, Err: err}
			return
		}

		conn.lastPing = common.NowMs()

		if rawMsg.Type == protocol.MsgAck {
			conn.HandleAck(rawMsg.MsgID)
			continue
		}

		r.readQueue <- ReadEvent{Conn: conn, Message: &rawMsg}
	}
}

func (r *Reactor) readWorker(workerID int) {
	defer r.wg.Done()
	common.LogDebug("read worker %d started", workerID)

	for {
		select {
		case <-r.shutdownCh:
			common.LogDebug("read worker %d stopped", workerID)
			return
		case event, ok := <-r.readQueue:
			if !ok {
				return
			}
			if event.Err != nil {
				r.handleReadError(event.Conn, event.Err)
				continue
			}
			if event.Message != nil && r.handler != nil {
				r.handler(event.Conn.UserID, event.Conn.RoomID, event.Message)
			}
		}
	}
}

func (r *Reactor) writeWorker(workerID int) {
	defer r.wg.Done()
	common.LogDebug("write worker %d started", workerID)

	flushTicker := time.NewTicker(time.Duration(r.config.FlushIntervalMs) * time.Millisecond)
	defer flushTicker.Stop()

	batch := make([]PendingMessage, 0, r.config.BatchSize)

	flushBatch := func() {
		if len(batch) == 0 {
			return
		}
		for _, pm := range batch {
			r.doWrite(pm.Conn, pm.Message)
		}
		batch = batch[:0]
	}

	for {
		select {
		case <-r.shutdownCh:
			flushBatch()
			common.LogDebug("write worker %d stopped", workerID)
			return
		case pm, ok := <-r.writeQueue:
			if !ok {
				flushBatch()
				return
			}
			batch = append(batch, pm)
			if len(batch) >= r.config.BatchSize {
				flushBatch()
			}
		case <-flushTicker.C:
			flushBatch()
		}
	}
}

func (r *Reactor) doWrite(conn *Connection, msg *protocol.Message) {
	if conn.IsClosed() {
		return
	}

	data, err := msg.Marshal()
	if err != nil {
		common.LogWarn("marshal msg failed: %v", err)
		return
	}

	conn.mu.Lock()
	if conn.closed {
		conn.mu.Unlock()
		return
	}
	conn.WSConn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	err = conn.WSConn.WriteMessage(websocket.TextMessage, data)
	conn.mu.Unlock()

	if err != nil {
		common.LogWarn("write to user %s failed: %v, closing connection", conn.UserID, err)
		conn.Close()
		r.connections.Delete(conn.UserID)
	}
}

func (r *Reactor) handleReadError(conn *Connection, err error) {
	common.LogDebug("read error from user %s: %v", conn.UserID, err)
	conn.Close()
	r.connections.Delete(conn.UserID)
}

func (r *Reactor) RetryExpiredAcks() {
	now := common.NowMs()
	r.connections.Range(func(key, value interface{}) bool {
		if conn, ok := value.(*Connection); ok && !conn.IsClosed() {
			expired := conn.ackMgr.GetExpiredAt(now)
			for _, pm := range expired {
			if pm.SendCount < conn.ackMgr.MaxRetries() {
					r.EnqueueSend(conn, pm.Message)
				}
			}
		}
		return true
	})
}

func (r *Reactor) StartAckReticker(interval time.Duration) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for {
			select {
			case <-r.shutdownCh:
				return
			case <-ticker.C:
				r.RetryExpiredAcks()
			}
		}
	}()
}
