package collector

import (
	"context"
	"fmt"
	"log"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
)

type SyslogCollector struct {
	*BaseCollector
	cfg       config.SyslogConfig
	conn      net.PacketConn
	parseFunc func(string, net.Addr) *models.LogEvent
}

type SyslogMessage struct {
	Priority  int
	Facility  int
	Severity  int
	Timestamp time.Time
	Hostname  string
	Tag       string
	ProcessID string
	Message   string
}

func NewSyslogCollector(cfg config.SyslogConfig) (*SyslogCollector, error) {
	if cfg.Port == 0 {
		cfg.Port = 514
	}
	if cfg.Protocol == "" {
		cfg.Protocol = "udp"
	}
	if cfg.BindAddr == "" {
		cfg.BindAddr = "0.0.0.0"
	}

	return &SyslogCollector{
		BaseCollector: NewBaseCollector(cfg.Name, models.SourceSyslog, 1000),
		cfg:           cfg,
	}, nil
}

func (c *SyslogCollector) Start(ctx context.Context) error {
	if c.IsRunning() {
		return nil
	}

	addr := fmt.Sprintf("%s:%d", c.cfg.BindAddr, c.cfg.Port)
	conn, err := net.ListenPacket(c.cfg.Protocol, addr)
	if err != nil {
		return fmt.Errorf("failed to bind syslog %s on %s: %w", c.cfg.Protocol, addr, err)
	}

	c.conn = conn
	c.parseFunc = c.parseRFC5424

	c.SetRunning(true)
	c.wg.Add(1)
	go c.receiveLoop(ctx)

	log.Printf("Syslog collector started: %s (%s://%s)", c.name, c.cfg.Protocol, addr)
	return nil
}

func (c *SyslogCollector) receiveLoop(ctx context.Context) {
	defer c.wg.Done()
	defer c.conn.Close()

	buf := make([]byte, 65536)

	for {
		select {
		case <-ctx.Done():
			return
		case <-c.stopCh:
			return
		default:
		}

		if err := c.conn.SetReadDeadline(time.Now().Add(1 * time.Second)); err != nil {
			continue
		}

		n, addr, err := c.conn.ReadFrom(buf)
		if err != nil {
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				continue
			}
			log.Printf("Syslog read error [%s]: %v", c.name, err)
			continue
		}

		msg := string(buf[:n])
		event := c.parseFunc(msg, addr)
		if event != nil {
			c.Emit(event)
		}
	}
}

func (c *SyslogCollector) parseRFC5424(msg string, addr net.Addr) *models.LogEvent {
	event := models.NewLogEvent()
	event.Source = models.SourceSyslog
	event.SourceID = c.cfg.Name
	event.RawMessage = msg
	event.Message = msg

	if addr != nil {
		event.Host = strings.Split(addr.String(), ":")[0]
	}

	parsed := c.parseSyslogMessage(msg)
	if parsed != nil {
		event.Timestamp = parsed.Timestamp
		event.Host = parsed.Hostname
		event.Message = parsed.Message
		event.Level = c.severityToLogLevel(parsed.Severity)

		if parsed.Tag != "" {
			event.ServiceName = parsed.Tag
			if event.Labels == nil {
				event.Labels = make(map[string]string)
			}
			event.Labels["syslog_tag"] = parsed.Tag
		}
		if parsed.ProcessID != "" {
			if event.Labels == nil {
				event.Labels = make(map[string]string)
			}
			event.Labels["syslog_pid"] = parsed.ProcessID
		}
		if event.ParsedFields == nil {
			event.ParsedFields = make(map[string]interface{})
		}
		event.ParsedFields["syslog_facility"] = parsed.Facility
		event.ParsedFields["syslog_priority"] = parsed.Priority
	}

	if event.Timestamp.IsZero() {
		event.Timestamp = time.Now()
	}

	c.extractFields(event)

	if event.Level == models.LevelUnknown {
		event.Level = c.detectLevel(event.Message)
	}

	return event
}

func (c *SyslogCollector) parseSyslogMessage(msg string) *SyslogMessage {
	if len(msg) == 0 || msg[0] != '<' {
		return nil
	}

	end := strings.IndexByte(msg, '>')
	if end == -1 || end > 5 {
		return nil
	}

	priority, err := strconv.Atoi(msg[1:end])
	if err != nil {
		return nil
	}

	facility := priority >> 3
	severity := priority & 7

	result := &SyslogMessage{
		Priority: priority,
		Facility: facility,
		Severity: severity,
	}

	remaining := msg[end+1:]

	if strings.HasPrefix(remaining, "1 ") {
		return c.parseRFC5424Format(remaining[2:], result)
	}

	return c.parseRFC3164Format(remaining, result)
}

func (c *SyslogCollector) parseRFC5424Format(remaining string, result *SyslogMessage) *SyslogMessage {
	parts := strings.SplitN(remaining, " ", 6)
	if len(parts) < 6 {
		return result
	}

	if ts, err := time.Parse(time.RFC3339Nano, parts[0]); err == nil {
		result.Timestamp = ts
	}
	result.Hostname = parts[1]
	result.Tag = parts[2]
	result.ProcessID = parts[3]
	result.Message = parts[5]

	return result
}

func (c *SyslogCollector) parseRFC3164Format(remaining string, result *SyslogMessage) *SyslogMessage {
	if len(remaining) < 15 {
		return result
	}

	tsStr := remaining[:15]
	if ts, err := time.ParseInLocation("Jan 2 15:04:05", tsStr, time.Local); err == nil {
		now := time.Now()
		ts = time.Date(now.Year(), ts.Month(), ts.Day(), ts.Hour(), ts.Minute(), ts.Second(), 0, ts.Location())
		result.Timestamp = ts
		remaining = remaining[16:]
	}

	parts := strings.SplitN(remaining, " ", 2)
	if len(parts) >= 1 {
		result.Hostname = parts[0]
	}
	if len(parts) >= 2 {
		remaining = parts[1]
		if idx := strings.Index(remaining, ": "); idx > 0 {
			tagPart := remaining[:idx]
			result.Message = remaining[idx+2:]

			if pidIdx := strings.Index(tagPart, "["); pidIdx > 0 {
				result.Tag = tagPart[:pidIdx]
				if endIdx := strings.Index(tagPart[pidIdx:], "]"); endIdx > 0 {
					result.ProcessID = tagPart[pidIdx+1 : pidIdx+endIdx]
				}
			} else {
				result.Tag = tagPart
			}
		} else {
			result.Message = remaining
		}
	}

	return result
}

func (c *SyslogCollector) severityToLogLevel(severity int) models.LogLevel {
	switch severity {
	case 0, 1, 2:
		return models.LevelFatal
	case 3:
		return models.LevelError
	case 4:
		return models.LevelWarn
	case 5, 6:
		return models.LevelInfo
	case 7:
		return models.LevelDebug
	default:
		return models.LevelUnknown
	}
}

func (c *SyslogCollector) extractFields(event *models.LogEvent) {
	msg := event.Message

	if idx := strings.Index(msg, "trace_id="); idx != -1 {
		rest := msg[idx+8:]
		if end := strings.IndexAny(rest, " \t\n,"); end != -1 {
			event.TraceID = rest[:end]
		} else {
			event.TraceID = rest
		}
	}

	if idx := strings.Index(msg, "traceId="); idx != -1 {
		rest := msg[idx+7:]
		if end := strings.IndexAny(rest, " \t\n,"); end != -1 {
			event.TraceID = rest[:end]
		} else {
			event.TraceID = rest
		}
	}

	if idx := strings.Index(msg, "span_id="); idx != -1 {
		rest := msg[idx+7:]
		if end := strings.IndexAny(rest, " \t\n,"); end != -1 {
			event.SpanID = rest[:end]
		} else {
			event.SpanID = rest
		}
	}

	if idx := strings.Index(msg, "error_code="); idx != -1 {
		rest := msg[idx+10:]
		if end := strings.IndexAny(rest, " \t\n,"); end != -1 {
			event.ErrorCode = rest[:end]
		} else {
			event.ErrorCode = rest
		}
	}

	if idx := strings.Index(msg, "user_id="); idx != -1 {
		rest := msg[idx+7:]
		if end := strings.IndexAny(rest, " \t\n,"); end != -1 {
			event.UserID = rest[:end]
		} else {
			event.UserID = rest
		}
	}

	if idx := strings.Index(msg, "client_ip="); idx != -1 {
		rest := msg[idx+10:]
		if end := strings.IndexAny(rest, " \t\n,"); end != -1 {
			event.ClientIP = rest[:end]
		} else {
			event.ClientIP = rest
		}
	}
}

func (c *SyslogCollector) detectLevel(message string) models.LogLevel {
	upper := strings.ToUpper(message)
	if strings.Contains(upper, "ERROR") || strings.Contains(upper, "ERR") {
		return models.LevelError
	}
	if strings.Contains(upper, "WARN") || strings.Contains(upper, "WARNING") {
		return models.LevelWarn
	}
	if strings.Contains(upper, "FATAL") || strings.Contains(upper, "CRITICAL") {
		return models.LevelFatal
	}
	if strings.Contains(upper, "DEBUG") {
		return models.LevelDebug
	}
	if strings.Contains(upper, "INFO") {
		return models.LevelInfo
	}
	return models.LevelUnknown
}

func (c *SyslogCollector) Stop() error {
	var err error
	if err = c.BaseCollector.Stop(); err != nil {
		return err
	}
	if c.conn != nil {
		err = c.conn.Close()
	}
	return err
}

func (c *SyslogCollector) StopOnce() *sync.Once {
	return &sync.Once{}
}
