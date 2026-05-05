package scene

import (
	"errors"
	"sync"

	"pixelrealm/pkg/models"
)

var (
	ErrMapNotInitialized = errors.New("map grid shard not initialized")
)

type SceneManagerV2 struct {
	players      map[models.PlayerID]*ScenePlayerV2
	playersByMap map[string]map[models.PlayerID]struct{}
	gridShards   map[string]*GridShard
	mapConfigs   map[string]*MapConfig
	mu           sync.RWMutex
	
	gridSize   float64
	aoiRadius  float64
	aoiStrategy AOIStrategy
	
	playerCache interface{
		Get(playerID models.PlayerID) (*models.Player, bool)
		Put(player *models.Player)
		Save(player *models.Player, async bool)
	}
}

type ScenePlayerV2 struct {
	PlayerID   models.PlayerID
	MapID      string
	Position   models.Position
	GridCoord  GridCoord
	LastUpdate int64
}

func NewSceneManagerV2(
	playerCache interface{
		Get(playerID models.PlayerID) (*models.Player, bool)
		Put(player *models.Player)
		Save(player *models.Player, async bool)
	},
) *SceneManagerV2 {
	return NewSceneManagerV2WithConfig(
		playerCache,
		DefaultGridSize,
		DefaultAOIRadius,
		AOINineGrids,
	)
}

func NewSceneManagerV2WithConfig(
	playerCache interface{
		Get(playerID models.PlayerID) (*models.Player, bool)
		Put(player *models.Player)
		Save(player *models.Player, async bool)
	},
	gridSize float64,
	aoiRadius float64,
	aoiStrategy AOIStrategy,
) *SceneManagerV2 {
	sm := &SceneManagerV2{
		players:      make(map[models.PlayerID]*ScenePlayerV2),
		playersByMap: make(map[string]map[models.PlayerID]struct{}),
		gridShards:   make(map[string]*GridShard),
		mapConfigs:   make(map[string]*MapConfig),
		gridSize:     gridSize,
		aoiRadius:    aoiRadius,
		aoiStrategy:  aoiStrategy,
		playerCache:  playerCache,
	}
	
	sm.initDefaultMaps()
	
	return sm
}

func (sm *SceneManagerV2) initDefaultMaps() {
	defaultMaps := []*MapConfig{
		{
			MapID:   "forest_01",
			Name:    "新手森林",
			Width:   2000,
			Height:  2000,
			MinX:    0,
			MinY:    0,
			MaxX:    2000,
			MaxY:    2000,
			EntryPoints: map[string]models.Position{
				"default": {X: 1000, Y: 1000, MapID: "forest_01"},
				"south":   {X: 1000, Y: 1900, MapID: "forest_01"},
				"north":   {X: 1000, Y: 100, MapID: "forest_01"},
			},
			ConnectedMaps: map[string]string{
				"north_gate": "forest_02",
				"south_gate": "village_01",
			},
			SpawnPoints: []models.Position{
				{X: 900, Y: 900, MapID: "forest_01"},
				{X: 1100, Y: 900, MapID: "forest_01"},
				{X: 1000, Y: 1100, MapID: "forest_01"},
			},
		},
		{
			MapID:   "forest_02",
			Name:    "幽暗森林",
			Width:   2000,
			Height:  2000,
			MinX:    0,
			MinY:    0,
			MaxX:    2000,
			MaxY:    2000,
			EntryPoints: map[string]models.Position{
				"default":     {X: 1000, Y: 1000, MapID: "forest_02"},
				"south_gate":  {X: 1000, Y: 1900, MapID: "forest_02"},
			},
			ConnectedMaps: map[string]string{
				"south_gate": "forest_01",
			},
			SpawnPoints: []models.Position{
				{X: 1000, Y: 1000, MapID: "forest_02"},
			},
		},
		{
			MapID:   "village_01",
			Name:    "新手村",
			Width:   1000,
			Height:  1000,
			MinX:    0,
			MinY:    0,
			MaxX:    1000,
			MaxY:    1000,
			EntryPoints: map[string]models.Position{
				"default":    {X: 500, Y: 500, MapID: "village_01"},
				"north_gate": {X: 500, Y: 100, MapID: "village_01"},
			},
			ConnectedMaps: map[string]string{
				"north_gate": "forest_01",
			},
			SpawnPoints: []models.Position{
				{X: 500, Y: 500, MapID: "village_01"},
			},
		},
	}
	
	sm.mu.Lock()
	defer sm.mu.Unlock()
	
	for _, m := range defaultMaps {
		sm.mapConfigs[m.MapID] = m
		sm.playersByMap[m.MapID] = make(map[models.PlayerID]struct{})
		sm.gridShards[m.MapID] = NewGridShard(
			sm.gridSize,
			m.MinX,
			m.MaxX,
			m.MinY,
			m.MaxY,
		)
	}
}

func (sm *SceneManagerV2) PlayerEnter(playerID models.PlayerID, mapID string, entryPoint string) (*models.Position, []models.PlayerID, error) {
	sm.mu.Lock()
	
	mapConfig, exists := sm.mapConfigs[mapID]
	if !exists {
		sm.mu.Unlock()
		return nil, nil, ErrMapNotFound
	}
	
	gridShard, exists := sm.gridShards[mapID]
	if !exists {
		sm.mu.Unlock()
		return nil, nil, ErrMapNotInitialized
	}
	
	var spawnPos models.Position
	if entryPoint != "" {
		if pos, ok := mapConfig.EntryPoints[entryPoint]; ok {
			spawnPos = pos
		} else {
			spawnPos = mapConfig.EntryPoints["default"]
		}
	} else {
		if len(mapConfig.SpawnPoints) > 0 {
			spawnPos = mapConfig.SpawnPoints[0]
		} else {
			spawnPos = mapConfig.EntryPoints["default"]
		}
	}
	
	if oldScenePlayer, exists := sm.players[playerID]; exists {
		if oldScenePlayer.MapID == mapID {
			sm.mu.Unlock()
			nearbyPlayers := sm.GetAOIPlayers(mapID, spawnPos.X, spawnPos.Y)
			return &spawnPos, nearbyPlayers, nil
		}
		
		if oldGridShard, ok := sm.gridShards[oldScenePlayer.MapID]; ok {
			oldGridShard.RemovePlayerFromGrid(playerID, oldScenePlayer.GridCoord)
		}
		
		if oldMapPlayers, ok := sm.playersByMap[oldScenePlayer.MapID]; ok {
			delete(oldMapPlayers, playerID)
		}
	}
	
	gridCoord, err := gridShard.AddPlayerToGrid(playerID, spawnPos.X, spawnPos.Y)
	if err != nil {
		sm.mu.Unlock()
		return nil, nil, err
	}
	
	scenePlayer := &ScenePlayerV2{
		PlayerID:  playerID,
		MapID:     mapID,
		Position:  spawnPos,
		GridCoord: gridCoord,
	}
	
	sm.players[playerID] = scenePlayer
	
	if mapPlayers, ok := sm.playersByMap[mapID]; ok {
		mapPlayers[playerID] = struct{}{}
	} else {
		sm.playersByMap[mapID] = make(map[models.PlayerID]struct{})
		sm.playersByMap[mapID][playerID] = struct{}{}
	}
	
	sm.mu.Unlock()
	
	nearbyPlayers := sm.GetAOIPlayers(mapID, spawnPos.X, spawnPos.Y)
	
	return &spawnPos, nearbyPlayers, nil
}

func (sm *SceneManagerV2) PlayerLeave(playerID models.PlayerID) (string, error) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	
	scenePlayer, exists := sm.players[playerID]
	if !exists {
		return "", ErrPlayerNotFound
	}
	
	oldMapID := scenePlayer.MapID
	
	if gridShard, ok := sm.gridShards[oldMapID]; ok {
		gridShard.RemovePlayerFromGrid(playerID, scenePlayer.GridCoord)
	}
	
	if mapPlayers, ok := sm.playersByMap[oldMapID]; ok {
		delete(mapPlayers, playerID)
	}
	
	delete(sm.players, playerID)
	
	return oldMapID, nil
}

func (sm *SceneManagerV2) UpdatePosition(playerID models.PlayerID, pos models.Position) error {
	sm.mu.Lock()
	
	scenePlayer, exists := sm.players[playerID]
	if !exists {
		sm.mu.Unlock()
		return ErrPlayerNotFound
	}
	
	oldPos := scenePlayer.Position
	
	if scenePlayer.MapID != pos.MapID {
		if oldGridShard, ok := sm.gridShards[scenePlayer.MapID]; ok {
			oldGridShard.RemovePlayerFromGrid(playerID, scenePlayer.GridCoord)
		}
		
		if oldMapPlayers, ok := sm.playersByMap[scenePlayer.MapID]; ok {
			delete(oldMapPlayers, playerID)
		}
		
		scenePlayer.MapID = pos.MapID
		
		if newGridShard, ok := sm.gridShards[pos.MapID]; ok {
			newCoord, err := newGridShard.AddPlayerToGrid(playerID, pos.X, pos.Y)
			if err != nil {
				sm.mu.Unlock()
				return err
			}
			scenePlayer.GridCoord = newCoord
		} else {
			sm.mu.Unlock()
			return ErrMapNotInitialized
		}
		
		if mapPlayers, ok := sm.playersByMap[pos.MapID]; ok {
			mapPlayers[playerID] = struct{}{}
		} else {
			sm.playersByMap[pos.MapID] = make(map[models.PlayerID]struct{})
			sm.playersByMap[pos.MapID][playerID] = struct{}{}
		}
	} else {
		if gridShard, ok := sm.gridShards[pos.MapID]; ok {
			oldCoord, newCoord, err := gridShard.MovePlayer(
				playerID,
				oldPos.X, oldPos.Y,
				pos.X, pos.Y,
			)
			if err != nil {
				sm.mu.Unlock()
				return err
			}
			scenePlayer.GridCoord = newCoord
			
			if oldCoord != newCoord {
				scenePlayer.GridCoord = newCoord
			}
		}
	}
	
	scenePlayer.Position = pos
	
	sm.mu.Unlock()
	
	return nil
}

func (sm *SceneManagerV2) GetAOIPlayers(mapID string, x, y float64) []models.PlayerID {
	sm.mu.RLock()
	gridShard, exists := sm.gridShards[mapID]
	sm.mu.RUnlock()
	
	if !exists {
		return []models.PlayerID{}
	}
	
	switch sm.aoiStrategy {
	case AOINineGrids:
		players, _ := gridShard.GetPlayersInNineGridsByPosition(x, y)
		return players
		
	case AOIDistance:
		return sm.getPlayersByDistance(mapID, x, y)
		
	case AOIHybrid:
		gridPlayers, _ := gridShard.GetPlayersInNineGridsByPosition(x, y)
		return sm.filterByDistance(gridPlayers, mapID, x, y)
		
	default:
		players, _ := gridShard.GetPlayersInNineGridsByPosition(x, y)
		return players
	}
}

func (sm *SceneManagerV2) getPlayersByDistance(mapID string, x, y float64) []models.PlayerID {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	mapPlayers, exists := sm.playersByMap[mapID]
	if !exists {
		return []models.PlayerID{}
	}
	
	var nearbyPlayers []models.PlayerID
	radiusSquared := sm.aoiRadius * sm.aoiRadius
	
	for pid := range mapPlayers {
		if scenePlayer, ok := sm.players[pid]; ok {
			dx := scenePlayer.Position.X - x
			dy := scenePlayer.Position.Y - y
			distanceSquared := dx*dx + dy*dy
			
			if distanceSquared <= radiusSquared {
				nearbyPlayers = append(nearbyPlayers, pid)
			}
		}
	}
	
	return nearbyPlayers
}

func (sm *SceneManagerV2) filterByDistance(candidates []models.PlayerID, mapID string, x, y float64) []models.PlayerID {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	var filtered []models.PlayerID
	radiusSquared := sm.aoiRadius * sm.aoiRadius
	
	for _, pid := range candidates {
		if scenePlayer, ok := sm.players[pid]; ok {
			dx := scenePlayer.Position.X - x
			dy := scenePlayer.Position.Y - y
			distanceSquared := dx*dx + dy*dy
			
			if distanceSquared <= radiusSquared {
				filtered = append(filtered, pid)
			}
		}
	}
	
	return filtered
}

func (sm *SceneManagerV2) GetAOIPlayersForBroadcast(attackerID, targetID models.PlayerID) ([]models.PlayerID, error) {
	sm.mu.RLock()
	
	attacker, exists := sm.players[attackerID]
	if !exists {
		sm.mu.RUnlock()
		return nil, ErrPlayerNotFound
	}
	
	target, exists := sm.players[targetID]
	if !exists {
		sm.mu.RUnlock()
		return nil, ErrPlayerNotFound
	}
	
	if attacker.MapID != target.MapID {
		sm.mu.RUnlock()
		return nil, ErrPlayerNotInMap
	}
	
	mapID := attacker.MapID
	
	centerX := (attacker.Position.X + target.Position.X) / 2.0
	centerY := (attacker.Position.Y + target.Position.Y) / 2.0
	
	sm.mu.RUnlock()
	
	return sm.GetAOIPlayers(mapID, centerX, centerY), nil
}

func (sm *SceneManagerV2) GetPlayerMap(playerID models.PlayerID) (string, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	scenePlayer, exists := sm.players[playerID]
	if !exists {
		return "", ErrPlayerNotFound
	}
	
	return scenePlayer.MapID, nil
}

func (sm *SceneManagerV2) GetPlayerPosition(playerID models.PlayerID) (*models.Position, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	scenePlayer, exists := sm.players[playerID]
	if !exists {
		return nil, ErrPlayerNotFound
	}
	
	posCopy := scenePlayer.Position
	return &posCopy, nil
}

func (sm *SceneManagerV2) GetPlayersInMap(mapID string) []models.PlayerID {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	mapPlayers, exists := sm.playersByMap[mapID]
	if !exists {
		return []models.PlayerID{}
	}
	
	players := make([]models.PlayerID, 0, len(mapPlayers))
	for pid := range mapPlayers {
		players = append(players, pid)
	}
	
	return players
}

func (sm *SceneManagerV2) GetNearbyPlayers(mapID string, x, y, radius float64) []models.PlayerID {
	sm.mu.RLock()
	gridShard, exists := sm.gridShards[mapID]
	sm.mu.RUnlock()
	
	if !exists {
		return []models.PlayerID{}
	}
	
	gridPlayers, _ := gridShard.GetPlayersInNineGridsByPosition(x, y)
	
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	var filtered []models.PlayerID
	radiusSquared := radius * radius
	
	for _, pid := range gridPlayers {
		if scenePlayer, ok := sm.players[pid]; ok {
			dx := scenePlayer.Position.X - x
			dy := scenePlayer.Position.Y - y
			distanceSquared := dx*dx + dy*dy
			
			if distanceSquared <= radiusSquared {
				filtered = append(filtered, pid)
			}
		}
	}
	
	return filtered
}

func (sm *SceneManagerV2) ArePlayersInSameMap(playerID1, playerID2 models.PlayerID) bool {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	p1, exists1 := sm.players[playerID1]
	if !exists1 {
		return false
	}
	
	p2, exists2 := sm.players[playerID2]
	if !exists2 {
		return false
	}
	
	return p1.MapID == p2.MapID
}

func (sm *SceneManagerV2) GetDistance(playerID1, playerID2 models.PlayerID) (float64, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	p1, exists := sm.players[playerID1]
	if !exists {
		return 0, ErrPlayerNotFound
	}
	
	p2, exists := sm.players[playerID2]
	if !exists {
		return 0, ErrPlayerNotFound
	}
	
	if p1.MapID != p2.MapID {
		return -1, ErrPlayerNotInMap
	}
	
	return calculateDistance(p1.Position, p2.Position), nil
}

func calculateDistance(pos1, pos2 models.Position) float64 {
	dx := pos1.X - pos2.X
	dy := pos1.Y - pos2.Y
	return dx*dx + dy*dy
}

func (sm *SceneManagerV2) GetMapConfig(mapID string) (*MapConfig, bool) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	config, exists := sm.mapConfigs[mapID]
	return config, exists
}

func (sm *SceneManagerV2) AddMapConfig(config *MapConfig) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	
	sm.mapConfigs[config.MapID] = config
	
	if _, exists := sm.playersByMap[config.MapID]; !exists {
		sm.playersByMap[config.MapID] = make(map[models.PlayerID]struct{})
	}
	
	if _, exists := sm.gridShards[config.MapID]; !exists {
		sm.gridShards[config.MapID] = NewGridShard(
			sm.gridSize,
			config.MinX,
			config.MaxX,
			config.MinY,
			config.MaxY,
		)
	}
}

func (sm *SceneManagerV2) GetOnlineCount() int {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	return len(sm.players)
}

func (sm *SceneManagerV2) GetMapPlayerCount(mapID string) int {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	if mapPlayers, exists := sm.playersByMap[mapID]; exists {
		return len(mapPlayers)
	}
	return 0
}

func (sm *SceneManagerV2) IsPlayerOnline(playerID models.PlayerID) bool {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	_, exists := sm.players[playerID]
	return exists
}

func (sm *SceneManagerV2) GetGridShard(mapID string) (*GridShard, bool) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	shard, exists := sm.gridShards[mapID]
	return shard, exists
}

func (sm *SceneManagerV2) SetAOIStrategy(strategy AOIStrategy) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	sm.aoiStrategy = strategy
}

func (sm *SceneManagerV2) GetAOIStrategy() AOIStrategy {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	return sm.aoiStrategy
}
