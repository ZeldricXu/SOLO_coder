package battle

import (
	"sync"
)

type SkillType string

const (
	SkillTypeMelee  SkillType = "melee"
	SkillTypeRanged SkillType = "ranged"
	SkillTypeMagic  SkillType = "magic"
	SkillTypeHeal   SkillType = "heal"
	SkillTypeBuff   SkillType = "buff"
)

type Skill struct {
	SkillID      string    `json:"skill_id"`
	Name         string    `json:"name"`
	Description  string    `json:"description"`
	SkillType    SkillType `json:"skill_type"`
	DamageBonus  int       `json:"damage_bonus"`
	HealAmount   int       `json:"heal_amount"`
	Range        float64   `json:"range"`
	Cooldown     int64     `json:"cooldown"`
	Cost         int       `json:"cost"`
	CritChance   float64   `json:"crit_chance"`
	TargetType   string    `json:"target_type"`
	AreaEffect   bool      `json:"area_effect"`
	AreaRadius   float64   `json:"area_radius"`
}

type SkillManager struct {
	skills map[string]*Skill
	mu     sync.RWMutex
}

func NewSkillManager() *SkillManager {
	sm := &SkillManager{
		skills: make(map[string]*Skill),
	}
	sm.initDefaultSkills()
	return sm
}

func (sm *SkillManager) initDefaultSkills() {
	defaultSkills := []*Skill{
		{
			SkillID:     "basic_swing",
			Name:        "普通攻击",
			Description: "基础的近战攻击",
			SkillType:   SkillTypeMelee,
			DamageBonus: 0,
			HealAmount:  0,
			Range:       80.0,
			Cooldown:    1000,
			Cost:        0,
			CritChance:  0.1,
			TargetType:  "single",
			AreaEffect:  false,
		},
		{
			SkillID:     "power_strike",
			Name:        "强力一击",
			Description: "强力的近战攻击，造成额外伤害",
			SkillType:   SkillTypeMelee,
			DamageBonus: 20,
			HealAmount:  0,
			Range:       80.0,
			Cooldown:    3000,
			Cost:        0,
			CritChance:  0.15,
			TargetType:  "single",
			AreaEffect:  false,
		},
		{
			SkillID:     "arrow_shot",
			Name:        "弓箭射击",
			Description: "远程攻击",
			SkillType:   SkillTypeRanged,
			DamageBonus: 5,
			HealAmount:  0,
			Range:       300.0,
			Cooldown:    1500,
			Cost:        0,
			CritChance:  0.1,
			TargetType:  "single",
			AreaEffect:  false,
		},
		{
			SkillID:     "fireball",
			Name:        "火球术",
			Description: "发射一个火球，对目标造成魔法伤害",
			SkillType:   SkillTypeMagic,
			DamageBonus: 30,
			HealAmount:  0,
			Range:       250.0,
			Cooldown:    5000,
			Cost:        0,
			CritChance:  0.1,
			TargetType:  "single",
			AreaEffect:  false,
		},
		{
			SkillID:     "heal_self",
			Name:        "治疗术",
			Description: "恢复自身生命值",
			SkillType:   SkillTypeHeal,
			DamageBonus: 0,
			HealAmount:  50,
			Range:       0.0,
			Cooldown:    10000,
			Cost:        0,
			CritChance:  0.0,
			TargetType:  "self",
			AreaEffect:  false,
		},
		{
			SkillID:     "whirlwind",
			Name:        "旋风斩",
			Description: "对周围所有敌人造成伤害",
			SkillType:   SkillTypeMelee,
			DamageBonus: 15,
			HealAmount:  0,
			Range:       0.0,
			Cooldown:    8000,
			Cost:        0,
			CritChance:  0.1,
			TargetType:  "self",
			AreaEffect:  true,
			AreaRadius:  120.0,
		},
	}
	
	for _, skill := range defaultSkills {
		sm.skills[skill.SkillID] = skill
	}
}

func (sm *SkillManager) GetSkill(skillID string) *Skill {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	if skill, exists := sm.skills[skillID]; exists {
		return skill
	}
	return nil
}

func (sm *SkillManager) GetDefaultSkill() *Skill {
	return sm.GetSkill("basic_swing")
}

func (sm *SkillManager) AddSkill(skill *Skill) {
	sm.mu.Lock()
	defer sm.mu.Unlock()
	
	sm.skills[skill.SkillID] = skill
}

func (sm *SkillManager) GetAllSkills() []*Skill {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	skills := make([]*Skill, 0, len(sm.skills))
	for _, skill := range sm.skills {
		skills = append(skills, skill)
	}
	return skills
}

func (sm *SkillManager) GetSkillsByType(skillType SkillType) []*Skill {
	sm.mu.RLock()
	defer sm.mu.RUnlock()
	
	var skills []*Skill
	for _, skill := range sm.skills {
		if skill.SkillType == skillType {
			skills = append(skills, skill)
		}
	}
	return skills
}

type PlayerSkills struct {
	PlayerID    models.PlayerID
	Learned     map[string]struct{}
	Equipped    []string
	CurrentSlot int
	mu          sync.RWMutex
}

func NewPlayerSkills(playerID models.PlayerID) *PlayerSkills {
	return &PlayerSkills{
		PlayerID: playerID,
		Learned:  make(map[string]struct{}),
		Equipped: make([]string, 0, 4),
	}
}

func (ps *PlayerSkills) LearnSkill(skillID string) {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	
	ps.Learned[skillID] = struct{}{}
}

func (ps *PlayerSkills) UnlearnSkill(skillID string) {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	
	delete(ps.Learned, skillID)
	
	for i, equipped := range ps.Equipped {
		if equipped == skillID {
			ps.Equipped = append(ps.Equipped[:i], ps.Equipped[i+1:]...)
			break
		}
	}
}

func (ps *PlayerSkills) EquipSkill(skillID string, slot int) bool {
	ps.mu.Lock()
	defer ps.mu.Unlock()
	
	if _, exists := ps.Learned[skillID]; !exists {
		return false
	}
	
	if slot < 0 || slot >= 4 {
		return false
	}
	
	for len(ps.Equipped) <= slot {
		ps.Equipped = append(ps.Equipped, "")
	}
	
	ps.Equipped[slot] = skillID
	return true
}

func (ps *PlayerSkills) GetEquippedSkill(slot int) string {
	ps.mu.RLock()
	defer ps.mu.RUnlock()
	
	if slot < 0 || slot >= len(ps.Equipped) {
		return ""
	}
	
	return ps.Equipped[slot]
}

func (ps *PlayerSkills) HasSkill(skillID string) bool {
	ps.mu.RLock()
	defer ps.mu.RUnlock()
	
	_, exists := ps.Learned[skillID]
	return exists
}

func (ps *PlayerSkills) GetLearnedSkills() []string {
	ps.mu.RLock()
	defer ps.mu.RUnlock()
	
	skills := make([]string, 0, len(ps.Learned))
	for skillID := range ps.Learned {
		skills = append(skills, skillID)
	}
	return skills
}

type PlayerID = models.PlayerID
