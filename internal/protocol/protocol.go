package protocol

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

type ProtocolType string

const (
	ProtocolHTTP   ProtocolType = "http"
	ProtocolHTTPS  ProtocolType = "https"
	ProtocolSOCKS5 ProtocolType = "socks5"
	ProtocolUnknown ProtocolType = "unknown"
)

const (
	socks5Version = 0x05
	socks5AuthNoPassword = 0x00
	socks5AuthUsernamePassword = 0x02
	socks5AuthNoAcceptable = 0xFF
	socks5CmdConnect = 0x01
	socks5AddrTypeIPv4 = 0x01
	socks5AddrTypeDomain = 0x03
	socks5AddrTypeIPv6 = 0x04
	socks5ReplySucceeded = 0x00
	socks5ReplyGeneralFailure = 0x01
	socks5ReplyConnectionNotAllowed = 0x02
	socks5ReplyNetworkUnreachable = 0x03
	socks5ReplyHostUnreachable = 0x04
	socks5ReplyConnectionRefused = 0x05
	socks5ReplyTTLExpired = 0x06
	socks5ReplyCommandNotSupported = 0x07
	socks5ReplyAddrTypeNotSupported = 0x08
)

type ProxyRequest struct {
	Protocol     ProtocolType
	TargetHost   string
	TargetPort   int
	Method       string
	Path         string
	HTTPVersion  string
	Headers      http.Header
	Body         []byte
	RawRequest   []byte
	IsConnect    bool
}

type ProtocolParser struct{}

var (
	ErrInvalidProtocol    = errors.New("invalid protocol")
	ErrInvalidRequest     = errors.New("invalid request")
	ErrUnsupportedVersion = errors.New("unsupported protocol version")
	ErrUnsupportedCommand = errors.New("unsupported command")
	ErrUnsupportedAuth    = errors.New("unsupported authentication method")
	ErrInvalidAddressType = errors.New("invalid address type")
)

func NewProtocolParser() *ProtocolParser {
	return &ProtocolParser{}
}

func (p *ProtocolParser) DetectProtocol(reader *bufio.Reader) (ProtocolType, error) {
	buf, err := reader.Peek(4)
	if err != nil {
		return ProtocolUnknown, err
	}

	if buf[0] == socks5Version {
		return ProtocolSOCKS5, nil
	}

	if len(buf) >= 3 {
		method := strings.ToUpper(string(buf[:3]))
		if method == "GET" || method == "POS" || method == "PUT" || method == "DEL" ||
			method == "HEA" || method == "OPT" || method == "PAT" || method == "TRA" ||
			method == "CON" {
			return ProtocolHTTP, nil
		}
	}

	return ProtocolUnknown, ErrInvalidProtocol
}

func (p *ProtocolParser) Parse(reader *bufio.Reader) (*ProxyRequest, error) {
	protocol, err := p.DetectProtocol(reader)
	if err != nil {
		return nil, err
	}

	switch protocol {
	case ProtocolHTTP:
		return p.parseHTTP(reader)
	case ProtocolSOCKS5:
		return p.parseSOCKS5(reader)
	default:
		return nil, ErrInvalidProtocol
	}
}

func (p *ProtocolParser) parseHTTP(reader *bufio.Reader) (*ProxyRequest, error) {
	req, err := http.ReadRequest(reader)
	if err != nil {
		return nil, fmt.Errorf("failed to parse HTTP request: %w", err)
	}

	proxyReq := &ProxyRequest{
		Protocol:    ProtocolHTTP,
		Method:      req.Method,
		Path:        req.URL.Path,
		HTTPVersion: fmt.Sprintf("%d.%d", req.ProtoMajor, req.ProtoMinor),
		Headers:     req.Header,
		IsConnect:   req.Method == http.MethodConnect,
	}

	if req.Method == http.MethodConnect {
		proxyReq.Protocol = ProtocolHTTPS
		hostPort := req.Host
		host, portStr, err := net.SplitHostPort(hostPort)
		if err != nil {
			if strings.Contains(hostPort, ":") {
				return nil, err
			}
			proxyReq.TargetHost = hostPort
			proxyReq.TargetPort = 443
		} else {
			proxyReq.TargetHost = host
			port, err := strconv.Atoi(portStr)
			if err != nil {
				return nil, fmt.Errorf("invalid port: %w", err)
			}
			proxyReq.TargetPort = port
		}
	} else {
		if req.URL.Host != "" {
			host, portStr, err := net.SplitHostPort(req.URL.Host)
			if err != nil {
				if strings.Contains(req.URL.Host, ":") {
					return nil, err
				}
				proxyReq.TargetHost = req.URL.Host
				if req.URL.Scheme == "https" {
					proxyReq.TargetPort = 443
				} else {
					proxyReq.TargetPort = 80
				}
			} else {
				proxyReq.TargetHost = host
				port, err := strconv.Atoi(portStr)
				if err != nil {
					return nil, fmt.Errorf("invalid port: %w", err)
				}
				proxyReq.TargetPort = port
			}
		} else if req.Host != "" {
			host, portStr, err := net.SplitHostPort(req.Host)
			if err != nil {
				if strings.Contains(req.Host, ":") {
					return nil, err
				}
				proxyReq.TargetHost = req.Host
				proxyReq.TargetPort = 80
			} else {
				proxyReq.TargetHost = host
				port, err := strconv.Atoi(portStr)
				if err != nil {
					return nil, fmt.Errorf("invalid port: %w", err)
				}
				proxyReq.TargetPort = port
			}
		} else {
			return nil, errors.New("unable to determine target host")
		}

		if req.Body != nil {
			body, err := io.ReadAll(req.Body)
			if err != nil {
				return nil, fmt.Errorf("failed to read request body: %w", err)
			}
			proxyReq.Body = body
		}
	}

	return proxyReq, nil
}

func (p *ProtocolParser) parseSOCKS5(reader *bufio.Reader) (*ProxyRequest, error) {
	version, err := reader.ReadByte()
	if err != nil {
		return nil, err
	}
	if version != socks5Version {
		return nil, ErrUnsupportedVersion
	}

	nmethods, err := reader.ReadByte()
	if err != nil {
		return nil, err
	}

	methods := make([]byte, nmethods)
	if _, err := io.ReadFull(reader, methods); err != nil {
		return nil, err
	}

	authMethod := socks5AuthNoPassword
	for _, m := range methods {
		if m == socks5AuthNoPassword {
			authMethod = socks5AuthNoPassword
			break
		}
	}

	proxyReq := &ProxyRequest{
		Protocol: ProtocolSOCKS5,
	}

	if authMethod == socks5AuthNoPassword {
		return proxyReq, nil
	}

	return nil, ErrUnsupportedAuth
}

func (p *ProtocolParser) CompleteSOCKS5Handshake(conn net.Conn, req *ProxyRequest) error {
	_, err := conn.Write([]byte{socks5Version, socks5AuthNoPassword})
	if err != nil {
		return err
	}

	version, err := readByte(conn)
	if err != nil {
		return err
	}
	if version != socks5Version {
		return ErrUnsupportedVersion
	}

	cmd, err := readByte(conn)
	if err != nil {
		return err
	}
	if cmd != socks5CmdConnect {
		writeSOCKS5Reply(conn, socks5ReplyCommandNotSupported, "", 0)
		return ErrUnsupportedCommand
	}

	_, err = readByte(conn)
	if err != nil {
		return err
	}

	addrType, err := readByte(conn)
	if err != nil {
		return err
	}

	var targetHost string
	var targetPort int

	switch addrType {
	case socks5AddrTypeIPv4:
		ip := make([]byte, 4)
		if _, err := io.ReadFull(conn, ip); err != nil {
			return err
		}
		targetHost = net.IP(ip).String()

	case socks5AddrTypeDomain:
		length, err := readByte(conn)
		if err != nil {
			return err
		}
		domain := make([]byte, length)
		if _, err := io.ReadFull(conn, domain); err != nil {
			return err
		}
		targetHost = string(domain)

	case socks5AddrTypeIPv6:
		ip := make([]byte, 16)
		if _, err := io.ReadFull(conn, ip); err != nil {
			return err
		}
		targetHost = net.IP(ip).String()

	default:
		writeSOCKS5Reply(conn, socks5ReplyAddrTypeNotSupported, "", 0)
		return ErrInvalidAddressType
	}

	portBytes := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBytes); err != nil {
		return err
	}
	targetPort = int(binary.BigEndian.Uint16(portBytes))

	req.TargetHost = targetHost
	req.TargetPort = targetPort

	return nil
}

func (p *ProtocolParser) SendSOCKS5SuccessReply(conn net.Conn, boundAddr net.Addr) error {
	host, portStr, err := net.SplitHostPort(boundAddr.String())
	if err != nil {
		return err
	}
	port, _ := strconv.Atoi(portStr)
	return writeSOCKS5Reply(conn, socks5ReplySucceeded, host, port)
}

func (p *ProtocolParser) SendSOCKS5FailureReply(conn net.Conn, replyCode byte) error {
	return writeSOCKS5Reply(conn, replyCode, "", 0)
}

func (p *ProtocolParser) BuildHTTPRequest(req *ProxyRequest) *http.Request {
	httpReq := &http.Request{
		Method: req.Method,
		URL: &url.URL{
			Scheme: "http",
			Host:   fmt.Sprintf("%s:%d", req.TargetHost, req.TargetPort),
			Path:   req.Path,
		},
		Proto:      req.HTTPVersion,
		ProtoMajor: 1,
		ProtoMinor: 1,
		Header:     req.Headers,
		Body:       io.NopCloser(bytes.NewReader(req.Body)),
		Host:       fmt.Sprintf("%s:%d", req.TargetHost, req.TargetPort),
	}
	return httpReq
}

func (p *ProtocolParser) SerializeHTTPRequest(req *ProxyRequest) []byte {
	var buf bytes.Buffer

	buf.WriteString(fmt.Sprintf("%s %s HTTP/%s\r\n", req.Method, req.Path, req.HTTPVersion))
	buf.WriteString(fmt.Sprintf("Host: %s:%d\r\n", req.TargetHost, req.TargetPort))

	for key, values := range req.Headers {
		for _, value := range values {
			buf.WriteString(fmt.Sprintf("%s: %s\r\n", key, value))
		}
	}
	buf.WriteString("\r\n")

	if len(req.Body) > 0 {
		buf.Write(req.Body)
	}

	return buf.Bytes()
}

func readByte(reader io.Reader) (byte, error) {
	buf := make([]byte, 1)
	_, err := io.ReadFull(reader, buf)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func writeSOCKS5Reply(conn net.Conn, replyCode byte, host string, port int) error {
	var buf bytes.Buffer
	buf.WriteByte(socks5Version)
	buf.WriteByte(replyCode)
	buf.WriteByte(0x00)

	ip := net.ParseIP(host)
	if ip != nil {
		if ip.To4() != nil {
			buf.WriteByte(socks5AddrTypeIPv4)
			buf.Write(ip.To4())
		} else {
			buf.WriteByte(socks5AddrTypeIPv6)
			buf.Write(ip.To16())
		}
	} else {
		buf.WriteByte(socks5AddrTypeDomain)
		buf.WriteByte(byte(len(host)))
		buf.WriteString(host)
	}

	portBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(portBytes, uint16(port))
	buf.Write(portBytes)

	_, err := conn.Write(buf.Bytes())
	return err
}

func DetectProtocol(reader *bufio.Reader) (ProtocolType, error) {
	return NewProtocolParser().DetectProtocol(reader)
}

func Parse(reader *bufio.Reader) (*ProxyRequest, error) {
	return NewProtocolParser().Parse(reader)
}

func BuildHTTPRequest(req *ProxyRequest) *http.Request {
	return NewProtocolParser().BuildHTTPRequest(req)
}

func SerializeHTTPRequest(req *ProxyRequest) []byte {
	return NewProtocolParser().SerializeHTTPRequest(req)
}
