package battle

import (
	"math"
	"math/rand"
	"time"

	"pixelrealm/pkg/models"
)

type DamageCalculator struct {
	itemManager *models.ItemManager
	randSource  *rand.Rand
}

type DamageContext struct {
	Attacker   *models.Player
	Target     *models.Player
	Skill      *Skill
	AttackPos  models.Position
	TargetPos  models.Position
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
	DamageBreakdown *DamageBreakdown
}

type DamageBreakdown struct {
	BaseDamage      float64
	DefenseReduction float64
	CritMultiplier  float64
	RandomVariance  float64
}

func NewDamageCalculator(itemManager *models.ItemManager) *DamageCalculator {
	return &DamageCalculator{
		itemManager: itemManager,
		randSource:  rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

func (dc *DamageCalculator) Calculate(ctx *DamageContext) *DamageResult {
	result := &DamageResult{
		AttackerID: ctx.Attacker.PlayerID,
		TargetID:   ctx.Target.PlayerID,
		DamageBreakdown: &DamageBreakdown{},
	}
	
	if dc.checkMiss() {
		result.IsMiss = true
		result.FinalDamage = 0
		result.TargetHPRemain = ctx.Target.Attributes.HP
		return result
	}
	
	attackerAttack := ctx.Attacker.GetEffectiveAttack(dc.itemManager)
	targetDefense := ctx.Target.GetEffectiveDefense(dc.itemManager)
	
	baseDamage := float64(attackerAttack + ctx.Skill.DamageBonus)
	result.DamageBreakdown.BaseDamage = baseDamage
	
	defenseReduction := float64(targetDefense) / (float64(targetDefense) + 100.0)
	result.DamageBreakdown.DefenseReduction = defenseReduction
	
	effectiveDamage := baseDamage * (1.0 - defenseReduction)
	
	isCritical := false
	critMultiplier := 1.0
	if dc.randSource.Float64() < ctx.Skill.CritChance {
		isCritical = true
		critMultiplier = 1.5
		effectiveDamage *= critMultiplier
	}
	result.IsCritical = isCritical
	result.DamageBreakdown.CritMultiplier = critMultiplier
	
	randomVariance := 0.9 + dc.randSource.Float64()*0.2
	effectiveDamage = effectiveDamage * randomVariance
	result.DamageBreakdown.RandomVariance = randomVariance
	
	result.RawDamage = int(math.Round(effectiveDamage))
	result.FinalDamage = int(math.Max(1, float64(result.RawDamage)))
	
	return result
}

func (dc *DamageCalculator) ApplyDamage(target *models.Player, result *DamageResult) {
	if result.IsMiss {
		result.TargetHPRemain = target.Attributes.HP
		return
	}
	
	target.TakeDamage(result.FinalDamage)
	result.TargetHPRemain = target.Attributes.HP
	
	if target.IsDead() {
		result.IsKill = true
		result.Drops = dc.generateDrops()
	}
}

func (dc *DamageCalculator) checkMiss() bool {
	return dc.randSource.Float64() < 0.05
}

func (dc *DamageCalculator) generateDrops() []DropItem {
	var drops []DropItem
	
	dropTable := []struct {
		ItemID   string
		MinCount int
		MaxCount int
		Chance   float64
	}{
		{"potion_hp_small", 1, 3, 0.3},
		{"potion_hp_large", 1, 1, 0.1},
	}
	
	for _, drop := range dropTable {
		if dc.randSource.Float64() < drop.Chance {
			count := drop.MinCount
			if drop.MaxCount > drop.MinCount {
				count = drop.MinCount + dc.randSource.Intn(drop.MaxCount-drop.MinCount+1)
			}
			drops = append(drops, DropItem{
				ItemID: drop.ItemID,
				Count:  count,
			})
		}
	}
	
	return drops
}

func (dc *DamageCalculator) CalculateHeal(healer *models.Player, target *models.Player, skill *Skill) *HealResult {
	healAmount := skill.HealAmount
	
	if dc.randSource.Float64() < 0.1 {
		healAmount = int(float64(healAmount) * 1.5)
	}
	
	actualHeal := target.Heal(healAmount)
	
	return &HealResult{
		HealerID:      healer.PlayerID,
		TargetID:      target.PlayerID,
		HealAmount:    healAmount,
		ActualHeal:    actualHeal,
		TargetHPRemain: target.Attributes.HP,
		IsCrit:        healAmount != skill.HealAmount,
	}
}

type HealResult struct {
	HealerID       models.PlayerID
	TargetID       models.PlayerID
	HealAmount     int
	ActualHeal     int
	TargetHPRemain int
	IsCrit         bool
}

func (dc *DamageCalculator) ValidateAttack(ctx *DamageContext, maxDistance float64) error {
	if ctx.Target.IsDead() {
		return ErrTargetAlreadyDead
	}
	
	distance := dc.calculateDistance(ctx.AttackPos, ctx.TargetPos)
	
	if distance > ctx.Skill.Range {
		return ErrTargetNotInRange
	}
	
	return nil
}

func (dc *DamageCalculator) calculateDistance(pos1, pos2 models.Position) float64 {
	dx := pos1.X - pos2.X
	dy := pos1.Y - pos2.Y
	return math.Sqrt(dx*dx + dy*dy)
}
