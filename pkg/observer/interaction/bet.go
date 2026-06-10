package interaction

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/go-redis/redis/v8"
	"github.com/studio/gameroom/pkg/common"
)

type BetSide int

const (
	BetSidePlayerA BetSide = 1
	BetSidePlayerB BetSide = 2
	BetSideDraw    BetSide = 3
)

type BetStatus int

const (
	BetStatusOpen    BetStatus = 1
	BetStatusClosed  BetStatus = 2
	BetStatusSettled BetStatus = 3
	BetStatusCancelled BetStatus = 4
)

type Bet struct {
	ID         string        `json:"id"`
	RoomID     common.RoomID `json:"room_id"`
	Title      string        `json:"title"`
	Options    []*BetOption  `json:"options"`
	Status     BetStatus     `json:"status"`
	ResultSide BetSide       `json:"result_side,omitempty"`
	OpenedAt   int64         `json:"opened_at"`
	ClosedAt   int64         `json:"closed_at,omitempty"`
	SettledAt  int64         `json:"settled_at,omitempty"`
	TotalPool  int64         `json:"total_pool"`
	CreatorID  common.UserID `json:"creator_id"`
}

type BetOption struct {
	Side        BetSide `json:"side"`
	Name        string  `json:"name"`
	TotalAmount int64   `json:"total_amount"`
	BetCount    int     `json:"bet_count"`
	Odds        float64 `json:"odds,omitempty"`
}

type UserBet struct {
	ID        string        `json:"id"`
	BetID     string        `json:"bet_id"`
	RoomID    common.RoomID `json:"room_id"`
	UserID    common.UserID `json:"user_id"`
	Side      BetSide       `json:"side"`
	Amount    int64         `json:"amount"`
	PlacedAt  int64         `json:"placed_at"`
	IsWinner  bool          `json:"is_winner,omitempty"`
	Payout    int64         `json:"payout,omitempty"`
	Settled   bool          `json:"settled"`
}

type BetManager struct {
	redis     *redis.Client
	mu        sync.RWMutex
	bets      map[common.RoomID]*Bet
	userBets  map[string][]*UserBet
	balanceFn func(userID common.UserID) (int64, error)
	debitFn   func(userID common.UserID, amount int64) error
	creditFn  func(userID common.UserID, amount int64) error
}

func NewBetManager(redisClient *redis.Client) *BetManager {
	return &BetManager{
		redis:    redisClient,
		bets:     make(map[common.RoomID]*Bet),
		userBets: make(map[string][]*UserBet),
	}
}

func (bm *BetManager) SetBalanceHandlers(
	getBalance func(userID common.UserID) (int64, error),
	debit func(userID common.UserID, amount int64) error,
	credit func(userID common.UserID, amount int64) error,
) {
	bm.balanceFn = getBalance
	bm.debitFn = debit
	bm.creditFn = credit
}

func (bm *BetManager) OpenBet(roomID common.RoomID, creatorID common.UserID, title string, sideAName string, sideBName string) (*Bet, error) {
	bm.mu.Lock()
	defer bm.mu.Unlock()

	if existing, ok := bm.bets[roomID]; ok && existing.Status == BetStatusOpen {
		return nil, fmt.Errorf("bet already open for this room")
	}

	bet := &Bet{
		ID:        common.GenerateID(),
		RoomID:    roomID,
		Title:     title,
		Status:    BetStatusOpen,
		OpenedAt:  common.NowMs(),
		CreatorID: creatorID,
		Options: []*BetOption{
			{Side: BetSidePlayerA, Name: sideAName},
			{Side: BetSidePlayerB, Name: sideBName},
		},
	}

	bm.bets[roomID] = bet

	if bm.redis != nil {
		bm.saveBetToRedis(bet)
	}

	return bet, nil
}

func (bm *BetManager) PlaceBet(roomID common.RoomID, userID common.UserID, side BetSide, amount int64) (*UserBet, error) {
	if amount <= 0 {
		return nil, fmt.Errorf("invalid bet amount: %d", amount)
	}

	bm.mu.Lock()
	defer bm.mu.Unlock()

	bet, ok := bm.bets[roomID]
	if !ok || bet.Status != BetStatusOpen {
		return nil, fmt.Errorf("no open bet for this room")
	}

	if bm.balanceFn != nil {
		balance, err := bm.balanceFn(userID)
		if err != nil {
			return nil, err
		}
		if balance < amount {
			return nil, fmt.Errorf("insufficient balance")
		}
	}

	if bm.debitFn != nil {
		if err := bm.debitFn(userID, amount); err != nil {
			return nil, err
		}
	}

	userBet := &UserBet{
		ID:       common.GenerateID(),
		BetID:    bet.ID,
		RoomID:   roomID,
		UserID:   userID,
		Side:     side,
		Amount:   amount,
		PlacedAt: common.NowMs(),
	}

	for _, opt := range bet.Options {
		if opt.Side == side {
			opt.TotalAmount += amount
			opt.BetCount++
		}
	}
	bet.TotalPool += amount

	bm.calculateOdds(bet)

	bm.userBets[bet.ID] = append(bm.userBets[bet.ID], userBet)

	if bm.redis != nil {
		bm.saveBetToRedis(bet)
		bm.saveUserBetToRedis(userBet)
	}

	return userBet, nil
}

func (bm *BetManager) CloseBet(roomID common.RoomID) (*Bet, error) {
	bm.mu.Lock()
	defer bm.mu.Unlock()

	bet, ok := bm.bets[roomID]
	if !ok {
		return nil, fmt.Errorf("no bet found for this room")
	}
	if bet.Status != BetStatusOpen {
		return nil, fmt.Errorf("bet is not open")
	}

	bet.Status = BetStatusClosed
	bet.ClosedAt = common.NowMs()

	if bm.redis != nil {
		bm.saveBetToRedis(bet)
	}

	return bet, nil
}

func (bm *BetManager) SettleBet(roomID common.RoomID, winnerSide BetSide) (*Bet, []*UserBet, error) {
	bm.mu.Lock()
	defer bm.mu.Unlock()

	bet, ok := bm.bets[roomID]
	if !ok {
		return nil, nil, fmt.Errorf("no bet found for this room")
	}
	if bet.Status != BetStatusClosed {
		return nil, nil, fmt.Errorf("bet is not closed")
	}

	bet.Status = BetStatusSettled
	bet.ResultSide = winnerSide
	bet.SettledAt = common.NowMs()

	settledBets := make([]*UserBet, 0)
	userBets := bm.userBets[bet.ID]

	var winnerTotal int64
	for _, ub := range userBets {
		if ub.Side == winnerSide {
			winnerTotal += ub.Amount
		}
	}

	if winnerTotal > 0 && bm.creditFn != nil {
		for _, ub := range userBets {
			if ub.Side == winnerSide {
				ratio := float64(ub.Amount) / float64(winnerTotal)
				payout := int64(float64(bet.TotalPool) * ratio)
				ub.IsWinner = true
				ub.Payout = payout
				ub.Settled = true

				if err := bm.creditFn(ub.UserID, payout); err != nil {
					common.LogWarn("failed to credit payout to user %s: %v", ub.UserID, err)
				}
				settledBets = append(settledBets, ub)
			} else {
				ub.IsWinner = false
				ub.Settled = true
			}
		}
	}

	if bm.redis != nil {
		bm.saveBetToRedis(bet)
		for _, ub := range userBets {
			bm.saveUserBetToRedis(ub)
		}
	}

	return bet, settledBets, nil
}

func (bm *BetManager) CancelBet(roomID common.RoomID) (*Bet, error) {
	bm.mu.Lock()
	defer bm.mu.Unlock()

	bet, ok := bm.bets[roomID]
	if !ok {
		return nil, fmt.Errorf("no bet found for this room")
	}

	if bet.Status == BetStatusSettled {
		return nil, fmt.Errorf("bet already settled")
	}

	bet.Status = BetStatusCancelled

	userBets := bm.userBets[bet.ID]
	if bm.creditFn != nil {
		for _, ub := range userBets {
			if !ub.Settled {
				ub.Settled = true
				if err := bm.creditFn(ub.UserID, ub.Amount); err != nil {
					common.LogWarn("failed to refund bet to user %s: %v", ub.UserID, err)
				}
			}
		}
	}

	if bm.redis != nil {
		bm.saveBetToRedis(bet)
	}

	return bet, nil
}

func (bm *BetManager) GetCurrentBet(roomID common.RoomID) *Bet {
	bm.mu.RLock()
	defer bm.mu.RUnlock()

	if bet, ok := bm.bets[roomID]; ok {
		return bet
	}
	return nil
}

func (bm *BetManager) GetUserBets(roomID common.RoomID, userID common.UserID) []*UserBet {
	bm.mu.RLock()
	defer bm.mu.RUnlock()

	bet := bm.bets[roomID]
	if bet == nil {
		return nil
	}

	result := make([]*UserBet, 0)
	for _, ub := range bm.userBets[bet.ID] {
		if ub.UserID == userID {
			result = append(result, ub)
		}
	}
	return result
}

func (bm *BetManager) calculateOdds(bet *Bet) {
	if bet.TotalPool == 0 {
		return
	}

	for _, opt := range bet.Options {
		if opt.TotalAmount > 0 {
			opt.Odds = float64(bet.TotalPool) / float64(opt.TotalAmount)
		} else {
			opt.Odds = float64(bet.TotalPool) / 1.0
		}
	}
}

func (bm *BetManager) saveBetToRedis(bet *Bet) {
	ctx := context.Background()
	key := fmt.Sprintf("bet:%s", bet.RoomID)
	data, err := json.Marshal(bet)
	if err == nil {
		bm.redis.Set(ctx, key, data, 24*time.Hour)
	}
}

func (bm *BetManager) saveUserBetToRedis(ub *UserBet) {
	ctx := context.Background()
	key := fmt.Sprintf("user_bet:%s:%s", ub.BetID, ub.ID)
	data, err := json.Marshal(ub)
	if err == nil {
		bm.redis.Set(ctx, key, data, 24*time.Hour)
	}
}

func (bm *BetManager) Cleanup(roomID common.RoomID) {
	bm.mu.Lock()
	defer bm.mu.Unlock()

	bet := bm.bets[roomID]
	if bet != nil {
		delete(bm.userBets, bet.ID)
	}
	delete(bm.bets, roomID)

	if bm.redis != nil {
		ctx := context.Background()
		bm.redis.Del(ctx, fmt.Sprintf("bet:%s", roomID))
	}
}
