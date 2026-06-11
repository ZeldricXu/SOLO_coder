package network

import (
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/protocol"
	"github.com/stretchr/testify/assert"
)

func makeReactorConfig() *ReactorConfig {
	return &ReactorConfig{
		ReadWorkers:     2,
		WriteWorkers:    2,
		BatchSize:       4,
		WriteBufferSize: 32,
		FlushIntervalMs: 30,
	}
}

func startWSServer(t *testing.T, upgrader websocket.Upgrader) (*httptest.Server, chan *websocket.Conn) {
	connCh := make(chan *websocket.Conn, 1)
	handler := func(w http.ResponseWriter, r *http.Request) {
		c, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			t.Fatalf("ws upgrade: %v", err)
			return
		}
		connCh <- c
	}
	s := httptest.NewServer(http.HandlerFunc(handler))
	return s, connCh
}

func dialWS(t *testing.T, url string) *websocket.Conn {
	d := websocket.Dialer{}
	c, _, err := d.Dial(url, nil)
	assert.NoError(t, err)
	return c
}

func newTestConnection(t *testing.T, userID common.UserID, roomID common.RoomID, isObserver bool) (*Connection, *websocket.Conn, *httptest.Server) {
	upgrader := websocket.Upgrader{}
	s, connCh := startWSServer(t, upgrader)
	client := dialWS(t, "ws"+s.URL[4:])
	server := <-connCh

	c := NewConnection(userID, server, isObserver)
	c.RoomID = roomID
	return c, client, s
}

func TestReactor_StartStop_NoHandler(t *testing.T) {
	cfg := makeReactorConfig()
	r := NewReactor(cfg)
	r.Start(nil)
	time.Sleep(20 * time.Millisecond)
	r.Stop()
}

func TestReactor_RegisterUnregister(t *testing.T) {
	cfg := makeReactorConfig()
	r := NewReactor(cfg)
	r.Start(nil)
	defer r.Stop()

	c, client, s := newTestConnection(t, "u1", "r1", false)
	defer s.Close()
	defer client.Close()

	r.Register(c)
	time.Sleep(10 * time.Millisecond)
	assert.Same(t, c, r.GetConnection("u1"))

	r.Unregister("u1")
	time.Sleep(10 * time.Millisecond)
	assert.Nil(t, r.GetConnection("u1"))
}

func TestReactor_SendMessage_Delivered(t *testing.T) {
	cfg := makeReactorConfig()
	cfg.FlushIntervalMs = 20
	r := NewReactor(cfg)

	var msgCount atomic.Int64
	var wg sync.WaitGroup
	wg.Add(1)

	r.Start(nil)
	defer r.Stop()

	c, client, s := newTestConnection(t, "u_send_1", "r_send", false)
	defer s.Close()
	defer client.Close()

	r.Register(c)

	go func() {
		defer wg.Done()
		_, _, err := client.ReadMessage()
		if err == nil {
			msgCount.Add(1)
		}
	}()

	msg := protocol.NewMessage(protocol.MsgBroadcast, "r_send", "u_send_1", map[string]int{"n": 1})
	r.SendMessage("u_send_1", msg)

	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
	}

	assert.GreaterOrEqual(t, msgCount.Load(), int64(1))
}

func TestReactor_BroadcastToRoom_DeliveredAll(t *testing.T) {
	cfg := makeReactorConfig()
	cfg.FlushIntervalMs = 15
	r := NewReactor(cfg)

	var received sync.Map
	var total atomic.Int64

	handler := func(uid common.UserID, rid common.RoomID, m *protocol.Message) {}
	r.Start(handler)
	defer r.Stop()

	N := 4
	for i := 0; i < N; i++ {
		uid := common.UserID("u_bc_" + string(rune('a'+i)))
		c, client, s := newTestConnection(t, uid, "r_bc", false)
		r.Register(c)
		go func(c *websocket.Conn, s *httptest.Server) {
			defer s.Close()
			defer c.Close()
			for {
				_, _, err := c.ReadMessage()
				if err != nil {
					return
				}
				total.Add(1)
				received.Store(true, true)
			}
		}(client, s)
	}

	time.Sleep(50 * time.Millisecond)
	msg := protocol.NewMessage(protocol.MsgBroadcast, "r_bc", "", nil)
	r.BroadcastToRoom("r_bc", msg, false)

	deadline := time.Now().Add(1 * time.Second)
	for time.Now().Before(deadline) && total.Load() < int64(N) {
		time.Sleep(20 * time.Millisecond)
	}
	assert.Equal(t, int64(N), total.Load())
}

func TestReactor_GetConnection_ReturnsNilAfterClose(t *testing.T) {
	cfg := makeReactorConfig()
	r := NewReactor(cfg)
	r.Start(nil)
	defer r.Stop()

	c, client, s := newTestConnection(t, "u_close", "r", false)
	defer s.Close()
	defer client.Close()

	r.Register(c)
	c.Close()
	time.Sleep(10 * time.Millisecond)
	assert.Nil(t, r.GetConnection("u_close"))
}
