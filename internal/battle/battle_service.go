package battle

import (
	"errors"
	"sync"
	"time"

	"pixelrealm/pkg/config"
	"pixelrealm/pkg/models"
)

var (
	ErrAttackCooldown  = errors.New("attack is on cooldown")
	ErrSkillNotFound   = errors.New("skill not found")
	ErrNotInSameMap    = errors.New("players are not in the same map")
)

type BattleService struct {
	config           *config.GameConfig
	skillManager     *SkillManager
	damageCalculator *DamageCalculator
	cooldownTracker  *CooldownTracker
	
	playerCache interface{
		Get(playerID models.PlayerID) (*models.Player, bool)
		Put(player *models.Player)
		Save(player *models.Player, async bool)
	}
	
	positionProvider interface{
		GetPlayerMap(playerID models.PlayerID) (string, error)
		GetPlayerPosition(playerID models.PlayerID) (*models.Position, error)
		ArePlayersInSameMap(playerID1, playerID2 models.PlayerID) bool
		GetDistance(playerID1, playerID2 models.PlayerID) (float64, error)
	}
}

type CooldownTracker struct {
	cooldowns map[models.PlayerID]map[string]int64
	mu        sync.RWMutex
}

func NewCooldownTracker() *CooldownTracker {
	return &CooldownTracker{
		cooldowns: make(map[models.PlayerID]map[string]int64),
	}
}

func (ct *CooldownTracker) CheckAndSetCooldown(playerID models.PlayerID, skillID string, cooldownMs int64) error {
	ct.mu.Lock()
	defer ct.mu.Unlock()
	
	playerCooldowns, exists := ct.cooldowns[playerID]
	if !exists {
		playerCooldowns = make(map[string]int64)
		ct.cooldowns[playerID] = playerCooldowns
	}
	
	expireTime, exists := playerCooldowns[skillID]
	now := time.Now().UnixMilli()
	
	if exists && now < expireTime {
		return ErrAttackCooldown
	}
	
	playerCooldowns[skillID] = now + cooldownMs
	return nil
}

func (ct *CooldownTracker) ClearCooldown(playerID models.PlayerID, skillID string) {
	ct.mu.Lock()
	defer ct.mu.Unlock()
	
	if playerCooldowns, exists := ct.cooldowns[playerID]; exists {
		delete(playerCooldowns, skillID)
	}
}

func (ct *CooldownTracker) GetRemainingCooldown(playerID models.PlayerID, skillID string) int64 {
	ct.mu.RLock()
	defer ct.mu.RUnlock()
	
	playerCooldowns, exists := ct.cooldowns[playerID]
	if !exists {
		return 0
	}
	
	expireTime, exists := playerCooldowns[skillID]
	if !exists {
		return 0
	}
	
	remaining := expireTime - time.Now().UnixMilli()
	if remaining < 0 {
		return 0
	}
	return remaining
}

func NewBattleService(
	cfg *config.GameConfig,
	itemManager *models.ItemManager,
	playerCache interface{
		Get(playerID models.PlayerID) (*models.Player, bool)
		Put(player *models.Player)
		Save(player *models.Player, async bool)
	},
	positionProvider interface{
		GetPlayerMap(playerID models.PlayerID) (string, error)
		GetPlayerPosition(playerID models.PlayerID) (*models.Position, error)
		ArePlayersInSameMap(playerID1, playerID2 models.PlayerID) bool
		GetDistance(playerID1, playerID2 models.PlayerID) (float64, error)
	},
) *BattleService {
	return &BattleService{
		config:           cfg,
		skillManager:     NewSkillManager(),
		damageCalculator: NewDamageCalculator(itemManager),
		cooldownTracker:  NewCooldownTracker(),
		playerCache:      playerCache,
		positionProvider: positionProvider,
	}
}

type AttackRequest struct {
	AttackerID models.PlayerID
	TargetID   models.PlayerID
	SkillID    string
}

type AttackResult struct {
	DamageResult *DamageResult
	AttackerPos  *models.Position
	TargetPos    *models.Position
	MapID        string
}

func (bs *BattleService) ProcessAttack(req *AttackRequest) (*AttackResult, error) {
	attacker, exists := bs.playerCache.Get(req.AttackerID)
	if !exists {
		return nil, ErrTargetNotFound
	}
	
	target, exists := bs.playerCache.Get(req.TargetID)
	if !exists {
		return nil, ErrTargetNotFound
	}
	
	if !bs.positionProvider.ArePlayersInSameMap(req.AttackerID, req.TargetID) {
		return nil, ErrNotInSameMap
	}
	
	attackerPos, err := bs.positionProvider.GetPlayerPosition(req.AttackerID)
	if err != nil {
		return nil, err
	}
	
	targetPos, err := bs.positionProvider.GetPlayerPosition(req.TargetID)
	if err != nil {
		return nil, err
	}
	
	mapID, err := bs.positionProvider.GetPlayerMap(req.AttackerID)
	if err != nil {
		return nil, err
	}
	
	skill := bs.skillManager.GetSkill(req.SkillID)
	if skill == nil {
		skill = bs.skillManager.GetDefaultSkill()
	}
	
	distance, err := bs.positionProvider.GetDistance(req.AttackerID, req.TargetID)
	if err != nil {
		return nil, err
	}
	
	if distance > skill.Range {
		return nil, ErrTargetNotInRange
	}
	
	if err := bs.cooldownTracker.CheckAndSetCooldown(req.AttackerID, req.SkillID, skill.Cooldown); err != nil {
		return nil, err
	}
	
	if target.IsDead() {
		return nil, ErrTargetAlreadyDead
	}
	
	damageCtx := &DamageContext{
		Attacker:  attacker,
		Target:    target,
		Skill:     skill,
		AttackPos: *attackerPos,
		TargetPos: *targetPos,
	}
	
	damageResult := bs.damageCalculator.Calculate(damageCtx)
	
	bs.damageCalculator.ApplyDamage(target, damageResult)
	
	bs.playerCache.Save(attacker, true)
	bs.playerCache.Save(target, true)
	
	return &AttackResult{
		DamageResult: damageResult,
		AttackerPos:  attackerPos,
		TargetPos:    targetPos,
		MapID:        mapID,
	}, nil
}

func (bs *BattleService) GetSkillManager() *SkillManager {
	return bs.skillManager
}

func (bs *BattleService) GetDamageCalculator() *DamageCalculator {
	return bs.damageCalculator
}

func (bs *BattleService) GetCooldownTracker() *CooldownTracker {
	return bs.cooldownTracker
}

func (bs *BattleService) GetBattleCenter(attackerPos, targetPos *models.Position) (float64, float64) {
	centerX := (attackerPos.X + targetPos.X) / 2.0
	centerY := (attackerPos.Y + targetPos.Y) / 2.0
	return centerX, centerY
}
