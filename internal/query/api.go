package query

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"log-pipeline/internal/storage"
	"log-pipeline/pkg/config"
)

type QueryEngine struct {
	config  *config.QueryAPIConfig
	chStore *storage.ClickHouseStore
}

type QueryRequest struct {
	Query     string    `json:"query"`
	StartTime time.Time `json:"start_time"`
	EndTime   time.Time `json:"end_time"`
	Limit     int       `json:"limit"`
}

type QueryResponse struct {
	Status    string        `json:"status"`
	Data      []interface{} `json:"data"`
	Count     int           `json:"count"`
	TimeTaken string        `json:"time_taken"`
}

type LogQLExpr struct {
	Matchers   []LabelMatcher
	FilterExpr *FilterExpr
	AggrFunc   *AggrFunc
}

type LabelMatcher struct {
	Key      string
	Operator string
	Value    string
}

type FilterExpr struct {
	Operator string
	Value    string
	IsRegex  bool
}

type AggrFunc struct {
	Name   string
	Labels []string
}

func NewQueryEngine(cfg *config.QueryAPIConfig, chStore *storage.ClickHouseStore) *QueryEngine {
	return &QueryEngine{
		config:  cfg,
		chStore: chStore,
	}
}

func (qe *QueryEngine) Start() error {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/v1/query", qe.handleQuery)
	mux.HandleFunc("/api/v1/labels", qe.handleLabels)
	mux.HandleFunc("/api/v1/label/{name}/values", qe.handleLabelValues)
	mux.HandleFunc("/api/v1/series", qe.handleSeries)
	mux.HandleFunc("/health", qe.handleHealth)

	server := &http.Server{
		Addr:    fmt.Sprintf(":%d", qe.config.Port),
		Handler: mux,
	}

	fmt.Printf("Query API server listening on port %d\n", qe.config.Port)

	go func() {
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			fmt.Printf("Query API server error: %v\n", err)
		}
	}()

	return nil
}

func (qe *QueryEngine) handleQuery(w http.ResponseWriter, r *http.Request) {
	startTime := time.Now()

	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req QueryRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	if req.Limit == 0 {
		req.Limit = 100
	}

	if req.EndTime.IsZero() {
		req.EndTime = time.Now()
	}

	if req.StartTime.IsZero() {
		req.StartTime = req.EndTime.Add(-1 * time.Hour)
	}

	results, err := qe.Execute(r.Context(), &req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	response := QueryResponse{
		Status:    "success",
		Data:      results,
		Count:     len(results),
		TimeTaken: time.Since(startTime).String(),
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(response)
}

func (qe *QueryEngine) handleLabels(w http.ResponseWriter, r *http.Request) {
	labels := []string{"source", "host", "level", "fields"}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status": "success",
		"data":   labels,
	})
}

func (qe *QueryEngine) handleLabelValues(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	query := fmt.Sprintf(`SELECT DISTINCT %s FROM logs WHERE timestamp >= NOW() - INTERVAL 1 HOUR LIMIT 100`, name)

	rows, err := qe.chStore.QueryAggregate(r.Context(), query)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer rows.Close()

	var values []string
	for rows.Next() {
		var value string
		if err := rows.Scan(&value); err == nil {
			values = append(values, value)
		}
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status": "success",
		"data":   values,
	})
}

func (qe *QueryEngine) handleSeries(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status": "success",
		"data":   []string{},
	})
}

func (qe *QueryEngine) handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

func (qe *QueryEngine) Execute(ctx context.Context, req *QueryRequest) ([]interface{}, error) {
	expr, err := qe.ParseLogQL(req.Query)
	if err != nil {
		return nil, err
	}

	return qe.ExecuteExpr(ctx, expr, req.StartTime, req.EndTime, req.Limit)
}

func (qe *QueryEngine) ParseLogQL(query string) (*LogQLExpr, error) {
	expr := &LogQLExpr{}

	query = strings.TrimSpace(query)

	labelMatcherRegex := regexp.MustCompile(`(\w+)\s*(=~|!~|=|!=)\s*"([^"]*)"`)
	matches := labelMatcherRegex.FindAllStringSubmatch(query, -1)

	for _, match := range matches {
		expr.Matchers = append(expr.Matchers, LabelMatcher{
			Key:      match[1],
			Operator: match[2],
			Value:    match[3],
		})
		query = strings.Replace(query, match[0], "", 1)
	}

	query = strings.TrimSpace(query)

	if strings.HasPrefix(query, "|=") || strings.HasPrefix(query, "|~") ||
		strings.HasPrefix(query, "!=") || strings.HasPrefix(query, "!~") {
		expr.FilterExpr = &FilterExpr{
			Operator: query[:2],
			Value:    strings.Trim(strings.TrimSpace(query[2:]), `"`),
			IsRegex:  query[1] == '~',
		}
	}

	aggrRegex := regexp.MustCompile(`^(count|sum|avg|min|max)\(([^)]*)\)\s*(by|without)\s*\(([^)]*)\)`)
	aggrMatch := aggrRegex.FindStringSubmatch(query)
	if len(aggrMatch) > 0 {
		expr.AggrFunc = &AggrFunc{
			Name:   aggrMatch[1],
			Labels: strings.Split(aggrMatch[4], ","),
		}
		for i, label := range expr.AggrFunc.Labels {
			expr.AggrFunc.Labels[i] = strings.TrimSpace(label)
		}
	}

	return expr, nil
}

func (qe *QueryEngine) ExecuteExpr(ctx context.Context, expr *LogQLExpr, startTime, endTime time.Time, limit int) ([]interface{}, error) {
	var whereClauses []string
	var args []interface{}

	whereClauses = append(whereClauses, "timestamp >= ? AND timestamp <= ?")
	args = append(args, startTime, endTime)

	for _, matcher := range expr.Matchers {
		clause, value := qe.buildMatcherClause(matcher)
		whereClauses = append(whereClauses, clause)
		args = append(args, value)
	}

	if expr.FilterExpr != nil {
		clause, value := qe.buildFilterClause(expr.FilterExpr)
		whereClauses = append(whereClauses, clause)
		args = append(args, value)
	}

	whereSQL := strings.Join(whereClauses, " AND ")

	var sql string
	if expr.AggrFunc != nil {
		sql = qe.buildAggregateSQL(expr.AggrFunc, whereSQL)
	} else {
		sql = fmt.Sprintf(`SELECT id, timestamp, source, host, level, message, fields, raw 
			FROM logs WHERE %s ORDER BY timestamp DESC LIMIT ?`, whereSQL)
		args = append(args, limit)
	}

	rows, err := qe.chStore.QueryAggregate(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var results []interface{}
	for rows.Next() {
		if expr.AggrFunc != nil {
			result, err := qe.scanAggregateResult(rows, expr.AggrFunc)
			if err != nil {
				return nil, err
			}
			results = append(results, result)
		} else {
			result, err := qe.scanLogResult(rows)
			if err != nil {
				return nil, err
			}
			results = append(results, result)
		}
	}

	return results, nil
}

func (qe *QueryEngine) buildMatcherClause(matcher LabelMatcher) (string, string) {
	var clause string
	var value string

	switch matcher.Operator {
	case "=":
		clause = fmt.Sprintf("%s = ?", matcher.Key)
		value = matcher.Value
	case "!=":
		clause = fmt.Sprintf("%s != ?", matcher.Key)
		value = matcher.Value
	case "=~":
		clause = fmt.Sprintf("match(%s, ?)", matcher.Key)
		value = matcher.Value
	case "!~":
		clause = fmt.Sprintf("NOT match(%s, ?)", matcher.Key)
		value = matcher.Value
	}

	return clause, value
}

func (qe *QueryEngine) buildFilterClause(filter *FilterExpr) (string, string) {
	var clause string
	var value string

	switch filter.Operator {
	case "|=":
		clause = "position(? IN message) > 0"
		value = filter.Value
	case "!=":
		clause = "position(? IN message) = 0"
		value = filter.Value
	case "|~":
		clause = "match(message, ?)"
		value = filter.Value
	case "!~":
		clause = "NOT match(message, ?)"
		value = filter.Value
	}

	return clause, value
}

func (qe *QueryEngine) buildAggregateSQL(aggr *AggrFunc, whereSQL string) string {
	var aggrSQL string
	labels := strings.Join(aggr.Labels, ", ")

	switch aggr.Name {
	case "count":
		aggrSQL = fmt.Sprintf("SELECT %s, count(*) as value FROM logs WHERE %s GROUP BY %s", labels, whereSQL, labels)
	case "sum":
		aggrSQL = fmt.Sprintf("SELECT %s, sum(1) as value FROM logs WHERE %s GROUP BY %s", labels, whereSQL, labels)
	default:
		aggrSQL = fmt.Sprintf("SELECT %s, count(*) as value FROM logs WHERE %s GROUP BY %s", labels, whereSQL, labels)
	}

	return aggrSQL
}

func (qe *QueryEngine) scanLogResult(rows interface {
	Scan(dest ...interface{}) error
}) (map[string]interface{}, error) {
	var id, source, host, level, message, raw string
	var timestamp time.Time
	var fields map[string]string

	err := rows.Scan(&id, &timestamp, &source, &host, &level, &message, &fields, &raw)
	if err != nil {
		return nil, err
	}

	return map[string]interface{}{
		"id":        id,
		"timestamp": timestamp,
		"source":    source,
		"host":      host,
		"level":     level,
		"message":   message,
		"fields":    fields,
		"raw":       raw,
	}, nil
}

func (qe *QueryEngine) scanAggregateResult(rows interface {
	Scan(dest ...interface{}) error
}, aggr *AggrFunc) (map[string]interface{}, error) {
	result := make(map[string]interface{})

	scanArgs := make([]interface{}, len(aggr.Labels)+1)
	for i := range aggr.Labels {
		scanArgs[i] = new(string)
	}
	var value float64
	scanArgs[len(aggr.Labels)] = &value

	if err := rows.Scan(scanArgs...); err != nil {
		return nil, err
	}

	for i, label := range aggr.Labels {
		result[label] = *scanArgs[i].(*string)
	}
	result[aggr.Name] = value

	return result, nil
}

func ParseDuration(s string) (time.Duration, error) {
	s = strings.ToLower(s)
	multiplier := time.Second

	if strings.HasSuffix(s, "h") {
		multiplier = time.Hour
		s = s[:len(s)-1]
	} else if strings.HasSuffix(s, "m") {
		multiplier = time.Minute
		s = s[:len(s)-1]
	} else if strings.HasSuffix(s, "s") {
		s = s[:len(s)-1]
	}

	value, err := strconv.Atoi(s)
	if err != nil {
		return 0, err
	}

	return time.Duration(value) * multiplier, nil
}
