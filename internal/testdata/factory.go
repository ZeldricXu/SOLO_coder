package testdata

import (
	"encoding/json"
	"fmt"
	"math/rand"
	"strings"
	"time"

	"github.com/datateam/loganalyzer/internal/models"
)

func NewNginxAccessLog(ts time.Time, statusCode int, responseTime int64, clientIP string) string {
	methods := []string{"GET", "POST", "PUT", "DELETE"}
	paths := []string{"/api/users", "/api/orders", "/api/products", "/api/payments"}
	userAgents := []string{
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
		"curl/7.68.0",
		"python-requests/2.25.1",
	}

	if ts.IsZero() {
		ts = time.Now()
	}
	if clientIP == "" {
		clientIP = fmt.Sprintf("192.168.%d.%d", rand.Intn(255), rand.Intn(255))
	}
	if statusCode == 0 {
		statusCodes := []int{200, 200, 200, 201, 204, 301, 400, 401, 403, 404, 500, 502, 503}
		statusCode = statusCodes[rand.Intn(len(statusCodes))]
	}
	if responseTime == 0 {
		responseTime = int64(rand.Intn(500)) + 10
	}

	method := methods[rand.Intn(len(methods))]
	path := paths[rand.Intn(len(paths))]
	userAgent := userAgents[rand.Intn(len(userAgents))]
	referer := "-"

	return fmt.Sprintf(`%s - - [%s] "%s %s HTTP/1.1" %d %d "%s" "%s"`,
		clientIP,
		ts.Format("02/Jan/2006:15:04:05 -0700"),
		method,
		path,
		statusCode,
		responseTime,
		referer,
		userAgent,
	)
}

func NewLogEvent(opts ...func(*models.LogEvent)) *models.LogEvent {
	event := models.NewLogEvent()
	event.Timestamp = time.Now()
	event.ServiceName = "api-gateway"
	event.Level = models.LevelInfo
	event.Message = "test log message"
	event.Source = models.SourceElasticsearch
	event.Host = "server-01"

	for _, opt := range opts {
		opt(event)
	}

	return event
}

func WithServiceName(name string) func(*models.LogEvent) {
	return func(e *models.LogEvent) {
		e.ServiceName = name
	}
}

func WithLevel(level models.LogLevel) func(*models.LogEvent) {
	return func(e *models.LogEvent) {
		e.Level = level
	}
}

func WithMessage(msg string) func(*models.LogEvent) {
	return func(e *models.LogEvent) {
		e.Message = msg
		e.RawMessage = msg
	}
}

func WithTimestamp(ts time.Time) func(*models.LogEvent) {
	return func(e *models.LogEvent) {
		e.Timestamp = ts
	}
}

func WithTraceID(traceID string) func(*models.LogEvent) {
	return func(e *models.LogEvent) {
		e.TraceID = traceID
	}
}

func WithClientIP(ip string) func(*models.LogEvent) {
	return func(e *models.LogEvent) {
		e.ClientIP = ip
	}
}

func WithResponseTime(ms int64) func(*models.LogEvent) {
	return func(e *models.LogEvent) {
		e.ResponseTime = ms
	}
}

func WithStatusCode(code int) func(*models.LogEvent) {
	return func(e *models.LogEvent) {
		e.StatusCode = code
	}
}

func WithErrorCode(code string) func(*models.LogEvent) {
	return func(e *models.LogEvent) {
		e.ErrorCode = code
	}
}

func WithParsedField(key string, value interface{}) func(*models.LogEvent) {
	return func(e *models.LogEvent) {
		if e.ParsedFields == nil {
			e.ParsedFields = make(map[string]interface{})
		}
		e.ParsedFields[key] = value
	}
}

func NewESDocument(event *models.LogEvent) map[string]interface{} {
	doc := map[string]interface{}{
		"@timestamp":   event.Timestamp.Format(time.RFC3339Nano),
		"message":      event.Message,
		"level":        string(event.Level),
		"host":         event.Host,
		"service.name": event.ServiceName,
	}

	if event.TraceID != "" {
		doc["trace_id"] = event.TraceID
	}
	if event.ClientIP != "" {
		doc["client_ip"] = event.ClientIP
	}
	if event.ResponseTime > 0 {
		doc["response_time"] = event.ResponseTime
	}
	if event.StatusCode > 0 {
		doc["status_code"] = event.StatusCode
	}
	if event.ErrorCode != "" {
		doc["error_code"] = event.ErrorCode
	}

	return doc
}

func NewESHit(doc map[string]interface{}, id string) map[string]interface{} {
	return map[string]interface{}{
		"_index":   "logs-2024.01.01",
		"_id":      id,
		"_source":  doc,
		"_score":   1.0,
	}
}

func NewAlert(opts ...func(*models.Alert)) *models.Alert {
	alert := models.NewAlert(models.AlertTypeErrorRate, models.SeverityHigh, "api-gateway")
	alert.Title = "Test Alert"
	alert.Description = "This is a test alert"
	alert.MetricValue = 15.5
	alert.Threshold = 3.0
	alert.WindowSize = 5 * time.Minute
	alert.Algorithm = "zscore"
	alert.ZScore = 4.2

	for _, opt := range opts {
		opt(alert)
	}

	return alert
}

func WithAlertType(at models.AlertType) func(*models.Alert) {
	return func(a *models.Alert) {
		a.AlertType = at
	}
}

func WithAlertSeverity(s models.Severity) func(*models.Alert) {
	return func(a *models.Alert) {
		a.Severity = s
	}
}

func WithAlertServiceName(name string) func(*models.Alert) {
	return func(a *models.Alert) {
		a.ServiceName = name
	}
}

func WithAlertErrorCode(code string) func(*models.Alert) {
	return func(a *models.Alert) {
		a.ErrorCode = code
	}
}

func GenerateErrorRateDataset(baseErrorRate float64, anomalyStartIdx int, anomalyErrorRate float64, totalPoints int) []float64 {
	data := make([]float64, totalPoints)
	for i := 0; i < totalPoints; i++ {
		if i >= anomalyStartIdx {
			data[i] = anomalyErrorRate + rand.NormFloat64()*2
		} else {
			data[i] = baseErrorRate + rand.NormFloat64()*0.5
		}
		if data[i] < 0 {
			data[i] = 0
		}
	}
	return data
}

func GenerateLatencyDataset(baseLatency float64, anomalyStartIdx int, anomalyLatency float64, totalPoints int) []float64 {
	data := make([]float64, totalPoints)
	for i := 0; i < totalPoints; i++ {
		if i >= anomalyStartIdx {
			data[i] = anomalyLatency + rand.NormFloat64()*50
		} else {
			data[i] = baseLatency + rand.NormFloat64()*20
		}
		if data[i] < 0 {
			data[i] = 0
		}
	}
	return data
}

func NewLogEventBatch(count int, opts ...func(*models.LogEvent)) []*models.LogEvent {
	events := make([]*models.LogEvent, count)
	now := time.Now()
	for i := 0; i < count; i++ {
		event := NewLogEvent(opts...)
		event.Timestamp = now.Add(-time.Duration(count-i) * time.Second)
		events[i] = event
	}
	return events
}

func NewESBulkRequest(events []*models.LogEvent) string {
	var builder strings.Builder
	for i, event := range events {
		meta := map[string]interface{}{
			"index": map[string]interface{}{
				"_index": "logs-test",
				"_id":    fmt.Sprintf("doc-%d", i),
			},
		}
		metaBytes, _ := json.Marshal(meta)
		builder.Write(metaBytes)
		builder.WriteString("\n")

		doc := NewESDocument(event)
		docBytes, _ := json.Marshal(doc)
		builder.Write(docBytes)
		builder.WriteString("\n")
	}
	return builder.String()
}
