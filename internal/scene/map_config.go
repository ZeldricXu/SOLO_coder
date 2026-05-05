package scene

import "pixelrealm/pkg/models"

type MapConfig struct {
	MapID         string                      `json:"map_id"`
	Name          string                      `json:"name"`
	Width         float64                     `json:"width"`
	Height        float64                     `json:"height"`
	MinX          float64                     `json:"min_x"`
	MinY          float64                     `json:"min_y"`
	MaxX          float64                     `json:"max_x"`
	MaxY          float64                     `json:"max_y"`
	EntryPoints   map[string]models.Position  `json:"entry_points"`
	ConnectedMaps map[string]string           `json:"connected_maps"`
	SpawnPoints   []models.Position           `json:"spawn_points"`
	AOIConfig     *AOIConfig                  `json:"aoi_config,omitempty"`
	NPCTemplates  []NPCTemplate                `json:"npc_templates,omitempty"`
}

type AOIConfig struct {
	VisibleRange   float64 `json:"visible_range"`
	BroadcastRange float64 `json:"broadcast_range"`
	GridSize       float64 `json:"grid_size"`
}

type NPCTemplate struct {
	NPCID       string           `json:"npc_id"`
	Name        string           `json:"name"`
	Type        string           `json:"type"`
	Position    models.Position  `json:"position"`
	Attributes  NPCAttributes    `json:"attributes"`
	DialogID    string           `json:"dialog_id,omitempty"`
	SpawnPoints []models.Position `json:"spawn_points,omitempty"`
	RespawnTime int64            `json:"respawn_time"`
	DropTable   []DropItem       `json:"drop_table,omitempty"`
}

type NPCAttributes struct {
	HP        int `json:"hp"`
	MaxHP     int `json:"max_hp"`
	Attack    int `json:"attack"`
	Defense   int `json:"defense"`
	Level     int `json:"level"`
	MoveSpeed float64 `json:"move_speed"`
}

type DropItem struct {
	ItemID   string  `json:"item_id"`
	MinCount int     `json:"min_count"`
	MaxCount int     `json:"max_count"`
	Chance   float64 `json:"chance"`
}

type WorldMap struct {
	Maps map[string]*MapInstance
}

type MapInstance struct {
	MapID     string
	Config    *MapConfig
	Players   map[models.PlayerID]struct{}
	NPCs      map[string]*NPCInstance
}

type NPCInstance struct {
	NPCID       string
	Template    *NPCTemplate
	Position    models.Position
	Attributes  NPCAttributes
	IsAlive     bool
	LastDeath   int64
	TargetID    models.PlayerID
}

func (m *MapConfig) IsValidPosition(x, y float64) bool {
	return x >= m.MinX && x <= m.MaxX && y >= m.MinY && y <= m.MaxY
}

func (m *MapConfig) ClampPosition(x, y float64) (float64, float64) {
	if x < m.MinX {
		x = m.MinX
	} else if x > m.MaxX {
		x = m.MaxX
	}
	
	if y < m.MinY {
		y = m.MinY
	} else if y > m.MaxY {
		y = m.MaxY
	}
	
	return x, y
}

func (m *MapConfig) GetEntryPoint(entryPoint string) (models.Position, bool) {
	pos, exists := m.EntryPoints[entryPoint]
	return pos, exists
}

func (m *MapConfig) GetConnectedMap(gate string) (string, bool) {
	mapID, exists := m.ConnectedMaps[gate]
	return mapID, exists
}

func NewWorldMap() *WorldMap {
	return &WorldMap{
		Maps: make(map[string]*MapInstance),
	}
}

func (wm *WorldMap) AddMap(config *MapConfig) {
	wm.Maps[config.MapID] = &MapInstance{
		MapID:   config.MapID,
		Config:  config,
		Players: make(map[models.PlayerID]struct{}),
		NPCs:    make(map[string]*NPCInstance),
	}
}

func (wm *WorldMap) GetMap(mapID string) (*MapInstance, bool) {
	mi, exists := wm.Maps[mapID]
	return mi, exists
}

func (wm *WorldMap) AddPlayer(mapID string, playerID models.PlayerID) bool {
	mi, exists := wm.Maps[mapID]
	if !exists {
		return false
	}
	mi.Players[playerID] = struct{}{}
	return true
}

func (wm *WorldMap) RemovePlayer(mapID string, playerID models.PlayerID) bool {
	mi, exists := wm.Maps[mapID]
	if !exists {
		return false
	}
	delete(mi.Players, playerID)
	return true
}

func (wm *WorldMap) GetPlayers(mapID string) []models.PlayerID {
	mi, exists := wm.Maps[mapID]
	if !exists {
		return []models.PlayerID{}
	}
	
	players := make([]models.PlayerID, 0, len(mi.Players))
	for pid := range mi.Players {
		players = append(players, pid)
	}
	return players
}

func (wm *WorldMap) GetNPC(mapID, npcID string) (*NPCInstance, bool) {
	mi, exists := wm.Maps[mapID]
	if !exists {
		return nil, false
	}
	
	npc, exists := mi.NPCs[npcID]
	return npc, exists
}

func (wm *WorldMap) AddNPC(mapID string, npc *NPCInstance) bool {
	mi, exists := wm.Maps[mapID]
	if !exists {
		return false
	}
	mi.NPCs[npc.NPCID] = npc
	return true
}

func (n *NPCInstance) TakeDamage(damage int) int {
	actualDamage := damage
	if actualDamage > n.Attributes.HP {
		actualDamage = n.Attributes.HP
	}
	n.Attributes.HP -= actualDamage
	
	if n.Attributes.HP <= 0 {
		n.IsAlive = false
		n.LastDeath = 0
	}
	
	return actualDamage
}

func (n *NPCInstance) Heal(amount int) int {
	healAmount := amount
	if n.Attributes.HP+amount > n.Attributes.MaxHP {
		healAmount = n.Attributes.MaxHP - n.Attributes.HP
	}
	n.Attributes.HP += healAmount
	return healAmount
}

func (n *NPCInstance) IsDead() bool {
	return !n.IsAlive
}
