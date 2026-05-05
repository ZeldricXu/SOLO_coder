package scene

import (
	"errors"
	"math"
	"sync"

	"pixelrealm/pkg/models"
)

var (
	ErrPlayerNotFound = errors.New("player not found in scene")
	ErrMapNotFound    = errors.New("map not found")
	ErrPlayerNotInMap = errors.New("player not in target map")
)

type SceneManager struct {
	players      map[models.PlayerID]*ScenePlayer
	playersByMap map[string]map[models.PlayerID]struct{}
	mapConfigs   map[string]*MapConfig
	mu           sync.RWMutex
	
	playerCache interface{
		Get(playerID models.PlayerID) (*models.Player, bool)
		Put(player *models.Player)
		Save(player *models.Player, async bool)
	}
}

type ScenePlayer struct {
	PlayerID   models.PlayerID
	MapID      string
	Position   models.Position
	LastUpdate int64
}

func NewSceneManager(playerCache interface{
	Get(playerID models.PlayerID) (*models.Player, bool)
	Put(player *models.Player)
	Save(player *models.Player, async bool)
}) *SceneManager {
	sm := &SceneManager{
		players:      make(map[models.PlayerID]*ScenePlayer),
		playersByMap: make(map[string]map[models.PlayerID]struct{}),
		mapConfigs:   make(map[string]*MapConfig),
		playerCache:  playerCache,
	}
	
	sm.initDefaultMaps()
	
	return sm
}

func (sm *SceneManager) initDefaultMaps() {
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
	}
}

func (sm *SceneManager) PlayerEnter(playerID models.PlayerID, mapID string, entryPoint string) (*models.Position, []models.PlayerID, error) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	
	mapConfig, exists := sm.mapConfigs[mapID]
	if !exists {
		return nil, nil, ErrMapNotFound
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
			nearbyPlayers := sm.getNearbyPlayersLocked(mapID, spawnPos.X, spawnPos.Y, DefaultAOIRadius)
			return &spawnPos, nearbyPlayers, nil
		}
		
		if oldMapPlayers, ok := sm.playersByMap[oldScenePlayer.MapID]; ok {
			delete(oldMapPlayers, playerID)
		}
	}
	
	scenePlayer := &ScenePlayer{
		PlayerID: playerID,
		MapID:    mapID,
		Position: spawnPos,
	}
	
	sm.players[playerID] = scenePlayer
	
	if mapPlayers, ok := sm.playersByMap[mapID]; ok {
		mapPlayers[playerID] = struct{}{}
	} else {
		newMapPlayers := make(map[models.PlayerID]struct{})
		newMapPlayers[playerID] = struct{}{}
		sm.playersByMap[mapID] = newMapPlayers
	}
	
	nearbyPlayers := sm.getNearbyPlayersLocked(mapID, spawnPos.X, spawnPos.Y, DefaultAOIRadius)
	
	return &spawnPos, nearbyPlayers, nil
}

func (sm *SceneManager) PlayerLeave(playerID models.PlayerID) (string, error) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	
	scenePlayer, exists := sm.players[playerID]
	if !exists {
		return "", ErrPlayerNotFound
	}
	
	oldMapID := scenePlayer.MapID
	
	if mapPlayers, ok := sm.playersByMap[oldMapID]; ok {
		delete(mapPlayers, playerID)
	}
	
	delete(sm.players, playerID)
	
	return oldMapID, nil
}

func (sm *SceneManager) UpdatePosition(playerID models.PlayerID, pos models.Position) error {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	
	scenePlayer, exists := sm.players[playerID]
	if !exists {
		return ErrPlayerNotFound
	}
	
	if scenePlayer.MapID != pos.MapID {
		if oldMapPlayers, ok := sm.playersByMap[scenePlayer.MapID]; ok {
			delete(oldMapPlayers, playerID)
		}
		
		scenePlayer.MapID = pos.MapID
		
		if newMapPlayers, ok := sm.playersByMap[pos.MapID]; ok {
			newMapPlayers[playerID] = struct{}{}
		} else {
			newMapPlayers := make(map[models.PlayerID]struct{})
			newMapPlayers[playerID] = struct{}{}
			sm.playersByMap[pos.MapID] = newMapPlayers
		}
	}
	
	scenePlayer.Position = pos
	
	return nil
}

func (sm *SceneManager) GetPlayerMap(playerID models.PlayerID) (string, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	scenePlayer, exists := sm.players[playerID]
	if !exists {
		return "", ErrPlayerNotFound
	}
	
	return scenePlayer.MapID, nil
}

func (sm *SceneManager) GetPlayerPosition(playerID models.PlayerID) (*models.Position, error) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	scenePlayer, exists := sm.players[playerID]
	if !exists {
		return nil, ErrPlayerNotFound
	}
	
	posCopy := scenePlayer.Position
	return &posCopy, nil
}

func (sm *SceneManager) GetPlayersInMap(mapID string) []models.PlayerID {
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

func (sm *SceneManager) GetNearbyPlayers(mapID string, x, y, radius float64) []models.PlayerID {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	return sm.getNearbyPlayersLocked(mapID, x, y, radius)
}

func (sm *SceneManager) GetNearbyPlayersForAOI(mapID string, centerX, centerY float64) []models.PlayerID {
	return sm.GetNearbyPlayers(mapID, centerX, centerY, DefaultAOIRadius)
}

func (sm *SceneManager) getNearbyPlayersLocked(mapID string, x, y, radius float64) []models.PlayerID {
	mapPlayers, exists := sm.playersByMap[mapID]
	if !exists {
		return []models.PlayerID{}
	}
	
	var nearbyPlayers []models.PlayerID
	radiusSquared := radius * radius
	
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

func (sm *SceneManager) GetDistance(playerID1, playerID2 models.PlayerID) (float64, error) {
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
	
	dx := p1.Position.X - p2.Position.X
	dy := p1.Position.Y - p2.Position.Y
	
	return math.Sqrt(dx*dx + dy*dy), nil
}

func (sm *SceneManager) ArePlayersInSameMap(playerID1, playerID2 models.PlayerID) bool {
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

func (sm *SceneManager) GetMapConfig(mapID string) (*MapConfig, bool) {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	config, exists := sm.mapConfigs[mapID]
	return config, exists
}

func (sm *SceneManager) AddMapConfig(config *MapConfig) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	
	sm.mapConfigs[config.MapID] = config
	if _, exists := sm.playersByMap[config.MapID]; !exists {
		sm.playersByMap[config.MapID] = make(map[models.PlayerID]struct{})
	}
}

func (sm *SceneManager) GetOnlineCount() int {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	return len(sm.players)
}

func (sm *SceneManager) GetMapPlayerCount(mapID string) int {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	if mapPlayers, exists := sm.playersByMap[mapID]; exists {
		return len(mapPlayers)
	}
	return 0
}

func (sm *SceneManager) IsPlayerOnline(playerID models.PlayerID) bool {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	_, exists := sm.players[playerID]
	return exists
}

func (sm *SceneManager) GetAllOnlinePlayers() []models.PlayerID {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	players := make([]models.PlayerID, 0, len(sm.players))
	for pid := range sm.players {
		players = append(players, pid)
	}
	return players
}

const (
	DefaultAOIRadius      = 300.0
	BroadcastAOIRadius    = 400.0
	MaxViewDistance       = 500.0
)
