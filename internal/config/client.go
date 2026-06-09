package config

import (
	"context"
	"fmt"
	"sync"
	"time"

	clientv3 "go.etcd.io/etcd/client/v3"
)

type ETCDClient struct {
	client   *clientv3.Client
	config   clientv3.Config
	mu       sync.RWMutex
	ctx      context.Context
	cancel   context.CancelFunc
}

func NewETCDClient(endpoints []string, username, password string) (*ETCDClient, error) {
	if len(endpoints) == 0 {
		return nil, fmt.Errorf("endpoints cannot be empty")
	}

	cfg := clientv3.Config{
		Endpoints:            endpoints,
		DialTimeout:          5 * time.Second,
		DialKeepAliveTime:    30 * time.Second,
		DialKeepAliveTimeout: 10 * time.Second,
		PermitWithoutStream:  true,
	}

	if username != "" {
		cfg.Username = username
		cfg.Password = password
	}

	client, err := clientv3.New(cfg)
	if err != nil {
		return nil, fmt.Errorf("failed to create etcd client: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	etcdClient := &ETCDClient{
		client: client,
		config: cfg,
		ctx:    ctx,
		cancel: cancel,
	}

	go etcdClient.keepAlive()

	return etcdClient, nil
}

func (c *ETCDClient) keepAlive() {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-c.ctx.Done():
			return
		case <-ticker.C:
			c.mu.RLock()
			cli := c.client
			c.mu.RUnlock()

			if cli == nil {
				c.reconnect()
				continue
			}

			ctx, cancel := context.WithTimeout(c.ctx, 3*time.Second)
			_, err := cli.Get(ctx, "health")
			cancel()

			if err != nil {
				c.reconnect()
			}
		}
	}
}

func (c *ETCDClient) reconnect() {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.client != nil {
		_ = c.client.Close()
	}

	client, err := clientv3.New(c.config)
	if err != nil {
		return
	}

	c.client = client
}

func (c *ETCDClient) Get(key string) ([]byte, error) {
	c.mu.RLock()
	cli := c.client
	c.mu.RUnlock()

	if cli == nil {
		return nil, fmt.Errorf("etcd client is not connected")
	}

	ctx, cancel := context.WithTimeout(c.ctx, 5*time.Second)
	defer cancel()

	resp, err := cli.Get(ctx, key)
	if err != nil {
		return nil, fmt.Errorf("failed to get key %s: %w", key, err)
	}

	if len(resp.Kvs) == 0 {
		return nil, fmt.Errorf("key %s not found", key)
	}

	return resp.Kvs[0].Value, nil
}

func (c *ETCDClient) Put(key string, value []byte) error {
	c.mu.RLock()
	cli := c.client
	c.mu.RUnlock()

	if cli == nil {
		return fmt.Errorf("etcd client is not connected")
	}

	ctx, cancel := context.WithTimeout(c.ctx, 5*time.Second)
	defer cancel()

	_, err := cli.Put(ctx, key, string(value))
	if err != nil {
		return fmt.Errorf("failed to put key %s: %w", key, err)
	}

	return nil
}

func (c *ETCDClient) Delete(key string) error {
	c.mu.RLock()
	cli := c.client
	c.mu.RUnlock()

	if cli == nil {
		return fmt.Errorf("etcd client is not connected")
	}

	ctx, cancel := context.WithTimeout(c.ctx, 5*time.Second)
	defer cancel()

	_, err := cli.Delete(ctx, key)
	if err != nil {
		return fmt.Errorf("failed to delete key %s: %w", key, err)
	}

	return nil
}

func (c *ETCDClient) List(prefix string) (map[string][]byte, error) {
	c.mu.RLock()
	cli := c.client
	c.mu.RUnlock()

	if cli == nil {
		return nil, fmt.Errorf("etcd client is not connected")
	}

	ctx, cancel := context.WithTimeout(c.ctx, 10*time.Second)
	defer cancel()

	resp, err := cli.Get(ctx, prefix, clientv3.WithPrefix())
	if err != nil {
		return nil, fmt.Errorf("failed to list prefix %s: %w", prefix, err)
	}

	result := make(map[string][]byte, len(resp.Kvs))
	for _, kv := range resp.Kvs {
		result[string(kv.Key)] = kv.Value
	}

	return result, nil
}

func (c *ETCDClient) Watch(ctx context.Context, prefix string) clientv3.WatchChan {
	c.mu.RLock()
	cli := c.client
	c.mu.RUnlock()

	if cli == nil {
		return nil
	}

	return cli.Watch(ctx, prefix, clientv3.WithPrefix())
}

func (c *ETCDClient) Close() error {
	c.cancel()

	c.mu.Lock()
	defer c.mu.Unlock()

	if c.client != nil {
		return c.client.Close()
	}

	return nil
}

func (c *ETCDClient) Client() *clientv3.Client {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.client
}
