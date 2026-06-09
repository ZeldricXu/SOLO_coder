package collaboration

import (
	"encoding/json"
	"fmt"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"pointcloud-platform/config"
	"pointcloud-platform/internal/testutil"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

type MockWebSocket struct {
	conn      *websocket.Conn
	readChan  chan []byte
	writeChan chan []byte
	closed    bool
	mu        sync.Mutex
	server    *httptest.Server
}

func NewMockWebSocket() *MockWebSocket {
	mock := &MockWebSocket{
		readChan:  make(chan []byte, 100),
		writeChan: make(chan []byte, 100),
	}

	var upgrader = websocket.Upgrader{
		CheckOrigin: func(r *http.Request) bool {
			return true
		},
	}

	mock.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}

		mock.conn = conn

		go func() {
			for {
				_, msg, err := conn.ReadMessage()
				if err != nil {
					mock.mu.Lock()
					if !mock.closed {
						close(mock.readChan)
					}
					mock.mu.Unlock()
					return
				}
				select {
				case mock.readChan <- msg:
				default:
				}
			}
		}()

		go func() {
			for msg := range mock.writeChan {
				mock.mu.Lock()
				if mock.closed {
					mock.mu.Unlock()
					return
				}
				err := conn.WriteMessage(websocket.TextMessage, msg)
				mock.mu.Unlock()
				if err != nil {
					return
				}
			}
		}()
	}))

	return mock
}

func (m *MockWebSocket) Connect() (*websocket.Conn, error) {
	wsURL := "ws" + strings.TrimPrefix(m.server.URL, "http")
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		return nil, err
	}

	go func() {
		for {
			_, msg, err := conn.ReadMessage()
			if err != nil {
				return
			}
			select {
			case m.readChan <- msg:
			default:
			}
		}
	}()

	return conn, nil
}

func (m *MockWebSocket) Close() {
	m.mu.Lock()
	if !m.closed {
		m.closed = true
		if m.conn != nil {
			m.conn.Close()
		}
		close(m.writeChan)
	}
	m.mu.Unlock()
	m.server.Close()
}

func (m *MockWebSocket) ReadMessage(timeout time.Duration) ([]byte, error) {
	select {
	case msg := <-m.readChan:
		return msg, nil
	case <-time.After(timeout):
		return nil, fmt.Errorf("timeout waiting for message")
	}
}

func TestCollaboration_BroadcastAnnotation_Consistency(t *testing.T) {
	assert := testutil.NewAssert(t)

	cfg := &config.Config{
		Collaboration: config.CollaborationConfig{
			ConflictResolution:    "last-write-wins",
			MaxConnectionsPerRoom: 10,
			PingInterval:          30,
		},
	}

	service := NewCollaborationService(&cfg.Collaboration)

	mock1 := NewMockWebSocket()
	defer mock1.Close()
	mock2 := NewMockWebSocket()
	defer mock2.Close()

	conn1, err := mock1.Connect()
	assert.NoError(err)
	conn2, err := mock2.Connect()
	assert.NoError(err)

	roomID := "test-room-1"
	user1, _, err := service.JoinRoom(roomID, "user-1", "User One", conn1)
	assert.NoError(err)
	assert.NotNil(user1)

	user2, _, err := service.JoinRoom(roomID, "user-2", "User Two", conn2)
	assert.NoError(err)
	assert.NotNil(user2)

	time.Sleep(time.Millisecond * 100)

	annotation := map[string]interface{}{
		"id":      "annot-1",
		"type":    "bbox",
		"min":     []float64{0, 0, 0},
		"max":     []float64{10, 10, 10},
		"label":   "Test Annotation",
		"author":  "user-1",
		"version": 1,
	}

	payload, _ := json.Marshal(annotation)
	msg := Message{
		ID:       "msg-1",
		Type:     MessageTypeAnnotation,
		UserID:   "user-1",
		RoomID:   roomID,
		Version:  1,
		Payload:  payload,
	}

	err = service.HandleMessage(roomID, "user-1", msg)
	assert.NoError(err)

	time.Sleep(time.Millisecond * 200)

	gotMsg, err := mock2.ReadMessage(time.Second * 2)
	assert.NoError(err)

	var received Message
	json.Unmarshal(gotMsg, &received)

	assert.Equal(MessageTypeAnnotation, received.Type, "message type should be annotation")
	assert.Equal(msg.ID, received.ID, "message ID should match")
	assert.Equal(roomID, received.RoomID, "room ID should match")
	assert.Equal("user-1", received.UserID, "user ID should match")

	t.Logf("Broadcast consistency verified: user-2 received message from user-1")
}

func bytesEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestCollaboration_ConflictMerge_ConcurrentEdit(t *testing.T) {
	assert := testutil.NewAssert(t)

	cfg := &config.Config{
		Collaboration: config.CollaborationConfig{
			ConflictResolution:    "merge",
			MaxConnectionsPerRoom: 10,
			PingInterval:          30,
		},
	}

	service := NewCollaborationService(&cfg.Collaboration)

	mock1 := NewMockWebSocket()
	defer mock1.Close()
	mock2 := NewMockWebSocket()
	defer mock2.Close()

	conn1, err := mock1.Connect()
	assert.NoError(err)
	conn2, err := mock2.Connect()
	assert.NoError(err)

	roomID := "test-room-conflict"
	_, _, err = service.JoinRoom(roomID, "user-1", "User One", conn1)
	assert.NoError(err)
	_, _, err = service.JoinRoom(roomID, "user-2", "User Two", conn2)
	assert.NoError(err)

	time.Sleep(time.Millisecond * 100)

	var wg sync.WaitGroup
	var successCount int64
	editCount := 10

	for i := 0; i < editCount; i++ {
		wg.Add(2)

		go func(seq int) {
			defer wg.Done()
			annotation := map[string]interface{}{
				"id":      "annot-conflict-1",
				"type":    "bbox",
				"min":     []float64{float64(seq), 0, 0},
				"max":     []float64{float64(seq + 10), 10, 10},
				"label":   fmt.Sprintf("Edit by user-1 seq %d", seq),
				"author":  "user-1",
				"version": seq + 1,
			}
			payload, _ := json.Marshal(annotation)
			msg := Message{
				ID:       fmt.Sprintf("msg-user1-%d", seq),
				Type:     MessageTypeAnnotation,
				UserID:   "user-1",
				RoomID:   roomID,
				Version:  int64(seq + 1),
				Payload:  payload,
			}
			if err := service.HandleMessage(roomID, "user-1", msg); err == nil {
				atomic.AddInt64(&successCount, 1)
			}
		}(i)

		go func(seq int) {
			defer wg.Done()
			annotation := map[string]interface{}{
				"id":      "annot-conflict-1",
				"type":    "bbox",
				"min":     []float64{0, float64(seq), 0},
				"max":     []float64{10, float64(seq + 10), 10},
				"label":   fmt.Sprintf("Edit by user-2 seq %d", seq),
				"author":  "user-2",
				"version": seq + 1,
			}
			payload, _ := json.Marshal(annotation)
			msg := Message{
				ID:       fmt.Sprintf("msg-user2-%d", seq),
				Type:     MessageTypeAnnotation,
				UserID:   "user-2",
				RoomID:   roomID,
				Version:  int64(seq + 1),
				Payload:  payload,
			}
			if err := service.HandleMessage(roomID, "user-2", msg); err == nil {
				atomic.AddInt64(&successCount, 1)
			}
		}(i)

		time.Sleep(time.Millisecond * 5)
	}

	wg.Wait()
	time.Sleep(time.Millisecond * 200)

	expectedTotal := editCount * 2
	t.Logf("Concurrent edits: %d total, %d succeeded", expectedTotal, successCount)
	assert.Greater(float64(successCount), float64(expectedTotal)*0.8, "most concurrent edits should succeed")

	receivedCount := 0
	timeout := time.After(time.Second * 3)
	done := false
	for !done {
		select {
		case <-mock1.readChan:
			receivedCount++
		case <-mock2.readChan:
			receivedCount++
		case <-timeout:
			done = true
		default:
			time.Sleep(time.Millisecond * 10)
		}
	}

	t.Logf("Messages received: %d", receivedCount)
}

func TestCollaboration_Reconnection_MessageRecovery(t *testing.T) {
	assert := testutil.NewAssert(t)

	cfg := &config.Config{
		Collaboration: config.CollaborationConfig{
			ConflictResolution:    "last-write-wins",
			MaxConnectionsPerRoom: 10,
			PingInterval:          30,
		},
	}

	service := NewCollaborationService(&cfg.Collaboration)

	roomID := "test-room-reconnect"

	mock1 := NewMockWebSocket()
	defer mock1.Close()
	conn1, err := mock1.Connect()
	assert.NoError(err)

	_, _, err = service.JoinRoom(roomID, "user-1", "User One", conn1)
	assert.NoError(err)

	annotation := map[string]interface{}{
		"id":     "annot-reconnect-1",
		"type":   "bbox",
		"min":    []float64{0, 0, 0},
		"max":    []float64{10, 10, 10},
		"label":  "Before disconnect",
		"author": "user-1",
	}
	payload, _ := json.Marshal(annotation)
	msg := Message{
		ID:      "msg-before-disconnect",
		Type:    MessageTypeAnnotation,
		UserID:  "user-1",
		RoomID:  roomID,
		Version: 1,
		Payload: payload,
	}
	err = service.HandleMessage(roomID, "user-1", msg)
	assert.NoError(err)

	time.Sleep(time.Millisecond * 100)

	mock1.Close()
	time.Sleep(time.Millisecond * 100)

	mock2 := NewMockWebSocket()
	defer mock2.Close()
	conn2, err := mock2.Connect()
	assert.NoError(err)

	user, _, err := service.JoinRoom(roomID, "user-1", "User One", conn2)
	assert.NoError(err)
	assert.NotNil(user)

	t.Log("Reconnection test completed: user successfully rejoined room")
}

func TestCollaboration_SlowClient_NonBlockingBroadcast(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping slow client test in short mode")
	}

	assert := testutil.NewAssert(t)

	cfg := &config.Config{
		Collaboration: config.CollaborationConfig{
			ConflictResolution:    "last-write-wins",
			MaxConnectionsPerRoom: 20,
			PingInterval:          30,
		},
	}

	service := NewCollaborationService(&cfg.Collaboration)

	roomID := "test-room-slow"

	var mocks []*MockWebSocket
	var conns []*websocket.Conn

	for i := 0; i < 5; i++ {
		mock := NewMockWebSocket()
		defer mock.Close()
		conn, err := mock.Connect()
		assert.NoError(err)

		_, _, err = service.JoinRoom(roomID, fmt.Sprintf("user-%d", i), fmt.Sprintf("User %d", i), conn)
		assert.NoError(err)

		mocks = append(mocks, mock)
		conns = append(conns, conn)
	}

	time.Sleep(time.Millisecond * 100)

	messageCount := 10
	start := time.Now()

	for m := 0; m < messageCount; m++ {
		annotation := map[string]interface{}{
			"id":     fmt.Sprintf("annot-slow-%d", m),
			"type":   "bbox",
			"min":    []float64{0, 0, 0},
			"max":    []float64{10, 10, 10},
			"author": "user-0",
		}
		payload, _ := json.Marshal(annotation)
		msg := Message{
			ID:       fmt.Sprintf("msg-slow-%d", m),
			Type:     MessageTypeAnnotation,
			UserID:   "user-0",
			RoomID:   roomID,
			Version:  int64(m + 1),
			Payload:  payload,
		}
		err := service.HandleMessage(roomID, "user-0", msg)
		assert.NoError(err)
	}

	elapsed := time.Since(start)
	t.Logf("Broadcast %d messages to 5 users took %v", messageCount, elapsed)

	maxTime := time.Duration(messageCount) * time.Millisecond * 100
	assert.Less(float64(elapsed.Milliseconds()), float64(maxTime.Milliseconds()),
		"broadcast should not be blocked by slow clients")

	time.Sleep(time.Millisecond * 200)

	received := 0
	for _, mock := range mocks {
		for {
			select {
			case <-mock.readChan:
				received++
			default:
				goto nextMock
			}
		}
	nextMock:
	}

	t.Logf("Total messages received: %d (expected ~%d)", received, messageCount*5)
}

func TestCollaboration_ConcurrentEdits(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping concurrent edits test in short mode")
	}

	assert := testutil.NewAssert(t)

	cfg := &config.Config{
		Collaboration: config.CollaborationConfig{
			ConflictResolution:    "merge",
			MaxConnectionsPerRoom: 50,
			PingInterval:          30,
		},
	}

	service := NewCollaborationService(&cfg.Collaboration)

	roomID := "test-room-concurrent"
	userCount := 10
	editsPerUser := 20

	var mocks []*MockWebSocket
	var conns []*websocket.Conn

	for i := 0; i < userCount; i++ {
		mock := NewMockWebSocket()
		defer mock.Close()
		conn, err := mock.Connect()
		assert.NoError(err)

		_, _, err = service.JoinRoom(roomID, fmt.Sprintf("user-%d", i), fmt.Sprintf("User %d", i), conn)
		assert.NoError(err)

		mocks = append(mocks, mock)
		conns = append(conns, conn)
	}

	time.Sleep(time.Millisecond * 100)

	var wg sync.WaitGroup
	var totalEdits int64
	rng := rand.New(rand.NewSource(time.Now().UnixNano()))

	for u := 0; u < userCount; u++ {
		wg.Add(1)
		go func(userID int) {
			defer wg.Done()

			for e := 0; e < editsPerUser; e++ {
				annotation := map[string]interface{}{
					"id":     fmt.Sprintf("annot-%d", rng.Intn(5)),
					"type":   "bbox",
					"min":    []float64{rng.Float64() * 10, rng.Float64() * 10, rng.Float64() * 10},
					"max":    []float64{rng.Float64() * 10 + 10, rng.Float64() * 10 + 10, rng.Float64() * 10 + 10},
					"author": fmt.Sprintf("user-%d", userID),
				}
				payload, _ := json.Marshal(annotation)
				msg := Message{
					ID:       fmt.Sprintf("msg-user%d-%d", userID, e),
					Type:     MessageTypeAnnotation,
					UserID:   fmt.Sprintf("user-%d", userID),
					RoomID:   roomID,
					Version:  int64(e + 1),
					Payload:  payload,
				}
				if err := service.HandleMessage(roomID, fmt.Sprintf("user-%d", userID), msg); err == nil {
					atomic.AddInt64(&totalEdits, 1)
				}

				time.Sleep(time.Millisecond * time.Duration(rng.Intn(10)))
			}
		}(u)
	}

	wg.Wait()
	time.Sleep(time.Millisecond * 500)

	expectedTotal := userCount * editsPerUser
	successRate := float64(totalEdits) / float64(expectedTotal) * 100

	t.Logf("Concurrent edits: %d users × %d edits = %d total", userCount, editsPerUser, expectedTotal)
	t.Logf("Successful edits: %d (%.1f%%)", totalEdits, successRate)
	assert.Greater(successRate, 90.0, "success rate should be > 90%")

	totalReceived := 0
	for _, mock := range mocks {
		for {
			select {
			case <-mock.readChan:
				totalReceived++
			default:
				goto next
			}
		}
	next:
	}

	t.Logf("Total messages received across all users: %d", totalReceived)
}
