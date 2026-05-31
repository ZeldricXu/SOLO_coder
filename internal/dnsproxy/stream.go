package dnsproxy

import (
	"context"
	"sync"
	"time"

	"session130/internal/logger"
)

type StreamResolver struct {
	selector   UpstreamSelector
	cache      Cache
	config     StreamBatchConfig
	requestChan chan *streamRequest
	resultChan  chan ResolveResult
	wg         sync.WaitGroup
	ctx        context.Context
	cancel     context.CancelFunc
	mu         sync.Mutex
	progress   *ResolveProgress
}

type streamRequest struct {
	ctx      context.Context
	request  DnsResolveRequest
	resultCh chan ResolveResult
}

func NewStreamResolver(config StreamBatchConfig) *StreamResolver {
	config = applyDefaults(config)

	return &StreamResolver{
		selector:    GetUpstreamManager(),
		cache:       GetCacheManager(),
		config:      config,
		requestChan: make(chan *streamRequest, 10000),
		resultChan:  make(chan ResolveResult, 10000),
		progress:    &ResolveProgress{StartTime: time.Now()},
	}
}

func applyDefaults(config StreamBatchConfig) StreamBatchConfig {
	if config.BatchSize <= 0 {
		config.BatchSize = 100
	}
	if config.FlushInterval <= 0 {
		config.FlushInterval = 100 * time.Millisecond
	}
	if config.MaxConcurrency <= 0 {
		config.MaxConcurrency = 10
	}
	if config.TimeoutPerBatch <= 0 {
		config.TimeoutPerBatch = 30 * time.Second
	}
	if config.MaxRetries <= 0 {
		config.MaxRetries = 3
	}
	return config
}

func (sr *StreamResolver) Start() {
	sr.ctx, sr.cancel = context.WithCancel(context.Background())

	for i := 0; i < sr.config.MaxConcurrency; i++ {
		sr.wg.Add(1)
		go sr.worker(i)
	}

	go sr.batchProcessor()
	go sr.progressReporter()

	logger.Info("", "Stream resolver started", map[string]interface{}{
		"batch_size":      sr.config.BatchSize,
		"flush_interval":  sr.config.FlushInterval.Milliseconds(),
		"max_concurrency": sr.config.MaxConcurrency,
	})
}

func (sr *StreamResolver) Stop() {
	sr.cancel()
	close(sr.requestChan)
	sr.wg.Wait()
	close(sr.resultChan)
	logger.Info("", "Stream resolver stopped", nil)
}

func (sr *StreamResolver) Resolve(ctx context.Context, req DnsResolveRequest) (*DnsResolveResponse, error) {
	if !req.SkipCache {
		if resp, hit := sr.tryCache(req); hit {
			return resp, nil
		}
	}
	return sr.resolveViaUpstream(ctx, req)
}

func (sr *StreamResolver) tryCache(req DnsResolveRequest) (*DnsResolveResponse, bool) {
	entry, hit := sr.cache.Get(req.Domain, req.RecordType)
	if !hit {
		return nil, false
	}
	return &DnsResolveResponse{
		Domain:        req.Domain,
		RecordType:    req.RecordType,
		Records:       entry.RecordData,
		TTL:           int64(time.Until(entry.ExpiresAt).Seconds()),
		FromCache:     true,
		UpstreamUsed:  "cache",
		ResolveTimeMs: 0,
		ResolvedAt:    time.Now(),
		TraceID:       req.TraceID,
	}, true
}

func (sr *StreamResolver) resolveViaUpstream(ctx context.Context, req DnsResolveRequest) (*DnsResolveResponse, error) {
	resultCh := make(chan ResolveResult, 1)
	select {
	case sr.requestChan <- &streamRequest{
		ctx:      ctx,
		request:  req,
		resultCh: resultCh,
	}:
	case <-ctx.Done():
		return nil, ctx.Err()
	}

	select {
	case result := <-resultCh:
		return result.Response, result.Error
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (sr *StreamResolver) ResolveBatch(ctx context.Context, requests []DnsResolveRequest) *BatchResolveResponse {
	start := time.Now()
	responses := make([]DnsResolveResponse, len(requests))
	var wg sync.WaitGroup

	for i, req := range requests {
		wg.Add(1)
		go sr.resolveOne(ctx, i, req, responses, &wg)
	}

	wg.Wait()

	return &BatchResolveResponse{
		Responses: responses,
		TotalTime: time.Since(start).Milliseconds(),
	}
}

func (sr *StreamResolver) resolveOne(ctx context.Context, idx int, req DnsResolveRequest, responses []DnsResolveResponse, wg *sync.WaitGroup) {
	defer wg.Done()
	resp, err := sr.Resolve(ctx, req)
	if err != nil {
		responses[idx] = DnsResolveResponse{
			Domain:     req.Domain,
			RecordType: req.RecordType,
			TraceID:    req.TraceID,
		}
	} else {
		responses[idx] = *resp
	}
}

func (sr *StreamResolver) worker(id int) {
	defer sr.wg.Done()

	for req := range sr.requestChan {
		sr.processRequest(req)
	}
}

func (sr *StreamResolver) processRequest(req *streamRequest) {
	resp, err := sr.selector.Resolve(req.ctx, req.request)
	if err == nil && resp != nil {
		sr.cache.Put(req.request.Domain, req.request.RecordType, resp.Records, resp.TTL)
	}

	result := ResolveResult{
		Request:  req.request,
		Response: resp,
		Error:    err,
	}

	sendResult(req, result)
	sr.updateProgress(err)
	sr.publishResult(result)
}

func sendResult(req *streamRequest, result ResolveResult) {
	select {
	case req.resultCh <- result:
	case <-req.ctx.Done():
	}
}

func (sr *StreamResolver) updateProgress(err error) {
	sr.mu.Lock()
	defer sr.mu.Unlock()
	sr.progress.Completed++
	if err != nil {
		sr.progress.Failed++
	}
}

func (sr *StreamResolver) publishResult(result ResolveResult) {
	select {
	case sr.resultChan <- result:
	default:
	}
}

func (sr *StreamResolver) batchProcessor() {
	ticker := time.NewTicker(sr.config.FlushInterval)
	defer ticker.Stop()

	batch := make([]*streamRequest, 0, sr.config.BatchSize)

	for {
		select {
		case <-sr.ctx.Done():
			return
		case <-ticker.C:
			batch = sr.flushBatch(batch)
		}
	}
}

func (sr *StreamResolver) flushBatch(batch []*streamRequest) []*streamRequest {
	if len(batch) > 0 {
		logger.Debug("", "Flushing batch by timeout", map[string]interface{}{
			"batch_size": len(batch),
		})
		return nil
	}
	return batch
}

func (sr *StreamResolver) progressReporter() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-sr.ctx.Done():
			return
		case <-ticker.C:
			sr.reportProgress()
		}
	}
}

func (sr *StreamResolver) reportProgress() {
	sr.mu.Lock()
	defer sr.mu.Unlock()
	elapsed := time.Since(sr.progress.StartTime).Seconds()
	qps := 0.0
	if elapsed > 0 {
		qps = float64(sr.progress.Completed) / elapsed
	}
	logger.Info("", "Stream resolver progress", map[string]interface{}{
		"total":     sr.progress.Total,
		"completed": sr.progress.Completed,
		"failed":    sr.progress.Failed,
		"qps":       qps,
		"elapsed_s": elapsed,
	})
}

func (sr *StreamResolver) GetProgress() *ResolveProgress {
	sr.mu.Lock()
	defer sr.mu.Unlock()
	p := *sr.progress
	return &p
}

func (sr *StreamResolver) Results() <-chan ResolveResult {
	return sr.resultChan
}

func (sr *StreamResolver) AddToTotal(n int) {
	sr.mu.Lock()
	defer sr.mu.Unlock()
	sr.progress.Total += n
}
