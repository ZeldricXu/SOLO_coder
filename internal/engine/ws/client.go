package ws

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/gorilla/websocket"
)

type Client struct {
	conn     *websocket.Conn
	url      string
	headers  map[string]string
	messages chan []byte
	done     chan struct{}
}

type Message struct {
	Content   string
	Type      int
	Timestamp time.Time
	Direction string
}

func NewClient(url string, headers map[string]string) *Client {
	return &Client{
		url:      url,
		headers:  headers,
		messages: make(chan []byte, 256),
		done:     make(chan struct{}),
	}
}

func (c *Client) Connect(ctx context.Context) error {
	dialer := websocket.Dialer{}

	header := make(map[string][]string)
	for k, v := range c.headers {
		header[k] = []string{v}
	}

	conn, _, err := dialer.DialContext(ctx, c.url, header)
	if err != nil {
		return fmt.Errorf("connecting to %s: %w", c.url, err)
	}

	c.conn = conn

	go func() {
		defer close(c.messages)
		for {
			select {
			case <-c.done:
				return
			default:
			}

			_, msgBytes, err := c.conn.ReadMessage()
			if err != nil {
				return
			}
			c.messages <- msgBytes
		}
	}()

	return nil
}

func (c *Client) Send(msg string, msgType ...int) error {
	if c.conn == nil {
		return fmt.Errorf("not connected")
	}

	t := websocket.TextMessage
	if len(msgType) > 0 {
		t = msgType[0]
	}

	err := c.conn.WriteMessage(t, []byte(msg))
	if err != nil {
		return fmt.Errorf("sending message: %w", err)
	}

	return nil
}

func (c *Client) SendJSON(v interface{}) error {
	data, err := json.Marshal(v)
	if err != nil {
		return fmt.Errorf("marshaling JSON: %w", err)
	}

	return c.Send(string(data), websocket.TextMessage)
}

func (c *Client) Receive() (<-chan Message, error) {
	if c.conn == nil {
		return nil, fmt.Errorf("not connected")
	}

	ch := make(chan Message, 256)

	go func() {
		defer close(ch)
		for {
			select {
			case <-c.done:
				return
			case msgBytes, ok := <-c.messages:
				if !ok {
					return
				}
				ch <- Message{
					Content:   string(msgBytes),
					Type:      websocket.TextMessage,
					Timestamp: time.Now(),
					Direction: "received",
				}
			}
		}
	}()

	return ch, nil
}

func (c *Client) Close() error {
	close(c.done)
	if c.conn != nil {
		err := c.conn.WriteMessage(websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
		if err != nil {
			return fmt.Errorf("sending close message: %w", err)
		}
		return c.conn.Close()
	}
	return nil
}
