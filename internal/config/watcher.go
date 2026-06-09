package config

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"

	"DF1-56/internal/models"

	clientv3 "go.etcd.io/etcd/client/v3"
)

type watcher struct {
	ctx    context.Context
	cancel context.CancelFunc
	prefix string
}

type ConfigWatcher struct {
	client   *ETCDClient
	watchers map[string]*watcher
	mu       sync.Mutex
	ctx      context.Context
	cancel   context.CancelFunc
}

func NewConfigWatcher(client *ETCDClient) *ConfigWatcher {
	ctx, cancel := context.WithCancel(context.Background())
	return &ConfigWatcher{
		client:   client,
		watchers: make(map[string]*watcher),
		ctx:      ctx,
		cancel:   cancel,
	}
}

func (w *ConfigWatcher) Watch(prefix string, onChange func(key string, value []byte, deleted bool)) error {
	w.mu.Lock()
	if _, exists := w.watchers[prefix]; exists {
		w.mu.Unlock()
		return fmt.Errorf("watcher for prefix %s already exists", prefix)
	}
	w.mu.Unlock()

	ctx, cancel := context.WithCancel(w.ctx)

	go w.watchLoop(ctx, prefix, onChange)

	w.mu.Lock()
	w.watchers[prefix] = &watcher{
		ctx:    ctx,
		cancel: cancel,
		prefix: prefix,
	}
	w.mu.Unlock()

	return nil
}

func (w *ConfigWatcher) watchLoop(ctx context.Context, prefix string, onChange func(key string, value []byte, deleted bool)) {
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		watchChan := w.client.Watch(ctx, prefix)
		if watchChan == nil {
			return
		}

		for resp := range watchChan {
			if resp.Canceled {
				break
			}

			for _, ev := range resp.Events {
				key := string(ev.Kv.Key)
				var value []byte
				deleted := false

				if ev.Type == clientv3.EventTypeDelete {
					deleted = true
				} else {
					value = ev.Kv.Value
				}

				onChange(key, value, deleted)
			}
		}

		select {
		case <-ctx.Done():
			return
		default:
		}
	}
}

func (w *ConfigWatcher) WatchRoutes(onChange func(route *models.Route, deleted bool)) error {
	return w.Watch(EtcdKeyPrefixRoutes, func(key string, value []byte, deleted bool) {
		id := strings.TrimPrefix(key, EtcdKeyPrefixRoutes)
		if id == "" {
			return
		}

		if deleted {
			onChange(&models.Route{ID: id}, true)
			return
		}

		var route models.Route
		if err := json.Unmarshal(value, &route); err != nil {
			return
		}
		if route.ID == "" {
			route.ID = id
		}
		onChange(&route, false)
	})
}

func (w *ConfigWatcher) WatchRateLimits(onChange func(policy *models.RateLimitPolicy, deleted bool)) error {
	return w.Watch(EtcdKeyPrefixRateLimits, func(key string, value []byte, deleted bool) {
		id := strings.TrimPrefix(key, EtcdKeyPrefixRateLimits)
		if id == "" {
			return
		}

		if deleted {
			onChange(&models.RateLimitPolicy{ID: id}, true)
			return
		}

		var policy models.RateLimitPolicy
		if err := json.Unmarshal(value, &policy); err != nil {
			return
		}
		if policy.ID == "" {
			policy.ID = id
		}
		onChange(&policy, false)
	})
}

func (w *ConfigWatcher) WatchAuths(onChange func(policy *models.AuthPolicy, deleted bool)) error {
	return w.Watch(EtcdKeyPrefixAuths, func(key string, value []byte, deleted bool) {
		id := strings.TrimPrefix(key, EtcdKeyPrefixAuths)
		if id == "" {
			return
		}

		if deleted {
			onChange(&models.AuthPolicy{ID: id}, true)
			return
		}

		var policy models.AuthPolicy
		if err := json.Unmarshal(value, &policy); err != nil {
			return
		}
		if policy.ID == "" {
			policy.ID = id
		}
		onChange(&policy, false)
	})
}

func (w *ConfigWatcher) WatchCircuitBreakers(onChange func(policy *models.CircuitBreakerPolicy, deleted bool)) error {
	return w.Watch(EtcdKeyPrefixCircuitBreakers, func(key string, value []byte, deleted bool) {
		id := strings.TrimPrefix(key, EtcdKeyPrefixCircuitBreakers)
		if id == "" {
			return
		}

		if deleted {
			onChange(&models.CircuitBreakerPolicy{ID: id}, true)
			return
		}

		var policy models.CircuitBreakerPolicy
		if err := json.Unmarshal(value, &policy); err != nil {
			return
		}
		if policy.ID == "" {
			policy.ID = id
		}
		onChange(&policy, false)
	})
}

func (w *ConfigWatcher) WatchGrays(onChange func(policy *models.GrayPolicy, deleted bool)) error {
	return w.Watch(EtcdKeyPrefixGrays, func(key string, value []byte, deleted bool) {
		id := strings.TrimPrefix(key, EtcdKeyPrefixGrays)
		if id == "" {
			return
		}

		if deleted {
			onChange(&models.GrayPolicy{ID: id}, true)
			return
		}

		var policy models.GrayPolicy
		if err := json.Unmarshal(value, &policy); err != nil {
			return
		}
		if policy.ID == "" {
			policy.ID = id
		}
		onChange(&policy, false)
	})
}

func (w *ConfigWatcher) WatchMirrors(onChange func(policy *models.MirrorPolicy, deleted bool)) error {
	return w.Watch(EtcdKeyPrefixMirrors, func(key string, value []byte, deleted bool) {
		id := strings.TrimPrefix(key, EtcdKeyPrefixMirrors)
		if id == "" {
			return
		}

		if deleted {
			onChange(&models.MirrorPolicy{ID: id}, true)
			return
		}

		var policy models.MirrorPolicy
		if err := json.Unmarshal(value, &policy); err != nil {
			return
		}
		if policy.ID == "" {
			policy.ID = id
		}
		onChange(&policy, false)
	})
}

func (w *ConfigWatcher) WatchUpstreams(onChange func(cluster *models.UpstreamCluster, deleted bool)) error {
	return w.Watch(EtcdKeyPrefixUpstreams, func(key string, value []byte, deleted bool) {
		id := strings.TrimPrefix(key, EtcdKeyPrefixUpstreams)
		if id == "" {
			return
		}

		if deleted {
			onChange(&models.UpstreamCluster{ID: id}, true)
			return
		}

		var cluster models.UpstreamCluster
		if err := json.Unmarshal(value, &cluster); err != nil {
			return
		}
		if cluster.ID == "" {
			cluster.ID = id
		}
		onChange(&cluster, false)
	})
}

func (w *ConfigWatcher) Stop() {
	w.cancel()

	w.mu.Lock()
	defer w.mu.Unlock()

	for _, watcher := range w.watchers {
		watcher.cancel()
	}

	w.watchers = make(map[string]*watcher)
}
