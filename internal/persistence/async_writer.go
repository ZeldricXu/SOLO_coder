package persistence

import (
	"context"
	"sync"
	"time"

	"pixelrealm/pkg/models"
)

type AsyncWriter struct {
	playerStore *PlayerStore
	writeQueue  chan *models.Player
	wg          sync.WaitGroup
	stopChan    chan struct{}
	batchSize   int
	flushInterval time.Duration
}

func NewAsyncWriter(store *PlayerStore, queueSize int, batchSize int, flushInterval time.Duration) *AsyncWriter {
	writer := &AsyncWriter{
		playerStore:   store,
		writeQueue:    make(chan *models.Player, queueSize),
		stopChan:      make(chan struct{}),
		batchSize:     batchSize,
		flushInterval: flushInterval,
	}
	
	return writer
}

func (w *AsyncWriter) Start() {
	w.wg.Add(1)
	go w.processLoop()
}

func (w *AsyncWriter) Stop() {
	close(w.stopChan)
	w.wg.Wait()
}

func (w *AsyncWriter) Write(player *models.Player) {
	select {
	case w.writeQueue <- player:
	default:
	}
}

func (w *AsyncWriter) processLoop() {
	defer w.wg.Done()
	
	ticker := time.NewTicker(w.flushInterval)
	defer ticker.Stop()
	
	batch := make([]*models.Player, 0, w.batchSize)
	
	for {
		select {
		case player := <-w.writeQueue:
			batch = append(batch, player)
			if len(batch) >= w.batchSize {
				w.flushBatch(batch)
				batch = batch[:0]
			}
		case <-ticker.C:
			if len(batch) > 0 {
				w.flushBatch(batch)
				batch = batch[:0]
			}
		case <-w.stopChan:
			if len(batch) > 0 {
				w.flushBatch(batch)
			}
			return
		}
	}
}

func (w *AsyncWriter) flushBatch(players []*models.Player) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	
	for _, player := range players {
		w.playerStore.Update(ctx, player)
	}
}

type PlayerCache struct {
	players     map[models.PlayerID]*models.Player
	playerStore *PlayerStore
	asyncWriter *AsyncWriter
	mu          sync.RWMutex
}

func NewPlayerCache(store *PlayerStore, writer *AsyncWriter) *PlayerCache {
	return &PlayerCache{
		players:     make(map[models.PlayerID]*models.Player),
		playerStore: store,
		asyncWriter: writer,
	}
}

func (c *PlayerCache) Get(playerID models.PlayerID) (*models.Player, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	
	player, exists := c.players[playerID]
	return player, exists
}

func (c *PlayerCache) Load(ctx context.Context, playerID models.PlayerID) (*models.Player, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	
	if player, exists := c.players[playerID]; exists {
		return player, nil
	}
	
	player, err := c.playerStore.FindByID(ctx, playerID)
	if err != nil {
		return nil, err
	}
	
	c.players[playerID] = player
	return player, nil
}

func (c *PlayerCache) Put(player *models.Player) {
	c.mu.Lock()
	defer c.mu.Unlock()
	
	c.players[player.PlayerID] = player
}

func (c *PlayerCache) Remove(playerID models.PlayerID) {
	c.mu.Lock()
	defer c.mu.Unlock()
	
	delete(c.players, playerID)
}

func (c *PlayerCache) Save(player *models.Player, async bool) {
	c.Put(player)
	
	if async && c.asyncWriter != nil {
		c.asyncWriter.Write(player)
	}
}

func (c *PlayerCache) FlushAll(ctx context.Context) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	
	for _, player := range c.players {
		c.playerStore.Update(ctx, player)
	}
}

func (c *PlayerCache) GetAllInMap(mapID string) []*models.Player {
	c.mu.RLock()
	defer c.mu.RUnlock()
	
	var result []*models.Player
	for _, player := range c.players {
		if player.OnlineStatus && player.Position.MapID == mapID {
			result = append(result, player)
		}
	}
	return result
}

func (c *PlayerCache) GetAllOnline() []*models.Player {
	c.mu.RLock()
	defer c.mu.RUnlock()
	
	var result []*models.Player
	for _, player := range c.players {
		if player.OnlineStatus {
			result = append(result, player)
		}
	}
	return result
}
