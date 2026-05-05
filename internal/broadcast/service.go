package broadcast

import (
	"encoding/json"
	"sync"

	"pixelrealm/pkg/models"
)

type BroadcastType int

const (
	BroadcastTypeAOI BroadcastType = iota
	BroadcastTypeMap
	BroadcastTypeWorld
	BroadcastTypePrivate
)

type BroadcastMessage struct {
	EventType string
	Data      interface{}
	Type      BroadcastType
	SourceID  models.PlayerID
	TargetID  models.PlayerID
	MapID     string
	PositionX float64
	PositionY float64
	Radius    float64
}

type BroadcastService struct {
	messageSender MessageSender
	aoiProvider   AOIProvider
	playerFinder  PlayerFinder
	
	stats *BroadcastStats
}

type MessageSender interface {
	SendToPlayer(playerID models.PlayerID, response *models.Response) error
	SendToPlayers(playerIDs []models.PlayerID, response *models.Response)
	Broadcast(response *models.Response)
}

type AOIProvider interface {
	GetAOIPlayers(mapID string, x, y float64) []models.PlayerID
	GetAOIPlayersForBroadcast(attackerID, targetID models.PlayerID) ([]models.PlayerID, error)
	GetPlayersInMap(mapID string) []models.PlayerID
}

type PlayerFinder interface {
	GetPlayerPosition(playerID models.PlayerID) (*models.Position, error)
	GetPlayerMap(playerID models.PlayerID) (string, error)
}

type BroadcastStats struct {
	TotalMessages   int64
	AOIMessages     int64
	MapMessages     int64
	WorldMessages   int64
	PrivateMessages int64
	PlayersReached  int64
	mu              sync.RWMutex
}

func NewBroadcastService(
	sender MessageSender,
	aoiProvider AOIProvider,
	playerFinder PlayerFinder,
) *BroadcastService {
	return &BroadcastService{
		messageSender: sender,
		aoiProvider:   aoiProvider,
		playerFinder:  playerFinder,
		stats: &BroadcastStats{},
	}
}

func (bs *BroadcastService) BroadcastBattleResult(
	attackerID models.PlayerID,
	targetID models.PlayerID,
	damageResult *DamageResult,
) error {
	aoiPlayers, err := bs.aoiProvider.GetAOIPlayersForBroadcast(attackerID, targetID)
	if err != nil {
		return err
	}
	
	resp := models.NewResponse(models.EventBattleResult, models.BattleResultData{
		AttackerID:     damageResult.AttackerID,
		TargetID:       damageResult.TargetID,
		Damage:         damageResult.FinalDamage,
		TargetHPRemain: damageResult.TargetHPRemain,
		IsKill:         damageResult.IsKill,
	})
	
	if len(aoiPlayers) > 0 {
		bs.messageSender.SendToPlayers(aoiPlayers, resp)
		bs.stats.recordAOIBroadcast(len(aoiPlayers))
	}
	
	return nil
}

func (bs *BroadcastService) BroadcastPlayerMove(
	playerID models.PlayerID,
	position models.Position,
) {
	aoiPlayers := bs.aoiProvider.GetAOIPlayers(
		position.MapID,
		position.X,
		position.Y,
	)
	
	resp := models.NewResponse(models.EventPlayerMove, models.PlayerMoveData{
		PlayerID: playerID,
		Position: position,
	})
	
	if len(aoiPlayers) > 0 {
		bs.messageSender.SendToPlayers(aoiPlayers, resp)
		bs.stats.recordAOIBroadcast(len(aoiPlayers))
	}
}

func (bs *BroadcastService) BroadcastPlayerJoin(
	playerID models.PlayerID,
	username string,
	position models.Position,
) {
	aoiPlayers := bs.aoiProvider.GetAOIPlayers(
		position.MapID,
		position.X,
		position.Y,
	)
	
	resp := models.NewResponse(models.EventPlayerJoin, models.PlayerJoinData{
		PlayerID: playerID,
		Username: username,
		Position: position,
	})
	
	var otherPlayers []models.PlayerID
	for _, pid := range aoiPlayers {
		if pid != playerID {
			otherPlayers = append(otherPlayers, pid)
		}
	}
	
	if len(otherPlayers) > 0 {
		bs.messageSender.SendToPlayers(otherPlayers, resp)
		bs.stats.recordAOIBroadcast(len(otherPlayers))
	}
}

func (bs *BroadcastService) BroadcastPlayerLeave(
	playerID models.PlayerID,
	mapID string,
) {
	mapPlayers := bs.aoiProvider.GetPlayersInMap(mapID)
	
	resp := models.NewResponse(models.EventPlayerLeave, models.PlayerLeaveData{
		PlayerID: playerID,
	})
	
	var otherPlayers []models.PlayerID
	for _, pid := range mapPlayers {
		if pid != playerID {
			otherPlayers = append(otherPlayers, pid)
		}
	}
	
	if len(otherPlayers) > 0 {
		bs.messageSender.SendToPlayers(otherPlayers, resp)
		bs.stats.recordMapBroadcast(len(otherPlayers))
	}
}

func (bs *BroadcastService) BroadcastChatMessage(
	playerID models.PlayerID,
	username string,
	channel string,
	content string,
	position models.Position,
	timestamp int64,
) {
	chatData := models.ChatMessageData{
		PlayerID:  playerID,
		Username:  username,
		Channel:   channel,
		Content:   content,
		Timestamp: timestamp,
	}
	
	resp := models.NewResponse(models.EventChatMessage, chatData)
	
	if channel == "world" {
		bs.messageSender.Broadcast(resp)
		bs.stats.recordWorldBroadcast()
	} else {
		aoiPlayers := bs.aoiProvider.GetAOIPlayers(
			position.MapID,
			position.X,
			position.Y,
		)
		
		if len(aoiPlayers) > 0 {
			bs.messageSender.SendToPlayers(aoiPlayers, resp)
			bs.stats.recordAOIBroadcast(len(aoiPlayers))
		}
	}
}

func (bs *BroadcastService) BroadcastSceneEnter(
	playerID models.PlayerID,
	mapID string,
	position models.Position,
	nearbyPlayers []models.PlayerID,
) {
	resp := models.NewResponse(models.EventSceneEnter, models.SceneEnterData{
		MapID:         mapID,
		Position:      position,
		NearbyPlayers: nearbyPlayers,
	})
	
	bs.messageSender.SendToPlayer(playerID, resp)
	bs.stats.recordPrivateBroadcast()
}

func (bs *BroadcastService) SendPrivateMessage(
	targetID models.PlayerID,
	eventType string,
	data interface{},
) error {
	resp := models.NewResponse(eventType, data)
	err := bs.messageSender.SendToPlayer(targetID, resp)
	if err == nil {
		bs.stats.recordPrivateBroadcast()
	}
	return err
}

func (bs *BroadcastService) SendError(
	targetID models.PlayerID,
	code int,
	message string,
) {
	resp := models.NewErrorResponse(code, message)
	bs.messageSender.SendToPlayer(targetID, resp)
	bs.stats.recordPrivateBroadcast()
}

func (bs *BroadcastService) BroadcastMessage(msg *BroadcastMessage) {
	resp := models.NewResponse(msg.EventType, msg.Data)
	
	switch msg.Type {
	case BroadcastTypeAOI:
		aoiPlayers := bs.aoiProvider.GetAOIPlayers(
			msg.MapID,
			msg.PositionX,
			msg.PositionY,
		)
		if len(aoiPlayers) > 0 {
			bs.messageSender.SendToPlayers(aoiPlayers, resp)
			bs.stats.recordAOIBroadcast(len(aoiPlayers))
		}
		
	case BroadcastTypeMap:
		mapPlayers := bs.aoiProvider.GetPlayersInMap(msg.MapID)
		if len(mapPlayers) > 0 {
			bs.messageSender.SendToPlayers(mapPlayers, resp)
			bs.stats.recordMapBroadcast(len(mapPlayers))
		}
		
	case BroadcastTypeWorld:
		bs.messageSender.Broadcast(resp)
		bs.stats.recordWorldBroadcast()
		
	case BroadcastTypePrivate:
		if msg.TargetID != "" {
			bs.messageSender.SendToPlayer(msg.TargetID, resp)
			bs.stats.recordPrivateBroadcast()
		}
	}
}

func (bs *BroadcastService) GetStats() *BroadcastStats {
	bs.stats.mu.RLock()
	defer bs.stats.mu.RUnlock()
	
	return &BroadcastStats{
		TotalMessages:   bs.stats.TotalMessages,
		AOIMessages:     bs.stats.AOIMessages,
		MapMessages:     bs.stats.MapMessages,
		WorldMessages:   bs.stats.WorldMessages,
		PrivateMessages: bs.stats.PrivateMessages,
		PlayersReached:  bs.stats.PlayersReached,
	}
}

func (s *BroadcastStats) recordAOIBroadcast(playerCount int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.TotalMessages++
	s.AOIMessages++
	s.PlayersReached += int64(playerCount)
}

func (s *BroadcastStats) recordMapBroadcast(playerCount int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.TotalMessages++
	s.MapMessages++
	s.PlayersReached += int64(playerCount)
}

func (s *BroadcastStats) recordWorldBroadcast() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.TotalMessages++
	s.WorldMessages++
}

func (s *BroadcastStats) recordPrivateBroadcast() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.TotalMessages++
	s.PrivateMessages++
	s.PlayersReached++
}

type DamageResult struct {
	AttackerID      models.PlayerID
	TargetID        models.PlayerID
	RawDamage       int
	FinalDamage     int
	TargetHPRemain  int
	IsCritical      bool
	IsMiss          bool
	IsKill          bool
	Drops           []DropItem
}

type DropItem struct {
	ItemID string
	Count  int
}

func (dr *DamageResult) ToBattleResultData() *models.BattleResultData {
	return &models.BattleResultData{
		AttackerID:     dr.AttackerID,
		TargetID:       dr.TargetID,
		Damage:         dr.FinalDamage,
		TargetHPRemain: dr.TargetHPRemain,
		IsKill:         dr.IsKill,
	}
}

func ConvertBattleResult(br *battleAttackResult) *DamageResult {
	return &DamageResult{
		AttackerID:     br.AttackerID,
		TargetID:       br.TargetID,
		RawDamage:      br.Damage,
		FinalDamage:    br.Damage,
		TargetHPRemain: br.TargetHPRemain,
		IsCritical:     br.IsCritical,
		IsMiss:         br.IsMiss,
		IsKill:         br.IsKill,
	}
}

type battleAttackResult struct {
	AttackerID     models.PlayerID
	TargetID       models.PlayerID
	Damage         int
	TargetHPRemain int
	IsKill         bool
	IsCritical     bool
	IsMiss         bool
}
