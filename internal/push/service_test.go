package push

import (
	"GameLeaderboard/internal/config"
	"encoding/json"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestBroadcastMessageSerialization(t *testing.T) {
	timestamp := time.Now()

	msg := &BroadcastMessage{
		GameID:    "test_game",
		SeasonID:  "test_season",
		Type:      "rank_change",
		Timestamp: timestamp,
		Data: map[string]interface{}{
			"player_id": "player_001",
			"old_rank":  10,
			"new_rank":  5,
			"old_score": 800,
			"new_score": 900,
		},
	}

	data, err := json.Marshal(msg)
	require.NoError(t, err)
	assert.NotEmpty(t, data)

	var decoded BroadcastMessage
	err = json.Unmarshal(data, &decoded)
	require.NoError(t, err)

	assert.Equal(t, msg.GameID, decoded.GameID)
	assert.Equal(t, msg.SeasonID, decoded.SeasonID)
	assert.Equal(t, msg.Type, decoded.Type)

	decodedData, ok := decoded.Data.(map[string]interface{})
	assert.True(t, ok)
	assert.Equal(t, "player_001", decodedData["player_id"])
	assert.Equal(t, float64(10), decodedData["old_rank"])
	assert.Equal(t, float64(5), decodedData["new_rank"])
}

func TestBroadcastMessageTypes(t *testing.T) {
	testCases := []struct {
		name     string
		msgType  string
		data     map[string]interface{}
		validate func(*testing.T, map[string]interface{})
	}{
		{
			name:    "rank_change_message",
			msgType: "rank_change",
			data: map[string]interface{}{
				"player_id": "p1",
				"old_rank":  5,
				"new_rank":  3,
				"old_score": 800,
				"new_score": 900,
			},
			validate: func(t *testing.T, data map[string]interface{}) {
				assert.Equal(t, "p1", data["player_id"])
				assert.Equal(t, float64(5), data["old_rank"])
				assert.Equal(t, float64(3), data["new_rank"])
			},
		},
		{
			name:    "batch_rank_change_message",
			msgType: "batch_rank_change",
			data: map[string]interface{}{
				"events": []interface{}{
					map[string]interface{}{"player_id": "p1", "new_rank": 1},
					map[string]interface{}{"player_id": "p2", "new_rank": 2},
				},
				"count": 2,
			},
			validate: func(t *testing.T, data map[string]interface{}) {
				events, ok := data["events"].([]interface{})
				assert.True(t, ok)
				assert.Len(t, events, 2)
				assert.Equal(t, float64(2), data["count"])
			},
		},
		{
			name:    "season_switch_message",
			msgType: "season_switch",
			data: map[string]interface{}{
				"old_season_id": "season_old",
				"new_season_id": "season_new",
			},
			validate: func(t *testing.T, data map[string]interface{}) {
				assert.Equal(t, "season_old", data["old_season_id"])
				assert.Equal(t, "season_new", data["new_season_id"])
			},
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			msg := &BroadcastMessage{
				GameID:    "test_game",
				Type:      tc.msgType,
				Timestamp: time.Now(),
				Data:      tc.data,
			}

			data, err := json.Marshal(msg)
			require.NoError(t, err)

			var decoded BroadcastMessage
			err = json.Unmarshal(data, &decoded)
			require.NoError(t, err)

			decodedData, ok := decoded.Data.(map[string]interface{})
			assert.True(t, ok)
			tc.validate(t, decodedData)
		})
	}
}

func TestPushServiceInitialization(t *testing.T) {
	t.Run("with_nil_config", func(t *testing.T) {
		service := NewPushService(nil)

		assert.NotNil(t, service)
		assert.NotNil(t, service.config)
		assert.NotNil(t, service.clients)
		assert.NotNil(t, service.games)
		assert.NotNil(t, service.broadcast)
		assert.NotNil(t, service.register)
		assert.NotNil(t, service.unregister)
	})

	t.Run("with_custom_config", func(t *testing.T) {
		cfg := &config.WebSocketConfig{
			ReadBufferSize:  2048,
			WriteBufferSize: 2048,
			WriteWait:       20,
			PongWait:        120,
			PingPeriod:      100,
		}

		service := NewPushService(cfg)

		assert.Equal(t, cfg, service.config)
	})
}

func TestClientRegistration(t *testing.T) {
	service := &PushService{
		config: &config.WebSocketConfig{
			ReadBufferSize:  1024,
			WriteBufferSize: 1024,
		},
		clients: make(map[string]map[*Client]bool),
		games:   make(map[string]map[*Client]bool),
		mu:      sync.RWMutex{},
	}

	client := &Client{
		gameID:   "game_001",
		playerID: "player_001",
		send:     make(chan []byte, 256),
	}

	t.Run("register_new_client", func(t *testing.T) {
		service.mu.Lock()
		if service.clients[client.playerID] == nil {
			service.clients[client.playerID] = make(map[*Client]bool)
		}
		service.clients[client.playerID][client] = true

		if service.games[client.gameID] == nil {
			service.games[client.gameID] = make(map[*Client]bool)
		}
		service.games[client.gameID][client] = true
		service.mu.Unlock()

		assert.Equal(t, 1, service.GetPlayerClientCount("player_001"))
		assert.Equal(t, 1, service.GetGameClientCount("game_001"))
		assert.Equal(t, 1, service.GetTotalClientCount())
	})

	t.Run("register_second_client_same_game", func(t *testing.T) {
		client2 := &Client{
			gameID:   "game_001",
			playerID: "player_002",
			send:     make(chan []byte, 256),
		}

		service.mu.Lock()
		if service.clients[client2.playerID] == nil {
			service.clients[client2.playerID] = make(map[*Client]bool)
		}
		service.clients[client2.playerID][client2] = true

		if service.games[client2.gameID] == nil {
			service.games[client2.gameID] = make(map[*Client]bool)
		}
		service.games[client2.gameID][client2] = true
		service.mu.Unlock()

		assert.Equal(t, 2, service.GetGameClientCount("game_001"))
		assert.Equal(t, 2, service.GetTotalClientCount())
	})

	t.Run("unregister_client", func(t *testing.T) {
		service.mu.Lock()
		if service.clients[client.playerID] != nil {
			if _, ok := service.clients[client.playerID][client]; ok {
				delete(service.clients[client.playerID], client)
				if len(service.clients[client.playerID]) == 0 {
					delete(service.clients, client.playerID)
				}
			}
		}

		if service.games[client.gameID] != nil {
			if _, ok := service.games[client.gameID][client]; ok {
				delete(service.games[client.gameID], client)
				if len(service.games[client.gameID]) == 0 {
					delete(service.games, client.gameID)
				}
			}
		}
		service.mu.Unlock()

		assert.Equal(t, 0, service.GetPlayerClientCount("player_001"))
		assert.Equal(t, 1, service.GetGameClientCount("game_001"))
		assert.Equal(t, 1, service.GetTotalClientCount())
	})
}

func TestClientCountFunctions(t *testing.T) {
	service := &PushService{
		clients: make(map[string]map[*Client]bool),
		games:   make(map[string]map[*Client]bool),
		mu:      sync.RWMutex{},
	}

	t.Run("count_with_no_clients", func(t *testing.T) {
		assert.Equal(t, 0, service.GetTotalClientCount())
		assert.Equal(t, 0, service.GetGameClientCount("non_existent"))
		assert.Equal(t, 0, service.GetPlayerClientCount("non_existent"))
	})

	t.Run("count_with_multiple_clients", func(t *testing.T) {
		c1 := &Client{gameID: "g1", playerID: "p1", send: make(chan []byte)}
		c2 := &Client{gameID: "g1", playerID: "p2", send: make(chan []byte)}
		c3 := &Client{gameID: "g2", playerID: "p3", send: make(chan []byte)}

		service.mu.Lock()
		service.clients["p1"] = map[*Client]bool{c1: true}
		service.clients["p2"] = map[*Client]bool{c2: true}
		service.clients["p3"] = map[*Client]bool{c3: true}
		service.games["g1"] = map[*Client]bool{c1: true, c2: true}
		service.games["g2"] = map[*Client]bool{c3: true}
		service.mu.Unlock()

		assert.Equal(t, 3, service.GetTotalClientCount())
		assert.Equal(t, 2, service.GetGameClientCount("g1"))
		assert.Equal(t, 1, service.GetGameClientCount("g2"))
		assert.Equal(t, 1, service.GetPlayerClientCount("p1"))
		assert.Equal(t, 0, service.GetGameClientCount("g3"))
	})
}

func TestBroadcastToGame(t *testing.T) {
	service := &PushService{
		config: &config.WebSocketConfig{},
		clients: make(map[string]map[*Client]bool),
		games:   make(map[string]map[*Client]bool),
		broadcast: make(chan *BroadcastMessage, 100),
		mu:      sync.RWMutex{},
	}

	msg := &BroadcastMessage{
		GameID:    "",
		Type:      "test_message",
		Timestamp: time.Now(),
		Data:      map[string]interface{}{"key": "value"},
	}

	service.BroadcastToGame("test_game", msg)

	select {
	case received := <-service.broadcast:
		assert.Equal(t, "test_game", received.GameID)
		assert.Equal(t, "test_message", received.Type)
	case <-time.After(100 * time.Millisecond):
		t.Error("Message not sent to broadcast channel")
	}
}

func TestBroadcastMessageGameIDSetting(t *testing.T) {
	service := &PushService{
		config:    &config.WebSocketConfig{},
		clients:   make(map[string]map[*Client]bool),
		games:     make(map[string]map[*Client]bool),
		broadcast: make(chan *BroadcastMessage, 100),
		mu:        sync.RWMutex{},
	}

	t.Run("message_without_game_id", func(t *testing.T) {
		msg := &BroadcastMessage{
			GameID:    "",
			Type:      "test",
			Timestamp: time.Now(),
		}

		service.BroadcastToGame("specified_game", msg)

		received := <-service.broadcast
		assert.Equal(t, "specified_game", received.GameID)
	})

	t.Run("message_with_existing_game_id", func(t *testing.T) {
		msg := &BroadcastMessage{
			GameID:    "original_game",
			Type:      "test",
			Timestamp: time.Now(),
		}

		service.BroadcastToGame("new_game", msg)

		received := <-service.broadcast
		assert.Equal(t, "original_game", received.GameID)
	})
}

func TestBroadcastMessageDataTypes(t *testing.T) {
	testCases := []struct {
		name     string
		data     map[string]interface{}
		expected string
	}{
		{
			name: "string_values",
			data: map[string]interface{}{
				"player_id": "player_123",
				"game_id":   "game_abc",
			},
		},
		{
			name: "numeric_values",
			data: map[string]interface{}{
				"rank":   5,
				"score":  1500,
				"delta":  -50,
			},
		},
		{
			name: "boolean_values",
			data: map[string]interface{}{
				"is_new":        true,
				"has_reward":    false,
				"requires_sync": true,
			},
		},
		{
			name: "nested_objects",
			data: map[string]interface{}{
				"player": map[string]interface{}{
					"id":   "p1",
					"name": "Player One",
				},
				"reward": map[string]interface{}{
					"type":  "gems",
					"value": 100,
				},
			},
		},
		{
			name: "array_values",
			data: map[string]interface{}{
				"affected_players": []string{"p1", "p2", "p3"},
				"ranks":            []int{1, 2, 3},
			},
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			msg := &BroadcastMessage{
				GameID:    "test",
				Type:      "test",
				Timestamp: time.Now(),
				Data:      tc.data,
			}

			data, err := json.Marshal(msg)
			require.NoError(t, err)

			var decoded BroadcastMessage
			err = json.Unmarshal(data, &decoded)
			require.NoError(t, err)

			assert.NotNil(t, decoded.Data)
		})
	}
}

func TestEmptyBroadcastMessage(t *testing.T) {
	msg := &BroadcastMessage{}

	data, err := json.Marshal(msg)
	require.NoError(t, err)

	var decoded BroadcastMessage
	err = json.Unmarshal(data, &decoded)
	require.NoError(t, err)

	assert.Empty(t, decoded.GameID)
	assert.Empty(t, decoded.SeasonID)
	assert.Empty(t, decoded.Type)
	assert.Nil(t, decoded.Data)
}

func TestBroadcastMessageWithNilData(t *testing.T) {
	msg := &BroadcastMessage{
		GameID:    "test_game",
		Type:      "heartbeat",
		Timestamp: time.Now(),
		Data:      nil,
	}

	data, err := json.Marshal(msg)
	require.NoError(t, err)

	var decoded BroadcastMessage
	err = json.Unmarshal(data, &decoded)
	require.NoError(t, err)

	assert.Nil(t, decoded.Data)
}

func TestWebSocketConfig(t *testing.T) {
	cfg := &config.WebSocketConfig{
		ReadBufferSize:  4096,
		WriteBufferSize: 4096,
		WriteWait:       30,
		PongWait:        180,
		PingPeriod:      150,
	}

	assert.Equal(t, 4096, cfg.ReadBufferSize)
	assert.Equal(t, 4096, cfg.WriteBufferSize)
	assert.Equal(t, 30, cfg.WriteWait)
	assert.Equal(t, 180, cfg.PongWait)
	assert.Equal(t, 150, cfg.PingPeriod)

	assert.True(t, time.Duration(cfg.PingPeriod)*time.Second < time.Duration(cfg.PongWait)*time.Second)
}

func TestClientStructure(t *testing.T) {
	client := &Client{
		gameID:   "game_001",
		playerID: "player_001",
		send:     make(chan []byte, 256),
	}

	assert.Equal(t, "game_001", client.gameID)
	assert.Equal(t, "player_001", client.playerID)
	assert.NotNil(t, client.send)

	select {
	case client.send <- []byte("test message"):
		msg := <-client.send
		assert.Equal(t, []byte("test message"), msg)
	default:
		t.Error("Send channel should accept messages")
	}
}

func TestConcurrencySafety(t *testing.T) {
	service := &PushService{
		clients: make(map[string]map[*Client]bool),
		games:   make(map[string]map[*Client]bool),
		mu:      sync.RWMutex{},
	}

	var wg sync.WaitGroup
	clientCount := 100

	for i := 0; i < clientCount; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			client := &Client{
				gameID:   fmtGameID(idx % 3),
				playerID: fmtPlayerID(idx),
				send:     make(chan []byte),
			}

			service.mu.Lock()
			if service.clients[client.playerID] == nil {
				service.clients[client.playerID] = make(map[*Client]bool)
			}
			service.clients[client.playerID][client] = true

			if service.games[client.gameID] == nil {
				service.games[client.gameID] = make(map[*Client]bool)
			}
			service.games[client.gameID][client] = true
			service.mu.Unlock()
		}(i)
	}

	wg.Wait()

	assert.Equal(t, clientCount, service.GetTotalClientCount())
}

func fmtGameID(i int) string {
	return fmt.Sprintf("game_%d", i)
}

func fmtPlayerID(i int) string {
	return fmt.Sprintf("player_%d", i)
}

func TestBroadcastMessageTimestamps(t *testing.T) {
	before := time.Now()
	msg := &BroadcastMessage{
		GameID:    "test",
		Type:      "test",
		Timestamp: time.Now(),
		Data:      map[string]interface{}{},
	}
	after := time.Now()

	assert.True(t, msg.Timestamp.After(before) || msg.Timestamp.Equal(before))
	assert.True(t, msg.Timestamp.Before(after) || msg.Timestamp.Equal(after))

	data, err := json.Marshal(msg)
	require.NoError(t, err)

	var decoded BroadcastMessage
	err = json.Unmarshal(data, &decoded)
	require.NoError(t, err)

	assert.WithinDuration(t, msg.Timestamp, decoded.Timestamp, time.Second)
}
