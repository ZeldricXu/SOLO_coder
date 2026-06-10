package interaction

import (
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/studio/gameroom/pkg/common"
)

type Gift struct {
	ID        string  `json:"id"`
	Name      string  `json:"name"`
	Icon      string  `json:"icon"`
	Price     int64   `json:"price"`
	Currency  string  `json:"currency"`
	Animation string  `json:"animation,omitempty"`
	Effect    string  `json:"effect,omitempty"`
	Weight    int     `json:"weight"`
}

type GiftSendEvent struct {
	ID          string          `json:"id"`
	RoomID      common.RoomID   `json:"room_id"`
	SenderID    common.UserID   `json:"sender_id"`
	SenderName  string          `json:"sender_name"`
	ReceiverID  common.UserID   `json:"receiver_id,omitempty"`
	Gift        *Gift           `json:"gift"`
	Count       int             `json:"count"`
	TotalPrice  int64           `json:"total_price"`
	Timestamp   int64           `json:"timestamp"`
	ComboCount  int             `json:"combo_count,omitempty"`
	ComboID     string          `json:"combo_id,omitempty"`
}

type GiftConfig struct {
	Gifts         []*Gift `json:"gifts"`
	ComboWindowMs int64   `json:"combo_window_ms"`
	MaxComboCount int     `json:"max_combo_count"`
}

type GiftManager struct {
	config    *GiftConfig
	giftIndex map[string]*Gift
	mu        sync.RWMutex
	combos    map[string]*giftCombo
}

type giftCombo struct {
	GiftID    string
	Count     int
	LastSend  int64
	ComboID   string
}

func DefaultGiftConfig() *GiftConfig {
	return &GiftConfig{
		ComboWindowMs: 3000,
		MaxComboCount: 99,
		Gifts: []*Gift{
			{ID: "flower", Name: "鲜花", Icon: "💐", Price: 10, Currency: "coin", Animation: "float_up", Weight: 1},
			{ID: "rocket", Name: "火箭", Icon: "🚀", Price: 100, Currency: "coin", Animation: "fly_across", Effect: "full_screen", Weight: 10},
			{ID: "crown", Name: "皇冠", Icon: "👑", Price: 500, Currency: "coin", Animation: "sparkle", Effect: "full_screen", Weight: 50},
			{ID: "sports_car", Name: "跑车", Icon: "🏎️", Price: 1000, Currency: "coin", Animation: "drift", Effect: "full_screen", Weight: 100},
			{ID: "yacht", Name: "游艇", Icon: "🛥️", Price: 5000, Currency: "coin", Animation: "wave", Effect: "full_screen", Weight: 500},
			{ID: "private_jet", Name: "私人飞机", Icon: "✈️", Price: 10000, Currency: "coin", Animation: "sky", Effect: "full_screen", Weight: 1000},
		},
	}
}

func NewGiftManager(config *GiftConfig) *GiftManager {
	if config == nil {
		config = DefaultGiftConfig()
	}

	gm := &GiftManager{
		config:    config,
		giftIndex: make(map[string]*Gift),
		combos:    make(map[string]*giftCombo),
	}

	for _, g := range config.Gifts {
		gm.giftIndex[g.ID] = g
	}

	return gm
}

func (gm *GiftManager) GetGift(giftID string) *Gift {
	gm.mu.RLock()
	defer gm.mu.RUnlock()
	return gm.giftIndex[giftID]
}

func (gm *GiftManager) GetAllGifts() []*Gift {
	gm.mu.RLock()
	defer gm.mu.RUnlock()
	result := make([]*Gift, len(gm.config.Gifts))
	copy(result, gm.config.Gifts)
	return result
}

func (gm *GiftManager) SendGift(
	roomID common.RoomID,
	senderID common.UserID,
	senderName string,
	receiverID common.UserID,
	giftID string,
	count int,
) (*GiftSendEvent, error) {

	if count <= 0 {
		return nil, fmt.Errorf("invalid gift count: %d", count)
	}

	gift := gm.GetGift(giftID)
	if gift == nil {
		return nil, fmt.Errorf("gift not found: %s", giftID)
	}

	gm.mu.Lock()
	comboKey := fmt.Sprintf("%s:%s:%s", roomID, senderID, giftID)
	now := common.NowMs()

	combo := gm.combos[comboKey]
	comboCount := 1

	if combo != nil && now-combo.LastSend <= gm.config.ComboWindowMs {
		combo.Count += count
		combo.LastSend = now
		if combo.Count > gm.config.MaxComboCount {
			combo.Count = gm.config.MaxComboCount
		}
		comboCount = combo.Count
	} else {
		combo = &giftCombo{
			GiftID:   giftID,
			Count:    count,
			LastSend: now,
			ComboID:  common.GenerateID(),
		}
		gm.combos[comboKey] = combo
	}
	gm.mu.Unlock()

	event := &GiftSendEvent{
		ID:         common.GenerateID(),
		RoomID:     roomID,
		SenderID:   senderID,
		SenderName: senderName,
		ReceiverID: receiverID,
		Gift:       gift,
		Count:      count,
		TotalPrice: gift.Price * int64(count),
		Timestamp:  now,
		ComboCount: comboCount,
		ComboID:    combo.ComboID,
	}

	return event, nil
}

func (gm *GiftManager) StartComboCleaner(interval time.Duration) {
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()

		for range ticker.C {
			gm.cleanupExpiredCombos()
		}
	}()
}

func (gm *GiftManager) cleanupExpiredCombos() {
	gm.mu.Lock()
	defer gm.mu.Unlock()

	now := common.NowMs()
	for key, combo := range gm.combos {
		if now-combo.LastSend > gm.config.ComboWindowMs*2 {
			delete(gm.combos, key)
		}
	}
}

func (gm *GiftManager) UpdateConfig(config *GiftConfig) {
	gm.mu.Lock()
	defer gm.mu.Unlock()

	gm.config = config
	gm.giftIndex = make(map[string]*Gift)
	for _, g := range config.Gifts {
		gm.giftIndex[g.ID] = g
	}
}

func (e *GiftSendEvent) ToJSON() (string, error) {
	data, err := json.Marshal(e)
	if err != nil {
		return "", err
	}
	return string(data), nil
}
