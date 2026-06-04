package ingestor

import (
	"bufio"
	"context"
	"io"
	"net"
	"strings"
)

type ProtocolParser interface {
	Parse(raw string, source string, remoteAddr string) string
	Protocol() string
}

type TCPParser struct{}

func NewTCPParser() *TCPParser {
	return &TCPParser{}
}

func (p *TCPParser) Parse(raw string, source string, remoteAddr string) string {
	return strings.TrimSpace(raw)
}

func (p *TCPParser) Protocol() string {
	return "tcp"
}

func (p *TCPParser) ReadConnection(ctx context.Context, conn net.Conn, handler func(string)) {
	defer conn.Close()

	reader := bufio.NewReader(conn)
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		line, err := reader.ReadString('\n')
		if err != nil {
			if err != io.EOF {
			}
			return
		}

		handler(strings.TrimSpace(line))
	}
}

type UDPParser struct{}

func NewUDPParser() *UDPParser {
	return &UDPParser{}
}

func (p *UDPParser) Parse(raw string, source string, remoteAddr string) string {
	return strings.TrimSpace(raw)
}

func (p *UDPParser) Protocol() string {
	return "udp"
}

type HTTPParser struct{}

func NewHTTPParser() *HTTPParser {
	return &HTTPParser{}
}

func (p *HTTPParser) Parse(raw string, source string, remoteAddr string) string {
	return raw
}

func (p *HTTPParser) Protocol() string {
	return "http"
}

func NewProtocolParser(protocol string) ProtocolParser {
	switch strings.ToLower(protocol) {
	case "tcp":
		return NewTCPParser()
	case "udp":
		return NewUDPParser()
	case "http":
		return NewHTTPParser()
	default:
		return NewHTTPParser()
	}
}
