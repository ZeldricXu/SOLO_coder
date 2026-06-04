package batcher

import (
	"context"
	"encoding/json"
	"fmt"
	"model-inference-platform/internal/pkg/config"
	"model-inference-platform/internal/pkg/redis"
	"model-inference-platform/internal/pkg/triton"
	"sync"
	"time"

	"go.uber.org/zap"
)

type BatchRequest struct {
	RequestID  string
	TraceID    string
	ModelName  string
	Version    string
	Namespace  string
	Inputs     map[string]interface{}
	ResponseCh chan *BatchResponse `json:"-"`
	Timestamp  time.Time
}

type BatchResponse struct {
	RequestID string
	Outputs   map[string]interface{}
	LatencyMs int64
	Error     string
	BatchSize int
}

type ModelBatch struct {
	ModelName string
	Version   string
	Namespace string
	Requests  []*BatchRequest
	CreatedAt time.Time
}

const (
	idleTimeout      = 5 * time.Minute
	maxWorkers       = 4
	flushChannelSize = 1000
)

type PerModelBatcher struct {
	key        string
	ModelName  string
	Version    string
	Namespace  string
	batcher    *Batcher

	requests []*BatchRequest
	mu       sync.Mutex

	windowTimer *time.Timer
	flushCh     chan struct{}
	wakeupCh    chan struct{}

	lastActivity time.Time
	stopCh       chan struct{}
	wg           sync.WaitGroup
}

func (p *PerModelBatcher) Start() {
	p.wg.Add(1)
	go p.run()
}

func (p *PerModelBatcher) Stop() {
	close(p.stopCh)
	p.wg.Wait()
}

func (p *PerModelBatcher) Submit(req *BatchRequest) {
	p.mu.Lock()
	p.requests = append(p.requests, req)
	p.lastActivity = time.Now()
	currentSize := len(p.requests)

	if p.windowTimer == nil && currentSize == 1 {
		p.windowTimer = time.NewTimer(p.batcher.cfg.BatchWindow)
		p.mu.Unlock()
		select {
		case p.wakeupCh <- struct{}{}:
		default:
		}
		return
	}

	if currentSize >= p.batcher.cfg.MaxBatchSize {
		if p.windowTimer != nil {
			p.windowTimer.Stop()
			p.windowTimer = nil
		}
		p.mu.Unlock()
		select {
		case p.flushCh <- struct{}{}:
		default:
		}
		return
	}
	p.mu.Unlock()
}

func (p *PerModelBatcher) run() {
	defer p.wg.Done()

	idleCheckTicker := time.NewTicker(30 * time.Second)
	defer idleCheckTicker.Stop()

	var timerCh <-chan time.Time

	for {
		p.mu.Lock()
		if p.windowTimer != nil {
			timerCh = p.windowTimer.C
		} else {
			timerCh = nil
		}
		p.mu.Unlock()

		select {
		case <-p.stopCh:
			p.drainAndFlush()
			return
		case <-p.flushCh:
			p.flush()
		case <-p.wakeupCh:
			continue
		case <-timerCh:
			p.flush()
		case <-idleCheckTicker.C:
			if time.Since(p.lastActivity) >= idleTimeout {
				p.mu.Lock()
				hasPending := len(p.requests) > 0
				p.mu.Unlock()
				if hasPending {
					p.flush()
				}
				p.batcher.removeIdleBatcher(p.key)
				return
			}
		}
	}
}

func (p *PerModelBatcher) drainAndFlush() {
	p.mu.Lock()
	if p.windowTimer != nil {
		p.windowTimer.Stop()
		p.windowTimer = nil
	}
	p.mu.Unlock()

	p.flush()
}

func (p *PerModelBatcher) flush() {
	p.mu.Lock()
	if len(p.requests) == 0 {
		if p.windowTimer != nil {
			p.windowTimer.Stop()
			p.windowTimer = nil
		}
		p.mu.Unlock()
		return
	}

	requests := p.requests
	p.requests = nil
	if p.windowTimer != nil {
		p.windowTimer.Stop()
		p.windowTimer = nil
	}
	p.mu.Unlock()

	p.batcher.workerSemaphore <- struct{}{}
	go func() {
		defer func() { <-p.batcher.workerSemaphore }()
		p.processRequests(requests)
	}()
}

func (p *PerModelBatcher) processRequests(requests []*BatchRequest) {
	batch := &ModelBatch{
		ModelName: p.ModelName,
		Version:   p.Version,
		Namespace: p.Namespace,
		Requests:  requests,
		CreatedAt: requests[0].Timestamp,
	}

	p.batcher.processBatch(batch)
}

type Batcher struct {
	cfg          config.BatcherConfig
	redisClient  redis.RedisClient
	tritonClient triton.TritonClient
	logger       *zap.Logger

	batches   map[string]*PerModelBatcher
	batchesMu sync.Mutex

	workerSemaphore chan struct{}

	stopCh chan struct{}
	wg     sync.WaitGroup
}

func New(cfg config.BatcherConfig, redisClient redis.RedisClient,
	tritonClient triton.TritonClient, logger *zap.Logger) *Batcher {
	return &Batcher{
		cfg:             cfg,
		redisClient:     redisClient,
		tritonClient:    tritonClient,
		logger:          logger,
		batches:         make(map[string]*PerModelBatcher),
		workerSemaphore: make(chan struct{}, maxWorkers),
		stopCh:          make(chan struct{}),
	}
}

func (b *Batcher) Start(ctx context.Context) error {
	b.logger.Info("Batcher started",
		zap.Int("max_batch_size", b.cfg.MaxBatchSize),
		zap.Duration("batch_window", b.cfg.BatchWindow),
		zap.Int("max_workers", maxWorkers))
	return nil
}

func (b *Batcher) Stop() {
	close(b.stopCh)

	b.batchesMu.Lock()
	for _, pmb := range b.batches {
		pmb.Stop()
	}
	b.batches = make(map[string]*PerModelBatcher)
	b.batchesMu.Unlock()

	b.logger.Info("Batcher stopped")
}

func (b *Batcher) Submit(ctx context.Context, req *BatchRequest) (*BatchResponse, error) {
	key := fmt.Sprintf("%s:%s:%s", req.Namespace, req.ModelName, req.Version)

	pmb := b.getOrCreatePerModelBatcher(key, req)

	pmb.Submit(req)

	b.logger.Debug("Request added to batch",
		zap.String("key", key),
		zap.String("request_id", req.RequestID))

	select {
	case resp := <-req.ResponseCh:
		return resp, nil
	case <-ctx.Done():
		return &BatchResponse{
			RequestID: req.RequestID,
			Error:     "request timeout",
		}, ctx.Err()
	case <-b.stopCh:
		return &BatchResponse{
			RequestID: req.RequestID,
			Error:     "service shutting down",
		}, nil
	}
}

func (b *Batcher) getOrCreatePerModelBatcher(key string, req *BatchRequest) *PerModelBatcher {
	b.batchesMu.Lock()
	defer b.batchesMu.Unlock()

	pmb, ok := b.batches[key]
	if !ok {
		pmb = &PerModelBatcher{
			key:          key,
			ModelName:    req.ModelName,
			Version:      req.Version,
			Namespace:    req.Namespace,
			batcher:      b,
			flushCh:      make(chan struct{}, flushChannelSize),
			wakeupCh:     make(chan struct{}, 1),
			lastActivity: time.Now(),
			stopCh:       make(chan struct{}),
		}
		b.batches[key] = pmb
		pmb.Start()
		b.logger.Debug("Created PerModelBatcher",
			zap.String("key", key))
	}
	return pmb
}

func (b *Batcher) removeIdleBatcher(key string) {
	b.batchesMu.Lock()
	defer b.batchesMu.Unlock()

	pmb, ok := b.batches[key]
	if !ok {
		return
	}

	pmb.mu.Lock()
	idle := len(pmb.requests) == 0 && time.Since(pmb.lastActivity) >= idleTimeout
	pmb.mu.Unlock()

	if idle {
		delete(b.batches, key)
		b.logger.Debug("Removed idle PerModelBatcher",
			zap.String("key", key))
	}
}

func (b *Batcher) processBatch(batch *ModelBatch) {
	key := fmt.Sprintf("%s:%s:%s", batch.Namespace, batch.ModelName, batch.Version)

	b.logger.Info("Processing batch",
		zap.String("key", key),
		zap.Int("batch_size", len(batch.Requests)),
		zap.Duration("wait_time", time.Since(batch.CreatedAt)))

	start := time.Now()

	mergedInputs := b.mergeInputs(batch.Requests)

	inputs := make([]*triton.InferenceTensor, 0, len(mergedInputs))
	for name, data := range mergedInputs {
		inputs = append(inputs, &triton.InferenceTensor{
			Name:  name,
			Shape: []int64{int64(len(batch.Requests)), 3, 224, 224},
			DType: "FP32",
			Data:  data,
		})
	}

	result, err := b.tritonClient.Infer(context.Background(), batch.ModelName, batch.Version, inputs, []string{"output"})
	latency := time.Since(start)
	if result != nil && result.Latency > latency {
		latency = result.Latency
	}

	if err != nil {
		b.logger.Error("Batch inference failed",
			zap.String("key", key),
			zap.Error(err))
		for _, req := range batch.Requests {
			req.ResponseCh <- &BatchResponse{
				RequestID: req.RequestID,
				Error:     err.Error(),
				BatchSize: len(batch.Requests),
			}
		}
		return
	}

	batchSize := len(batch.Requests)
	for i, req := range batch.Requests {
		outputs := b.extractOutput(result, i, batchSize)
		req.ResponseCh <- &BatchResponse{
			RequestID: req.RequestID,
			Outputs:   outputs,
			LatencyMs: latency.Milliseconds(),
			BatchSize: batchSize,
		}
	}

	b.logger.Debug("Batch completed",
		zap.String("key", key),
		zap.Int("batch_size", len(batch.Requests)),
		zap.Duration("latency", latency))
}

func (b *Batcher) mergeInputs(requests []*BatchRequest) map[string]interface{} {
	merged := make(map[string]interface{})

	for _, req := range requests {
		for name, val := range req.Inputs {
			if arr, ok := merged[name].([]float32); ok {
				if data, ok := val.([]float32); ok {
					merged[name] = append(arr, data...)
				}
			} else {
				merged[name] = val
			}
		}
	}

	return merged
}

func (b *Batcher) extractOutput(result *triton.InferenceResult, index int, batchSize int) map[string]interface{} {
	outputs := make(map[string]interface{})

	for _, out := range result.Outputs {
		if data, ok := out.Data.([]float32); ok {
			if batchSize <= 0 {
				batchSize = 1
			}
			perSample := len(data) / batchSize
			start := index * perSample
			end := start + perSample
			if end <= len(data) {
				outputs[out.Name] = data[start:end]
			}
		} else {
			outputs[out.Name] = out.Data
		}
	}

	return outputs
}

func (b *Batcher) GetQueueStats() map[string]interface{} {
	b.batchesMu.Lock()
	defer b.batchesMu.Unlock()

	stats := make(map[string]interface{})
	for key, pmb := range b.batches {
		pmb.mu.Lock()
		stats[key] = map[string]interface{}{
			"pending_requests": len(pmb.requests),
			"last_activity":    pmb.lastActivity.Format(time.RFC3339),
		}
		pmb.mu.Unlock()
	}
	return stats
}

func (b *Batcher) MarshalStats() ([]byte, error) {
	return json.Marshal(b.GetQueueStats())
}
