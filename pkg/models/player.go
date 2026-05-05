package models

import (
	"time"
)

type PlayerID string

type Player struct {
	PlayerID      PlayerID       `bson:"player_id" json:"player_id"`
	Username      string         `bson:"username" json:"username"`
	PasswordHash  string         `bson:"password_hash,omitempty" json:"-"`
	Position      Position       `bson:"position" json:"position"`
	Attributes    Attributes     `bson:"attributes" json:"attributes"`
	Equipment     Equipment      `bson:"equipment" json:"equipment"`
	Inventory     []InventoryItem `bson:"inventory" json:"inventory"`
	OnlineStatus  bool           `bson:"online_status" json:"online_status"`
	LastSyncTime  time.Time      `bson:"last_sync_time" json:"last_sync_time"`
	Version       int            `bson:"version" json:"version"`
}

type Position struct {
	X     float64 `bson:"x" json:"x"`
	Y     float64 `bson:"y" json:"y"`
	MapID string  `bson:"map_id" json:"map_id"`
}

type Attributes struct {
	HP        int `bson:"hp" json:"hp"`
	MaxHP     int `bson:"max_hp" json:"max_hp"`
	Attack    int `bson:"attack" json:"attack"`
	Defense   int `bson:"defense" json:"defense"`
	Level     int `bson:"level" json:"level"`
	Experience int `bson:"experience" json:"experience"`
}

type Equipment struct {
	Weapon string `bson:"weapon" json:"weapon"`
	Armor  string `bson:"armor" json:"armor"`
	Helmet string `bson:"helmet" json:"helmet"`
	Boots  string `bson:"boots" json:"boots"`
}

type InventoryItem struct {
	ItemID string `bson:"item_id" json:"item_id"`
	Count  int    `bson:"count" json:"count"`
}

type Item struct {
	ItemID     string      `bson:"item_id" json:"item_id"`
	ItemType   string      `bson:"item_type" json:"item_type"`
	Name       string      `bson:"name" json:"name"`
	Attributes ItemAttributes `bson:"attributes" json:"attributes"`
	DropRate   float64     `bson:"drop_rate" json:"drop_rate"`
}

type ItemAttributes struct {
	AttackBonus  int `bson:"attack_bonus,omitempty" json:"attack_bonus,omitempty"`
	DefenseBonus int `bson:"defense_bonus,omitempty" json:"defense_bonus,omitempty"`
	HPBonus      int `bson:"hp_bonus,omitempty" json:"hp_bonus,omitempty"`
	Weight       int `bson:"weight" json:"weight"`
}

func NewPlayer(playerID PlayerID, username, passwordHash string, cfg *Attributes) *Player {
	return &Player{
		PlayerID:     playerID,
		Username:     username,
		PasswordHash: passwordHash,
		Position: Position{
			X:     100.0,
			Y:     100.0,
			MapID: "forest_01",
		},
		Attributes: Attributes{
			HP:         cfg.HP,
			MaxHP:      cfg.MaxHP,
			Attack:     cfg.Attack,
			Defense:    cfg.Defense,
			Level:      1,
			Experience: 0,
		},
		Equipment: Equipment{
			Weapon: "",
			Armor:  "",
			Helmet: "",
			Boots:  "",
		},
		Inventory:    []InventoryItem{},
		OnlineStatus: false,
		LastSyncTime: time.Now(),
		Version:      1,
	}
}

func (p *Player) GetEffectiveAttack(itemManager *ItemManager) int {
	attack := p.Attributes.Attack
	
	if p.Equipment.Weapon != "" {
		if item := itemManager.GetItem(p.Equipment.Weapon); item != nil {
			attack += item.Attributes.AttackBonus
		}
	}
	
	return attack
}

func (p *Player) GetEffectiveDefense(itemManager *ItemManager) int {
	defense := p.Attributes.Defense
	
	if p.Equipment.Armor != "" {
		if item := itemManager.GetItem(p.Equipment.Armor); item != nil {
			defense += item.Attributes.DefenseBonus
		}
	}
	if p.Equipment.Helmet != "" {
		if item := itemManager.GetItem(p.Equipment.Helmet); item != nil {
			defense += item.Attributes.DefenseBonus
		}
	}
	
	return defense
}

func (p *Player) IsDead() bool {
	return p.Attributes.HP <= 0
}

func (p *Player) TakeDamage(damage int) int {
	actualDamage := damage
	if actualDamage > p.Attributes.HP {
		actualDamage = p.Attributes.HP
	}
	p.Attributes.HP -= actualDamage
	return actualDamage
}

func (p *Player) Heal(amount int) int {
	healAmount := amount
	if p.Attributes.HP+amount > p.Attributes.MaxHP {
		healAmount = p.Attributes.MaxHP - p.Attributes.HP
	}
	p.Attributes.HP += healAmount
	return healAmount
}

func (p *Player) AddItem(itemID string, count int) bool {
	for i, item := range p.Inventory {
		if item.ItemID == itemID {
			p.Inventory[i].Count += count
			return true
		}
	}
	
	p.Inventory = append(p.Inventory, InventoryItem{
		ItemID: itemID,
		Count:  count,
	})
	return true
}

func (p *Player) RemoveItem(itemID string, count int) bool {
	for i, item := range p.Inventory {
		if item.ItemID == itemID {
			if item.Count >= count {
				p.Inventory[i].Count -= count
				if p.Inventory[i].Count == 0 {
					p.Inventory = append(p.Inventory[:i], p.Inventory[i+1:]...)
				}
				return true
			}
			return false
		}
	}
	return false
}

func (p *Player) GetItemCount(itemID string) int {
	for _, item := range p.Inventory {
		if item.ItemID == itemID {
			return item.Count
		}
	}
	return 0
}
