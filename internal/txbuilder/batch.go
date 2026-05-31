package txbuilder

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/ethereum/go-ethereum/core/types"
	"go.uber.org/zap"

	"github.com/blockchain-middleware/core/internal/common/logger"
)

type BatchRequest struct {
	Requests []TransactionRequest `json:"requests"`
	Options  BatchOptions          `json:"options"`
}

type BatchOptions struct {
	MaxBatchSize   int           `json:"max_batch_size"`
	Timeout        time.Duration `json:"timeout"`
	FailOnError    bool          `json:"fail_on_error"`
	OptimizeNonce  bool          `json:"optimize_nonce"`
}

type BatchResult struct {
	Results    []BatchResultItem `json:"results"`
	SuccessCount int             `json:"success_count"`
	FailCount    int             `json:"fail_count"`
	DurationMs   int64           `json:"duration_ms"`
}

type BatchResultItem struct {
	Index     int         `json:"index"`
	Success   bool        `json:"success"`
	Data      interface{} `json:"data,omitempty"`
	Error     string      `json:"error,omitempty"`
	TxHash    string      `json:"tx_hash,omitempty"`
}

type BatchBuilder struct {
	txBuilder *TransactionBuilder
	mu        sync.Mutex
}

func NewBatchBuilder(txBuilder *TransactionBuilder) *BatchBuilder {
	return &BatchBuilder{
		txBuilder: txBuilder,
	}
}

func (bb *BatchBuilder) BuildBatch(ctx context.Context, batchReq BatchRequest) (*BatchResult, error) {
	startTime := time.Now()

	if len(batchReq.Requests) == 0 {
		return &BatchResult{
			Results:    []BatchResultItem{},
			SuccessCount: 0,
			FailCount:    0,
			DurationMs:   0,
		}, nil
	}

	maxSize := batchReq.Options.MaxBatchSize
	if maxSize <= 0 {
		maxSize = 100
	}

	timeout := batchReq.Options.Timeout
	if timeout <= 0 {
		timeout = 30 * time.Second
	}

	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	results := make([]BatchResultItem, len(batchReq.Requests))
	successCount := 0
	failCount := 0

	var wg sync.WaitGroup
	var mu sync.Mutex

	concurrency := 10
	if len(batchReq.Requests) < concurrency {
		concurrency = len(batchReq.Requests)
	}

	sem := make(chan struct{}, concurrency)

	for i, req := range batchReq.Requests {
		wg.Add(1)
		sem <- struct{}{}

		go func(index int, request TransactionRequest) {
			defer wg.Done()
			defer func() { <-sem }()

			select {
			case <-ctx.Done():
				mu.Lock()
				results[index] = BatchResultItem{
					Index:   index,
					Success: false,
					Error:   "timeout",
				}
				failCount++
				mu.Unlock()
				return
			default:
			}

			tx, err := bb.txBuilder.BuildTransaction(ctx, request)
			if err != nil {
				mu.Lock()
				results[index] = BatchResultItem{
					Index:   index,
					Success: false,
					Error:   err.Error(),
				}
				failCount++
				mu.Unlock()

				if batchReq.Options.FailOnError {
					cancel()
				}
				return
			}

			mu.Lock()
			results[index] = BatchResultItem{
				Index:   index,
				Success: true,
				Data:    tx,
				TxHash:  tx.Hash().Hex(),
			}
			successCount++
			mu.Unlock()
		}(i, req)
	}

	wg.Wait()

	duration := time.Since(startTime).Milliseconds()

	return &BatchResult{
		Results:      results,
		SuccessCount: successCount,
		FailCount:    failCount,
		DurationMs:   duration,
	}, nil
}

func (bb *BatchBuilder) SignBatch(ctx context.Context, txs []*types.Transaction, signerAddresses []string) (*BatchResult, error) {
	startTime := time.Now()

	if len(txs) != len(signerAddresses) {
		return nil, fmt.Errorf("transactions and signers count mismatch")
	}

	results := make([]BatchResultItem, len(txs))
	successCount := 0
	failCount := 0

	var wg sync.WaitGroup
	var mu sync.Mutex

	concurrency := 10
	if len(txs) < concurrency {
		concurrency = len(txs)
	}

	sem := make(chan struct{}, concurrency)

	for i, tx := range txs {
		wg.Add(1)
		sem <- struct{}{}

		go func(index int, transaction *types.Transaction, signerAddr string) {
			defer wg.Done()
			defer func() { <-sem }()

			signedTx, err := bb.txBuilder.SignTransaction(ctx, transaction, signerAddr)
			if err != nil {
				mu.Lock()
				results[index] = BatchResultItem{
					Index:   index,
					Success: false,
					Error:   err.Error(),
				}
				failCount++
				mu.Unlock()
				return
			}

			mu.Lock()
			results[index] = BatchResultItem{
				Index:   index,
				Success: true,
				Data:    signedTx,
				TxHash:  signedTx.TxHash,
			}
			successCount++
			mu.Unlock()
		}(i, tx, signerAddresses[i])
	}

	wg.Wait()

	duration := time.Since(startTime).Milliseconds()

	return &BatchResult{
		Results:      results,
		SuccessCount: successCount,
		FailCount:    failCount,
		DurationMs:   duration,
	}, nil
}

type RequestBatcher struct {
	queue        chan *QueuedRequest
	batchSize    int
	batchTimeout time.Duration
	processor    func(ctx context.Context, requests []*QueuedRequest) ([]BatchResultItem, error)
	ctx          context.Context
	cancel       context.CancelFunc
	wg           sync.WaitGroup
	mu           sync.Mutex
	stats        *BatcherStats
}

type QueuedRequest struct {
	Request    TransactionRequest
	ResultChan chan BatchResultItem
	Timestamp  time.Time
}

type BatcherStats struct {
	TotalRequests   uint64
	TotalBatches    uint64
	AvgBatchSize    float64
	AvgLatencyMs    float64
	TotalDurationMs int64
}

func NewRequestBatcher(
	batchSize int,
	batchTimeout time.Duration,
	processor func(ctx context.Context, requests []*QueuedRequest) ([]BatchResultItem, error),
) *RequestBatcher {
	ctx, cancel := context.WithCancel(context.Background())
	return &RequestBatcher{
		queue:        make(chan *QueuedRequest, batchSize*10),
		batchSize:    batchSize,
		batchTimeout: batchTimeout,
		processor:    processor,
		ctx:          ctx,
		cancel:       cancel,
		stats:        &BatcherStats{},
	}
}

func (rb *RequestBatcher) Start() {
	rb.wg.Add(1)
	go rb.processLoop()
	logger.Log.Info("Request batcher started",
		zap.Int("batch_size", rb.batchSize),
		zap.Duration("timeout", rb.batchTimeout))
}

func (rb *RequestBatcher) Stop() {
	rb.cancel()
	close(rb.queue)
	rb.wg.Wait()
	logger.Log.Info("Request batcher stopped")
}

func (rb *RequestBatcher) processLoop() {
	defer rb.wg.Done()

	batch := make([]*QueuedRequest, 0, rb.batchSize)
	timer := time.NewTimer(rb.batchTimeout)
	defer timer.Stop()

	for {
		select {
		case <-rb.ctx.Done():
			if len(batch) > 0 {
				rb.processBatch(batch)
			}
			return

		case req, ok := <-rb.queue:
			if !ok {
				if len(batch) > 0 {
					rb.processBatch(batch)
				}
				return
			}

			batch = append(batch, req)
			if len(batch) >= rb.batchSize {
				rb.processBatch(batch)
				batch = make([]*QueuedRequest, 0, rb.batchSize)
				timer.Reset(rb.batchTimeout)
			}

		case <-timer.C:
			if len(batch) > 0 {
				rb.processBatch(batch)
				batch = make([]*QueuedRequest, 0, rb.batchSize)
			}
			timer.Reset(rb.batchTimeout)
		}
	}
}

func (rb *RequestBatcher) processBatch(batch []*QueuedRequest) {
	startTime := time.Now()

	rb.mu.Lock()
	rb.stats.TotalBatches++
	rb.stats.TotalRequests += uint64(len(batch))
	rb.mu.Unlock()

	results, err := rb.processor(rb.ctx, batch)

	duration := time.Since(startTime).Milliseconds()

	rb.mu.Lock()
	rb.stats.TotalDurationMs += duration
	rb.stats.AvgBatchSize = float64(rb.stats.TotalRequests) / float64(rb.stats.TotalBatches)
	rb.stats.AvgLatencyMs = float64(rb.stats.TotalDurationMs) / float64(rb.stats.TotalBatches)
	rb.mu.Unlock()

	for i, req := range batch {
		if i < len(results) {
			req.ResultChan <- results[i]
		} else {
			errorMsg := "batch processing failed"
			if err != nil {
				errorMsg = err.Error()
			}
			req.ResultChan <- BatchResultItem{
				Index:   i,
				Success: false,
				Error:   errorMsg,
			}
		}
		close(req.ResultChan)
	}

	logger.Log.Debug("Batch processed",
		zap.Int("size", len(batch)),
		zap.Int64("duration_ms", duration))
}

func (rb *RequestBatcher) Submit(ctx context.Context, req TransactionRequest) (BatchResultItem, error) {
	queued := &QueuedRequest{
		Request:    req,
		ResultChan: make(chan BatchResultItem, 1),
		Timestamp:  time.Now(),
	}

	select {
	case rb.queue <- queued:
	case <-ctx.Done():
		return BatchResultItem{}, ctx.Err()
	}

	select {
	case result := <-queued.ResultChan:
		return result, nil
	case <-ctx.Done():
		return BatchResultItem{}, ctx.Err()
	}
}

func (rb *RequestBatcher) GetStats() BatcherStats {
	rb.mu.Lock()
	defer rb.mu.Unlock()
	return *rb.stats
}

func (rb *RequestBatcher) ResetStats() {
	rb.mu.Lock()
	defer rb.mu.Unlock()
	rb.stats = &BatcherStats{}
}

type SendBatchRequest struct {
	ChainID uint64   `json:"chain_id"`
	RawTxs  [][]byte `json:"raw_txs"`
}

type SendBatchResult struct {
	TxHashes     []string `json:"tx_hashes"`
	SuccessCount int      `json:"success_count"`
	FailCount    int      `json:"fail_count"`
	Errors       []string `json:"errors,omitempty"`
}
