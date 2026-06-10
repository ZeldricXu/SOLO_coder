package match

import (
	"sync"
	"time"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/room"
)

type Service struct {
	matchers      map[common.GameType]*Matcher
	roomManager   *room.Manager
	robotFactory  *RobotFactory
	gameMinMax    map[common.GameType][2]int

	mu            sync.RWMutex
	running       bool
}

type RobotFactory struct {
	nicknames []string
	avatars   []string
	counter   int
	mu        sync.Mutex
}

func NewRobotFactory() *RobotFactory {
	return &RobotFactory{
		nicknames: []string{
			"AI-小明", "AI-小红", "AI-小刚", "AI-小丽",
			"AI-阿强", "AI-阿梅", "AI-老王", "AI-小李",
		},
		avatars: []string{
			"robot_1.png", "robot_2.png", "robot_3.png", "robot_4.png",
		},
	}
}

func (f *RobotFactory) CreateRobot(gameType common.GameType, elo float64) *common.Player {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.counter++
	idx := f.counter % len(f.nicknames)
	avatarIdx := f.counter % len(f.avatars)
	return &common.Player{
		UserID:   common.UserID(common.GenerateID()),
		Nickname: f.nicknames[idx],
		Avatar:   f.avatars[avatarIdx],
		Level:    1,
		Elo:      elo,
		IsRobot:  true,
		IsOnline: true,
		IsReady:  true,
		JoinedAt: time.Now(),
	}
}

func NewService(roomManager *room.Manager) *Service {
	return &Service{
		matchers:     make(map[common.GameType]*Matcher),
		roomManager:  roomManager,
		robotFactory: NewRobotFactory(),
		gameMinMax:   make(map[common.GameType][2]int),
	}
}

func (s *Service) RegisterGame(gameType common.GameType, minPlayers, maxPlayers int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	cfg := DefaultMatcherConfig(minPlayers, maxPlayers)
	s.matchers[gameType] = NewMatcher(nil, cfg)
	s.gameMinMax[gameType] = [2]int{minPlayers, maxPlayers}
	common.LogInfo("match service registered game: %s, min=%d, max=%d", gameType, minPlayers, maxPlayers)
}

func (s *Service) RequestMatch(req *common.MatchRequest) error {
	s.mu.RLock()
	matcher, ok := s.matchers[req.GameType]
	s.mu.RUnlock()
	if !ok {
		return common.ErrInvalidAction
	}
	return matcher.AddRequest(req)
}

func (s *Service) CancelMatch(userID common.UserID, gameType common.GameType) {
	s.mu.RLock()
	matcher, ok := s.matchers[gameType]
	s.mu.RUnlock()
	if ok {
		matcher.RemoveRequest(userID, gameType)
	}
}

func (s *Service) Start() {
	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return
	}
	s.running = true
	gameTypes := make([]common.GameType, 0, len(s.matchers))
	for gt := range s.matchers {
		gameTypes = append(gameTypes, gt)
	}
	s.mu.Unlock()

	go s.processMatchResults(gameTypes)
	for _, gt := range gameTypes {
		s.matchers[gt].StartTicker([]common.GameType{gt}, 2*time.Second)
	}
	common.LogInfo("match service started")
}

func (s *Service) processMatchResults(gameTypes []common.GameType) {
	for {
		s.mu.RLock()
		var firstCh <-chan MatchResult
		for _, matcher := range s.matchers {
			firstCh = matcher.NotifyChannel()
			break
		}
		s.mu.RUnlock()

		if firstCh == nil {
			time.Sleep(1 * time.Second)
			continue
		}

		allResults := make([]MatchResult, 0)
		s.mu.RLock()
		for gt, matcher := range s.matchers {
			_ = gt
			select {
			case r := <-matcher.NotifyChannel():
				allResults = append(allResults, r)
			default:
			}
		}
		s.mu.RUnlock()

		for _, result := range allResults {
			s.createRoomFromMatch(result)
		}

		time.Sleep(500 * time.Millisecond)
	}
}

func (s *Service) createRoomFromMatch(result MatchResult) {
	if len(result.Players) == 0 {
		return
	}

	minMax, ok := s.gameMinMax[result.GameType]
	if !ok {
		return
	}

	config := &common.RoomConfig{
		GameType:        result.GameType,
		MaxPlayers:      minMax[1],
		MinPlayers:      minMax[0],
		IsFriendRoom:    false,
		BaseScore:       100,
		TurnTimeoutSec:  15,
		ReadyTimeoutSec: 30,
		AllowObserver:   true,
		PlaybackEnabled: true,
	}

	var host *common.Player
	players := make([]*common.Player, 0, len(result.Players))

	for _, req := range result.Players {
		p := &common.Player{
			UserID:   req.UserID,
			Nickname: string(req.UserID),
			Level:    req.Level,
			Elo:      req.Elo,
			IsOnline: true,
			JoinedAt: time.Now(),
		}
		if len(req.UserID) > 5 && req.UserID[:5] == "robot" {
			p.IsRobot = true
			p.IsReady = true
			p.Nickname = "AI-Player"
		}
		players = append(players, p)
		if host == nil && !p.IsRobot {
			host = p
		}
	}

	if host == nil {
		host = players[0]
	}

	r, err := s.roomManager.CreateRoom(config, host)
	if err != nil {
		common.LogError("failed to create room for matched players: %v", err)
		return
	}

	for _, p := range players[1:] {
		if err := r.AddPlayer(p); err != nil {
			common.LogWarn("failed to add player %s to matched room: %v", p.UserID, err)
		}
	}

	for _, p := range players {
		if p.IsRobot {
			p.IsReady = true
		}
	}

	common.LogInfo("matched room created: %s, players=%d, reason=%s", r.ID, len(players), result.Reason)
}

func (s *Service) GetPoolSize(gameType common.GameType) int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if matcher, ok := s.matchers[gameType]; ok {
		return matcher.PoolSize(gameType)
	}
	return 0
}
