package featureflag

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/featureflag/sdk/breaker"
	"github.com/featureflag/sdk/cache"
	"github.com/featureflag/sdk/source"
)

type Client struct {
	options         *SDKOptions
	cache           CacheBackend
	source          SwitchSource
	circuitBreaker  CircuitBreaker
	statsCollector  *StatsCollector
	version         int64
	mu              sync.RWMutex
	stopChan        chan struct{}
	isRunning       bool
	snapshotFile    string
}

func NewClient(opts *SDKOptions) (*Client, error) {
	if opts == nil {
		opts = DefaultOptions()
	}

	if opts.ServerURL == "" {
		return nil, errors.New("server URL is required")
	}

	c := &Client{
		options:      opts,
		stopChan:     make(chan struct{}),
		snapshotFile: filepath.Join(os.TempDir(), "ff_snapshot.json"),
	}

	cacheBackend, err := cache.NewCacheBackend(opts.CacheType, opts.CacheTTL, opts.MaxCacheSize, nil)
	if err != nil {
		return nil, fmt.Errorf("create cache backend error: %w", err)
	}
	c.cache = cacheBackend

	httpOpts := &source.HTTPSourceOptions{
		ServerURL:       opts.ServerURL,
		AppKey:          opts.AppKey,
		AppSecret:       opts.AppSecret,
		LongPollTimeout: opts.LongPollTimeout,
	}
	c.source = source.NewHTTPSource(httpOpts)

	c.circuitBreaker = breaker.NewCircuitBreaker(opts.CircuitBreakerThreshold, opts.CircuitBreakerTimeout)

	if opts.StatsEnabled {
		c.statsCollector = NewStatsCollector(
			opts.ServiceName,
			opts.SDKVersion,
			opts.StatsReportInterval,
			c.source.ReportStats,
		)
	}

	if err := c.loadSnapshot(); err != nil {
		fmt.Printf("load snapshot warning: %v\n", err)
	}

	if err := c.initialize(); err != nil {
		fmt.Printf("initialize warning: %v\n", err)
	}

	c.startBackgroundTasks()

	return c, nil
}

func (c *Client) WithCustomSource(src SwitchSource) *Client {
	c.source = src
	return c
}

func (c *Client) WithCustomCache(cache CacheBackend) *Client {
	c.cache = cache
	return c
}

func (c *Client) WithCustomCircuitBreaker(cb CircuitBreaker) *Client {
	c.circuitBreaker = cb
	return c
}

func (c *Client) initialize() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	config, err := c.source.Fetch(ctx, 0)
	if err != nil {
		return fmt.Errorf("fetch initial config error: %w", err)
	}

	c.updateConfig(config)
	return nil
}

func (c *Client) startBackgroundTasks() {
	c.isRunning = true

	go c.pollingLoop()
}

func (c *Client) pollingLoop() {
	ticker := time.NewTicker(c.options.PollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			c.poll()
		case <-c.stopChan:
			return
		}
	}
}

func (c *Client) poll() {
	if !c.circuitBreaker.Allow() {
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), c.options.LongPollTimeout)
	defer cancel()

	c.mu.RLock()
	currentVersion := c.version
	c.mu.RUnlock()

	config, err := c.source.Fetch(ctx, currentVersion)
	if err != nil {
		c.circuitBreaker.Failure()
		return
	}

	c.circuitBreaker.Success()
	c.updateConfig(config)
}

func (c *Client) updateConfig(config *SDKConfig) {
	if config == nil || config.Version <= c.version {
		return
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	c.version = config.Version

	switchMap := make(map[string]*SwitchSnapshot, len(config.Switches))
	for _, sw := range config.Switches {
		switchMap[sw.Key] = sw
	}

	if err := c.cache.SetAll(context.Background(), switchMap); err != nil {
		fmt.Printf("cache set all error: %v\n", err)
	}

	c.saveSnapshot(switchMap)
}

func (c *Client) Evaluate(key string, ctx *EvaluationContext) *EvaluationResult {
	start := time.Now()

	if ctx == nil {
		ctx = &EvaluationContext{}
	}

	result := c.evaluateInternal(key, ctx)

	latency := time.Since(start).Microseconds()

	if c.statsCollector != nil {
		if result.Evaluation > 0 {
			c.statsCollector.Record(key, result, latency)
		} else {
			c.statsCollector.RecordError(key)
		}
	}

	return result
}

func (c *Client) evaluateInternal(key string, ctx *EvaluationContext) *EvaluationResult {
	sw, ok := c.cache.Get(context.Background(), key)
	if ok {
		result := EvaluateSwitch(sw, ctx)
		result.Evaluation = 1
		return result
	}

	if c.options.FallbackEnabled {
		if c.circuitBreaker.Allow() {
			result, err := c.source.Evaluate(context.Background(), key, ctx)
			if err == nil {
				c.circuitBreaker.Success()
				result.Evaluation = 2
				return result
			}
			c.circuitBreaker.Failure()
		}

		snapshot := c.loadFromSnapshot(key)
		if snapshot != nil {
			result := EvaluateSwitch(snapshot, ctx)
			result.Evaluation = 3
			return result
		}
	}

	return &EvaluationResult{
		SwitchKey:  key,
		Enabled:    false,
		Matched:    false,
		Reason:     "fallback_default",
		Evaluation: 0,
	}
}

func (c *Client) BatchEvaluate(ctx *EvaluationContext) map[string]*EvaluationResult {
	if ctx == nil {
		ctx = &EvaluationContext{}
	}

	results := make(map[string]*EvaluationResult)

	switches, err := c.cache.GetAll(context.Background())
	if err == nil {
		for key, sw := range switches {
			results[key] = EvaluateSwitch(sw, ctx)
		}
		return results
	}

	if c.options.FallbackEnabled && c.circuitBreaker.Allow() {
		remoteResults, err := c.source.BatchEvaluate(context.Background(), ctx)
		if err == nil {
			c.circuitBreaker.Success()
			return remoteResults
		}
		c.circuitBreaker.Failure()
	}

	return results
}

func (c *Client) GetBoolean(key string, ctx *EvaluationContext, defaultValue bool) bool {
	result := c.Evaluate(key, ctx)
	if !result.Enabled {
		return defaultValue
	}
	if b, ok := result.Value.(bool); ok {
		return b
	}
	return result.Matched
}

func (c *Client) GetString(key string, ctx *EvaluationContext, defaultValue string) string {
	result := c.Evaluate(key, ctx)
	if !result.Enabled {
		return defaultValue
	}
	if s, ok := result.Value.(string); ok {
		return s
	}
	if result.Matched {
		return "true"
	}
	return defaultValue
}

func (c *Client) GetInt(key string, ctx *EvaluationContext, defaultValue int) int {
	result := c.Evaluate(key, ctx)
	if !result.Enabled {
		return defaultValue
	}
	if i, ok := result.Value.(int); ok {
		return i
	}
	if result.Matched {
		return 1
	}
	return defaultValue
}

func (c *Client) GetFloat64(key string, ctx *EvaluationContext, defaultValue float64) float64 {
	result := c.Evaluate(key, ctx)
	if !result.Enabled {
		return defaultValue
	}
	if f, ok := result.Value.(float64); ok {
		return f
	}
	if result.Matched {
		return 1.0
	}
	return defaultValue
}

func (c *Client) IsEnabled(key string, ctx *EvaluationContext) bool {
	return c.GetBoolean(key, ctx, false)
}

func (c *Client) GetAllSwitches() map[string]*SwitchSnapshot {
	switches, _ := c.cache.GetAll(context.Background())
	return switches
}

func (c *Client) ForceRefresh() error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	config, err := c.source.Fetch(ctx, 0)
	if err != nil {
		return err
	}

	c.updateConfig(config)
	return nil
}

func (c *Client) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()

	if !c.isRunning {
		return
	}
	c.isRunning = false

	close(c.stopChan)

	if c.statsCollector != nil {
		c.statsCollector.Stop()
	}

	if c.cache != nil {
		_ = c.cache.Close()
	}
}

func (c *Client) saveSnapshot(switches map[string]*SwitchSnapshot) {
	data, err := json.Marshal(switches)
	if err != nil {
		return
	}

	if err := os.WriteFile(c.snapshotFile, data, 0644); err != nil {
		fmt.Printf("save snapshot error: %v\n", err)
	}
}

func (c *Client) loadSnapshot() error {
	data, err := os.ReadFile(c.snapshotFile)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	var switches map[string]*SwitchSnapshot
	if err := json.Unmarshal(data, &switches); err != nil {
		return err
	}

	return c.cache.SetAll(context.Background(), switches)
}

func (c *Client) loadFromSnapshot(key string) *SwitchSnapshot {
	data, err := os.ReadFile(c.snapshotFile)
	if err != nil {
		return nil
	}

	var switches map[string]*SwitchSnapshot
	if err := json.Unmarshal(data, &switches); err != nil {
		return nil
	}

	if sw, ok := switches[key]; ok {
		return sw
	}
	return nil
}

func (c *Client) GetVersion() int64 {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.version
}

func (c *Client) GetStats() map[string]interface{} {
	c.mu.RLock()
	defer c.mu.RUnlock()

	return map[string]interface{}{
		"version":           c.version,
		"circuit_breaker":   c.circuitBreaker.State(),
		"cache_type":        c.options.CacheType,
		"poll_interval":     c.options.PollInterval,
		"stats_enabled":     c.options.StatsEnabled,
		"fallback_enabled":  c.options.FallbackEnabled,
	}
}

func (c *Client) HealthCheck() error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	_, err := c.source.Fetch(ctx, c.version)
	return err
}

type EvaluationContextBuilder struct {
	ctx *EvaluationContext
}

func NewContextBuilder() *EvaluationContextBuilder {
	return &EvaluationContextBuilder{
		ctx: &EvaluationContext{
			Attributes: make(map[string]string),
		},
	}
}

func (b *EvaluationContextBuilder) WithUserID(userID string) *EvaluationContextBuilder {
	b.ctx.UserID = userID
	return b
}

func (b *EvaluationContextBuilder) WithDepartment(dept string) *EvaluationContextBuilder {
	b.ctx.Department = dept
	return b
}

func (b *EvaluationContextBuilder) WithTags(tags ...string) *EvaluationContextBuilder {
	b.ctx.Tags = tags
	return b
}

func (b *EvaluationContextBuilder) WithEnvironment(env string) *EvaluationContextBuilder {
	b.ctx.Environment = env
	return b
}

func (b *EvaluationContextBuilder) WithTenantID(tenantID string) *EvaluationContextBuilder {
	b.ctx.TenantID = tenantID
	return b
}

func (b *EvaluationContextBuilder) WithAttribute(key, value string) *EvaluationContextBuilder {
	b.ctx.Attributes[key] = value
	return b
}

func (b *EvaluationContextBuilder) Build() *EvaluationContext {
	return b.ctx
}
