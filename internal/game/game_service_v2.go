package game

import (
	"context"
	"encoding/json"
	"time"

	"pixelrealm/internal/auth"
	"pixelrealm/internal/battle"
	"pixelrealm/internal/broadcast"
	"pixelrealm/internal/network"
	"pixelrealm/internal/persistence"
	"pixelrealm/internal/scene"
	"pixelrealm/pkg/config"
	"pixelrealm/pkg/models"

	"github.com/olahol/melody"
	"github.com/google/uuid"
)

type GameServiceV2 struct {
	config         *config.Config
	webSocketServer *network.WebSocketServer
	tokenManager   *auth.TokenManager
	passwordHasher *auth.PasswordHasher
	sessionManager *auth.SessionManager
	playerCache    *persistence.PlayerCache
	sceneManager   *scene.SceneManagerV2
	battleService  *battle.BattleService
	broadcastService *broadcast.BroadcastService
	itemManager    *models.ItemManager
}

type messageSenderAdapter struct {
	wsServer *network.WebSocketServer
}

func (a *messageSenderAdapter) SendToPlayer(playerID models.PlayerID, response *models.Response) error {
	return a.wsServer.SendToPlayer(playerID, response)
}

func (a *messageSenderAdapter) SendToPlayers(playerIDs []models.PlayerID, response *models.Response) {
	a.wsServer.SendToPlayers(playerIDs, response)
}

func (a *messageSenderAdapter) Broadcast(response *models.Response) {
	a.wsServer.Broadcast(response)
}

func NewGameServiceV2(
	cfg *config.Config,
	playerCache *persistence.PlayerCache,
) *GameServiceV2 {
	itemManager := models.NewItemManager()
	
	sceneManager := scene.NewSceneManagerV2WithConfig(
		playerCache,
		scene.DefaultGridSize,
		scene.DefaultAOIRadius,
		scene.AOIHybrid,
	)
	
	battleService := battle.NewBattleService(
		&cfg.Game,
		itemManager,
		playerCache,
		sceneManager,
	)
	
	gs := &GameServiceV2{
		config:         cfg,
		tokenManager:   auth.NewTokenManager(&cfg.JWT),
		passwordHasher: auth.NewPasswordHasher(nil),
		sessionManager: auth.NewSessionManager(24*time.Hour, 30*time.Minute),
		playerCache:    playerCache,
		sceneManager:   sceneManager,
		battleService:  battleService,
		itemManager:    itemManager,
	}
	
	gs.webSocketServer = network.NewWebSocketServer(&cfg.WebSocket)
	
	sender := &messageSenderAdapter{wsServer: gs.webSocketServer}
	gs.broadcastService = broadcast.NewBroadcastService(sender, sceneManager, sceneManager)
	
	return gs
}

func (gs *GameServiceV2) Initialize() error {
	gs.webSocketServer.RegisterHandlers()
	gs.registerMessageHandlers()
	return nil
}

func (gs *GameServiceV2) GetWebSocketServer() *network.WebSocketServer {
	return gs.webSocketServer
}

func (gs *GameServiceV2) GetSceneManager() *scene.SceneManagerV2 {
	return gs.sceneManager
}

func (gs *GameServiceV2) GetBattleService() *battle.BattleService {
	return gs.battleService
}

func (gs *GameServiceV2) GetBroadcastService() *broadcast.BroadcastService {
	return gs.broadcastService
}

func (gs *GameServiceV2) GetItemManager() *models.ItemManager {
	return gs.itemManager
}

func (gs *GameServiceV2) registerMessageHandlers() {
	router := gs.webSocketServer.GetMessageRouter()
	if router == nil {
		router = network.NewMessageRouter()
		gs.webSocketServer.SetMessageRouter(router)
	}
	
	router.Register(models.ActionLogin, gs.handleLogin)
	router.Register(models.ActionPlayerMove, gs.handlePlayerMove)
	router.Register(models.ActionBattleAttack, gs.handleBattleAttack)
	router.Register(models.ActionSceneTransfer, gs.handleSceneTransfer)
	router.Register(models.ActionItemPickup, gs.handleItemPickup)
	router.Register(models.ActionItemDrop, gs.handleItemDrop)
	router.Register(models.ActionItemEquip, gs.handleItemEquip)
	router.Register(models.ActionChatMessage, gs.handleChatMessage)
	
	router.SetOnDisconnect(gs.handlePlayerDisconnect)
}

func (gs *GameServiceV2) handleLogin(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
	var req models.LoginRequest
	if err := json.Unmarshal(data, &req); err != nil {
		gs.broadcastService.SendError("", 400, "Invalid login request")
		return
	}
	
	if err := auth.ValidateUsername(req.Username); err != nil {
		gs.broadcastService.SendError("", 400, err.Error())
		return
	}
	
	ctx := context.Background()
	player, err := gs.playerCache.Load(ctx, models.PlayerID(req.Username))
	
	if err != nil {
		if err == persistence.ErrPlayerNotFound {
			player, err = gs.createNewPlayer(req.Username, req.Password)
			if err != nil {
				server.SendError(session, 500, "Failed to create player")
				return
			}
			
			gs.playerCache.Put(player)
		} else {
			server.SendError(session, 500, "Failed to load player")
			return
		}
	} else {
		match, err := gs.passwordHasher.ComparePasswordAndHash(req.Password, player.PasswordHash)
		if err != nil || !match {
			server.SendError(session, 401, "Invalid password")
			return
		}
	}
	
	token, err := gs.tokenManager.GenerateToken(player.PlayerID, player.Username)
	if err != nil {
		server.SendError(session, 500, "Failed to generate token")
		return
	}
	
	gs.sessionManager.CreateSession(player.PlayerID, token, "", "")
	
	player.OnlineStatus = true
	gs.playerCache.Save(player, true)
	
	server.BindSession(session, player.PlayerID)
	
	pos, nearbyPlayers, err := gs.sceneManager.PlayerEnter(player.PlayerID, player.Position.MapID, "default")
	if err == nil {
		player.Position = *pos
	}
	
	resp := models.NewResponse(models.EventLoginSuccess, models.LoginSuccessData{
		PlayerID: player.PlayerID,
		Token:    token,
		Player:   player,
	})
	
	respData, _ := json.Marshal(resp)
	session.Write(respData)
	
	if len(nearbyPlayers) > 0 {
		gs.broadcastService.BroadcastPlayerJoin(
			player.PlayerID,
			player.Username,
			player.Position,
		)
	}
}

func (gs *GameServiceV2) createNewPlayer(username, password string) (*models.Player, error) {
	playerID := models.PlayerID("p_" + uuid.New().String()[:8])
	
	passwordHash, err := gs.passwordHasher.HashPassword(password)
	if err != nil {
		return nil, err
	}
	
	player := models.NewPlayer(
		playerID,
		username,
		passwordHash,
		&models.Attributes{
			HP:         gs.config.Game.MaxHP,
			MaxHP:      gs.config.Game.MaxHP,
			Attack:     gs.config.Game.BaseAttack,
			Defense:    gs.config.Game.BaseDefense,
			Level:      1,
			Experience: 0,
		},
	)
	
	player.Position = models.Position{
		X:     gs.config.Game.StartPosition.X,
		Y:     gs.config.Game.StartPosition.Y,
		MapID: gs.config.Game.DefaultMapID,
	}
	
	player.AddItem("potion_hp_small", 5)
	
	return player, nil
}

func (gs *GameServiceV2) handlePlayerMove(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
	playerID := server.GetPlayerID(session)
	if playerID == nil {
		server.SendError(session, 401, "Not logged in")
		return
	}
	
	var req models.PlayerMoveRequest
	if err := json.Unmarshal(data, &req); err != nil {
		server.SendError(session, 400, "Invalid move request")
		return
	}
	
	mapConfig, exists := gs.sceneManager.GetMapConfig(req.Position.MapID)
	if exists {
		if !mapConfig.IsValidPosition(req.Position.X, req.Position.Y) {
			req.Position.X, req.Position.Y = mapConfig.ClampPosition(req.Position.X, req.Position.Y)
		}
	}
	
	oldMapID, _ := gs.sceneManager.GetPlayerMap(*playerID)
	
	if err := gs.sceneManager.UpdatePosition(*playerID, req.Position); err != nil {
		server.SendError(session, 500, "Failed to update position")
		return
	}
	
	player, exists := gs.playerCache.Get(*playerID)
	if exists {
		player.Position = req.Position
		gs.playerCache.Save(player, true)
	}
	
	newMapID, _ := gs.sceneManager.GetPlayerMap(*playerID)
	
	if oldMapID != newMapID {
		oldPlayers := gs.sceneManager.GetPlayersInMap(oldMapID)
		if len(oldPlayers) > 0 {
			gs.broadcastService.BroadcastPlayerLeave(*playerID, oldMapID)
		}
		
		newPlayers := gs.sceneManager.GetPlayersInMap(newMapID)
		if len(newPlayers) > 0 {
			gs.broadcastService.BroadcastPlayerJoin(*playerID, player.Username, req.Position)
		}
	} else {
		gs.broadcastService.BroadcastPlayerMove(*playerID, req.Position)
	}
}

func (gs *GameServiceV2) handleBattleAttack(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
	playerID := server.GetPlayerID(session)
	if playerID == nil {
		server.SendError(session, 401, "Not logged in")
		return
	}
	
	var req models.BattleAttackRequest
	if err := json.Unmarshal(data, &req); err != nil {
		server.SendError(session, 400, "Invalid attack request")
		return
	}
	
	attackReq := &battle.AttackRequest{
		AttackerID: *playerID,
		TargetID:   req.TargetID,
		SkillID:    req.SkillID,
	}
	
	result, err := gs.battleService.ProcessAttack(attackReq)
	if err != nil {
		var errorCode int
		switch err {
		case battle.ErrTargetNotFound:
			errorCode = 404
		case battle.ErrTargetNotInRange:
			errorCode = 403
		case battle.ErrTargetAlreadyDead:
			errorCode = 410
		case battle.ErrAttackCooldown:
			errorCode = 429
		case battle.ErrNotInSameMap:
			errorCode = 403
		default:
			errorCode = 500
		}
		server.SendError(session, errorCode, err.Error())
		return
	}
	
	broadcastResult := &broadcast.DamageResult{
		AttackerID:     result.DamageResult.AttackerID,
		TargetID:       result.DamageResult.TargetID,
		RawDamage:      result.DamageResult.RawDamage,
		FinalDamage:    result.DamageResult.FinalDamage,
		TargetHPRemain: result.DamageResult.TargetHPRemain,
		IsCritical:     result.DamageResult.IsCritical,
		IsMiss:         result.DamageResult.IsMiss,
		IsKill:         result.DamageResult.IsKill,
	}
	
	err = gs.broadcastService.BroadcastBattleResult(
		*playerID,
		req.TargetID,
		broadcastResult,
	)
	
	if err != nil {
		resp := models.NewResponse(models.EventBattleResult, models.BattleResultData{
			AttackerID:     result.DamageResult.AttackerID,
			TargetID:       result.DamageResult.TargetID,
			Damage:         result.DamageResult.FinalDamage,
			TargetHPRemain: result.DamageResult.TargetHPRemain,
			IsKill:         result.DamageResult.IsKill,
		})
		server.SendToPlayer(*playerID, resp)
	}
}

func (gs *GameServiceV2) handleSceneTransfer(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
	playerID := server.GetPlayerID(session)
	if playerID == nil {
		server.SendError(session, 401, "Not logged in")
		return
	}
	
	var req models.SceneTransferRequest
	if err := json.Unmarshal(data, &req); err != nil {
		server.SendError(session, 400, "Invalid transfer request")
		return
	}
	
	pos, nearbyPlayers, err := gs.sceneManager.PlayerEnter(*playerID, req.TargetMapID, req.EntryPoint)
	if err != nil {
		server.SendError(session, 500, "Failed to transfer scene")
		return
	}
	
	player, exists := gs.playerCache.Get(*playerID)
	if exists {
		player.Position = *pos
		gs.playerCache.Save(player, true)
	}
	
	gs.broadcastService.BroadcastSceneEnter(
		*playerID,
		req.TargetMapID,
		*pos,
		nearbyPlayers,
	)
	
	gs.broadcastService.BroadcastPlayerJoin(
		*playerID,
		player.Username,
		*pos,
	)
}

func (gs *GameServiceV2) handleItemPickup(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
	playerID := server.GetPlayerID(session)
	if playerID == nil {
		server.SendError(session, 401, "Not logged in")
		return
	}
	
	var req models.ItemPickupRequest
	if err := json.Unmarshal(data, &req); err != nil {
		server.SendError(session, 400, "Invalid pickup request")
		return
	}
	
	player, exists := gs.playerCache.Get(*playerID)
	if !exists {
		server.SendError(session, 404, "Player not found")
		return
	}
	
	item := gs.itemManager.GetItem(req.ItemID)
	if item == nil {
		server.SendError(session, 404, "Item not found")
		return
	}
	
	player.AddItem(req.ItemID, req.Count)
	gs.playerCache.Save(player, true)
	
	resp := models.NewResponse(models.EventItemPicked, models.ItemPickedData{
		ItemID: req.ItemID,
		Count:  req.Count,
	})
	respData, _ := json.Marshal(resp)
	session.Write(respData)
}

func (gs *GameServiceV2) handleItemDrop(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
	playerID := server.GetPlayerID(session)
	if playerID == nil {
		server.SendError(session, 401, "Not logged in")
		return
	}
	
	var req models.ItemDropRequest
	if err := json.Unmarshal(data, &req); err != nil {
		server.SendError(session, 400, "Invalid drop request")
		return
	}
	
	player, exists := gs.playerCache.Get(*playerID)
	if !exists {
		server.SendError(session, 404, "Player not found")
		return
	}
	
	if player.GetItemCount(req.ItemID) < req.Count {
		server.SendError(session, 400, "Not enough items")
		return
	}
	
	player.RemoveItem(req.ItemID, req.Count)
	gs.playerCache.Save(player, true)
	
	resp := models.NewResponse(models.EventItemDropped, models.ItemDroppedData{
		ItemID: req.ItemID,
		Count:  req.Count,
	})
	respData, _ := json.Marshal(resp)
	session.Write(respData)
}

func (gs *GameServiceV2) handleItemEquip(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
	playerID := server.GetPlayerID(session)
	if playerID == nil {
		server.SendError(session, 401, "Not logged in")
		return
	}
	
	var req models.ItemEquipRequest
	if err := json.Unmarshal(data, &req); err != nil {
		server.SendError(session, 400, "Invalid equip request")
		return
	}
	
	player, exists := gs.playerCache.Get(*playerID)
	if !exists {
		server.SendError(session, 404, "Player not found")
		return
	}
	
	item := gs.itemManager.GetItem(req.ItemID)
	if item == nil {
		server.SendError(session, 404, "Item not found")
		return
	}
	
	if !gs.itemManager.IsEquipable(item.ItemType) {
		server.SendError(session, 400, "Item is not equipable")
		return
	}
	
	if player.GetItemCount(req.ItemID) < 1 {
		server.SendError(session, 400, "Item not in inventory")
		return
	}
	
	expectedSlot := gs.itemManager.GetSlotForType(item.ItemType)
	if expectedSlot != req.Slot {
		server.SendError(session, 400, "Invalid slot for item type")
		return
	}
	
	switch req.Slot {
	case "weapon":
		if player.Equipment.Weapon != "" {
			player.AddItem(player.Equipment.Weapon, 1)
		}
		player.Equipment.Weapon = req.ItemID
	case "armor":
		if player.Equipment.Armor != "" {
			player.AddItem(player.Equipment.Armor, 1)
		}
		player.Equipment.Armor = req.ItemID
	case "helmet":
		if player.Equipment.Helmet != "" {
			player.AddItem(player.Equipment.Helmet, 1)
		}
		player.Equipment.Helmet = req.ItemID
	case "boots":
		if player.Equipment.Boots != "" {
			player.AddItem(player.Equipment.Boots, 1)
		}
		player.Equipment.Boots = req.ItemID
	}
	
	player.RemoveItem(req.ItemID, 1)
	gs.playerCache.Save(player, true)
	
	resp := models.NewResponse(models.EventItemEquipped, models.ItemEquippedData{
		ItemID:  req.ItemID,
		Slot:    req.Slot,
		Success: true,
	})
	respData, _ := json.Marshal(resp)
	session.Write(respData)
}

func (gs *GameServiceV2) handleChatMessage(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
	playerID := server.GetPlayerID(session)
	if playerID == nil {
		server.SendError(session, 401, "Not logged in")
		return
	}
	
	var req models.ChatMessageRequest
	if err := json.Unmarshal(data, &req); err != nil {
		server.SendError(session, 400, "Invalid chat request")
		return
	}
	
	player, exists := gs.playerCache.Get(*playerID)
	if !exists {
		server.SendError(session, 404, "Player not found")
		return
	}
	
	gs.broadcastService.BroadcastChatMessage(
		*playerID,
		player.Username,
		req.Channel,
		req.Content,
		player.Position,
		time.Now().Unix(),
	)
}

func (gs *GameServiceV2) handlePlayerDisconnect(playerID models.PlayerID) {
	player, exists := gs.playerCache.Get(playerID)
	if !exists {
		return
	}
	
	oldMapID, _ := gs.sceneManager.GetPlayerMap(playerID)
	
	gs.sceneManager.PlayerLeave(playerID)
	
	player.OnlineStatus = false
	gs.playerCache.Save(player, true)
	
	gs.sessionManager.DestroySession(playerID)
	
	gs.broadcastService.BroadcastPlayerLeave(playerID, oldMapID)
}
