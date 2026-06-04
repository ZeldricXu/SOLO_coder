package ingestor

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"log-pipeline/pkg/config"
	"log-pipeline/pkg/models"
	"log-pipeline/pkg/utils"
)

type Ingestor struct {
	config       *config.IngestorConfig
	backPressure *utils.BackPressure
	rateLimiter  *utils.RateLimiter
	logChan      chan *models.LogEntry
	parsers      map[string]ProtocolParser
	wg           sync.WaitGroup
	ctx          context.Context
	cancel       context.CancelFunc
}

func NewIngestor(cfg *config.IngestorConfig) *Ingestor {
	ctx, cancel := context.WithCancel(context.Background())

	parsers := make(map[string]ProtocolParser)
	for _, source := range cfg.Sources {
		parsers[source] = NewProtocolParser(source)
	}

	return &Ingestor{
		config:       cfg,
		backPressure: utils.NewBackPressure(cfg.BufferSize),
		rateLimiter:  utils.NewRateLimiter(10000, 50000),
		logChan:      make(chan *models.LogEntry, cfg.BufferSize),
		parsers:      parsers,
		ctx:          ctx,
		cancel:       cancel,
	}
}

func (i *Ingestor) Start() error {
	i.wg.Add(3)
	go i.startTCP()
	go i.startUDP()
	go i.startHTTP()
	return nil
}

func (i *Ingestor) Stop() {
	i.cancel()
	i.wg.Wait()
	close(i.logChan)
}

func (i *Ingestor) Logs() <-chan *models.LogEntry {
	return i.logChan
}

func (i *Ingestor) GetBackPressureStats() map[string]interface{} {
	return i.backPressure.Stats()
}

func (i *Ingestor) GetParser(protocol string) ProtocolParser {
	return i.parsers[protocol]
}

func (i *Ingestor) startTCP() {
	defer i.wg.Done()

	parser, ok := i.parsers["tcp"]
	if !ok {
		return
	}
	_ = parser

	listener, err := net.Listen("tcp", fmt.Sprintf(":%d", i.config.TCPPort))
	if err != nil {
		fmt.Printf("TCP listener error: %v\n", err)
		return
	}
	defer listener.Close()

	go func() {
		<-i.ctx.Done()
		listener.Close()
	}()

	fmt.Printf("TCP ingestor listening on port %d\n", i.config.TCPPort)

	for {
		conn, err := listener.Accept()
		if err != nil {
			select {
			case <-i.ctx.Done():
				return
			default:
				continue
			}
		}

		if !i.rateLimiter.Allow() {
			conn.Close()
			continue
		}

		go i.handleTCPConn(conn)
	}
}

func (i *Ingestor) handleTCPConn(conn net.Conn) {
	defer conn.Close()

	tcpParser := i.parsers["tcp"]

	reader := bufio.NewReader(conn)
	for {
		select {
		case <-i.ctx.Done():
			return
		default:
		}

		line, err := reader.ReadString('\n')
		if err != nil {
			if err != io.EOF {
				fmt.Printf("TCP read error: %v\n", err)
			}
			return
		}

		raw := tcpParser.Parse(line, "tcp", conn.RemoteAddr().String())
		i.processLog(raw, "tcp", conn.RemoteAddr().String())
	}
}

func (i *Ingestor) startUDP() {
	defer i.wg.Done()

	parser, ok := i.parsers["udp"]
	if !ok {
		return
	}
	_ = parser

	addr, err := net.ResolveUDPAddr("udp", fmt.Sprintf(":%d", i.config.UDPPort))
	if err != nil {
		fmt.Printf("UDP resolve error: %v\n", err)
		return
	}

	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		fmt.Printf("UDP listener error: %v\n", err)
		return
	}
	defer conn.Close()

	go func() {
		<-i.ctx.Done()
		conn.Close()
	}()

	fmt.Printf("UDP ingestor listening on port %d\n", i.config.UDPPort)

	buffer := make([]byte, 65535)
	for {
		n, remoteAddr, err := conn.ReadFromUDP(buffer)
		if err != nil {
			select {
			case <-i.ctx.Done():
				return
			default:
				continue
			}
		}

		if !i.rateLimiter.Allow() {
			continue
		}

		udpParser := i.parsers["udp"]
		raw := udpParser.Parse(string(buffer[:n]), "udp", remoteAddr.String())
		i.processLog(raw, "udp", remoteAddr.String())
	}
}

func (i *Ingestor) startHTTP() {
	defer i.wg.Done()

	parser, ok := i.parsers["http"]
	if !ok {
		return
	}
	_ = parser

	mux := http.NewServeMux()
	mux.HandleFunc("/api/v1/logs", i.handleHTTPLogs)
	mux.HandleFunc("/api/v1/health", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]interface{}{
			"status": "ok",
			"stats":  i.backPressure.Stats(),
		})
	})

	server := &http.Server{
		Addr:    fmt.Sprintf(":%d", i.config.HTTPPort),
		Handler: mux,
	}

	fmt.Printf("HTTP ingestor listening on port %d\n", i.config.HTTPPort)

	go func() {
		<-i.ctx.Done()
		server.Shutdown(context.Background())
	}()

	if err := server.ListenAndServe(); err != http.ErrServerClosed {
		fmt.Printf("HTTP server error: %v\n", err)
	}
}

func (i *Ingestor) handleHTTPLogs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if !i.rateLimiter.Allow() {
		http.Error(w, "rate limited", http.StatusTooManyRequests)
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	httpParser := i.parsers["http"]
	raw := httpParser.Parse(string(body), "http", r.RemoteAddr)
	i.processLog(raw, "http", r.RemoteAddr)
	w.WriteHeader(http.StatusAccepted)
}

func (i *Ingestor) processLog(raw string, source string, remoteAddr string) bool {
	select {
	case <-i.ctx.Done():
		return false
	default:
	}

	if !i.backPressure.TryAcquire(i.ctx) {
		return false
	}
	defer i.backPressure.Release()

	entry := i.ParseLog(raw, source, remoteAddr)
	if entry == nil {
		return false
	}

	defer func() {
		if r := recover(); r != nil {
		}
	}()

	select {
	case <-i.ctx.Done():
		return false
	default:
	}

	select {
	case i.logChan <- entry:
		return true
	case <-i.ctx.Done():
		return false
	default:
		i.backPressure.Drop()
		return false
	}
}

func (i *Ingestor) ProcessLog(raw string, source string, remoteAddr string) bool {
	return i.processLog(raw, source, remoteAddr)
}

func (i *Ingestor) ParseLog(raw string, source string, remoteAddr string) *models.LogEntry {
	return i.parseLog(raw, source, remoteAddr)
}

func (i *Ingestor) parseLog(raw string, source string, remoteAddr string) *models.LogEntry {
	var entry models.LogEntry

	if err := json.Unmarshal([]byte(raw), &entry); err == nil {
		if entry.Timestamp.IsZero() {
			entry.Timestamp = time.Now()
		}
		if entry.ID == "" {
			entry.ID = utils.GenerateID()
		}
		entry.Raw = raw
		return &entry
	}

	return &models.LogEntry{
		ID:        utils.GenerateID(),
		Timestamp: time.Now(),
		Source:    source,
		Host:      ExtractHost(remoteAddr),
		Message:   raw,
		Raw:       raw,
		Fields:    make(map[string]string),
	}
}

func ExtractHost(addr string) string {
	if idx := strings.LastIndex(addr, ":"); idx > 0 {
		return addr[:idx]
	}
	return addr
}
