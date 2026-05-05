package battle

import (
	"errors"
	"math"
	"math/rand"
	"sync"
	"time"

	"pixelrealm/pkg/config"
	"pixelrealm/pkg/models"
)

var (
	ErrTargetNotFound    = errors.New("target not found")
	ErrTargetNotInRange  = errors.New("target not in attack range")
	ErrTargetAlreadyDead = errors.New("target is already dead")
	ErrInvalidAttack     = errors.New("invalid attack")
	ErrCooldownActive    = errors.New("skill cooldown active")
)

type BattleSystem struct {
	config         *config.GameConfig
	skillManager   *SkillManager
	itemManager    *models.ItemManager
	cooldowns      map[models.PlayerID]map[string]int64
	cooldownMu     sync.RWMutex
	
	playerCache interface{
		Get(playerID models.PlayerID) (*models.Player, bool)
		Put(player *models.Player)
		Save(player *models.Player, async bool)
	}
	
	sceneManager interface{
		GetPlayerMap(playerID models.PlayerID) (string, error)
		GetPlayerPosition(playerID models.PlayerID) (*models.Position, error)
		GetDistance(playerID1, playerID2 models.PlayerID) (float64, error)
		ArePlayersInSameMap(playerID1, playerID2 models.PlayerID) bool
		GetNearbyPlayers(mapID string, x, y, radius float64) []models.PlayerID
	}
}

type BattleResult struct {
	AttackerID    models.PlayerID `json:"attacker_id"`
	TargetID      models.PlayerID `json:"target_id"`
	Damage        int             `json:"damage"`
	TargetHPRemain int            `json:"target_hp_remain"`
	IsKill        bool            `json:"is_kill"`
	IsCritical    bool            `json:"is_critical"`
	IsMiss        bool            `json:"is_miss"`
	Drops         []DropItem      `json:"drops,omitempty"`
}

type DropItem struct {
	ItemID string `json:"item_id"`
	Count  int    `json:"count"`
}

type BattlePosition struct {
	MapID string
	X     float64
	Y     float64
}

func NewBattleSystem(
	cfg *config.GameConfig,
	itemManager *models.ItemManager,
	playerCache interface{
		Get(playerID models.PlayerID) (*models.Player, bool)
		Put(player *models.Player)
		Save(player *models.Player, async bool)
	},
	sceneManager interface{
		GetPlayerMap(playerID models.PlayerID) (string, error)
		GetPlayerPosition(playerID models.PlayerID) (*models.Position, error)
		GetDistance(playerID1, playerID2 models.PlayerID) (float64, error)
		ArePlayersInSameMap(playerID1, playerID2 models.PlayerID) bool
		GetNearbyPlayers(mapID string, x, y, radius float64) []models.PlayerID
	},
) *BattleSystem {
	return &BattleSystem{
		config:       cfg,
		skillManager: NewSkillManager(),
		itemManager:  itemManager,
		cooldowns:    make(map[models.PlayerID]map[string]int64),
		playerCache:  playerCache,
		sceneManager: sceneManager,
	}
}

func (bs *BattleSystem) ProcessAttack(attackerID models.PlayerID, targetID models.PlayerID, skillID string) (*BattleResult, error) {
	attacker, exists := bs.playerCache.Get(attackerID)
	if !exists {
		return nil, ErrTargetNotFound
	}
	
	target, exists := bs.playerCache.Get(targetID)
	if !exists {
		return nil, ErrTargetNotFound
	}
	
	if !bs.sceneManager.ArePlayersInSameMap(attackerID, targetID) {
		return nil, ErrTargetNotInRange
	}
	
	distance, err := bs.sceneManager.GetDistance(attackerID, targetID)
	if err != nil {
		return nil, err
	}
	
	skill := bs.skillManager.GetSkill(skillID)
	if skill == nil {
		skill = bs.skillManager.GetDefaultSkill()
	}
	
	if distance > skill.Range {
		return nil, ErrTargetNotInRange
	}
	
	if err := bs.checkCooldown(attackerID, skillID); err != nil {
		return nil, err
	}
	
	if target.IsDead() {
		return nil, ErrTargetAlreadyDead
	}
	
	bs.setCooldown(attackerID, skillID, skill.Cooldown)
	
	result := bs.calculateDamage(attacker, target, skill)
	
	target.TakeDamage(result.Damage)
	result.TargetHPRemain = target.Attributes.HP
	
	if target.IsDead() {
		result.IsKill = true
		result.Drops = bs.generateDrops(target)
	}
	
	bs.playerCache.Save(attacker, true)
	bs.playerCache.Save(target, true)
	
	return result, nil
}

func (bs *BattleSystem) calculateDamage(attacker, target *models.Player, skill *Skill) *BattleResult {
	attackerAttack := attacker.GetEffectiveAttack(bs.itemManager)
	targetDefense := target.GetEffectiveDefense(bs.itemManager)
	
	baseDamage := float64(attackerAttack + skill.DamageBonus)
	
	defenseReduction := float64(targetDefense) / (float64(targetDefense) + 100.0)
	effectiveDamage := baseDamage * (1.0 - defenseReduction)
	
	effectiveDamage = effectiveDamage * (0.9 + rand.Float64()*0.2)
	
	isCritical := false
	if rand.Float64() < skill.CritChance {
		effectiveDamage *= 1.5
		isCritical = true
	}
	
	isMiss := false
	if rand.Float64() < 0.05 {
		effectiveDamage = 0
		isMiss = true
	}
	
	finalDamage := int(math.Max(1, math.Round(effectiveDamage)))
	if isMiss {
		finalDamage = 0
	}
	
	return &BattleResult{
		AttackerID: attacker.PlayerID,
		TargetID:   target.PlayerID,
		Damage:     finalDamage,
		IsCritical: isCritical,
		IsMiss:     isMiss,
	}
}

func (bs *BattleSystem) generateDrops(target *models.Player) []DropItem {
	var drops []DropItem
	
	dropTable := []struct {
		ItemID  string
		MinCount int
		MaxCount int
		Chance   float64
	}{
		{"potion_hp_small", 1, 3, 0.3},
		{"potion_hp_large", 1, 1, 0.1},
	}
	
	for _, drop := range dropTable {
		if rand.Float64() < drop.Chance {
			count := drop.MinCount
			if drop.MaxCount > drop.MinCount {
				count = drop.MinCount + rand.Intn(drop.MaxCount-drop.MinCount+1)
			}
			drops = append(drops, DropItem{
				ItemID: drop.ItemID,
				Count:  count,
			})
		}
	}
	
	return drops
}

func (bs *BattleSystem) checkCooldown(playerID models.PlayerID, skillID string) error {
	bs.cooldownMu.RLock()
	defer bs.cooldownMu.RUnlock()
	
	playerCooldowns, exists := bs.cooldowns[playerID]
	if !exists {
		return nil
	}
	
	expireTime, exists := playerCooldowns[skillID]
	if !exists {
		return nil
	}
	
	if time.Now().UnixMilli() < expireTime {
		return ErrCooldownActive
	}
	
	return nil
}

func (bs *BattleSystem) setCooldown(playerID models.PlayerID, skillID string, cooldownMs int64) {
	bs.cooldownMu.Lock()
	defer bs.cooldownMu.Unlock()
	
	if _, exists := bs.cooldowns[playerID]; !exists {
		bs.cooldowns[playerID] = make(map[string]int64)
	}
	
	bs.cooldowns[playerID][skillID] = time.Now().UnixMilli() + cooldownMs
}

func (bs *BattleSystem) GetBattleCenterPosition(attackerID, targetID models.PlayerID) (*BattlePosition, error) {
	attackerPos, err := bs.sceneManager.GetPlayerPosition(attackerID)
	if err != nil {
		return nil, err
	}
	
	targetPos, err := bs.sceneManager.GetPlayerPosition(targetID)
	if err != nil {
		return nil, err
	}
	
	mapID, err := bs.sceneManager.GetPlayerMap(attackerID)
	if err != nil {
		return nil, err
	}
	
	centerX := (attackerPos.X + targetPos.X) / 2.0
	centerY := (attackerPos.Y + targetPos.Y) / 2.0
	
	return &BattlePosition{
		MapID: mapID,
		X:     centerX,
		Y:     centerY,
	}, nil
}

func (bs *BattleSystem) GetNearbyPlayersForBroadcast(attackerID models.PlayerID, targetID models.PlayerID) []models.PlayerID {
	battlePos, err := bs.GetBattleCenterPosition(attackerID, targetID)
	if err != nil {
		mapID, mapErr := bs.sceneManager.GetPlayerMap(attackerID)
		if mapErr != nil {
			return []models.PlayerID{}
		}
		
		pos, posErr := bs.sceneManager.GetPlayerPosition(attackerID)
		if posErr != nil {
			return []models.PlayerID{}
		}
		
		return bs.sceneManager.GetNearbyPlayers(mapID, pos.X, pos.Y, DefaultBattleBroadcastRadius)
	}
	
	return bs.sceneManager.GetNearbyPlayers(battlePos.MapID, battlePos.X, battlePos.Y, DefaultBattleBroadcastRadius)
}

func (bs *BattleSystem) GetNearbyPlayersForBroadcastByPosition(mapID string, x, y float64) []models.PlayerID {
	return bs.sceneManager.GetNearbyPlayers(mapID, x, y, DefaultBattleBroadcastRadius)
}

func (bs *BattleSystem) GetPlayersInAOIExclude(mapID string, x, y float64, excludeID models.PlayerID) []models.PlayerID {
	allNearby := bs.sceneManager.GetNearbyPlayers(mapID, x, y, DefaultBattleBroadcastRadius)
	
	var filtered []models.PlayerID
	for _, pid := range allNearby {
		if pid != excludeID {
			filtered = append(filtered, pid)
		}
	}
	
	return filtered
}

func (bs *BattleSystem) GetSkillManager() *SkillManager {
	return bs.skillManager
}

const (
	DefaultBattleBroadcastRadius = 400.0
	MinBattleBroadcastRadius     = 200.0
	MaxBattleBroadcastRadius     = 600.0
)
