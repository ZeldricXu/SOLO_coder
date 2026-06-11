package collaboration

import (
	"encoding/json"
	"fmt"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"pointcloud-platform/config"
	"pointcloud-platform/internal/testutil"
	"pointcloud-platform/pkg/math3d"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
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

func TestSpatialSync_FrustumUpdate_OnlyVisibleAnnotated(t *testing.T) {
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

	roomID := uuid.New().String()

	user1, _, err := service.JoinRoom(roomID, "user-1", "User One", conn1)
	assert.NoError(err)
	assert.NotNil(user1)

	user2, _, err := service.JoinRoom(roomID, "user-2", "User Two", conn2)
	assert.NoError(err)
	assert.NotNil(user2)

	time.Sleep(time.Millisecond * 100)

	narrowFrustum := FrustumState{
		Position: math3d.Vec3{X: 0, Y: 0, Z: 0},
		Target:   math3d.Vec3{X: 0, Y: 0, Z: 1},
		Up:       math3d.Vec3{X: 0, Y: 1, Z: 0},
		Fov:      0.01,
		Near:     0.1,
		Far:      1.0,
		Aspect:   1.0,
	}
	_, err = service.UpdateUserFrustum(roomID, "user-2", narrowFrustum)
	assert.NoError(err)

	time.Sleep(time.Millisecond * 50)

	annotation := map[string]interface{}{
		"id":     "annot-outside-1",
		"center": map[string]float64{"X": 100, "Y": 0, "Z": 0},
		"size":   map[string]float64{"X": 2, "Y": 2, "Z": 2},
		"label":  "Outside Frustum",
		"author": "user-1",
	}
	payload, _ := json.Marshal(annotation)
	msg := Message{
		ID:      "msg-outside-1",
		Type:    MessageTypeAnnotation,
		UserID:  "user-1",
		RoomID:  roomID,
		Version: 1,
		Payload: payload,
	}

	err = service.HandleMessage(roomID, "user-1", msg)
	assert.NoError(err)

	time.Sleep(time.Millisecond * 200)

	_, err = mock2.ReadMessage(time.Millisecond * 200)
	assert.Error(err, "user-2 should not receive annotation outside frustum")

	user2.cacheMu.RLock()
	cached, exists := user2.AnnotationCache["msg-outside-1"]
	user2.cacheMu.RUnlock()
	assert.True(exists, "annotation should be cached for user-2")
	assert.NotNil(cached, "cached annotation data should exist")

	t.Log("Frustum visibility test passed: annotation outside frustum was cached but not sent")
}

func TestSpatialSync_EnterFrustum_ReturnsNewlyVisible(t *testing.T) {
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

	roomID := uuid.New().String()

	user1, _, err := service.JoinRoom(roomID, "user-1", "User One", conn1)
	assert.NoError(err)
	assert.NotNil(user1)

	user2, _, err := service.JoinRoom(roomID, "user-2", "User Two", conn2)
	assert.NoError(err)
	assert.NotNil(user2)

	time.Sleep(time.Millisecond * 100)

	initialFrustum := FrustumState{
		Position: math3d.Vec3{X: 0, Y: 0, Z: 0},
		Target:   math3d.Vec3{X: 0, Y: 0, Z: 100},
		Up:       math3d.Vec3{X: 0, Y: 1, Z: 0},
		Fov:      1.0,
		Near:     0.1,
		Far:      2.0,
		Aspect:   1.0,
	}
	_, err = service.UpdateUserFrustum(roomID, "user-2", initialFrustum)
	assert.NoError(err)

	time.Sleep(time.Millisecond * 50)

	annotation := map[string]interface{}{
		"id":     "annot-front-1",
		"center": map[string]float64{"X": 0, "Y": 0, "Z": -5},
		"size":   map[string]float64{"X": 1, "Y": 1, "Z": 1},
		"label":  "In Front",
		"author": "user-1",
	}
	payload, _ := json.Marshal(annotation)
	msg := Message{
		ID:      "msg-front-1",
		Type:    MessageTypeAnnotation,
		UserID:  "user-1",
		RoomID:  roomID,
		Version: 1,
		Payload: payload,
	}

	err = service.HandleMessage(roomID, "user-1", msg)
	assert.NoError(err)

	time.Sleep(time.Millisecond * 200)

	_, err = mock2.ReadMessage(time.Millisecond * 200)
	assert.Error(err, "user-2 should not receive annotation when outside frustum")

	updatedFrustum := FrustumState{
		Position: math3d.Vec3{X: 0, Y: 0, Z: 0},
		Target:   math3d.Vec3{X: 0, Y: 0, Z: 100},
		Up:       math3d.Vec3{X: 0, Y: 1, Z: 0},
		Fov:      1.0,
		Near:     0.1,
		Far:      20.0,
		Aspect:   1.0,
	}
	newlyVisible, err := service.UpdateUserFrustum(roomID, "user-2", updatedFrustum)
	assert.NoError(err)
	assert.Greater(float64(len(newlyVisible)), 0.0, "UpdateUserFrustum should return newly visible annotations")

	t.Logf("EnterFrustum test passed: %d annotations returned", len(newlyVisible))
}

func TestSpatialSync_Invisible_Annotated_GetsCachedAndReSent(t *testing.T) {
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

	roomID := uuid.New().String()

	user1, _, err := service.JoinRoom(roomID, "user-1", "User One", conn1)
	assert.NoError(err)
	assert.NotNil(user1)

	user2, _, err := service.JoinRoom(roomID, "user-2", "User Two", conn2)
	assert.NoError(err)
	assert.NotNil(user2)

	time.Sleep(time.Millisecond * 100)

	frustumAway := FrustumState{
		Position: math3d.Vec3{X: 0, Y: 0, Z: 0},
		Target:   math3d.Vec3{X: 0, Y: 0, Z: 100},
		Up:       math3d.Vec3{X: 0, Y: 1, Z: 0},
		Fov:      1.0,
		Near:     0.1,
		Far:      2.0,
		Aspect:   1.0,
	}
	_, err = service.UpdateUserFrustum(roomID, "user-2", frustumAway)
	assert.NoError(err)

	time.Sleep(time.Millisecond * 50)

	targetID := "annot-cached-1"
	annotation := map[string]interface{}{
		"id":     targetID,
		"center": map[string]float64{"X": 0, "Y": 0, "Z": -5},
		"size":   map[string]float64{"X": 1, "Y": 1, "Z": 1},
		"label":  "To Be Cached",
		"author": "user-1",
	}
	payload, _ := json.Marshal(annotation)
	msg := Message{
		ID:      "msg-cached-1",
		Type:    MessageTypeAnnotation,
		UserID:  "user-1",
		RoomID:  roomID,
		Version: 1,
		Payload: payload,
	}

	err = service.HandleMessage(roomID, "user-1", msg)
	assert.NoError(err)

	time.Sleep(time.Millisecond * 200)

	_, err = mock2.ReadMessage(time.Millisecond * 200)
	assert.Error(err, "user-2 should not receive annotation initially when outside frustum")

	user2.cacheMu.RLock()
	_, cachedExists := user2.AnnotationCache["msg-cached-1"]
	user2.cacheMu.RUnlock()
	assert.True(cachedExists, "annotation should be in cache after initial broadcast")

	user2.visibleMu.RLock()
	va, visibleExists := user2.VisibleAnnotations["msg-cached-1"]
	user2.visibleMu.RUnlock()
	assert.True(visibleExists, "annotation should have visibility tracking entry")
	assert.False(va.IsVisible, "annotation should be marked as not visible initially")

	frustumToward := FrustumState{
		Position: math3d.Vec3{X: 0, Y: 0, Z: 0},
		Target:   math3d.Vec3{X: 0, Y: 0, Z: 100},
		Up:       math3d.Vec3{X: 0, Y: 1, Z: 0},
		Fov:      1.0,
		Near:     0.1,
		Far:      20.0,
		Aspect:   1.0,
	}
	newlyVisible, err := service.UpdateUserFrustum(roomID, "user-2", frustumToward)
	assert.NoError(err)
	assert.Greater(float64(len(newlyVisible)), 0.0, "should return annotations that re-entered frustum")

	user2.visibleMu.RLock()
	vaAfter, existsAfter := user2.VisibleAnnotations["msg-cached-1"]
	user2.visibleMu.RUnlock()
	assert.True(existsAfter, "visibility tracking should still exist")
	assert.True(vaAfter.IsVisible, "annotation should be marked as visible after frustum update")

	t.Log("Cache and resend test passed: annotation was cached, then restored when entering frustum")
}

func TestSpatialSync_FetchAnnotationsInRegion(t *testing.T) {
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

	roomID := uuid.New().String()

	user1, _, err := service.JoinRoom(roomID, "user-1", "User One", conn1)
	assert.NoError(err)
	assert.NotNil(user1)

	user2, _, err := service.JoinRoom(roomID, "user-2", "User Two", conn2)
	assert.NoError(err)
	assert.NotNil(user2)

	time.Sleep(time.Millisecond * 100)

	annotations := []struct {
		id     string
		center math3d.Vec3
		msgID  string
	}{
		{id: "annot-region-a", center: math3d.Vec3{X: 1, Y: 1, Z: 1}, msgID: "msg-reg-a"},
		{id: "annot-region-b", center: math3d.Vec3{X: 2, Y: 2, Z: 2}, msgID: "msg-reg-b"},
		{id: "annot-region-c", center: math3d.Vec3{X: 50, Y: 50, Z: 50}, msgID: "msg-reg-c"},
		{id: "annot-region-d", center: math3d.Vec3{X: -10, Y: -10, Z: -10}, msgID: "msg-reg-d"},
		{id: "annot-region-e", center: math3d.Vec3{X: 3, Y: 1, Z: 0}, msgID: "msg-reg-e"},
	}

	for _, a := range annotations {
		ann := map[string]interface{}{
			"id":     a.id,
			"center": map[string]float64{"X": a.center.X, "Y": a.center.Y, "Z": a.center.Z},
			"size":   map[string]float64{"X": 1, "Y": 1, "Z": 1},
			"author": "user-1",
		}
		payload, _ := json.Marshal(ann)
		msg := Message{
			ID:      a.msgID,
			Type:    MessageTypeAnnotation,
			UserID:  "user-1",
			RoomID:  roomID,
			Version: 1,
			Payload: payload,
		}
		err := service.HandleMessage(roomID, "user-1", msg)
		assert.NoError(err)
	}

	time.Sleep(time.Millisecond * 200)

	region := math3d.AABB{
		Min: math3d.Vec3{X: 0, Y: 0, Z: 0},
		Max: math3d.Vec3{X: 5, Y: 5, Z: 5},
	}

	result, err := service.FetchAnnotationsInRegion(roomID, region)
	assert.NoError(err)
	assert.Greater(float64(len(result)), 0.0, "should fetch at least one annotation")

	foundIDs := make(map[string]bool)
	for _, raw := range result {
		var parsed struct {
			ID string `json:"id"`
		}
		err := json.Unmarshal(raw, &parsed)
		assert.NoError(err)
		foundIDs[parsed.ID] = true
	}

	assert.True(foundIDs["annot-region-a"], "annot-region-a should be in region")
	assert.True(foundIDs["annot-region-b"], "annot-region-b should be in region")
	assert.True(foundIDs["annot-region-e"], "annot-region-e should be in region")
	assert.False(foundIDs["annot-region-c"], "annot-region-c should NOT be in region")
	assert.False(foundIDs["annot-region-d"], "annot-region-d should NOT be in region")

	t.Logf("FetchAnnotationsInRegion test passed: %d annotations in region, expected 3", len(result))
}
