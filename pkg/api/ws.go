package api

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/network"
	"github.com/studio/gameroom/pkg/observer"
	"github.com/studio/gameroom/pkg/protocol"
	"github.com/studio/gameroom/pkg/room"
)

var wsUpgrader = websocket.Upgrader{
	ReadBufferSize:  1024,
	WriteBufferSize: 1024,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}

type WSServer struct {
	ConnMgr        *network.ConnectionManager
	RoomManager    *room.Manager
	ObserverMgr    *observer.Manager
	InteractionMgr *observer.InteractionManager
}

func NewWSServer(connMgr *network.ConnectionManager, rm *room.Manager, obs *observer.Manager, im *observer.InteractionManager) *WSServer {
	return &WSServer{
		ConnMgr:        connMgr,
		RoomManager:    rm,
		ObserverMgr:    obs,
		InteractionMgr: im,
	}
}

func (s *WSServer) HandleWebSocket(w http.ResponseWriter, r *http.Request) {
	userID := common.UserID(r.URL.Query().Get("user_id"))
	roomID := common.RoomID(r.URL.Query().Get("room_id"))
	isObserver := r.URL.Query().Get("observer") == "1"

	if userID == "" {
		http.Error(w, "missing user_id", http.StatusBadRequest)
		return
	}

	ws, err := wsUpgrader.Upgrade(w, r, nil)
	if err != nil {
		common.LogError("websocket upgrade failed: %v", err)
		return
	}

	conn := network.NewConnection(userID, ws, isObserver)
	conn.RoomID = roomID
	s.ConnMgr.Add(conn)

	common.LogInfo("websocket connected: user=%s, room=%s, observer=%v", userID, roomID, isObserver)

	if isObserver && roomID != "" {
		s.ObserverMgr.AddObserver(roomID, userID)
	}

	go conn.WriteLoop()
	conn.ReadLoop(func(c *network.Connection, msg *protocol.Message) {
		s.handleMessage(c, msg)
	})

	s.ConnMgr.Remove(userID)
	if isObserver && roomID != "" {
		s.ObserverMgr.RemoveObserver(roomID, userID)
	}

	common.LogInfo("websocket disconnected: user=%s", userID)
}

func (s *WSServer) handleMessage(conn *network.Connection, msg *protocol.Message) {
	roomID := common.RoomID(msg.RoomID)
	userID := common.UserID(msg.UserID)

	switch msg.Type {
	case protocol.MsgAction:
		s.handleAction(roomID, userID, msg)
	case protocol.MsgChat:
		s.handleChat(roomID, userID, msg)
	case protocol.MsgDanmaku:
		s.handleDanmaku(roomID, userID, msg)
	case protocol.MsgGift:
		s.handleGift(roomID, userID, msg)
	case protocol.MsgLeaveRoom:
		s.ConnMgr.Remove(userID)
	}
}

func (s *WSServer) handleAction(roomID common.RoomID, userID common.UserID, msg *protocol.Message) {
	rm, ok := s.RoomManager.GetRoom(roomID)
	if !ok {
		resp := protocol.NewMessage(protocol.MsgError, string(roomID), string(userID),
			map[string]string{"error": "room not found"})
		s.ConnMgr.SendToUser(userID, resp)
		return
	}

	var actionData struct {
		ActionType string                 `json:"action_type"`
		Data       map[string]interface{} `json:"data"`
	}
	if len(msg.Payload) > 0 {
		if err := json.Unmarshal(msg.Payload, &actionData); err != nil {
			common.LogWarn("failed to unmarshal action: %v", err)
		}
	}

	action := &common.GameAction{
		ActionID:   common.GenerateID(),
		RoomID:     roomID,
		UserID:     userID,
		ActionType: common.ActionType(actionData.ActionType),
		Data:       actionData.Data,
		Timestamp:  time.Now(),
	}

	applied, err := rm.HandleAction(action)
	if err != nil {
		resp := protocol.NewMessage(protocol.MsgError, string(roomID), string(userID),
			map[string]string{"error": err.Error()})
		resp.NeedAck = true
		s.ConnMgr.SendToUser(userID, resp)
		return
	}

	result := protocol.NewMessage(protocol.MsgActionResult, string(roomID), string(userID), applied)
	result.NeedAck = true
	s.ConnMgr.BroadcastToRoom(roomID, result)
	s.ObserverMgr.BroadcastToObservers(roomID, result)

	if rm.GetState() == common.StateSettling {
		settlement, err := rm.Settle()
		if err == nil {
			settleMsg := protocol.NewMessage(protocol.MsgSettlement, string(roomID), "", settlement)
			settleMsg.NeedAck = true
			s.ConnMgr.BroadcastToRoom(roomID, settleMsg)
			s.ObserverMgr.BroadcastToObservers(roomID, settleMsg)
		}
	}
}

func (s *WSServer) handleChat(roomID common.RoomID, userID common.UserID, msg *protocol.Message) {
	var chat struct {
		Content  string `json:"content"`
		Nickname string `json:"nickname"`
	}
	if len(msg.Payload) > 0 {
		json.Unmarshal(msg.Payload, &chat)
	}

	payload := map[string]interface{}{
		"user_id":   userID,
		"nickname":  chat.Nickname,
		"content":   chat.Content,
		"timestamp": time.Now().Unix(),
	}
	broadcast := protocol.NewMessage(protocol.MsgChat, string(roomID), string(userID), payload)
	s.ConnMgr.BroadcastToRoom(roomID, broadcast)
	s.ObserverMgr.BroadcastToObservers(roomID, broadcast)
}

func (s *WSServer) handleDanmaku(roomID common.RoomID, userID common.UserID, msg *protocol.Message) {
	if s.InteractionMgr == nil {
		return
	}
	var danmaku struct {
		Content  string `json:"content"`
		Nickname string `json:"nickname"`
		Color    string `json:"color"`
	}
	if len(msg.Payload) > 0 {
		json.Unmarshal(msg.Payload, &danmaku)
	}

	d := observer.Danmaku{
		ID:        common.GenerateID(),
		RoomID:    roomID,
		UserID:    userID,
		Nickname:  danmaku.Nickname,
		Content:   danmaku.Content,
		Color:     danmaku.Color,
		Timestamp: common.NowMs(),
	}
	s.InteractionMgr.AddDanmaku(d)

	broadcast := protocol.NewMessage(protocol.MsgDanmaku, string(roomID), string(userID), d)
	s.ObserverMgr.BroadcastToObservers(roomID, broadcast)
}

func (s *WSServer) handleGift(roomID common.RoomID, userID common.UserID, msg *protocol.Message) {
	if s.InteractionMgr == nil {
		return
	}
	var gift struct {
		GiftID   string `json:"gift_id"`
		GiftName string `json:"gift_name"`
		Count    int    `json:"count"`
		Nickname string `json:"nickname"`
	}
	if len(msg.Payload) > 0 {
		json.Unmarshal(msg.Payload, &gift)
	}

	g := observer.Gift{
		ID:        common.GenerateID(),
		RoomID:    roomID,
		UserID:    userID,
		Nickname:  gift.Nickname,
		GiftID:    gift.GiftID,
		GiftName:  gift.GiftName,
		Count:     gift.Count,
		Timestamp: common.NowMs(),
	}
	s.InteractionMgr.AddGift(g)

	broadcast := protocol.NewMessage(protocol.MsgGift, string(roomID), string(userID), g)
	s.ObserverMgr.BroadcastToObservers(roomID, broadcast)
}
