package batch

import (
	"context"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/core/ports"
	"go.uber.org/zap"
)

type queuedRequest struct {
	req      *ports.BatchRequest
	future   *ports.BatchRequestFuture
	received time.Time
}

type Processor struct {
	mu              sync.Mutex
	queue           map[string][]*queuedRequest
	maxBatchSize    int
	maxWaitTime     time.Duration
	shutdownChan    chan struct{}
	wg              sync.WaitGroup
	processBatchFn  func(operation string, items []*ports.BatchRequest) []*ports.BatchOperationResult
	logger          *zap.Logger
}

func NewProcessor(
	maxBatchSize int,
	maxWaitTime time.Duration,
	processBatchFn func(operation string, items []*ports.BatchRequest) []*ports.BatchOperationResult,
	logger *zap.Logger,
) ports.BatchProcessor {
	if logger == nil {
		logger = zap.NewNop()
	}
	if maxBatchSize <= 0 {
		maxBatchSize = 100
	}
	if maxWaitTime <= 0 {
		maxWaitTime = 100 * time.Millisecond
	}

	p := &Processor{
		queue:          make(map[string][]*queuedRequest),
		maxBatchSize:   maxBatchSize,
		maxWaitTime:    maxWaitTime,
		shutdownChan:   make(chan struct{}),
		processBatchFn: processBatchFn,
		logger:         logger,
	}

	return p
}

func (p *Processor) QueueRequest(req *ports.BatchRequest) *ports.BatchRequestFuture {
	if req == nil {
		future := &ports.BatchRequestFuture{
			ResultChan: make(chan *ports.BatchOperationResult, 1),
		}
		future.ResultChan <- &ports.BatchOperationResult{
			Success: false,
			Error:   "request is nil",
		}
		close(future.ResultChan)
		return future
	}

	future := &ports.BatchRequestFuture{
		ResultChan: make(chan *ports.BatchOperationResult, 1),
	}

	p.mu.Lock()
	operation := req.Operation
	p.queue[operation] = append(p.queue[operation], &queuedRequest{
		req:      req,
		future:   future,
		received: time.Now(),
	})

	queueLen := len(p.queue[operation])
	p.mu.Unlock()

	if queueLen >= p.maxBatchSize {
		go p.flushBatch(operation)
	}

	return future
}

func (p *Processor) flushBatch(operation string) {
	p.mu.Lock()
	items, exists := p.queue[operation]
	if !exists || len(items) == 0 {
		p.mu.Unlock()
		return
	}

	batchSize := p.maxBatchSize
	if len(items) < batchSize {
		batchSize = len(items)
	}

	batch := items[:batchSize]
	p.queue[operation] = items[batchSize:]
	if len(p.queue[operation]) == 0 {
		delete(p.queue, operation)
	}
	p.mu.Unlock()

	p.processBatch(operation, batch)
}

func (p *Processor) processBatch(operation string, batch []*queuedRequest) {
	if len(batch) == 0 {
		return
	}

	start := time.Now()

	requests := make([]*ports.BatchRequest, 0, len(batch))
	for _, item := range batch {
		requests = append(requests, item.req)
	}

	results := p.processBatchFn(operation, requests)

	for i, result := range results {
		if i < len(batch) {
			batch[i].future.ResultChan <- result
			close(batch[i].future.ResultChan)
		}
	}

	p.logger.Debug("batch processed",
		zap.String("operation", operation),
		zap.Int("batch_size", len(batch)),
		zap.Duration("duration", time.Since(start)),
	)
}

func (p *Processor) Start() {
	p.wg.Add(1)
	go func() {
		defer p.wg.Done()
		ticker := time.NewTicker(p.maxWaitTime)
		defer ticker.Stop()

		for {
			select {
			case <-ticker.C:
				p.flushAll()
			case <-p.shutdownChan:
				return
			}
		}
	}()

	p.logger.Info("batch processor started",
		zap.Int("max_batch_size", p.maxBatchSize),
		zap.Duration("max_wait_time", p.maxWaitTime),
	)
}

func (p *Processor) flushAll() {
	p.mu.Lock()
	operations := make([]string, 0, len(p.queue))
	for op := range p.queue {
		operations = append(operations, op)
	}
	p.mu.Unlock()

	for _, op := range operations {
		p.flushBatch(op)
	}
}

func (p *Processor) Stop() {
	close(p.shutdownChan)
	p.wg.Wait()
	p.flushAll()

	p.logger.Info("batch processor stopped")
}
