package models

import (
	"sync"
)

type ItemManager struct {
	items map[string]*Item
	mu    sync.RWMutex
}

func NewItemManager() *ItemManager {
	im := &ItemManager{
		items: make(map[string]*Item),
	}
	im.initDefaultItems()
	return im
}

func (im *ItemManager) initDefaultItems() {
	defaultItems := []*Item{
		{
			ItemID:   "sword_iron_01",
			ItemType: "weapon",
			Name:     "铁剑",
			Attributes: ItemAttributes{
				AttackBonus: 15,
				Weight:      3,
			},
			DropRate: 0.05,
		},
		{
			ItemID:   "sword_steel_01",
			ItemType: "weapon",
			Name:     "钢剑",
			Attributes: ItemAttributes{
				AttackBonus: 25,
				Weight:      5,
			},
			DropRate: 0.02,
		},
		{
			ItemID:   "leather_basic",
			ItemType: "armor",
			Name:     "皮甲",
			Attributes: ItemAttributes{
				DefenseBonus: 5,
				Weight:       2,
			},
			DropRate: 0.1,
		},
		{
			ItemID:   "chain_mail",
			ItemType: "armor",
			Name:     "锁子甲",
			Attributes: ItemAttributes{
				DefenseBonus: 15,
				Weight:       8,
			},
			DropRate: 0.03,
		},
		{
			ItemID:   "potion_hp_small",
			ItemType: "consumable",
			Name:     "小型生命药水",
			Attributes: ItemAttributes{
				HPBonus: 30,
				Weight:  1,
			},
			DropRate: 0.15,
		},
		{
			ItemID:   "potion_hp_large",
			ItemType: "consumable",
			Name:     "大型生命药水",
			Attributes: ItemAttributes{
				HPBonus: 80,
				Weight:  2,
			},
			DropRate: 0.05,
		},
		{
			ItemID:   "iron_helmet",
			ItemType: "helmet",
			Name:     "铁头盔",
			Attributes: ItemAttributes{
				DefenseBonus: 8,
				Weight:       3,
			},
			DropRate: 0.04,
		},
		{
			ItemID:   "leather_boots",
			ItemType: "boots",
			Name:     "皮靴",
			Attributes: ItemAttributes{
				DefenseBonus: 3,
				Weight:       1,
			},
			DropRate: 0.08,
		},
	}

	for _, item := range defaultItems {
		im.items[item.ItemID] = item
	}
}

func (im *ItemManager) GetItem(itemID string) *Item {
	im.mu.RLock()
	defer im.mu.RUnlock()
	
	if item, exists := im.items[itemID]; exists {
		return item
	}
	return nil
}

func (im *ItemManager) AddItem(item *Item) {
	im.mu.Lock()
	defer im.mu.Unlock()
	
	im.items[item.ItemID] = item
}

func (im *ItemManager) GetItemsByType(itemType string) []*Item {
	im.mu.RLock()
	defer im.mu.RUnlock()
	
	var result []*Item
	for _, item := range im.items {
		if item.ItemType == itemType {
			result = append(result, item)
		}
	}
	return result
}

func (im *ItemManager) IsEquipable(itemType string) bool {
	return itemType == "weapon" || itemType == "armor" || itemType == "helmet" || itemType == "boots"
}

func (im *ItemManager) GetSlotForType(itemType string) string {
	switch itemType {
	case "weapon":
		return "weapon"
	case "armor":
		return "armor"
	case "helmet":
		return "helmet"
	case "boots":
		return "boots"
	default:
		return ""
	}
}
