package engine

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/htest/htest/internal/engine/ws"
)

type WSAdapter struct {
	url     string
	headers map[string]string
}

func NewWSAdapter(url string, headers map[string]string) *WSAdapter {
	return &WSAdapter{url: url, headers: headers}
}

func (a *WSAdapter) Execute(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
	client := ws.NewClient(a.url, a.headers)
	connErr := client.Connect(ctx)
	if connErr != nil {
		return nil, fmt.Errorf("connecting: %w", connErr)
	}
	defer client.Close()

	if req.Message != "" {
		if err := client.Send(req.Message); err != nil {
			return nil, fmt.Errorf("sending: %w", err)
		}
	}

	msgCh, err := client.Receive()
	if err != nil {
		return nil, fmt.Errorf("receiving: %w", err)
	}

	var messages []ws.Message
	timeout := time.After(5 * time.Second)
	for len(messages) < 10 {
		select {
		case msg, ok := <-msgCh:
			if !ok {
				goto done
			}
			messages = append(messages, msg)
		case <-timeout:
			goto done
		}
	}
done:

	bodyBytes, _ := json.Marshal(messages)
	return &ProtocolResponse{
		StatusCode: 101,
		Status:     "Switching Protocols",
		Headers:    map[string][]string{},
		Body:       string(bodyBytes),
		Raw:        messages,
	}, nil
}
