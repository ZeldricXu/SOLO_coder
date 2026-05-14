package logger

import (
	"apigateway/models"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"sync"
	"time"

	"github.com/google/uuid"
)

type LogLevel string

const (
	LevelDebug LogLevel = "DEBUG"
	LevelInfo  LogLevel = "INFO"
	LevelWarn  LogLevel = "WARN"
	LevelError LogLevel = "ERROR"
)

type LogEntry struct {
	Level      LogLevel              `json:"level"`
	Timestamp  time.Time             `json:"timestamp"`
	Message    string                `json:"message"`
	RequestLog *models.RequestLog    `json:"request_log,omitempty"`
	Data       map[string]interface{} `json:"data,omitempty"`
}

type RequestLogger struct {
	logs       map[string]*models.RequestLog
	logsByTime []string
	maxLogs    int
	mu         sync.RWMutex
	logger     *log.Logger
	level      LogLevel
}

func NewRequestLogger() *RequestLogger {
	return &RequestLogger{
		logs:       make(map[string]*models.RequestLog),
		logsByTime: make([]string, 0),
		maxLogs:    10000,
		logger:     log.New(os.Stdout, "[APIGateway] ", log.LstdFlags),
		level:      LevelInfo,
	}
}

func (rl *RequestLogger) SetLevel(level LogLevel) {
	rl.level = level
}

func (rl *RequestLogger) shouldLog(level LogLevel) bool {
	levels := map[LogLevel]int{
		LevelDebug: 0,
		LevelInfo:  1,
		LevelWarn:  2,
		LevelError: 3,
	}
	return levels[level] >= levels[rl.level]
}

func (rl *RequestLogger) LogRequest(requestLog *models.RequestLog) {
	if requestLog == nil {
		return
	}

	if requestLog.LogID == "" {
		requestLog.LogID = uuid.New().String()
	}
	if requestLog.RequestTime.IsZero() {
		requestLog.RequestTime = time.Now()
	}

	rl.mu.Lock()
	rl.logs[requestLog.LogID] = requestLog
	rl.logsByTime = append(rl.logsByTime, requestLog.LogID)

	if len(rl.logsByTime) > rl.maxLogs {
		oldestID := rl.logsByTime[0]
		delete(rl.logs, oldestID)
		rl.logsByTime = rl.logsByTime[1:]
	}
	rl.mu.Unlock()

	entry := &LogEntry{
		Level:      LevelInfo,
		Timestamp:  time.Now(),
		Message:    fmt.Sprintf("Request: %s %s -> %d (%dms)", 
			requestLog.RequestMethod, 
			requestLog.RequestPath, 
			requestLog.ResponseStatus, 
			requestLog.Latency),
		RequestLog: requestLog,
	}

	rl.writeLog(entry)
}

func (rl *RequestLogger) writeLog(entry *LogEntry) {
	if !rl.shouldLog(entry.Level) {
		return
	}

	data, err := json.Marshal(entry)
	if err != nil {
		rl.logger.Printf("%s: %s", entry.Level, entry.Message)
		return
	}

	rl.logger.Println(string(data))
}

func (rl *RequestLogger) GetLog(logID string) (*models.RequestLog, bool) {
	rl.mu.RLock()
	defer rl.mu.RUnlock()

	logEntry, exists := rl.logs[logID]
	return logEntry, exists
}

func (rl *RequestLogger) QueryLogs(routeID string, startTime, endTime time.Time, limit int) []*models.RequestLog {
	rl.mu.RLock()
	defer rl.mu.RUnlock()

	results := make([]*models.RequestLog, 0)

	for i := len(rl.logsByTime) - 1; i >= 0 && (limit <= 0 || len(results) < limit); i-- {
		logID := rl.logsByTime[i]
		logEntry, exists := rl.logs[logID]
		if !exists {
			continue
		}

		if routeID != "" && logEntry.RouteID != routeID {
			continue
		}

		if !startTime.IsZero() && logEntry.RequestTime.Before(startTime) {
			continue
		}
		if !endTime.IsZero() && logEntry.RequestTime.After(endTime) {
			continue
		}

		results = append(results, logEntry)
	}

	return results
}

func (rl *RequestLogger) GetLogsByRequestID(requestID string) []*models.RequestLog {
	rl.mu.RLock()
	defer rl.mu.RUnlock()

	results := make([]*models.RequestLog, 0)
	for _, logEntry := range rl.logs {
		if logEntry.RequestID == requestID {
			results = append(results, logEntry)
		}
	}
	return results
}

func (rl *RequestLogger) GetLogsByRoute(routeID string, limit int) []*models.RequestLog {
	return rl.QueryLogs(routeID, time.Time{}, time.Time{}, limit)
}

func (rl *RequestLogger) GetRecentLogs(limit int) []*models.RequestLog {
	return rl.QueryLogs("", time.Time{}, time.Time{}, limit)
}

func (rl *RequestLogger) ClearLogs() {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	rl.logs = make(map[string]*models.RequestLog)
	rl.logsByTime = make([]string, 0)
}

func (rl *RequestLogger) Debug(message string, data ...interface{}) {
	if !rl.shouldLog(LevelDebug) {
		return
	}
	entry := &LogEntry{
		Level:     LevelDebug,
		Timestamp: time.Now(),
		Message:   message,
	}
	if len(data) > 0 {
		entry.Data = make(map[string]interface{})
		for i := 0; i < len(data); i += 2 {
			if i+1 < len(data) {
				key, ok := data[i].(string)
				if ok {
					entry.Data[key] = data[i+1]
				}
			}
		}
	}
	rl.writeLog(entry)
}

func (rl *RequestLogger) Info(message string, data ...interface{}) {
	if !rl.shouldLog(LevelInfo) {
		return
	}
	entry := &LogEntry{
		Level:     LevelInfo,
		Timestamp: time.Now(),
		Message:   message,
	}
	if len(data) > 0 {
		entry.Data = make(map[string]interface{})
		for i := 0; i < len(data); i += 2 {
			if i+1 < len(data) {
				key, ok := data[i].(string)
				if ok {
					entry.Data[key] = data[i+1]
				}
			}
		}
	}
	rl.writeLog(entry)
}

func (rl *RequestLogger) Warn(message string, data ...interface{}) {
	if !rl.shouldLog(LevelWarn) {
		return
	}
	entry := &LogEntry{
		Level:     LevelWarn,
		Timestamp: time.Now(),
		Message:   message,
	}
	if len(data) > 0 {
		entry.Data = make(map[string]interface{})
		for i := 0; i < len(data); i += 2 {
			if i+1 < len(data) {
				key, ok := data[i].(string)
				if ok {
					entry.Data[key] = data[i+1]
				}
			}
		}
	}
	rl.writeLog(entry)
}

func (rl *RequestLogger) Error(message string, data ...interface{}) {
	if !rl.shouldLog(LevelError) {
		return
	}
	entry := &LogEntry{
		Level:     LevelError,
		Timestamp: time.Now(),
		Message:   message,
	}
	if len(data) > 0 {
		entry.Data = make(map[string]interface{})
		for i := 0; i < len(data); i += 2 {
			if i+1 < len(data) {
				key, ok := data[i].(string)
				if ok {
					entry.Data[key] = data[i+1]
				}
			}
		}
	}
	rl.writeLog(entry)
}

func (rl *RequestLogger) GetStats() map[string]interface{} {
	rl.mu.RLock()
	defer rl.mu.RUnlock()

	successCount := 0
	errorCount := 0
	totalLatency := int64(0)

	for _, logEntry := range rl.logs {
		if logEntry.ResponseStatus >= 200 && logEntry.ResponseStatus < 400 {
			successCount++
		} else {
			errorCount++
		}
		totalLatency += logEntry.Latency
	}

	avgLatency := int64(0)
	if len(rl.logs) > 0 {
		avgLatency = totalLatency / int64(len(rl.logs))
	}

	return map[string]interface{}{
		"total_logs":   len(rl.logs),
		"success_count": successCount,
		"error_count":   errorCount,
		"avg_latency":   avgLatency,
		"max_capacity":  rl.maxLogs,
	}
}
