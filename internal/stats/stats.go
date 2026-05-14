package stats

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"netproxy/internal/logger"
)

type TrafficStats struct {
	RequestCount  int64     `json:"request_count"`
	TrafficIn     int64     `json:"traffic_in"`
	TrafficOut    int64     `json:"traffic_out"`
	TotalLatency  int64     `json:"total_latency"`
	MinLatency    int64     `json:"min_latency"`
	MaxLatency    int64     `json:"max_latency"`
	ErrorCount    int64     `json:"error_count"`
	LastUpdated   time.Time `json:"last_updated"`
}

type StatsEvent struct {
	TargetHost  string
	TrafficIn   int64
	TrafficOut  int64
	Latency     int64
	HasError    bool
	Timestamp   time.Time
}

type HostStats struct {
	TargetHost   string
	Stats        TrafficStats
	Historical   []TrafficStats
	mu           sync.RWMutex
}

type PersistentStatsRecord struct {
	Timestamp    time.Time           `json:"timestamp"`
	HostStats    map[string]TrafficStats `json:"host_stats"`
	Aggregated   TrafficStats        `json:"aggregated"`
}

type StatsPersistenceConfig struct {
	Enabled        bool   `json:"enabled"`
	FilePath       string `json:"file_path"`
	Interval       int    `json:"interval_seconds"`
	MaxRecords     int    `json:"max_records"`
	BufferSize     int    `json:"buffer_size"`
	FlushInterval  int    `json:"flush_interval"`
}

type AsyncStatsWriter struct {
	eventQueue     chan *StatsEvent
	hostStats      map[string]*HostStats
	hostStatsMu    sync.RWMutex
	config         *StatsPersistenceConfig
	workerWg       sync.WaitGroup
	workerDone     chan struct{}
	workerRunning  bool
	flushTicker    *time.Ticker
	flushWg        sync.WaitGroup
	flushDone      chan struct{}
	flushRunning   bool
	pendingCount   int64
	lastFlushTime  time.Time
}

type StatsManager struct {
	hostStats        map[string]*HostStats
	mu               sync.RWMutex
	persistConfig    *StatsPersistenceConfig
	asyncWriter      *AsyncStatsWriter
	lastPersistTime  time.Time
}

var (
	instance *StatsManager
	once     sync.Once
)

func NewAsyncStatsWriter(cfg *StatsPersistenceConfig) *AsyncStatsWriter {
	if cfg == nil {
		cfg = &StatsPersistenceConfig{Enabled: false}
	}

	bufferSize := cfg.BufferSize
	if bufferSize <= 0 {
		bufferSize = 1000
	}

	return &AsyncStatsWriter{
		eventQueue:    make(chan *StatsEvent, bufferSize),
		hostStats:     make(map[string]*HostStats),
		config:        cfg,
		workerDone:    make(chan struct{}),
		flushDone:     make(chan struct{}),
	}
}

func (asw *AsyncStatsWriter) getOrCreateHostStats(targetHost string) *HostStats {
	asw.hostStatsMu.RLock()
	hs, exists := asw.hostStats[targetHost]
	asw.hostStatsMu.RUnlock()

	if exists {
		return hs
	}

	asw.hostStatsMu.Lock()
	defer asw.hostStatsMu.Unlock()

	if hs, exists := asw.hostStats[targetHost]; exists {
		return hs
	}

	hs = &HostStats{
		TargetHost: targetHost,
		Historical: make([]TrafficStats, 0, 100),
	}
	asw.hostStats[targetHost] = hs
	return hs
}

func (asw *AsyncStatsWriter) processEvent(event *StatsEvent) {
	hs := asw.getOrCreateHostStats(event.TargetHost)

	hs.mu.Lock()
	defer hs.mu.Unlock()

	atomic.AddInt64(&hs.Stats.RequestCount, 1)
	atomic.AddInt64(&hs.Stats.TrafficIn, event.TrafficIn)
	atomic.AddInt64(&hs.Stats.TrafficOut, event.TrafficOut)
	atomic.AddInt64(&hs.Stats.TotalLatency, event.Latency)

	currentMin := atomic.LoadInt64(&hs.Stats.MinLatency)
	if currentMin == 0 || event.Latency < currentMin {
		atomic.StoreInt64(&hs.Stats.MinLatency, event.Latency)
	}

	currentMax := atomic.LoadInt64(&hs.Stats.MaxLatency)
	if event.Latency > currentMax {
		atomic.StoreInt64(&hs.Stats.MaxLatency, event.Latency)
	}

	if event.HasError {
		atomic.AddInt64(&hs.Stats.ErrorCount, 1)
	}

	hs.Stats.LastUpdated = event.Timestamp
}

func (asw *AsyncStatsWriter) workerLoop() {
	defer asw.workerWg.Done()

	batchSize := 100
	batch := make([]*StatsEvent, 0, batchSize)

	for {
		select {
		case event := <-asw.eventQueue:
			batch = append(batch, event)
			atomic.AddInt64(&asw.pendingCount, -1)

			innerLoop := true
			for innerLoop && len(batch) < batchSize {
				select {
				case ev := <-asw.eventQueue:
					batch = append(batch, ev)
					atomic.AddInt64(&asw.pendingCount, -1)
				default:
					innerLoop = false
				}
			}

			for _, ev := range batch {
				asw.processEvent(ev)
			}
			batch = batch[:0]

		case <-asw.workerDone:
			remaining := true
			for remaining {
				select {
				case event := <-asw.eventQueue:
					asw.processEvent(event)
					atomic.AddInt64(&asw.pendingCount, -1)
				default:
					remaining = false
				}
			}
			return
		}
	}
}

func (asw *AsyncStatsWriter) StartWorker() {
	asw.hostStatsMu.Lock()
	if asw.workerRunning {
		asw.hostStatsMu.Unlock()
		return
	}
	asw.workerRunning = true
	asw.workerDone = make(chan struct{})
	asw.hostStatsMu.Unlock()

	asw.workerWg.Add(1)
	go asw.workerLoop()
	logger.Info("Async stats worker started")
}

func (asw *AsyncStatsWriter) StopWorker() {
	asw.hostStatsMu.Lock()
	if !asw.workerRunning {
		asw.hostStatsMu.Unlock()
		return
	}
	asw.workerRunning = false
	close(asw.workerDone)
	asw.hostStatsMu.Unlock()

	asw.workerWg.Wait()
	logger.Info("Async stats worker stopped")
}

func (asw *AsyncStatsWriter) StartFlusher() {
	asw.hostStatsMu.Lock()
	if asw.flushRunning {
		asw.hostStatsMu.Unlock()
		return
	}

	if asw.config == nil || !asw.config.Enabled {
		asw.hostStatsMu.Unlock()
		return
	}

	flushInterval := asw.config.FlushInterval
	if flushInterval <= 0 {
		flushInterval = 10
	}

	asw.flushRunning = true
	asw.flushTicker = time.NewTicker(time.Duration(flushInterval) * time.Second)
	asw.flushDone = make(chan struct{})
	asw.hostStatsMu.Unlock()

	asw.flushWg.Add(1)
	go asw.flusherLoop()

	logger.Info("Stats flusher started with interval %d seconds", flushInterval)
}

func (asw *AsyncStatsWriter) StopFlusher() {
	asw.hostStatsMu.Lock()
	if !asw.flushRunning {
		asw.hostStatsMu.Unlock()
		return
	}
	asw.flushRunning = false
	if asw.flushTicker != nil {
		asw.flushTicker.Stop()
	}
	close(asw.flushDone)
	asw.hostStatsMu.Unlock()

	asw.flushWg.Wait()
	logger.Info("Stats flusher stopped")
}

func (asw *AsyncStatsWriter) flusherLoop() {
	defer asw.flushWg.Done()

	for {
		select {
		case <-asw.flushTicker.C:
			asw.flushToPersistence()
		case <-asw.flushDone:
			asw.flushToPersistence()
			return
		}
	}
}

func (asw *AsyncStatsWriter) flushToPersistence() error {
	if asw.config == nil || !asw.config.Enabled {
		return nil
	}

	asw.hostStatsMu.RLock()
	hostStats := make(map[string]TrafficStats, len(asw.hostStats))
	for host, hs := range asw.hostStats {
		hs.mu.RLock()
		hostStats[host] = TrafficStats{
			RequestCount: atomic.LoadInt64(&hs.Stats.RequestCount),
			TrafficIn:    atomic.LoadInt64(&hs.Stats.TrafficIn),
			TrafficOut:   atomic.LoadInt64(&hs.Stats.TrafficOut),
			TotalLatency: atomic.LoadInt64(&hs.Stats.TotalLatency),
			MinLatency:   atomic.LoadInt64(&hs.Stats.MinLatency),
			MaxLatency:   atomic.LoadInt64(&hs.Stats.MaxLatency),
			ErrorCount:   atomic.LoadInt64(&hs.Stats.ErrorCount),
			LastUpdated:  hs.Stats.LastUpdated,
		}
		hs.mu.RUnlock()
	}
	asw.hostStatsMu.RUnlock()

	record := PersistentStatsRecord{
		Timestamp:  time.Now(),
		HostStats:  hostStats,
		Aggregated: aggregateStats(hostStats),
	}

	dir := filepath.Dir(asw.config.FilePath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		logger.Error("Failed to create stats directory: %v", err)
		return err
	}

	existingRecords, err := asw.loadExistingRecords()
	if err != nil {
		logger.Warn("Failed to load existing stats records: %v", err)
		existingRecords = []PersistentStatsRecord{}
	}

	maxRecords := asw.config.MaxRecords
	if maxRecords <= 0 {
		maxRecords = 1000
	}

	existingRecords = append(existingRecords, record)
	if len(existingRecords) > maxRecords {
		existingRecords = existingRecords[len(existingRecords)-maxRecords:]
	}

	data, err := json.MarshalIndent(existingRecords, "", "  ")
	if err != nil {
		logger.Error("Failed to marshal stats: %v", err)
		return err
	}

	if err := os.WriteFile(asw.config.FilePath, data, 0644); err != nil {
		logger.Error("Failed to write stats file: %v", err)
		return err
	}

	asw.lastFlushTime = time.Now()
	logger.Debug("Stats flushed to %s, pending events: %d",
		asw.config.FilePath, atomic.LoadInt64(&asw.pendingCount))
	return nil
}

func (asw *AsyncStatsWriter) loadExistingRecords() ([]PersistentStatsRecord, error) {
	if asw.config == nil || asw.config.FilePath == "" {
		return []PersistentStatsRecord{}, nil
	}

	data, err := os.ReadFile(asw.config.FilePath)
	if err != nil {
		if os.IsNotExist(err) {
			return []PersistentStatsRecord{}, nil
		}
		return nil, err
	}

	if len(data) == 0 {
		return []PersistentStatsRecord{}, nil
	}

	var records []PersistentStatsRecord
	if err := json.Unmarshal(data, &records); err != nil {
		return nil, err
	}

	return records, nil
}

func (asw *AsyncStatsWriter) LoadFromPersistence() error {
	if asw.config == nil || !asw.config.Enabled {
		return nil
	}

	records, err := asw.loadExistingRecords()
	if err != nil {
		logger.Warn("Failed to load stats from persistence: %v", err)
		return err
	}

	if len(records) == 0 {
		logger.Info("No existing stats found for recovery")
		return nil
	}

	latestRecord := records[len(records)-1]

	asw.hostStatsMu.Lock()
	defer asw.hostStatsMu.Unlock()

	for host, stats := range latestRecord.HostStats {
		hs := &HostStats{
			TargetHost: host,
			Stats:      stats,
			Historical: make([]TrafficStats, 0, 100),
		}
		asw.hostStats[host] = hs
	}

	logger.Info("Loaded stats from persistence: %d hosts, last update: %s",
		len(latestRecord.HostStats), latestRecord.Timestamp.Format(time.RFC3339))
	return nil
}

func (asw *AsyncStatsWriter) QueueEvent(targetHost string, trafficIn, trafficOut, latency int64, hasError bool) bool {
	event := &StatsEvent{
		TargetHost: targetHost,
		TrafficIn:  trafficIn,
		TrafficOut: trafficOut,
		Latency:    latency,
		HasError:   hasError,
		Timestamp:  time.Now(),
	}

	select {
	case asw.eventQueue <- event:
		atomic.AddInt64(&asw.pendingCount, 1)
		return true
	default:
		logger.Warn("Stats event queue full, dropping event for %s", targetHost)
		return false
	}
}

func (asw *AsyncStatsWriter) GetHostStats(targetHost string) *TrafficStats {
	asw.hostStatsMu.RLock()
	hs, exists := asw.hostStats[targetHost]
	asw.hostStatsMu.RUnlock()

	if !exists {
		return nil
	}

	hs.mu.RLock()
	defer hs.mu.RUnlock()

	return &TrafficStats{
		RequestCount: atomic.LoadInt64(&hs.Stats.RequestCount),
		TrafficIn:    atomic.LoadInt64(&hs.Stats.TrafficIn),
		TrafficOut:   atomic.LoadInt64(&hs.Stats.TrafficOut),
		TotalLatency: atomic.LoadInt64(&hs.Stats.TotalLatency),
		MinLatency:   atomic.LoadInt64(&hs.Stats.MinLatency),
		MaxLatency:   atomic.LoadInt64(&hs.Stats.MaxLatency),
		ErrorCount:   atomic.LoadInt64(&hs.Stats.ErrorCount),
		LastUpdated:  hs.Stats.LastUpdated,
	}
}

func (asw *AsyncStatsWriter) GetAllStats() map[string]TrafficStats {
	asw.hostStatsMu.RLock()
	defer asw.hostStatsMu.RUnlock()

	result := make(map[string]TrafficStats, len(asw.hostStats))
	for host, hs := range asw.hostStats {
		hs.mu.RLock()
		result[host] = TrafficStats{
			RequestCount: atomic.LoadInt64(&hs.Stats.RequestCount),
			TrafficIn:    atomic.LoadInt64(&hs.Stats.TrafficIn),
			TrafficOut:   atomic.LoadInt64(&hs.Stats.TrafficOut),
			TotalLatency: atomic.LoadInt64(&hs.Stats.TotalLatency),
			MinLatency:   atomic.LoadInt64(&hs.Stats.MinLatency),
			MaxLatency:   atomic.LoadInt64(&hs.Stats.MaxLatency),
			ErrorCount:   atomic.LoadInt64(&hs.Stats.ErrorCount),
			LastUpdated:  hs.Stats.LastUpdated,
		}
		hs.mu.RUnlock()
	}
	return result
}

func (asw *AsyncStatsWriter) GetPendingCount() int64 {
	return atomic.LoadInt64(&asw.pendingCount)
}

func (asw *AsyncStatsWriter) GetLastFlushTime() time.Time {
	return asw.lastFlushTime
}

func aggregateStats(hostStats map[string]TrafficStats) TrafficStats {
	var total TrafficStats
	var minLatency, maxLatency int64
	first := true

	for _, hs := range hostStats {
		total.RequestCount += hs.RequestCount
		total.TrafficIn += hs.TrafficIn
		total.TrafficOut += hs.TrafficOut
		total.TotalLatency += hs.TotalLatency
		total.ErrorCount += hs.ErrorCount

		if first {
			if hs.MinLatency > 0 {
				minLatency = hs.MinLatency
			}
			maxLatency = hs.MaxLatency
			first = false
		} else {
			if hs.MinLatency > 0 && (minLatency == 0 || hs.MinLatency < minLatency) {
				minLatency = hs.MinLatency
			}
			if hs.MaxLatency > maxLatency {
				maxLatency = hs.MaxLatency
			}
		}

		if hs.LastUpdated.After(total.LastUpdated) {
			total.LastUpdated = hs.LastUpdated
		}
	}

	total.MinLatency = minLatency
	total.MaxLatency = maxLatency
	return total
}

func NewStatsManager() *StatsManager {
	return &StatsManager{
		hostStats:      make(map[string]*HostStats),
		persistConfig:  &StatsPersistenceConfig{Enabled: false},
	}
}

func InitStatsManager() {
	once.Do(func() {
		instance = NewStatsManager()
	})
}

func InitStatsManagerWithPersistence(cfg *StatsPersistenceConfig) {
	once.Do(func() {
		instance = NewStatsManager()
		instance.persistConfig = cfg
		if cfg != nil && cfg.Enabled {
			instance.asyncWriter = NewAsyncStatsWriter(cfg)
			instance.asyncWriter.LoadFromPersistence()
			instance.asyncWriter.StartWorker()
			instance.asyncWriter.StartFlusher()
		}
	})
}

func GetStatsManager() *StatsManager {
	if instance == nil {
		InitStatsManager()
	}
	return instance
}

func (sm *StatsManager) getOrCreateHostStats(targetHost string) *HostStats {
	sm.mu.RLock()
	hs, exists := sm.hostStats[targetHost]
	sm.mu.RUnlock()

	if exists {
		return hs
	}

	sm.mu.Lock()
	defer sm.mu.Unlock()

	if hs, exists := sm.hostStats[targetHost]; exists {
		return hs
	}

	hs = &HostStats{
		TargetHost: targetHost,
		Historical: make([]TrafficStats, 0, 100),
	}
	sm.hostStats[targetHost] = hs
	return hs
}

func (sm *StatsManager) RecordRequest(targetHost string, trafficIn, trafficOut, latency int64, hasError bool) {
	if sm.asyncWriter != nil {
		sm.asyncWriter.QueueEvent(targetHost, trafficIn, trafficOut, latency, hasError)
		return
	}

	hs := sm.getOrCreateHostStats(targetHost)

	hs.mu.Lock()
	defer hs.mu.Unlock()

	atomic.AddInt64(&hs.Stats.RequestCount, 1)
	atomic.AddInt64(&hs.Stats.TrafficIn, trafficIn)
	atomic.AddInt64(&hs.Stats.TrafficOut, trafficOut)
	atomic.AddInt64(&hs.Stats.TotalLatency, latency)

	currentMin := atomic.LoadInt64(&hs.Stats.MinLatency)
	if currentMin == 0 || latency < currentMin {
		atomic.StoreInt64(&hs.Stats.MinLatency, latency)
	}

	currentMax := atomic.LoadInt64(&hs.Stats.MaxLatency)
	if latency > currentMax {
		atomic.StoreInt64(&hs.Stats.MaxLatency, latency)
	}

	if hasError {
		atomic.AddInt64(&hs.Stats.ErrorCount, 1)
	}

	hs.Stats.LastUpdated = time.Now()
}

func (sm *StatsManager) GetHostStats(targetHost string) *TrafficStats {
	if sm.asyncWriter != nil {
		return sm.asyncWriter.GetHostStats(targetHost)
	}

	sm.mu.RLock()
	hs, exists := sm.hostStats[targetHost]
	sm.mu.RUnlock()

	if !exists {
		return nil
	}

	hs.mu.RLock()
	defer hs.mu.RUnlock()

	stats := TrafficStats{
		RequestCount: atomic.LoadInt64(&hs.Stats.RequestCount),
		TrafficIn:    atomic.LoadInt64(&hs.Stats.TrafficIn),
		TrafficOut:   atomic.LoadInt64(&hs.Stats.TrafficOut),
		TotalLatency: atomic.LoadInt64(&hs.Stats.TotalLatency),
		MinLatency:   atomic.LoadInt64(&hs.Stats.MinLatency),
		MaxLatency:   atomic.LoadInt64(&hs.Stats.MaxLatency),
		ErrorCount:   atomic.LoadInt64(&hs.Stats.ErrorCount),
		LastUpdated:  hs.Stats.LastUpdated,
	}
	return &stats
}

func (sm *StatsManager) GetAllStats() map[string]TrafficStats {
	if sm.asyncWriter != nil {
		return sm.asyncWriter.GetAllStats()
	}

	sm.mu.RLock()
	defer sm.mu.RUnlock()

	result := make(map[string]TrafficStats, len(sm.hostStats))
	for host, hs := range sm.hostStats {
		hs.mu.RLock()
		result[host] = TrafficStats{
			RequestCount: atomic.LoadInt64(&hs.Stats.RequestCount),
			TrafficIn:    atomic.LoadInt64(&hs.Stats.TrafficIn),
			TrafficOut:   atomic.LoadInt64(&hs.Stats.TrafficOut),
			TotalLatency: atomic.LoadInt64(&hs.Stats.TotalLatency),
			MinLatency:   atomic.LoadInt64(&hs.Stats.MinLatency),
			MaxLatency:   atomic.LoadInt64(&hs.Stats.MaxLatency),
			ErrorCount:   atomic.LoadInt64(&hs.Stats.ErrorCount),
			LastUpdated:  hs.Stats.LastUpdated,
		}
		hs.mu.RUnlock()
	}
	return result
}

func (sm *StatsManager) GetAggregatedStats() TrafficStats {
	allStats := sm.GetAllStats()
	return aggregateStats(allStats)
}

func (sm *StatsManager) ResetHostStats(targetHost string) {
	sm.mu.RLock()
	hs, exists := sm.hostStats[targetHost]
	sm.mu.RUnlock()

	if !exists {
		return
	}

	hs.mu.Lock()
	defer hs.mu.Unlock()

	hs.Historical = append(hs.Historical, hs.Stats)
	if len(hs.Historical) > 100 {
		hs.Historical = hs.Historical[len(hs.Historical)-100:]
	}

	atomic.StoreInt64(&hs.Stats.RequestCount, 0)
	atomic.StoreInt64(&hs.Stats.TrafficIn, 0)
	atomic.StoreInt64(&hs.Stats.TrafficOut, 0)
	atomic.StoreInt64(&hs.Stats.TotalLatency, 0)
	atomic.StoreInt64(&hs.Stats.MinLatency, 0)
	atomic.StoreInt64(&hs.Stats.MaxLatency, 0)
	atomic.StoreInt64(&hs.Stats.ErrorCount, 0)
	hs.Stats.LastUpdated = time.Time{}
}

func (sm *StatsManager) ResetAllStats() {
	sm.mu.Lock()
	defer sm.mu.Unlock()

	for _, hs := range sm.hostStats {
		hs.mu.Lock()
		hs.Historical = append(hs.Historical, hs.Stats)
		if len(hs.Historical) > 100 {
			hs.Historical = hs.Historical[len(hs.Historical)-100:]
		}

		atomic.StoreInt64(&hs.Stats.RequestCount, 0)
		atomic.StoreInt64(&hs.Stats.TrafficIn, 0)
		atomic.StoreInt64(&hs.Stats.TrafficOut, 0)
		atomic.StoreInt64(&hs.Stats.TotalLatency, 0)
		atomic.StoreInt64(&hs.Stats.MinLatency, 0)
		atomic.StoreInt64(&hs.Stats.MaxLatency, 0)
		atomic.StoreInt64(&hs.Stats.ErrorCount, 0)
		hs.Stats.LastUpdated = time.Time{}
		hs.mu.Unlock()
	}
}

func (sm *StatsManager) StartPersistence() {
	if sm.asyncWriter == nil {
		return
	}
	sm.asyncWriter.StartWorker()
	sm.asyncWriter.StartFlusher()
}

func (sm *StatsManager) StopPersistence() {
	if sm.asyncWriter == nil {
		return
	}
	sm.asyncWriter.StopFlusher()
	sm.asyncWriter.StopWorker()
}

func (sm *StatsManager) SaveToPersistence() error {
	if sm.asyncWriter == nil {
		return nil
	}
	return sm.asyncWriter.flushToPersistence()
}

func (sm *StatsManager) LoadFromPersistence() error {
	if sm.asyncWriter == nil {
		return nil
	}
	return sm.asyncWriter.LoadFromPersistence()
}

func (sm *StatsManager) GetHistoricalRecords(startTime, endTime time.Time) []PersistentStatsRecord {
	if sm.asyncWriter == nil || sm.asyncWriter.config == nil {
		return []PersistentStatsRecord{}
	}

	records, err := sm.asyncWriter.loadExistingRecords()
	if err != nil {
		return []PersistentStatsRecord{}
	}

	filtered := make([]PersistentStatsRecord, 0, len(records))
	for _, record := range records {
		if (startTime.IsZero() || record.Timestamp.After(startTime)) &&
			(endTime.IsZero() || record.Timestamp.Before(endTime)) {
			filtered = append(filtered, record)
		}
	}

	return filtered
}

func (sm *StatsManager) GetLastPersistTime() time.Time {
	if sm.asyncWriter != nil {
		return sm.asyncWriter.GetLastFlushTime()
	}
	return sm.lastPersistTime
}

func (sm *StatsManager) IsPersistenceEnabled() bool {
	return sm.asyncWriter != nil && sm.asyncWriter.config != nil && sm.asyncWriter.config.Enabled
}

func (sm *StatsManager) GetPendingCount() int64 {
	if sm.asyncWriter != nil {
		return sm.asyncWriter.GetPendingCount()
	}
	return 0
}

func (ts *TrafficStats) GetAverageLatency() int64 {
	if ts.RequestCount == 0 {
		return 0
	}
	return ts.TotalLatency / ts.RequestCount
}

func RecordRequest(targetHost string, trafficIn, trafficOut, latency int64, hasError bool) {
	GetStatsManager().RecordRequest(targetHost, trafficIn, trafficOut, latency, hasError)
}

func GetHostStats(targetHost string) *TrafficStats {
	return GetStatsManager().GetHostStats(targetHost)
}

func GetAllStats() map[string]TrafficStats {
	return GetStatsManager().GetAllStats()
}

func GetAggregatedStats() TrafficStats {
	return GetStatsManager().GetAggregatedStats()
}

func ResetHostStats(targetHost string) {
	GetStatsManager().ResetHostStats(targetHost)
}

func ResetAllStats() {
	GetStatsManager().ResetAllStats()
}

func StartPersistence() {
	GetStatsManager().StartPersistence()
}

func StopPersistence() {
	GetStatsManager().StopPersistence()
}

func SaveToPersistence() error {
	return GetStatsManager().SaveToPersistence()
}

func LoadFromPersistence() error {
	return GetStatsManager().LoadFromPersistence()
}

func GetHistoricalRecords(startTime, endTime time.Time) []PersistentStatsRecord {
	return GetStatsManager().GetHistoricalRecords(startTime, endTime)
}

func GetLastPersistTime() time.Time {
	return GetStatsManager().GetLastPersistTime()
}

func IsPersistenceEnabled() bool {
	return GetStatsManager().IsPersistenceEnabled()
}

func GetPendingCount() int64 {
	return GetStatsManager().GetPendingCount()
}

func InitWithPersistence(cfg *StatsPersistenceConfig) {
	InitStatsManagerWithPersistence(cfg)
}
