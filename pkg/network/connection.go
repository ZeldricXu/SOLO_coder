package network

import (
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/protocol"
)

type Connection struct {
	UserID    common.UserID
	RoomID    common.RoomID
	WSConn    *websocket.Conn
	SendCh    chan *protocol.Message
	IsObserver bool

	ackMgr    *protocol.AckManager
	lastPing  int64
	mu        sync.Mutex
	closed    bool
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
}

func NewConnection(userID common.UserID, ws *websocket.Conn, isObserver bool) *Connection {
	return &Connection{
		UserID:     userID,
		WSConn:     ws,
		SendCh:     make(chan *protocol.Message, 256),
		IsObserver: isObserver,
		ackMgr:     protocol.NewAckManager(3*time.Second, 3),
		lastPing:   common.NowMs(),
	}
}

func (c *Connection) Send(msg *protocol.Message) {
	if c.closed {
		return
	}
	if msg.NeedAck {
		c.ackMgr.Add(msg)
	}
	select {
	case c.SendCh <- msg:
	default:
		common.LogWarn("connection send channel full for user %s, dropping message", c.UserID)
	}
}

func (c *Connection) SendDirect(msg *protocol.Message) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return nil
	}
	data, err := msg.Marshal()
	if err != nil {
		return err
	}
	return c.WSConn.WriteMessage(websocket.TextMessage, data)
}

func (c *Connection) HandleAck(msgID string) {
	c.ackMgr.Ack(msgID)
}

func (c *Connection) RetryExpired() {
	pending := c.ackMgr.GetExpired()
	for _, pm := range pending {
		common.LogDebug("retrying message %s, attempt %d", pm.Message.MsgID, pm.SendCount)
		c.Send(pm.Message)
	}
}

func (c *Connection) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return
	}
	c.closed = true
	close(c.SendCh)
	c.WSConn.Close()
}

func (c *Connection) IsClosed() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.closed
}

func (c *Connection) UpdatePing() {
	c.lastPing = common.NowMs()
}

func (c *Connection) LastPing() int64 {
	return c.lastPing
}

func (c *Connection) WriteLoop() {
	defer c.Close()

	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	retryTicker := time.NewTicker(1 * time.Second)
	defer retryTicker.Stop()

	for {
		select {
		case msg, ok := <-c.SendCh:
			if !ok {
				return
			}
			if err := c.SendDirect(msg); err != nil {
				common.LogWarn("failed to write message to %s: %v", c.UserID, err)
				return
			}
		case <-ticker.C:
			hb := protocol.NewMessage(protocol.MsgHeartbeat, string(c.RoomID), string(c.UserID), nil)
			if err := c.SendDirect(hb); err != nil {
				return
			}
		case <-retryTicker.C:
			c.RetryExpired()
		}
	}
}

func (c *Connection) ReadLoop(handler func(*Connection, *protocol.Message)) {
	defer c.Close()

	c.WSConn.SetReadLimit(65536)
	c.WSConn.SetPongHandler(func(string) error {
		c.UpdatePing()
		return nil
	})

	for {
		_, data, err := c.WSConn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseNormalClosure) {
				common.LogWarn("websocket error for %s: %v", c.UserID, err)
			}
			return
		}

		msg, err := protocol.UnmarshalMessage(data)
		if err != nil {
			common.LogWarn("failed to unmarshal message from %s: %v", c.UserID, err)
			continue
		}
		c.UpdatePing()

		if msg.Type == protocol.MsgAck {
			c.HandleAck(msg.AckMsgID)
			continue
		}

		if msg.Type == protocol.MsgHeartbeat {
			continue
		}

		if msg.NeedAck {
			ack := protocol.NewAck(msg.MsgID, string(c.UserID))
			c.Send(ack)
		}

		if handler != nil {
			handler(c, msg)
		}
	}
}
