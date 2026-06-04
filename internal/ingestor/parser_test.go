package ingestor

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestNewTCPParser(t *testing.T) {
	p := NewTCPParser()
	assert.NotNil(t, p)
	assert.Equal(t, "tcp", p.Protocol())
}

func TestTCPParser_Parse_TrimSpace(t *testing.T) {
	p := NewTCPParser()
	result := p.Parse("  hello world  \n", "tcp", "10.0.0.1:12345")
	assert.Equal(t, "hello world", result)
}

func TestTCPParser_Parse_NoTrimNeeded(t *testing.T) {
	p := NewTCPParser()
	result := p.Parse("hello", "tcp", "10.0.0.1:12345")
	assert.Equal(t, "hello", result)
}

func TestNewUDPParser(t *testing.T) {
	p := NewUDPParser()
	assert.NotNil(t, p)
	assert.Equal(t, "udp", p.Protocol())
}

func TestUDPParser_Parse_TrimSpace(t *testing.T) {
	p := NewUDPParser()
	result := p.Parse("  hello world  ", "udp", "10.0.0.1:12345")
	assert.Equal(t, "hello world", result)
}

func TestNewHTTPParser(t *testing.T) {
	p := NewHTTPParser()
	assert.NotNil(t, p)
	assert.Equal(t, "http", p.Protocol())
}

func TestHTTPParser_Parse_NoModification(t *testing.T) {
	p := NewHTTPParser()
	result := p.Parse("  hello world  ", "http", "10.0.0.1:12345")
	assert.Equal(t, "  hello world  ", result, "HTTP parser should not trim")
}

func TestNewProtocolParser_TCP(t *testing.T) {
	p := NewProtocolParser("tcp")
	_, ok := p.(*TCPParser)
	assert.True(t, ok, "should return TCPParser for 'tcp'")
}

func TestNewProtocolParser_UDP(t *testing.T) {
	p := NewProtocolParser("udp")
	_, ok := p.(*UDPParser)
	assert.True(t, ok, "should return UDPParser for 'udp'")
}

func TestNewProtocolParser_HTTP(t *testing.T) {
	p := NewProtocolParser("http")
	_, ok := p.(*HTTPParser)
	assert.True(t, ok, "should return HTTPParser for 'http'")
}

func TestNewProtocolParser_CaseInsensitive(t *testing.T) {
	p := NewProtocolParser("TCP")
	_, ok := p.(*TCPParser)
	assert.True(t, ok, "should return TCPParser for 'TCP'")

	p = NewProtocolParser("Udp")
	_, ok = p.(*UDPParser)
	assert.True(t, ok, "should return UDPParser for 'Udp'")
}

func TestNewProtocolParser_Unknown(t *testing.T) {
	p := NewProtocolParser("unknown")
	_, ok := p.(*HTTPParser)
	assert.True(t, ok, "should return HTTPParser as default for unknown protocol")
}

func TestIngestor_GetParser(t *testing.T) {
	cfg := testIngestorConfig()
	i := NewIngestor(cfg)

	tcpParser := i.GetParser("tcp")
	assert.NotNil(t, tcpParser, "should have TCP parser")
	assert.Equal(t, "tcp", tcpParser.Protocol())

	udpParser := i.GetParser("udp")
	assert.NotNil(t, udpParser, "should have UDP parser")
	assert.Equal(t, "udp", udpParser.Protocol())

	httpParser := i.GetParser("http")
	assert.NotNil(t, httpParser, "should have HTTP parser")
	assert.Equal(t, "http", httpParser.Protocol())
}

func TestIngestor_GetParser_NotConfigured(t *testing.T) {
	cfg := testIngestorConfig()
	cfg.Sources = []string{"http"}
	i := NewIngestor(cfg)

	tcpParser := i.GetParser("tcp")
	assert.Nil(t, tcpParser, "should not have TCP parser when not configured")
}
