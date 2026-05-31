package apigateway

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"github.com/solocoder/session136/pkg/common/utils"
	"go.uber.org/zap"
)

type requestLog struct {
	RequestID  string
	TraceID    string
	UserID     string
	Path       string
	Method     string
	StatusCode int
	Duration   int64
	Timestamp  int64
	Error      string
}

type span struct {
	TraceID    string
	SpanID     string
	ParentID   string
	Name       string
	StartTime  int64
	EndTime    int64
	Attributes map[string]string
	Status     string
}

type DefaultAPIGateway struct {
	middlewares    []interfaces.Middleware
	logger         *zap.Logger
	requestLogs    []*requestLog
	traceSpans     map[string][]*span
	mu             sync.RWMutex
	maxLogs        int
	maxSpans       int
	handler        interfaces.HandlerFunc
}

func NewDefaultAPIGateway(maxLogs, maxSpans int) *DefaultAPIGateway {
	return &DefaultAPIGateway{
		middlewares: make([]interfaces.Middleware, 0),
		logger:      utils.GetLogger(),
		requestLogs: make([]*requestLog, 0, maxLogs),
		traceSpans:  make(map[string][]*span),
		maxLogs:     maxLogs,
		maxSpans:    maxSpans,
	}
}

func (g *DefaultAPIGateway) SetHandler(handler interfaces.HandlerFunc) {
	g.handler = handler
}

func (g *DefaultAPIGateway) AddMiddleware(middleware interfaces.Middleware) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.middlewares = append(g.middlewares, middleware)
	g.logger.Info("Middleware added", zap.Int("total", len(g.middlewares)))
}

func (g *DefaultAPIGateway) HandleRequest(ctx context.Context, req *interfaces.GatewayRequest) (*interfaces.GatewayResponse, error) {
	startTime := time.Now().UnixNano()

	if req.ID == "" {
		req.ID = utils.GenerateID("req")
	}
	if req.TraceID == "" {
		req.TraceID = utils.GenerateTraceID()
	}
	req.Timestamp = startTime

	g.logger.Info("Request received",
		zap.String("request_id", req.ID),
		zap.String("trace_id", req.TraceID),
		zap.String("method", req.Method),
		zap.String("path", req.Path),
		zap.String("user_id", req.UserID),
	)

	span := g.startSpan(req.TraceID, "", req.Path, map[string]string{
		"request_id": req.ID,
		"user_id":    req.UserID,
		"method":     req.Method,
		"path":       req.Path,
	})

	handler := g.handler
	if handler == nil {
		handler = g.defaultHandler
	}

	for i := len(g.middlewares) - 1; i >= 0; i-- {
		mw := g.middlewares[i]
		next := handler
		handler = func(ctx context.Context, req *interfaces.GatewayRequest) (*interfaces.GatewayResponse, error) {
			return mw.Handle(ctx, req, next)
		}
	}

	resp, err := handler(ctx, req)
	duration := (time.Now().UnixNano() - startTime) / 1e6

	if resp == nil {
		resp = &interfaces.GatewayResponse{
			StatusCode: 500,
			Headers:    make(map[string]string),
			Body:       map[string]interface{}{"error": "internal error"},
			TraceID:    req.TraceID,
		}
	}

	if err != nil {
		resp.StatusCode = 500
		span.Status = "error"
	} else {
		span.Status = "ok"
	}

	g.endSpan(span)
	g.LogRequest(ctx, req, resp, duration)

	g.logger.Info("Request completed",
		zap.String("request_id", req.ID),
		zap.String("trace_id", req.TraceID),
		zap.Int("status_code", resp.StatusCode),
		zap.Int64("duration_ms", duration),
	)

	return resp, err
}

func (g *DefaultAPIGateway) LogRequest(ctx context.Context, req *interfaces.GatewayRequest, resp *interfaces.GatewayResponse, duration int64) {
	g.mu.Lock()
	defer g.mu.Unlock()

	log := &requestLog{
		RequestID:  req.ID,
		TraceID:    req.TraceID,
		UserID:     req.UserID,
		Path:       req.Path,
		Method:     req.Method,
		StatusCode: resp.StatusCode,
		Duration:   duration,
		Timestamp:  req.Timestamp,
	}

	if len(g.requestLogs) >= g.maxLogs {
		g.requestLogs = g.requestLogs[1:]
	}
	g.requestLogs = append(g.requestLogs, log)
}

func (g *DefaultAPIGateway) GetRequestLogs(traceID string) []*requestLog {
	g.mu.RLock()
	defer g.mu.RUnlock()

	if traceID != "" {
		var filtered []*requestLog
		for _, log := range g.requestLogs {
			if log.TraceID == traceID {
				filtered = append(filtered, log)
			}
		}
		return filtered
	}

	return g.requestLogs
}

func (g *DefaultAPIGateway) startSpan(traceID, parentID, name string, attributes map[string]string) *span {
	g.mu.Lock()
	defer g.mu.Unlock()

	span := &span{
		TraceID:    traceID,
		SpanID:     utils.GenerateID("span"),
		ParentID:   parentID,
		Name:       name,
		StartTime:  time.Now().UnixNano(),
		Attributes: attributes,
	}

	g.traceSpans[traceID] = append(g.traceSpans[traceID], span)

	if len(g.traceSpans) > g.maxSpans {
		for k := range g.traceSpans {
			delete(g.traceSpans, k)
			break
		}
	}

	g.logger.Debug("Span started",
		zap.String("trace_id", traceID),
		zap.String("span_id", span.SpanID),
		zap.String("name", name),
	)

	return span
}

func (g *DefaultAPIGateway) endSpan(s *span) {
	g.mu.Lock()
	defer g.mu.Unlock()

	s.EndTime = time.Now().UnixNano()
	g.logger.Debug("Span ended",
		zap.String("trace_id", s.TraceID),
		zap.String("span_id", s.SpanID),
		zap.String("status", s.Status),
		zap.Int64("duration_ns", s.EndTime-s.StartTime),
	)
}

func (g *DefaultAPIGateway) GetTrace(traceID string) []*span {
	g.mu.RLock()
	defer g.mu.RUnlock()

	spans, exists := g.traceSpans[traceID]
	if !exists {
		return nil
	}

	return spans
}

func (g *DefaultAPIGateway) defaultHandler(ctx context.Context, req *interfaces.GatewayRequest) (*interfaces.GatewayResponse, error) {
	return &interfaces.GatewayResponse{
		StatusCode: 200,
		Headers: map[string]string{
			"Content-Type": "application/json",
		},
		Body: map[string]interface{}{
			"message": "OK",
			"path":    req.Path,
			"method":  req.Method,
		},
		TraceID: req.TraceID,
	}, nil
}

type AuthMiddleware struct {
	validTokens map[string]string
}

func NewAuthMiddleware() *AuthMiddleware {
	return &AuthMiddleware{
		validTokens: make(map[string]string),
	}
}

func (m *AuthMiddleware) AddToken(token, userID string) {
	m.validTokens[token] = userID
}

func (m *AuthMiddleware) Handle(ctx context.Context, req *interfaces.GatewayRequest, next interfaces.HandlerFunc) (*interfaces.GatewayResponse, error) {
	token := req.Headers["Authorization"]
	if token == "" {
		return &interfaces.GatewayResponse{
			StatusCode: 401,
			Headers:    make(map[string]string),
			Body:       map[string]interface{}{"error": "missing authorization token"},
			TraceID:    req.TraceID,
		}, nil
	}

	if userID, ok := m.validTokens[token]; ok {
		req.UserID = userID
		return next(ctx, req)
	}

	return &interfaces.GatewayResponse{
		StatusCode: 403,
		Headers:    make(map[string]string),
		Body:       map[string]interface{}{"error": "invalid authorization token"},
		TraceID:    req.TraceID,
	}, nil
}

type RateLimitMiddleware struct {
	requests   map[string]int
	limit      int
	windowSecs int
	mu         sync.Mutex
}

func NewRateLimitMiddleware(limit, windowSecs int) *RateLimitMiddleware {
	return &RateLimitMiddleware{
		requests:   make(map[string]int),
		limit:      limit,
		windowSecs: windowSecs,
	}
}

func (m *RateLimitMiddleware) Handle(ctx context.Context, req *interfaces.GatewayRequest, next interfaces.HandlerFunc) (*interfaces.GatewayResponse, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	key := fmt.Sprintf("%s:%d", req.UserID, time.Now().Unix()/int64(m.windowSecs))
	m.requests[key]++

	if m.requests[key] > m.limit {
		return &interfaces.GatewayResponse{
			StatusCode: 429,
			Headers:    make(map[string]string),
			Body:       map[string]interface{}{"error": "rate limit exceeded"},
			TraceID:    req.TraceID,
		}, nil
	}

	return next(ctx, req)
}

type LoggingMiddleware struct {
	logger *zap.Logger
}

func NewLoggingMiddleware() *LoggingMiddleware {
	return &LoggingMiddleware{
		logger: utils.GetLogger(),
	}
}

func (m *LoggingMiddleware) Handle(ctx context.Context, req *interfaces.GatewayRequest, next interfaces.HandlerFunc) (*interfaces.GatewayResponse, error) {
	m.logger.Info("Middleware: Processing request",
		zap.String("request_id", req.ID),
		zap.String("path", req.Path),
	)

	resp, err := next(ctx, req)

	if resp != nil {
		m.logger.Info("Middleware: Request processed",
			zap.String("request_id", req.ID),
			zap.Int("status", resp.StatusCode),
		)
	}

	return resp, err
}
