package models

import "encoding/json"

const (
	ActionLogin          = "login"
	ActionHeartbeat      = "heartbeat"
	ActionPlayerMove     = "player_move"
	ActionBattleAttack   = "battle_attack"
	ActionSceneTransfer  = "scene_transfer"
	ActionItemPickup     = "item_pickup"
	ActionItemDrop       = "item_drop"
	ActionItemEquip      = "item_equip"
	ActionChatMessage    = "chat_message"
)

const (
	EventLoginSuccess    = "login_success"
	EventLoginFailed     = "login_failed"
	EventHeartbeat       = "heartbeat_response"
	EventPlayerJoin      = "player_join"
	EventPlayerLeave     = "player_leave"
	EventPlayerMove      = "player_move"
	EventBattleResult    = "battle_result"
	EventSceneEnter      = "scene_enter"
	EventItemPicked      = "item_picked"
	EventItemDropped     = "item_dropped"
	EventItemEquipped    = "item_equipped"
	EventChatMessage     = "chat_message"
	EventError           = "error"
)

type Request struct {
	Action string          `json:"action"`
	Data   json.RawMessage `json:"data"`
}

type Response struct {
	Event string      `json:"event"`
	Data  interface{} `json:"data"`
}

type ErrorData struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type LoginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type LoginSuccessData struct {
	PlayerID PlayerID `json:"player_id"`
	Token    string   `json:"token"`
	Player   *Player  `json:"player"`
}

type LoginFailedData struct {
	Reason string `json:"reason"`
}

type HeartbeatRequest struct {
	Timestamp int64 `json:"timestamp"`
}

type HeartbeatResponseData struct {
	ServerTimestamp int64 `json:"server_timestamp"`
}

type PlayerMoveRequest struct {
	Position Position `json:"position"`
}

type PlayerMoveData struct {
	PlayerID PlayerID `json:"player_id"`
	Position Position `json:"position"`
}

type BattleAttackRequest struct {
	TargetID PlayerID `json:"target_id"`
	SkillID  string   `json:"skill_id"`
}

type BattleResultData struct {
	AttackerID    PlayerID `json:"attacker_id"`
	TargetID      PlayerID `json:"target_id"`
	Damage        int      `json:"damage"`
	TargetHPRemain int     `json:"target_hp_remain"`
	IsKill        bool     `json:"is_kill"`
}

type SceneTransferRequest struct {
	TargetMapID string `json:"target_map_id"`
	EntryPoint  string `json:"entry_point"`
}

type SceneEnterData struct {
	MapID         string     `json:"map_id"`
	Position      Position   `json:"position"`
	NearbyPlayers []PlayerID `json:"nearby_players"`
}

type ItemPickupRequest struct {
	ItemID string `json:"item_id"`
	Count  int    `json:"count"`
}

type ItemDropRequest struct {
	ItemID string `json:"item_id"`
	Count  int    `json:"count"`
}

type ItemEquipRequest struct {
	ItemID string `json:"item_id"`
	Slot   string `json:"slot"`
}

type ItemPickedData struct {
	ItemID string `json:"item_id"`
	Count  int    `json:"count"`
}

type ItemDroppedData struct {
	ItemID string `json:"item_id"`
	Count  int    `json:"count"`
}

type ItemEquippedData struct {
	ItemID  string `json:"item_id"`
	Slot    string `json:"slot"`
	Success bool   `json:"success"`
}

type ChatMessageRequest struct {
	Channel string `json:"channel"`
	Content string `json:"content"`
}

type ChatMessageData struct {
	PlayerID  PlayerID `json:"player_id"`
	Username  string   `json:"username"`
	Channel   string   `json:"channel"`
	Content   string   `json:"content"`
	Timestamp int64    `json:"timestamp"`
}

type PlayerJoinData struct {
	PlayerID PlayerID `json:"player_id"`
	Username string   `json:"username"`
	Position Position `json:"position"`
}

type PlayerLeaveData struct {
	PlayerID PlayerID `json:"player_id"`
}

func NewResponse(event string, data interface{}) *Response {
	return &Response{
		Event: event,
		Data:  data,
	}
}

func NewErrorResponse(code int, message string) *Response {
	return &Response{
		Event: EventError,
		Data: ErrorData{
			Code:    code,
			Message: message,
		},
	}
}
