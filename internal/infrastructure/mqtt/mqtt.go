package mqtt

import (
	"context"
	"fmt"
	"sync"

	mqtt "github.com/eclipse/paho.mqtt.golang"
	"github.com/edgevision/edgevision/internal/infrastructure/config"
)

type Client struct {
	client mqtt.Client
	cfg    *config.MQTTConfig
	mu     sync.Mutex
}

func NewClient(cfg *config.MQTTConfig) (*Client, error) {
	opts := mqtt.NewClientOptions()
	opts.AddBroker(cfg.Broker)
	opts.SetClientID(cfg.ClientID)
	if cfg.Username != "" {
		opts.SetUsername(cfg.Username)
		opts.SetPassword(cfg.Password)
	}

	client := mqtt.NewClient(opts)
	if token := client.Connect(); token.Wait() && token.Error() != nil {
		return nil, fmt.Errorf("mqtt connect failed: %w", token.Error())
	}

	return &Client{
		client: client,
		cfg:    cfg,
	}, nil
}

func New(cfg config.MQTTConfig) (*Client, error) {
	return NewClient(&cfg)
}

func (c *Client) Publish(ctx context.Context, topic string, payload interface{}) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	token := c.client.Publish(topic, byte(c.cfg.QoS), false, payload)
	if token.Wait() && token.Error() != nil {
		return fmt.Errorf("mqtt publish failed: %w", token.Error())
	}
	return nil
}

func (c *Client) Subscribe(topic string, handler func(topic string, payload []byte)) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	token := c.client.Subscribe(topic, byte(c.cfg.QoS), func(client mqtt.Client, msg mqtt.Message) {
		handler(msg.Topic(), msg.Payload())
	})
	if token.Wait() && token.Error() != nil {
		return fmt.Errorf("mqtt subscribe failed: %w", token.Error())
	}
	return nil
}

func (c *Client) Unsubscribe(topic string) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	token := c.client.Unsubscribe(topic)
	if token.Wait() && token.Error() != nil {
		return fmt.Errorf("mqtt unsubscribe failed: %w", token.Error())
	}
	return nil
}

func (c *Client) Disconnect() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.client.Disconnect(250)
}
