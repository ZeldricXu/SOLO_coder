package config

import (
	"context"
	crand "crypto/rand"
	"encoding/hex"
	"fmt"
	"math/big"
	"sync"
	"sync/atomic"
	"time"

	"session130/internal/logger"
	"session130/internal/metrics"
)

const (
	defaultAsyncQueueSize  = 10000
	defaultAsyncWorkers    = 2
	randomStringLength     = 8
)

type CacheEventType string

const (
	CacheEventSet        CacheEventType = "cache.set"
	CacheEventGet        CacheEventType = "cache.get"
	CacheEventDelete     CacheEventType = "cache.delete"
	CacheEventEvict      CacheEventType = "cache.evict"
	CacheEventExpire     CacheEventType = "cache.expire"
	CacheEventClear      CacheEventType = "cache.clear"
	CacheEventInvalidate CacheEventType = "cache.invalidation"
	CacheEventOperation  CacheEventType = "cache.operation"
)

type CacheEventData struct {
	Event       CacheEventType      `json:"event"`
	Key         string              `json:"key"`
	Value       interface{}         `json:"value,omitempty"`
	TTL         time.Duration       `json:"ttl,omitempty"`
	Hit         bool                `json:"hit,omitempty"`
	Reason      string              `json:"reason,omitempty"`
	CacheName   string              `json:"cache_name"`
	CacheLevel  CacheLevel          `json:"cache_level,omitempty"`
	Timestamp   time.Time           `json:"timestamp"`
	Duration    time.Duration       `json:"duration,omitempty"`
	Success     bool                `json:"success"`
	Error       string              `json:"error,omitempty"`
	Labels      map[string]string   `json:"labels,omitempty"`
}

type CacheInvalidationEvent struct {
	Event          string        `json:"event"`
	Data           interface{}   `json:"data"`
	AffectedCaches []string      `json:"affected_caches"`
	Timestamp      time.Time     `json:"timestamp"`
}

type EventHandler func(ctx context.Context, event CacheEventData)

type EventSubscription struct {
	ID      string
	Event   CacheEventType
	Handler EventHandler
	Once    bool
	Active  bool
}

type EventBus struct {
	mu           sync.RWMutex
	subscribers  map[CacheEventType]map[string]EventHandler
	asyncQueue   chan CacheEventData
	asyncWorkers int
	stopChan     chan struct{}
	wg           sync.WaitGroup
	stats        eventStats
}

type eventStats struct {
	totalEmitted atomic.Int64
	totalDropped atomic.Int64
}

var (
	eventBusInstance *EventBus
	eventBusOnce     sync.Once
)

func NewEventBus(asyncWorkers int) *EventBus {
	if asyncWorkers <= 0 {
		asyncWorkers = defaultAsyncWorkers
	}

	eb := &EventBus{
		subscribers:  make(map[CacheEventType]map[string]EventHandler),
		asyncQueue:   make(chan CacheEventData, defaultAsyncQueueSize),
		asyncWorkers: asyncWorkers,
		stopChan:     make(chan struct{}),
	}

	eb.startAsyncWorkers()
	logger.Info("", "event bus initialized", map[string]interface{}{
		"async_workers": asyncWorkers,
	})

	return eb
}

func GetEventBus() *EventBus {
	eventBusOnce.Do(func() {
		eventBusInstance = NewEventBus(defaultAsyncWorkers)
	})
	return eventBusInstance
}

func (eb *EventBus) startAsyncWorkers() {
	for i := 0; i < eb.asyncWorkers; i++ {
		eb.wg.Add(1)
		go eb.asyncWorker(i)
	}
}

func (eb *EventBus) asyncWorker(id int) {
	defer eb.wg.Done()

	for {
		select {
		case event := <-eb.asyncQueue:
			eb.dispatchEvent(event)
		case <-eb.stopChan:
			return
		}
	}
}

func (eb *EventBus) dispatchEvent(event CacheEventData) {
	handlers := eb.getHandlers(event.Event)
	if len(handlers) == 0 {
		return
	}

	ctx := context.Background()
	for _, handler := range handlers {
		eb.safeInvokeHandler(ctx, handler, event)
	}

	metrics.Inc("config_cache_event_processed_total", map[string]string{
		"event": string(event.Event),
	})
}

func (eb *EventBus) getHandlers(event CacheEventType) []EventHandler {
	eb.mu.RLock()
	defer eb.mu.RUnlock()

	handlerMap, exists := eb.subscribers[event]
	if !exists {
		return nil
	}

	handlers := make([]EventHandler, 0, len(handlerMap))
	for _, h := range handlerMap {
		handlers = append(handlers, h)
	}
	return handlers
}

func (eb *EventBus) safeInvokeHandler(ctx context.Context, handler EventHandler, event CacheEventData) {
	defer func() {
		if r := recover(); r != nil {
			logger.Error("", "event handler panicked", map[string]interface{}{
				"event": string(event.Event),
				"panic": r,
			})
		}
	}()
	handler(ctx, event)
}

func (eb *EventBus) Subscribe(event CacheEventType, handler EventHandler) string {
	eb.mu.Lock()
	defer eb.mu.Unlock()

	if _, exists := eb.subscribers[event]; !exists {
		eb.subscribers[event] = make(map[string]EventHandler)
	}

	subID := generateSubscriptionID()
	eb.subscribers[event][subID] = handler

	metrics.Inc("config_cache_event_subscription_total", map[string]string{
		"event": string(event),
	})

	logger.Debug("", "subscribed to cache event", map[string]interface{}{
		"event":            string(event),
		"subscription_id":  subID,
	})

	return subID
}

func (eb *EventBus) SubscribeOnce(event CacheEventType, handler EventHandler) string {
	wrappedHandler := func(ctx context.Context, eventData CacheEventData) {
		handler(ctx, eventData)
	}
	return eb.Subscribe(event, wrappedHandler)
}

func (eb *EventBus) Unsubscribe(event CacheEventType, subscriptionID string) bool {
	eb.mu.Lock()
	defer eb.mu.Unlock()

	handlers, exists := eb.subscribers[event]
	if !exists {
		return false
	}

	if _, exists := handlers[subscriptionID]; !exists {
		return false
	}

	delete(handlers, subscriptionID)
	metrics.Inc("config_cache_event_unsubscription_total", map[string]string{
		"event": string(event),
	})

	logger.Debug("", "unsubscribed from cache event", map[string]interface{}{
		"event":            string(event),
		"subscription_id":  subscriptionID,
	})

	return true
}

func (eb *EventBus) Emit(event CacheEventData) {
	eb.stats.totalEmitted.Add(1)
	eb.syncEmit(event)
	eb.asyncEmit(event)
}

func (eb *EventBus) syncEmit(event CacheEventData) {
	handlers := eb.getHandlers(event.Event)
	if len(handlers) == 0 {
		return
	}

	ctx := context.Background()
	for _, handler := range handlers {
		eb.safeInvokeHandler(ctx, handler, event)
	}
}

func (eb *EventBus) asyncEmit(event CacheEventData) {
	select {
	case eb.asyncQueue <- event:
	default:
		eb.stats.totalDropped.Add(1)
		logger.Warn("", "event bus queue is full, dropping event", map[string]interface{}{
			"event": string(event.Event),
		})
		metrics.Inc("config_cache_event_dropped_total", map[string]string{
			"event": string(event.Event),
		})
	}
}

func (eb *EventBus) GetStats() map[string]interface{} {
	eb.mu.RLock()
	defer eb.mu.RUnlock()

	stats := make(map[string]interface{})
	totalSubscribers := 0

	for event, handlers := range eb.subscribers {
		stats[string(event)+"_subscribers"] = len(handlers)
		totalSubscribers += len(handlers)
	}

	stats["total_subscribers"] = totalSubscribers
	stats["queue_size"] = len(eb.asyncQueue)
	stats["async_workers"] = eb.asyncWorkers
	stats["total_emitted"] = eb.stats.totalEmitted.Load()
	stats["total_dropped"] = eb.stats.totalDropped.Load()

	return stats
}

func (eb *EventBus) GetSubscriberCount(event CacheEventType) int {
	eb.mu.RLock()
	defer eb.mu.RUnlock()

	handlers, exists := eb.subscribers[event]
	if !exists {
		return 0
	}
	return len(handlers)
}

func (eb *EventBus) Clear() {
	eb.mu.Lock()
	defer eb.mu.Unlock()

	eb.subscribers = make(map[CacheEventType]map[string]EventHandler)
	eb.stats.totalEmitted.Store(0)
	eb.stats.totalDropped.Store(0)

	logger.Info("", "event bus cleared", nil)
}

func (eb *EventBus) Shutdown() {
	close(eb.stopChan)
	eb.wg.Wait()
	close(eb.asyncQueue)
	logger.Info("", "event bus shutdown complete", nil)
}

func generateSubscriptionID() string {
	return fmt.Sprintf("sub_%s_%s", time.Now().Format("20060102150405"), generateRandomString(randomStringLength))
}

func generateRandomString(n int) string {
	b := make([]byte, n)
	_, err := crand.Read(b)
	if err != nil {
		return fallbackRandomString(n)
	}
	return hex.EncodeToString(b)[:n]
}

func fallbackRandomString(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, n)
	for i := range b {
		num, err := crand.Int(crand.Reader, big.NewInt(int64(len(letters))))
		if err != nil {
			b[i] = letters[time.Now().UnixNano()%int64(len(letters))]
		} else {
			b[i] = letters[num.Int64()]
		}
	}
	return string(b)
}

type CacheEventHandler interface {
	OnCacheEvent(ctx context.Context, event CacheEventData)
}

type WebhookNotifier struct {
	WebhookURL string
	HTTPClient interface {
		Post(url string, contentType string, body interface{}) error
	}
}

func NewWebhookNotifier(webhookURL string) *WebhookNotifier {
	return &WebhookNotifier{
		WebhookURL: webhookURL,
	}
}

func (w *WebhookNotifier) OnCacheEvent(ctx context.Context, event CacheEventData) {
	logger.Debug("", "sending webhook notification for cache event", map[string]interface{}{
		"event":       string(event.Event),
		"webhook_url": w.WebhookURL,
	})
}

type EventLogger struct{}

func NewEventLogger() *EventLogger {
	return &EventLogger{}
}

func (l *EventLogger) OnCacheEvent(ctx context.Context, event CacheEventData) {
	switch event.Event {
	case CacheEventSet:
		l.logEvent("cache set", event)
	case CacheEventGet:
		l.logEvent("cache get", event)
	case CacheEventDelete:
		l.logEvent("cache delete", event)
	case CacheEventEvict:
		l.logEvent("cache evict", event)
	case CacheEventExpire:
		l.logEvent("cache expire", event)
	case CacheEventInvalidate:
		l.logEvent("cache invalidate", event)
	}
}

func (l *EventLogger) logEvent(msg string, event CacheEventData) {
	fields := map[string]interface{}{
		"key":         event.Key,
		"cache_name":  event.CacheName,
		"cache_level": event.CacheLevel,
	}
	if event.Duration > 0 {
		fields["duration"] = event.Duration.String()
	}
	if event.Hit {
		fields["hit"] = event.Hit
	}
	if event.Reason != "" {
		fields["reason"] = event.Reason
	}
	logger.Debug("", msg, fields)
}
