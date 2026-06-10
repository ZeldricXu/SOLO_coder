package protocol

import (
	"encoding/json"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"github.com/studio/gameroom/pkg/common"
)

type MessageType int32

const (
	MsgUnknown     MessageType = 0
	MsgAck         MessageType = 1
	MsgHeartbeat   MessageType = 2
	MsgJoinRoom    MessageType = 10
	MsgLeaveRoom   MessageType = 11
	MsgRoomState   MessageType = 12
	MsgPlayerJoined MessageType = 13
	MsgPlayerLeft  MessageType = 14
	MsgPlayerReady MessageType = 15
	MsgGameStart   MessageType = 20
	MsgAction      MessageType = 21
	MsgActionResult MessageType = 22
	MsgBroadcast   MessageType = 23
	MsgDealCards   MessageType = 24
	MsgSettlement  MessageType = 25
	MsgGameOver    MessageType = 26
	MsgChat        MessageType = 30
	MsgObserverJoin  MessageType = 40
	MsgObserverLeave MessageType = 41
	MsgDanmaku     MessageType = 42
	MsgGift        MessageType = 43
	MsgError       MessageType = 99
)

type Message struct {
	Type      MessageType         `json:"type"`
	MsgID     string              `json:"msg_id"`
	RoomID    string              `json:"room_id,omitempty"`
	UserID    string              `json:"user_id,omitempty"`
	Seq       int64               `json:"seq,omitempty"`
	Timestamp int64               `json:"timestamp"`
	Payload   json.RawMessage     `json:"payload,omitempty"`
	NeedAck   bool                `json:"need_ack,omitempty"`
	AckMsgID  string              `json:"ack_msg_id,omitempty"`
}

var globalSeq int64

func NextSeq() int64 {
	return atomic.AddInt64(&globalSeq, 1)
}

func NewMessage(msgType MessageType, roomID, userID string, payload interface{}) *Message {
	msg := &Message{
		Type:      msgType,
		MsgID:     uuid.New().String(),
		RoomID:    roomID,
		UserID:    userID,
		Seq:       NextSeq(),
		Timestamp: common.NowMs(),
	}
	if payload != nil {
		data, err := json.Marshal(payload)
		if err == nil {
			msg.Payload = data
		}
	}
	return msg
}

func NewAck(msgID, userID string) *Message {
	return &Message{
		Type:      MsgAck,
		MsgID:     uuid.New().String(),
		UserID:    userID,
		AckMsgID:  msgID,
		Timestamp: common.NowMs(),
	}
}

func (m *Message) Marshal() ([]byte, error) {
	return json.Marshal(m)
}

func UnmarshalMessage(data []byte) (*Message, error) {
	var msg Message
	if err := json.Unmarshal(data, &msg); err != nil {
		return nil, err
	}
	return &msg, nil
}

type PendingMessage struct {
	Message    *Message
	SendCount  int
	LastSentAt int64
	MaxRetries int
}

type AckManager struct {
	pending map[string]*PendingMessage
	mu      sync.RWMutex
	timeout time.Duration
	maxRetries int
}

func NewAckManager(timeout time.Duration, maxRetries int) *AckManager {
	return &AckManager{
		pending:    make(map[string]*PendingMessage),
		timeout:    timeout,
		maxRetries: maxRetries,
	}
}

func (am *AckManager) Add(msg *Message) {
	am.mu.Lock()
	defer am.mu.Unlock()
	am.pending[msg.MsgID] = &PendingMessage{
		Message:    msg,
		SendCount:  1,
		LastSentAt: common.NowMs(),
		MaxRetries: am.maxRetries,
	}
}

func (am *AckManager) Ack(msgID string) bool {
	am.mu.Lock()
	defer am.mu.Unlock()
	if _, ok := am.pending[msgID]; ok {
		delete(am.pending, msgID)
		return true
	}
	return false
}

func (am *AckManager) GetExpired() []*PendingMessage {
	am.mu.Lock()
	defer am.mu.Unlock()

	expired := make([]*PendingMessage, 0)
	now := common.NowMs()
	for id, pm := range am.pending {
		if now-pm.LastSentAt >= am.timeout.Milliseconds() {
			if pm.SendCount < pm.MaxRetries {
				pm.SendCount++
				pm.LastSentAt = now
				expired = append(expired, pm)
			} else {
				delete(am.pending, id)
				common.LogWarn("message %s dropped after %d retries", id, pm.MaxRetries)
			}
		}
	}
	return expired
}

func (am *AckManager) Size() int {
	am.mu.RLock()
	defer am.mu.RUnlock()
	return len(am.pending)
}
