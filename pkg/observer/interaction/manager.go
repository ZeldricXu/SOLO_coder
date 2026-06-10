package interaction

import (
	"sync"

	"github.com/go-redis/redis/v8"
	"github.com/studio/gameroom/pkg/common"
)

type InteractionManager struct {
	Danmaku *DanmakuManager
	Gift    *GiftManager
	Bet     *BetManager
	enabled  bool
	mu       sync.RWMutex
	roomData map[common.RoomID]*RoomInteraction
}

type RoomInteraction struct {
	RoomID      common.RoomID
	DanmakuEnabled bool
	GiftEnabled    bool
	BetEnabled      bool
}

type InteractionConfig struct {
	EnableDanmaku bool
	EnableGift    bool
	EnableBet      bool
	GiftConfig    *GiftConfig
}

func DefaultInteractionConfig() *InteractionConfig {
	return &InteractionConfig{
		EnableDanmaku: true,
		EnableGift:    true,
		EnableBet:      true,
		GiftConfig:  DefaultGiftConfig(),
	}
}

func NewInteractionManager(redisClient *redis.Client, config *InteractionConfig) *InteractionManager {
	if config == nil {
		config = DefaultInteractionConfig()
	}

	im := &InteractionManager{
		enabled:    true,
		roomData: make(map[common.RoomID]*RoomInteraction),
	}

	if config.EnableDanmaku {
		im.Danmaku = NewDanmakuManager(redisClient)
	}

	if config.EnableGift {
		im.Gift = NewGiftManager(config.GiftConfig)
		im.Gift.StartComboCleaner(5 * 1000)
	}

	if config.EnableBet {
		im.Bet = NewBetManager(redisClient)
	}

	return im
}

func (im *InteractionManager) RegisterRoom(roomID common.RoomID) {
	im.mu.Lock()
	defer im.mu.Unlock()

	im.roomData[roomID] = &RoomInteraction{
		RoomID:         roomID,
		DanmakuEnabled: im.Danmaku != nil,
		GiftEnabled:    im.Gift != nil,
		BetEnabled:     im.Bet != nil,
	}
}

func (im *InteractionManager) UnregisterRoom(roomID common.RoomID) {
	im.mu.Lock()
	defer im.mu.Unlock()

	if im.Danmaku != nil {
		im.Danmaku.Cleanup(roomID)
	}
	if im.Bet != nil {
		im.Bet.Cleanup(roomID)
	}
	delete(im.roomData, roomID)
}

func (im *InteractionManager) GetRoomInteraction(roomID common.RoomID) *RoomInteraction {
	im.mu.RLock()
	defer im.mu.RUnlock()

	if ri, ok := im.roomData[roomID]; ok {
		return ri
	}
	return nil
}

func (im *InteractionManager) SetEnabled(roomID common.RoomID, danmaku, gift, bet bool) {
	im.mu.Lock()
	defer im.mu.Unlock()

	ri, ok := im.roomData[roomID]
	if !ok {
		ri = &RoomInteraction{RoomID: roomID}
		im.roomData[roomID] = ri
	}
	ri.DanmakuEnabled = danmaku && im.Danmaku != nil
	ri.GiftEnabled = gift && im.Gift != nil
	ri.BetEnabled = bet && im.Bet != nil
}

func (im *InteractionManager) IsDanmakuEnabled(roomID common.RoomID) bool {
	im.mu.RLock()
	defer im.mu.RUnlock()

	if ri, ok := im.roomData[roomID]; ok {
		return ri.DanmakuEnabled
	}
	return false
}

func (im *InteractionManager) IsGiftEnabled(roomID common.RoomID) bool {
	im.mu.RLock()
	defer im.mu.RUnlock()

	if ri, ok := im.roomData[roomID]; ok {
		return ri.GiftEnabled
	}
	return false
}

func (im *InteractionManager) IsBetEnabled(roomID common.RoomID) bool {
	im.mu.RLock()
	defer im.mu.RUnlock()

	if ri, ok := im.roomData[roomID]; ok {
		return ri.BetEnabled
	}
	return false
}

func (im *InteractionManager) Close() {
	im.mu.Lock()
	defer im.mu.Unlock()

	for roomID := range im.roomData {
		if im.Danmaku != nil {
			im.Danmaku.Cleanup(roomID)
		}
		if im.Bet != nil {
			im.Bet.Cleanup(roomID)
		}
	}
	im.roomData = make(map[common.RoomID]*RoomInteraction)
}
