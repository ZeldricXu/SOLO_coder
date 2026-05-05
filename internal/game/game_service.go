package game

import (
	"context"
	"encoding/json"
	"time"

	"pixelrealm/internal/auth"
	"pixelrealm/internal/battle"
	"pixelrealm/internal/network"
	"pixelrealm/internal/persistence"
	"pixelrealm/internal/scene"
	"pixelrealm/pkg/config"
	"pixelrealm/pkg/models"

	"github.com/olahol/melody"
	"github.com/google/uuid"
)

type GameService struct {
	config         *config.Config
	webSocketServer *network.WebSocketServer
	tokenManager   *auth.TokenManager
	passwordHasher *auth.PasswordHasher
	sessionManager *auth.SessionManager
	playerCache    *persistence.PlayerCache
	sceneManager   *scene.SceneManager
	battleSystem   *battle.BattleSystem
	itemManager    *models.ItemManager
}

func NewGameService(cfg *config.Config, playerCache *persistence.PlayerCache) *GameService {
	itemManager := models.NewItemManager()
	sceneManager := scene.NewSceneManager(playerCache)
	
	gs := &GameService{
		config:         cfg,
		tokenManager:   auth.NewTokenManager(&cfg.JWT),
		passwordHasher: auth.NewPasswordHasher(nil),
		sessionManager: auth.NewSessionManager(24*time.Hour, 30*time.Minute),
		playerCache:    playerCache,
		sceneManager:   sceneManager,
		itemManager:    itemManager,
	}
	
	gs.battleSystem = battle.NewBattleSystem(
		&cfg.Game,
		itemManager,
		playerCache,
		sceneManager,
	)
	
	gs.webSocketServer = network.NewWebSocketServer(&cfg.WebSocket)
	
	return gs
}

func (gs *GameService) Initialize() error {
	gs.webSocketServer.RegisterHandlers()
	gs.registerMessageHandlers()
	return nil
}

func (gs *GameService) GetWebSocketServer() *network.WebSocketServer {
	return gs.webSocketServer
}

func (gs *GameService) GetSceneManager() *scene.SceneManager {
	return gs.sceneManager
}

func (gs *GameService) GetBattleSystem() *battle.BattleSystem {
	return gs.battleSystem
}

func (gs *GameService) GetItemManager() *models.ItemManager {
	return gs.itemManager
}

func (gs *GameService) GetTokenManager() *auth.TokenManager {
	return gs.tokenManager
}

func (gs *GameService) GetPasswordHasher() *auth.PasswordHasher {
	return gs.passwordHasher
}

func (gs *GameService) GetSessionManager() *auth.SessionManager {
	return gs.sessionManager
}

func (gs *GameService) registerMessageHandlers() {
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

func (gs *GameService) handleLogin(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
	var req models.LoginRequest
	if err := json.Unmarshal(data, &req); err != nil {
		server.SendError(session, 400, "Invalid login request")
		return
	}
	
	if err := auth.ValidateUsername(req.Username); err != nil {
		server.SendError(session, 400, err.Error())
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
			
			playerStore := gs.getPlayerStore()
			if playerStore != nil {
				playerStore.Create(ctx, player)
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
		joinResp := models.NewResponse(models.EventPlayerJoin, models.PlayerJoinData{
			PlayerID: player.PlayerID,
			Username: player.Username,
			Position: player.Position,
		})
		server.SendToPlayers(nearbyPlayers, joinResp)
	}
}

func (gs *GameService) createNewPlayer(username, password string) (*models.Player, error) {
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

func (gs *GameService) handlePlayerMove(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
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
			leaveResp := models.NewResponse(models.EventPlayerLeave, models.PlayerLeaveData{
				PlayerID: *playerID,
			})
			server.SendToPlayers(oldPlayers, leaveResp)
		}
		
		newPlayers := gs.sceneManager.GetPlayersInMap(newMapID)
		if len(newPlayers) > 0 {
			joinResp := models.NewResponse(models.EventPlayerJoin, models.PlayerJoinData{
				PlayerID: *playerID,
				Username: player.Username,
				Position: req.Position,
			})
			server.SendToPlayers(newPlayers, joinResp)
		}
	} else {
		nearbyPlayers := gs.sceneManager.GetNearbyPlayers(req.Position.MapID, req.Position.X, req.Position.Y, 300)
		if len(nearbyPlayers) > 0 {
			moveResp := models.NewResponse(models.EventPlayerMove, models.PlayerMoveData{
				PlayerID: *playerID,
				Position: req.Position,
			})
			server.SendToPlayers(nearbyPlayers, moveResp)
		}
	}
}

func (gs *GameService) handleBattleAttack(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
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
	
	result, err := gs.battleSystem.ProcessAttack(*playerID, req.TargetID, req.SkillID)
	if err != nil {
		var errorCode int
		switch err {
		case battle.ErrTargetNotFound:
			errorCode = 404
		case battle.ErrTargetNotInRange:
			errorCode = 403
		case battle.ErrTargetAlreadyDead:
			errorCode = 410
		case battle.ErrCooldownActive:
			errorCode = 429
		default:
			errorCode = 500
		}
		server.SendError(session, errorCode, err.Error())
		return
	}
	
	resp := models.NewResponse(models.EventBattleResult, models.BattleResultData{
		AttackerID:    result.AttackerID,
		TargetID:      result.TargetID,
		Damage:        result.Damage,
		TargetHPRemain: result.TargetHPRemain,
		IsKill:        result.IsKill,
	})
	
	nearbyPlayers := gs.battleSystem.GetNearbyPlayersForBroadcast(*playerID, req.TargetID)
	if len(nearbyPlayers) > 0 {
		server.SendToPlayers(nearbyPlayers, resp)
	}
}

func (gs *GameService) handleSceneTransfer(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
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
	
	resp := models.NewResponse(models.EventSceneEnter, models.SceneEnterData{
		MapID:         req.TargetMapID,
		Position:      *pos,
		NearbyPlayers: nearbyPlayers,
	})
	respData, _ := json.Marshal(resp)
	session.Write(respData)
	
	joinResp := models.NewResponse(models.EventPlayerJoin, models.PlayerJoinData{
		PlayerID: *playerID,
		Username: player.Username,
		Position: *pos,
	})
	server.SendToPlayers(nearbyPlayers, joinResp)
}

func (gs *GameService) handleItemPickup(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
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

func (gs *GameService) handleItemDrop(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
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

func (gs *GameService) handleItemEquip(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
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

func (gs *GameService) handleChatMessage(session *melody.Session, data json.RawMessage, server *network.WebSocketServer) {
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
	
	chatResp := models.NewResponse(models.EventChatMessage, models.ChatMessageData{
		PlayerID:  *playerID,
		Username:  player.Username,
		Channel:   req.Channel,
		Content:   req.Content,
		Timestamp: time.Now().Unix(),
	})
	
	if req.Channel == "world" {
		server.Broadcast(chatResp)
	} else {
		nearbyPlayers := gs.sceneManager.GetNearbyPlayers(
			player.Position.MapID,
			player.Position.X,
			player.Position.Y,
			200,
		)
		if len(nearbyPlayers) > 0 {
			server.SendToPlayers(nearbyPlayers, chatResp)
		}
	}
}

func (gs *GameService) handlePlayerDisconnect(playerID models.PlayerID) {
	player, exists := gs.playerCache.Get(playerID)
	if !exists {
		return
	}
	
	oldMapID, _ := gs.sceneManager.GetPlayerMap(playerID)
	
	gs.sceneManager.PlayerLeave(playerID)
	
	player.OnlineStatus = false
	gs.playerCache.Save(player, true)
	
	gs.sessionManager.DestroySession(playerID)
	
	nearbyPlayers := gs.sceneManager.GetPlayersInMap(oldMapID)
	if len(nearbyPlayers) > 0 {
		leaveResp := models.NewResponse(models.EventPlayerLeave, models.PlayerLeaveData{
			PlayerID: playerID,
		})
		gs.webSocketServer.SendToPlayers(nearbyPlayers, leaveResp)
	}
}

func (gs *GameService) getPlayerStore() *persistence.PlayerStore {
	return nil
}
