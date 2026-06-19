//go:build integration

package integration

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"
	"time"

	"github.com/enterprise/knowledgebase/internal/ot"
	"github.com/gorilla/websocket"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type collabMessage struct {
	Type     string       `json:"type"`
	UserID   string       `json:"user_id"`
	DocID    string       `json:"doc_id"`
	Version  int64        `json:"version"`
	Op       *ot.Operation `json:"op,omitempty"`
	Ops      []*ot.Operation `json:"ops,omitempty"`
	Content  string       `json:"content,omitempty"`
	ClientID int          `json:"client_id,omitempty"`
}

type collabDocument struct {
	mu      sync.RWMutex
	content string
	version int64
	ops     []*ot.Operation
}

func newCollabDocument(initial string) *collabDocument {
	return &collabDocument{
		content: initial,
		version: 0,
		ops:     make([]*ot.Operation, 0),
	}
}

func (cd *collabDocument) applyOp(op *ot.Operation) (string, int64, error) {
	cd.mu.Lock()
	defer cd.mu.Unlock()
	newContent, err := ot.Apply(cd.content, op)
	if err != nil {
		return "", cd.version, err
	}
	cd.content = newContent
	cd.version++
	cd.ops = append(cd.ops, op)
	return newContent, cd.version, nil
}

func (cd *collabDocument) getContent() string {
	cd.mu.RLock()
	defer cd.mu.RUnlock()
	return cd.content
}

func (cd *collabDocument) getVersion() int64 {
	cd.mu.RLock()
	defer cd.mu.RUnlock()
	return cd.version
}

type collabHub struct {
	docs      map[string]*collabDocument
	clients   map[int]*websocket.Conn
	mu        sync.RWMutex
	nextID    int
	upgrader  websocket.Upgrader
}

func newCollabHub() *collabHub {
	return &collabHub{
		docs:    make(map[string]*collabDocument),
		clients: make(map[int]*websocket.Conn),
		upgrader: websocket.Upgrader{
			CheckOrigin: func(r *http.Request) bool { return true },
		},
	}
}

func (h *collabHub) getDoc(docID string) *collabDocument {
	h.mu.Lock()
	defer h.mu.Unlock()
	if doc, ok := h.docs[docID]; ok {
		return doc
	}
	doc := newCollabDocument("")
	h.docs[docID] = doc
	return doc
}

func (h *collabHub) addClient(conn *websocket.Conn) int {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.nextID++
	id := h.nextID
	h.clients[id] = conn
	return id
}

func (h *collabHub) removeClient(id int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.clients, id)
}

func (h *collabHub) broadcast(docID string, msg *collabMessage, excludeID int) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for id, conn := range h.clients {
		if id == excludeID {
			continue
		}
		_ = conn.WriteJSON(msg)
	}
}

func (h *collabHub) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()

	clientID := h.addClient(conn)
	defer h.removeClient(clientID)

	for {
		var msg collabMessage
		err := conn.ReadJSON(&msg)
		if err != nil {
			return
		}
		msg.ClientID = clientID

		doc := h.getDoc(msg.DocID)

		switch msg.Type {
		case "connect":
			resp := &collabMessage{
				Type:     "init",
				DocID:    msg.DocID,
				Content:  doc.getContent(),
				Version:  doc.getVersion(),
				ClientID: clientID,
			}
			_ = conn.WriteJSON(resp)

		case "op":
			if msg.Op == nil {
				continue
			}
			newContent, newVersion, err := doc.applyOp(msg.Op)
			if err != nil {
				continue
			}
			resp := &collabMessage{
				Type:     "op",
				DocID:    msg.DocID,
				Version:  newVersion,
				Op:       msg.Op,
				Content:  newContent,
				ClientID: clientID,
			}
			h.broadcast(msg.DocID, resp, clientID)
			_ = conn.WriteJSON(resp)

		case "sync":
			resp := &collabMessage{
				Type:     "sync",
				DocID:    msg.DocID,
				Content:  doc.getContent(),
				Version:  doc.getVersion(),
				ClientID: clientID,
			}
			_ = conn.WriteJSON(resp)
		}
	}
}

func TestIntegration_WebSocketCollab(t *testing.T) {
	hub := newCollabHub()

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", hub.handleWebSocket)

	server := httptest.NewServer(mux)
	defer server.Close()

	u, _ := url.Parse(server.URL)
	u.Scheme = "ws"
	u.Path = "/ws"

	docID := "collab-test-doc"
	initialText := "Hello World"

	hub.getDoc(docID).applyOp(ot.NewInsert(0, initialText))

	var wg sync.WaitGroup
	results := make([]string, 2)

	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(clientIdx int) {
			defer wg.Done()

			conn, _, err := websocket.DefaultDialer.Dial(u.String(), nil)
			require.NoError(t, err)
			defer conn.Close()

			connectMsg := collabMessage{
				Type:  "connect",
				DocID: docID,
			}
			err = conn.WriteJSON(&connectMsg)
			require.NoError(t, err)

			var initMsg collabMessage
			err = conn.ReadJSON(&initMsg)
			require.NoError(t, err)
			assert.Equal(t, "init", initMsg.Type)
			assert.Equal(t, initialText, initMsg.Content)

			insertText := fmt.Sprintf(" Client%d", clientIdx+1)
			insertPos := len(initialText)
			if clientIdx == 0 {
				insertPos = 0
				insertText = fmt.Sprintf("Client%d ", clientIdx+1)
			}

			opMsg := collabMessage{
				Type:  "op",
				DocID: docID,
				Op:    ot.NewInsert(insertPos, insertText),
			}
			err = conn.WriteJSON(&opMsg)
			require.NoError(t, err)

			timeout := time.After(5 * time.Second)
			receivedOps := 0
			for receivedOps < 2 {
				select {
				case <-timeout:
					return
				default:
					var msg collabMessage
					conn.SetReadDeadline(time.Now().Add(1 * time.Second))
					err := conn.ReadJSON(&msg)
					if err != nil {
						continue
					}
					if msg.Type == "op" {
						receivedOps++
						results[clientIdx] = msg.Content
					}
				}
			}
		}(i)
	}

	wg.Wait()

	finalDoc := hub.getDoc(docID)
	finalContent := finalDoc.getContent()
	assert.Contains(t, finalContent, "Client1")
	assert.Contains(t, finalContent, "Client2")
	assert.Contains(t, finalContent, "Hello World")
}

var _ = json.Marshal