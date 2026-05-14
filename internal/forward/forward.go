package forward

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"netproxy/internal/config"
	"netproxy/internal/health"
	"netproxy/internal/logger"
	"netproxy/internal/pool"
	"netproxy/internal/protocol"
	"netproxy/internal/stats"
)

type ForwardResult struct {
	Success      bool
	ResponseData []byte
	Latency      int64
	Error        error
	StatusCode   int
}

type ForwardEngine struct {
	configMgr    *config.ConfigManager
	poolMgr      *pool.PoolManager
	healthChecker *health.HealthChecker
	parser       *protocol.ProtocolParser
	activeConns  int64
	mu           sync.Mutex
}

var (
	ErrRuleNotFound   = errors.New("forward rule not found")
	ErrRuleDisabled   = errors.New("forward rule is disabled")
	ErrProtocolNotAllowed = errors.New("protocol not allowed by rule")
	ErrConnectionFailed = errors.New("failed to connect to target")
	ErrForwardFailed  = errors.New("forward failed")
)

func NewForwardEngine(configMgr *config.ConfigManager, poolMgr *pool.PoolManager, healthChecker *health.HealthChecker) *ForwardEngine {
	return &ForwardEngine{
		configMgr:     configMgr,
		poolMgr:       poolMgr,
		healthChecker: healthChecker,
		parser:        protocol.NewProtocolParser(),
	}
}

func (fe *ForwardEngine) HandleConnection(clientConn net.Conn) {
	defer clientConn.Close()

	startTime := time.Now()
	requestID := uuid.New().String()

	reader := bufio.NewReader(clientConn)
	proto, err := fe.parser.DetectProtocol(reader)
	if err != nil {
		logger.Warn("Failed to detect protocol: %v", err)
		fe.sendErrorResponse(clientConn, http.StatusBadRequest, "Invalid protocol")
		return
	}

	req, err := fe.parser.Parse(reader)
	if err != nil {
		logger.Warn("Failed to parse request: %v", err)
		fe.sendErrorResponse(clientConn, http.StatusBadRequest, "Invalid request")
		return
	}

	if proto == protocol.ProtocolSOCKS5 {
		if err := fe.parser.CompleteSOCKS5Handshake(clientConn, req); err != nil {
			logger.Warn("Failed to complete SOCKS5 handshake: %v", err)
			return
		}
	}

	sourceIP := clientConn.RemoteAddr().String()
	if host, _, err := net.SplitHostPort(sourceIP); err == nil {
		sourceIP = host
	}

	rule := fe.configMgr.GetRuleByTarget(req.TargetHost, string(req.Protocol))
	if rule == nil {
		logger.Warn("No matching rule found for target: %s, protocol: %s", req.TargetHost, req.Protocol)
		fe.sendErrorResponse(clientConn, http.StatusForbidden, "Access denied")
		fe.logAccess(requestID, req, sourceIP, 0, 0, 0, http.StatusForbidden, "No matching rule", "")
		return
	}

	if !rule.Enabled {
		logger.Warn("Rule is disabled: %s", rule.RuleID)
		fe.sendErrorResponse(clientConn, http.StatusForbidden, "Access denied")
		fe.logAccess(requestID, req, sourceIP, 0, 0, 0, http.StatusForbidden, "Rule disabled", rule.RuleID)
		return
	}

	if fe.healthChecker != nil && !fe.healthChecker.IsTargetHealthy(req.TargetHost, req.TargetPort) {
		logger.Warn("Target is unhealthy: %s:%d", req.TargetHost, req.TargetPort)
		fe.sendErrorResponse(clientConn, http.StatusServiceUnavailable, "Service unavailable")
		fe.logAccess(requestID, req, sourceIP, 0, 0, 0, http.StatusServiceUnavailable, "Target unhealthy", rule.RuleID)
		return
	}

	var targetConn net.Conn
	var requestSize int64
	var responseSize int64

	if req.Protocol == protocol.ProtocolSOCKS5 {
		fe.handleSOCKS5Forward(clientConn, req, requestID, sourceIP, rule, startTime)
		return
	}

	if req.Protocol == protocol.ProtocolHTTPS {
		fe.handleHTTPSForward(clientConn, req, requestID, sourceIP, rule, startTime)
		return
	}

	fe.handleHTTPForward(clientConn, req, requestID, sourceIP, rule, startTime, &requestSize, &responseSize)
}

func (fe *ForwardEngine) handleHTTPForward(clientConn net.Conn, req *protocol.ProxyRequest, requestID, sourceIP string, rule *config.ForwardRule, startTime time.Time, requestSize, responseSize *int64) {
	targetConn, err := fe.poolMgr.GetConnection(req.TargetHost, req.TargetPort)
	if err != nil {
		logger.Error("Failed to get connection from pool: %v", err)
		fe.sendErrorResponse(clientConn, http.StatusServiceUnavailable, "Service unavailable")
		fe.logAccess(requestID, req, sourceIP, *requestSize, *responseSize, time.Since(startTime).Milliseconds(), http.StatusServiceUnavailable, err.Error(), rule.RuleID)
		return
	}
	defer fe.poolMgr.ReleaseConnection(req.TargetHost, req.TargetPort, targetConn)

	serializedReq := fe.parser.SerializeHTTPRequest(req)
	*requestSize = int64(len(serializedReq))

	if _, err := targetConn.Write(serializedReq); err != nil {
		logger.Error("Failed to send request to target: %v", err)
		targetConn.MarkInvalid()
		fe.sendErrorResponse(clientConn, http.StatusBadGateway, "Bad gateway")
		fe.logAccess(requestID, req, sourceIP, *requestSize, *responseSize, time.Since(startTime).Milliseconds(), http.StatusBadGateway, err.Error(), rule.RuleID)
		stats.RecordRequest(req.TargetHost, *requestSize, *responseSize, time.Since(startTime).Milliseconds(), true)
		return
	}

	targetReader := bufio.NewReader(targetConn)
	resp, err := http.ReadResponse(targetReader, nil)
	if err != nil {
		logger.Error("Failed to read response from target: %v", err)
		targetConn.MarkInvalid()
		fe.sendErrorResponse(clientConn, http.StatusBadGateway, "Bad gateway")
		fe.logAccess(requestID, req, sourceIP, *requestSize, *responseSize, time.Since(startTime).Milliseconds(), http.StatusBadGateway, err.Error(), rule.RuleID)
		stats.RecordRequest(req.TargetHost, *requestSize, *responseSize, time.Since(startTime).Milliseconds(), true)
		return
	}
	defer resp.Body.Close()

	resp.Write(clientConn)
	*responseSize = fe.estimateResponseSize(resp)

	latency := time.Since(startTime).Milliseconds()
	fe.logAccess(requestID, req, sourceIP, *requestSize, *responseSize, latency, resp.StatusCode, "", rule.RuleID)
	stats.RecordRequest(req.TargetHost, *requestSize, *responseSize, latency, false)

	logger.Info("Request %s: %s %s:%d -> %d (%d ms)", requestID, req.Method, req.TargetHost, req.TargetPort, resp.StatusCode, latency)
}

func (fe *ForwardEngine) handleHTTPSForward(clientConn net.Conn, req *protocol.ProxyRequest, requestID, sourceIP string, rule *config.ForwardRule, startTime time.Time) {
	targetConn, err := fe.poolMgr.GetConnection(req.TargetHost, req.TargetPort)
	if err != nil {
		logger.Error("Failed to get connection from pool: %v", err)
		fe.sendErrorResponse(clientConn, http.StatusServiceUnavailable, "Service unavailable")
		return
	}
	defer fe.poolMgr.ReleaseConnection(req.TargetHost, req.TargetPort, targetConn)

	clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	var requestSize, responseSize int64
	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		written, _ := io.Copy(targetConn, clientConn)
		requestSize = written
	}()

	go func() {
		defer wg.Done()
		written, _ := io.Copy(clientConn, targetConn)
		responseSize = written
	}()

	wg.Wait()

	latency := time.Since(startTime).Milliseconds()
	fe.logAccess(requestID, req, sourceIP, requestSize, responseSize, latency, 200, "", rule.RuleID)
	stats.RecordRequest(req.TargetHost, requestSize, responseSize, latency, false)

	logger.Info("HTTPS Tunnel %s: %s:%d (in: %d, out: %d, %d ms)", requestID, req.TargetHost, req.TargetPort, requestSize, responseSize, latency)
}

func (fe *ForwardEngine) handleSOCKS5Forward(clientConn net.Conn, req *protocol.ProxyRequest, requestID, sourceIP string, rule *config.ForwardRule, startTime time.Time) {
	targetConn, err := fe.poolMgr.GetConnection(req.TargetHost, req.TargetPort)
	if err != nil {
		logger.Error("Failed to get connection from pool: %v", err)
		fe.parser.SendSOCKS5FailureReply(clientConn, 0x05)
		return
	}
	defer fe.poolMgr.ReleaseConnection(req.TargetHost, req.TargetPort, targetConn)

	if err := fe.parser.SendSOCKS5SuccessReply(clientConn, targetConn.LocalAddr()); err != nil {
		logger.Error("Failed to send SOCKS5 success reply: %v", err)
		return
	}

	var requestSize, responseSize int64
	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		defer wg.Done()
		written, _ := io.Copy(targetConn, clientConn)
		requestSize = written
	}()

	go func() {
		defer wg.Done()
		written, _ := io.Copy(clientConn, targetConn)
		responseSize = written
	}()

	wg.Wait()

	latency := time.Since(startTime).Milliseconds()
	fe.logAccess(requestID, req, sourceIP, requestSize, responseSize, latency, 0, "", rule.RuleID)
	stats.RecordRequest(req.TargetHost, requestSize, responseSize, latency, false)

	logger.Info("SOCKS5 Tunnel %s: %s:%d (in: %d, out: %d, %d ms)", requestID, req.TargetHost, req.TargetPort, requestSize, responseSize, latency)
}

func (fe *ForwardEngine) ForwardRequest(req *protocol.ProxyRequest, requestData []byte) (*ForwardResult, error) {
	startTime := time.Now()
	result := &ForwardResult{}

	rule := fe.configMgr.GetRuleByTarget(req.TargetHost, string(req.Protocol))
	if rule == nil {
		return nil, ErrRuleNotFound
	}

	if !rule.Enabled {
		return nil, ErrRuleDisabled
	}

	targetConn, err := fe.poolMgr.GetConnection(req.TargetHost, req.TargetPort)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrConnectionFailed, err)
	}
	defer fe.poolMgr.ReleaseConnection(req.TargetHost, req.TargetPort, targetConn)

	if _, err := targetConn.Write(requestData); err != nil {
		targetConn.MarkInvalid()
		return nil, fmt.Errorf("%w: %v", ErrForwardFailed, err)
	}

	var responseBuf []byte
	buf := make([]byte, 4096)
	for {
		n, err := targetConn.Read(buf)
		if n > 0 {
			responseBuf = append(responseBuf, buf[:n]...)
		}
		if err != nil {
			if err == io.EOF {
				break
			}
			break
		}
	}

	result.ResponseData = responseBuf
	result.Latency = time.Since(startTime).Milliseconds()
	result.Success = true

	stats.RecordRequest(req.TargetHost, int64(len(requestData)), int64(len(responseBuf)), result.Latency, false)

	return result, nil
}

func (fe *ForwardEngine) logAccess(requestID string, req *protocol.ProxyRequest, sourceIP string, requestSize, responseSize, latency int64, statusCode int, errMsg, ruleID string) {
	logger.Access(logger.AccessLogEntry{
		RequestID:    requestID,
		Protocol:     string(req.Protocol),
		SourceIP:     sourceIP,
		TargetHost:   req.TargetHost,
		TargetPort:   req.TargetPort,
		RequestSize:  requestSize,
		ResponseSize: responseSize,
		Latency:      latency,
		StatusCode:   statusCode,
		Error:        errMsg,
		RuleID:       ruleID,
	})
}

func (fe *ForwardEngine) sendErrorResponse(conn net.Conn, statusCode int, message string) {
	statusText := http.StatusText(statusCode)
	response := fmt.Sprintf("HTTP/1.1 %d %s\r\nContent-Type: text/plain\r\nContent-Length: %d\r\n\r\n%s",
		statusCode, statusText, len(message), message)
	conn.Write([]byte(response))
}

func (fe *ForwardEngine) estimateResponseSize(resp *http.Response) int64 {
	var size int64

	size += int64(len(fmt.Sprintf("HTTP/1.1 %d %s\r\n", resp.StatusCode, http.StatusText(resp.StatusCode))))
	for key, values := range resp.Header {
		for _, value := range values {
			size += int64(len(fmt.Sprintf("%s: %s\r\n", key, value)))
		}
	}
	size += 2

	if resp.ContentLength > 0 {
		size += resp.ContentLength
	} else if resp.Body != nil {
		body, _ := io.ReadAll(resp.Body)
		size += int64(len(body))
	}

	return size
}

func (fe *ForwardEngine) GetActiveConnections() int64 {
	return fe.activeConns
}

func (fe *ForwardEngine) ValidateRequest(req *protocol.ProxyRequest) error {
	if req.TargetHost == "" {
		return errors.New("target host is required")
	}

	if req.TargetPort <= 0 || req.TargetPort > 65535 {
		return errors.New("invalid target port")
	}

	rule := fe.configMgr.GetRuleByTarget(req.TargetHost, string(req.Protocol))
	if rule == nil {
		return ErrRuleNotFound
	}

	if !rule.Enabled {
		return ErrRuleDisabled
	}

	if len(rule.AllowedProtocols) > 0 {
		allowed := false
		for _, p := range rule.AllowedProtocols {
			if strings.EqualFold(p, string(req.Protocol)) {
				allowed = true
				break
			}
		}
		if !allowed {
			return ErrProtocolNotAllowed
		}
	}

	return nil
}
