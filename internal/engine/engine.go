package engine

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/datatrace/datatrace/internal/cache"
	"github.com/datatrace/datatrace/internal/cdc"
	"github.com/datatrace/datatrace/internal/gateway"
	"github.com/datatrace/datatrace/internal/lifecycle"
	"github.com/datatrace/datatrace/internal/lineage"
	"github.com/datatrace/datatrace/internal/logger"
	"github.com/datatrace/datatrace/internal/models"
	"github.com/datatrace/datatrace/internal/notification"
	"github.com/datatrace/datatrace/internal/scheduler"
	"github.com/datatrace/datatrace/internal/storage"
	"github.com/datatrace/datatrace/internal/tsdb"
	"github.com/google/uuid"
)

type EngineConfig struct {
	MaxWorkers      int
	MaxQueueSize    int
	RequestTimeout  time.Duration
	ShutdownTimeout time.Duration
}

type HandlerFunc func(ctx context.Context, request *Request) (*Response, error)

type Request struct {
	RequestID string                 `json:"request_id"`
	TraceID   string                 `json:"trace_id"`
	Namespace string                 `json:"namespace"`
	Action    string                 `json:"action"`
	Payload   map[string]interface{} `json:"payload"`
	Params    map[string]string      `json:"params"`
	CreatedAt time.Time              `json:"created_at"`
}

type Response struct {
	RequestID string                 `json:"request_id"`
	TraceID   string                 `json:"trace_id"`
	Status    string                 `json:"status"`
	Data      interface{}            `json:"data,omitempty"`
	Error     string                 `json:"error,omitempty"`
	ExecTime  time.Duration          `json:"exec_time"`
}

type CoreEngine struct {
	config       EngineConfig
	lineage      *lineage.LineageParser
	notification *notification.NotificationService
	cdc          *cdc.CDCService
	storage      *storage.StorageManager
	scheduler    *scheduler.Scheduler
	gateway      *gateway.APIGateway
	lifecycle    *lifecycle.LifecycleManager
	tsdb         *tsdb.TSDB
	cache        *cache.Cache
	handlers     map[string]HandlerFunc
	mu           sync.RWMutex
	metrics      *EngineMetrics
}

type EngineMetrics struct {
	RequestsTotal   int64
	RequestsSuccess int64
	RequestsFailed  int64
	LastRequestTime time.Time
	TotalExecTime   time.Duration
}

func NewCoreEngine(config EngineConfig) *CoreEngine {
	e := &CoreEngine{
		config:       config,
		lineage:      lineage.NewLineageParser(),
		notification: notification.NewNotificationService(config.MaxQueueSize),
		cdc:          cdc.NewCDCService(config.MaxQueueSize),
		storage:      storage.NewStorageManager(),
		scheduler:    scheduler.NewScheduler(config.MaxWorkers),
		gateway:      gateway.NewAPIGateway("datatrace", 10000),
		tsdb:         tsdb.NewTSDB(),
		cache:        cache.NewCache(1024*1024*100, 1*time.Hour, cache.EvictionLRU),
		handlers:     make(map[string]HandlerFunc),
		metrics:      &EngineMetrics{},
	}

	e.lifecycle = lifecycle.NewLifecycleManager(e.storage)
	e.registerDefaultHandlers()

	return e
}

func (e *CoreEngine) registerDefaultHandlers() {
	e.handlers["parse_sql"] = e.handleParseSQL
	e.handlers["send_notification"] = e.handleSendNotification
	e.handlers["ingest_cdc"] = e.handleIngestCDC
	e.handlers["store_data"] = e.handleStoreData
	e.handlers["retrieve_data"] = e.handleRetrieveData
	e.handlers["create_task"] = e.handleCreateTask
	e.handlers["execute_task"] = e.handleExecuteTask
	e.handlers["get_task_status"] = e.handleGetTaskStatus
	e.handlers["add_metric"] = e.handleAddMetric
	e.handlers["query_metrics"] = e.handleQueryMetrics
	e.handlers["get_lineage"] = e.handleGetLineage
	e.handlers["get_stats"] = e.handleGetStats
}

func (e *CoreEngine) Start() {
	e.notification.RegisterSender("default", notification.NewConsoleSender())
	e.notification.Start()

	e.cdc.RegisterParser("mysql_binlog", cdc.NewMySQLBinlogParser())
	e.cdc.RegisterParser("postgres_wal", cdc.NewPostgresWALParser())
	e.cdc.SetSerializer(cdc.NewJSONSerializer())
	e.cdc.SetOutputAdapter(cdc.NewMemoryAdapter())
	e.cdc.Start()

	e.scheduler.Start()
	e.lifecycle.Start()

	if l := logger.GetLogger(); l != nil {
		l.Info("Core engine started", map[string]interface{}{
			"max_workers":   e.config.MaxWorkers,
			"max_queue_size": e.config.MaxQueueSize,
		})
	}
}

func (e *CoreEngine) Stop() {
	e.notification.Stop()
	e.cdc.Stop()
	e.scheduler.Stop()
	e.lifecycle.Stop()
	e.cache.Close()

	if l := logger.GetLogger(); l != nil {
		l.Info("Core engine stopped", nil)
	}
}

func (e *CoreEngine) Execute(ctx context.Context, request *Request) (*Response, error) {
	startTime := time.Now()
	traceID := request.TraceID
	if traceID == "" {
		traceID = uuid.New().String()
	}
	request.TraceID = traceID
	request.RequestID = uuid.New().String()
	request.CreatedAt = time.Now()

	span := e.gateway.StartSpan(ctx, "engine.execute", "")
	defer e.gateway.EndSpan(span, "completed")

	e.mu.Lock()
	e.metrics.RequestsTotal++
	e.metrics.LastRequestTime = time.Now()
	e.mu.Unlock()

	defer func() {
		if r := recover(); r != nil {
			e.mu.Lock()
			e.metrics.RequestsFailed++
			e.mu.Unlock()

			if l := logger.GetLogger(); l != nil {
				l.Error("Panic in request handler", map[string]interface{}{
					"trace_id": traceID,
					"action":   request.Action,
					"panic":    fmt.Sprintf("%v", r),
				})
			}
		}
	}()

	handler, ok := e.handlers[request.Action]
	if !ok {
		e.mu.Lock()
		e.metrics.RequestsFailed++
		e.mu.Unlock()

		return &Response{
			RequestID: request.RequestID,
			TraceID:   traceID,
			Status:    "error",
			Error:     fmt.Sprintf("unknown action: %s", request.Action),
			ExecTime:  time.Since(startTime),
		}, fmt.Errorf("unknown action: %s", request.Action)
	}

	ctx, cancel := context.WithTimeout(ctx, e.config.RequestTimeout)
	defer cancel()

	result, err := handler(ctx, request)

	execTime := time.Since(startTime)
	e.mu.Lock()
	e.metrics.TotalExecTime += execTime
	e.mu.Unlock()

	if err != nil {
		e.mu.Lock()
		e.metrics.RequestsFailed++
		e.mu.Unlock()

		if l := logger.GetLogger(); l != nil {
			l.Error("Request handler failed", map[string]interface{}{
				"trace_id": traceID,
				"action":   request.Action,
				"error":    err.Error(),
			})
		}

		return &Response{
			RequestID: request.RequestID,
			TraceID:   traceID,
			Status:    "error",
			Error:     err.Error(),
			ExecTime:  execTime,
		}, err
	}

	e.mu.Lock()
	e.metrics.RequestsSuccess++
	e.mu.Unlock()

	result.RequestID = request.RequestID
	result.TraceID = traceID
	result.ExecTime = execTime

	if l := logger.GetLogger(); l != nil {
		l.Info("Request completed", map[string]interface{}{
			"trace_id": traceID,
			"action":   request.Action,
			"exec_ms":  execTime.Milliseconds(),
		})
	}

	return result, nil
}

func (e *CoreEngine) handleParseSQL(ctx context.Context, request *Request) (*Response, error) {
	sql, ok := request.Payload["sql"].(string)
	if !ok {
		return nil, errors.New("sql parameter is required")
	}

	tables, lineages, err := e.lineage.ParseSQL(sql)
	if err != nil {
		return nil, fmt.Errorf("failed to parse SQL: %w", err)
	}

	graph, err := e.lineage.BuildDAG(tables, lineages)
	if err != nil {
		return nil, fmt.Errorf("failed to build DAG: %w", err)
	}

	nodes := graph.GetNodes()

	return &Response{
		Status: "success",
		Data: map[string]interface{}{
			"tables":   tables,
			"lineages": lineages,
			"nodes":    nodes,
		},
	}, nil
}

func (e *CoreEngine) handleSendNotification(ctx context.Context, request *Request) (*Response, error) {
	notifType, _ := request.Payload["type"].(string)
	recipient, _ := request.Payload["recipient"].(string)
	payload, _ := request.Payload["payload"].(map[string]interface{})

	if notifType == "" {
		notifType = "default"
	}

	notif, err := e.notification.Send(ctx, notifType, recipient, payload)
	if err != nil {
		return nil, fmt.Errorf("failed to send notification: %w", err)
	}

	return &Response{
		Status: "success",
		Data:   notif,
	}, nil
}

func (e *CoreEngine) handleIngestCDC(ctx context.Context, request *Request) (*Response, error) {
	sourceType, _ := request.Payload["source_type"].(string)
	data, _ := request.Payload["data"].(string)

	if sourceType == "" {
		return nil, errors.New("source_type is required")
	}

	err := e.cdc.Ingest(ctx, sourceType, []byte(data))
	if err != nil {
		return nil, fmt.Errorf("failed to ingest CDC data: %w", err)
	}

	return &Response{
		Status: "success",
		Data: map[string]interface{}{
			"ingested": true,
		},
	}, nil
}

func (e *CoreEngine) handleStoreData(ctx context.Context, request *Request) (*Response, error) {
	key, _ := request.Payload["key"].(string)
	data, _ := request.Payload["data"].(string)
	tags, _ := request.Payload["tags"].(map[string]string)
	attributes, _ := request.Payload["attributes"].(map[string]interface{})

	if key == "" {
		return nil, errors.New("key is required")
	}

	meta, err := e.storage.Store(ctx, key, []byte(data), tags, attributes)
	if err != nil {
		return nil, fmt.Errorf("failed to store data: %w", err)
	}

	e.lifecycle.RegisterRecord(key, int64(len(data)), tags, attributes)

	return &Response{
		Status: "success",
		Data:   meta,
	}, nil
}

func (e *CoreEngine) handleRetrieveData(ctx context.Context, request *Request) (*Response, error) {
	key, _ := request.Payload["key"].(string)

	if key == "" {
		return nil, errors.New("key is required")
	}

	data, meta, err := e.storage.Retrieve(ctx, key)
	if err != nil {
		return nil, fmt.Errorf("failed to retrieve data: %w", err)
	}

	return &Response{
		Status: "success",
		Data: map[string]interface{}{
			"data": string(data),
			"meta": meta,
		},
	}, nil
}

func (e *CoreEngine) handleCreateTask(ctx context.Context, request *Request) (*Response, error) {
	name, _ := request.Payload["name"].(string)
	taskType, _ := request.Payload["type"].(string)
	cronExpr, _ := request.Payload["cron"].(string)
	intervalMs, _ := request.Payload["interval_ms"].(float64)
	payload, _ := request.Payload["payload"].(map[string]interface{})

	if name == "" {
		return nil, errors.New("task name is required")
	}

	var tType scheduler.TaskType
	switch taskType {
	case "cron":
		tType = scheduler.TaskTypeCron
	case "recurring":
		tType = scheduler.TaskTypeRecurring
	default:
		tType = scheduler.TaskTypeOneShot
	}

	handler := func(ctx context.Context, task *scheduler.Task) error {
		return nil
	}

	opts := make([]scheduler.TaskOption, 0)
	if cronExpr != "" {
		opts = append(opts, scheduler.WithCronExpression(cronExpr))
	}
	if intervalMs > 0 {
		opts = append(opts, scheduler.WithInterval(time.Duration(intervalMs)*time.Millisecond))
	}

	task, err := e.scheduler.CreateTask(name, tType, handler, payload, opts...)
	if err != nil {
		return nil, fmt.Errorf("failed to create task: %w", err)
	}

	return &Response{
		Status: "success",
		Data:   task,
	}, nil
}

func (e *CoreEngine) handleExecuteTask(ctx context.Context, request *Request) (*Response, error) {
	taskID, _ := request.Payload["task_id"].(string)

	if taskID == "" {
		return nil, errors.New("task_id is required")
	}

	err := e.scheduler.ExecuteTask(taskID)
	if err != nil {
		return nil, fmt.Errorf("failed to execute task: %w", err)
	}

	return &Response{
		Status: "success",
		Data: map[string]interface{}{
			"task_id": taskID,
			"started": true,
		},
	}, nil
}

func (e *CoreEngine) handleGetTaskStatus(ctx context.Context, request *Request) (*Response, error) {
	taskID, _ := request.Payload["task_id"].(string)

	if taskID == "" {
		return nil, errors.New("task_id is required")
	}

	status, progress, err := e.scheduler.GetTaskStatus(taskID)
	if err != nil {
		return nil, fmt.Errorf("failed to get task status: %w", err)
	}

	return &Response{
		Status: "success",
		Data: map[string]interface{}{
			"task_id":  taskID,
			"status":   status,
			"progress": progress,
		},
	}, nil
}

func (e *CoreEngine) handleAddMetric(ctx context.Context, request *Request) (*Response, error) {
	seriesName, _ := request.Payload["series"].(string)
	value, _ := request.Payload["value"].(float64)

	if seriesName == "" {
		return nil, errors.New("series name is required")
	}

	series, _ := e.tsdb.GetSeries(seriesName)
	if series == nil {
		series = e.tsdb.CreateSeries(seriesName, nil, 30*24*time.Hour)
	}

	err := e.tsdb.AddPoint(series.ID, time.Now(), value)
	if err != nil {
		return nil, fmt.Errorf("failed to add metric: %w", err)
	}

	return &Response{
		Status: "success",
		Data: map[string]interface{}{
			"series_id": series.ID,
			"added":     true,
		},
	}, nil
}

func (e *CoreEngine) handleQueryMetrics(ctx context.Context, request *Request) (*Response, error) {
	seriesID, _ := request.Payload["series_id"].(string)
	startStr, _ := request.Payload["start"].(string)
	endStr, _ := request.Payload["end"].(string)
	resolution, _ := request.Payload["resolution"].(string)

	if seriesID == "" {
		return nil, errors.New("series_id is required")
	}

	start, _ := time.Parse(time.RFC3339, startStr)
	end, _ := time.Parse(time.RFC3339, endStr)

	if start.IsZero() {
		start = time.Now().Add(-1 * time.Hour)
	}
	if end.IsZero() {
		end = time.Now()
	}

	points, aggregated, err := e.tsdb.Query(seriesID, start, end, tsdb.Resolution(resolution))
	if err != nil {
		return nil, fmt.Errorf("failed to query metrics: %w", err)
	}

	return &Response{
		Status: "success",
		Data: map[string]interface{}{
			"points":     points,
			"aggregated": aggregated,
		},
	}, nil
}

func (e *CoreEngine) handleGetLineage(ctx context.Context, request *Request) (*Response, error) {
	table, _ := request.Payload["table"].(string)

	if table == "" {
		return nil, errors.New("table name is required")
	}

	node, upstream, downstream := e.lineage.GetLineage(table)

	return &Response{
		Status: "success",
		Data: map[string]interface{}{
			"node":       node,
			"upstream":   upstream,
			"downstream": downstream,
		},
	}, nil
}

func (e *CoreEngine) handleGetStats(ctx context.Context, request *Request) (*Response, error) {
	e.mu.RLock()
	defer e.mu.RUnlock()

	cdcMetrics := e.cdc.GetMetrics()
	cacheStats := e.cache.GetStats()
	tierStats := e.lifecycle.GetTierStats()

	return &Response{
		Status: "success",
		Data: map[string]interface{}{
			"requests_total":   e.metrics.RequestsTotal,
			"requests_success": e.metrics.RequestsSuccess,
			"requests_failed":  e.metrics.RequestsFailed,
			"avg_exec_ms":      float64(e.metrics.TotalExecTime.Milliseconds()) / float64(e.metrics.RequestsSuccess+1),
			"cdc_events":       cdcMetrics.EventsProcessed,
			"cache_hit_rate":   cacheStats.HitRate,
			"cache_entries":    cacheStats.EntryCount,
			"tier_stats":       tierStats,
			"tasks_count":      len(e.scheduler.ListTasks()),
		},
	}, nil
}

func (e *CoreEngine) GetLineageParser() *lineage.LineageParser {
	return e.lineage
}

func (e *CoreEngine) GetNotificationService() *notification.NotificationService {
	return e.notification
}

func (e *CoreEngine) GetCDCService() *cdc.CDCService {
	return e.cdc
}

func (e *CoreEngine) GetStorageManager() *storage.StorageManager {
	return e.storage
}

func (e *CoreEngine) GetScheduler() *scheduler.Scheduler {
	return e.scheduler
}

func (e *CoreEngine) GetAPIGateway() *gateway.APIGateway {
	return e.gateway
}

func (e *CoreEngine) GetLifecycleManager() *lifecycle.LifecycleManager {
	return e.lifecycle
}

func (e *CoreEngine) GetTSDB() *tsdb.TSDB {
	return e.tsdb
}

func (e *CoreEngine) GetCache() *cache.Cache {
	return e.cache
}

func (e *CoreEngine) RegisterHandler(action string, handler HandlerFunc) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.handlers[action] = handler
}

func (e *CoreEngine) GetHandlers() []string {
	e.mu.RLock()
	defer e.mu.RUnlock()

	handlers := make([]string, 0, len(e.handlers))
	for h := range e.handlers {
		handlers = append(handlers, h)
	}
	return handlers
}

func (e *CoreEngine) ToEntity() *models.Entity {
	return &models.Entity{
		ID:        uuid.New().String(),
		Type:      "core_engine",
		Status:    "active",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}
}

func (r *Request) ToJSON() string {
	b, _ := json.Marshal(r)
	return string(b)
}

func (r *Response) ToJSON() string {
	b, _ := json.Marshal(r)
	return string(b)
}
