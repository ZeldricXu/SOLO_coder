package rest

import (
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"golang.org/x/net/http2"
)

type Request struct {
	Method  string
	URL     string
	Headers map[string]string
	Body    string
	Timeout int
}

type Response struct {
	StatusCode int
	Status     string
	Headers    map[string][]string
	Body       string
	Duration   time.Duration
	Proto      string
}

type Client struct {
	httpClient     *http.Client
	baseURL        string
	defaultHeaders map[string]string
}

func NewClient(baseURL string, defaultHeaders map[string]string, timeout int) *Client {
	transport := &http.Transport{
		DialContext: (&net.Dialer{
			Timeout: time.Duration(timeout) * time.Second,
		}).DialContext,
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: false,
		},
		ForceAttemptHTTP2: true,
	}
	http2.ConfigureTransports(transport)

	client := &http.Client{
		Transport: transport,
		Timeout:   time.Duration(timeout) * time.Second,
	}

	return &Client{
		httpClient:     client,
		baseURL:        strings.TrimRight(baseURL, "/"),
		defaultHeaders: defaultHeaders,
	}
}

func (c *Client) Do(req *Request) (*Response, error) {
	var bodyReader io.Reader
	if req.Body != "" {
		bodyReader = strings.NewReader(req.Body)
	}

	fullURL := req.URL
	if !strings.HasPrefix(req.URL, "http://") && !strings.HasPrefix(req.URL, "https://") {
		fullURL = c.baseURL + "/" + strings.TrimLeft(req.URL, "/")
	}

	httpReq, err := http.NewRequest(req.Method, fullURL, bodyReader)
	if err != nil {
		return nil, fmt.Errorf("creating request: %w", err)
	}

	for k, v := range c.defaultHeaders {
		httpReq.Header.Set(k, v)
	}
	for k, v := range req.Headers {
		httpReq.Header.Set(k, v)
	}

	start := time.Now()
	httpResp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("executing request: %w", err)
	}
	duration := time.Since(start)
	defer httpResp.Body.Close()

	bodyBytes, err := io.ReadAll(httpResp.Body)
	if err != nil {
		return nil, fmt.Errorf("reading response body: %w", err)
	}

	respHeaders := make(map[string][]string)
	for k, v := range httpResp.Header {
		respHeaders[k] = v
	}

	return &Response{
		StatusCode: httpResp.StatusCode,
		Status:     httpResp.Status,
		Headers:    respHeaders,
		Body:       string(bodyBytes),
		Duration:   duration,
		Proto:      httpResp.Proto,
	}, nil
}

func (c *Client) Get(url string, headers map[string]string) (*Response, error) {
	return c.Do(&Request{
		Method:  http.MethodGet,
		URL:     url,
		Headers: headers,
	})
}

func (c *Client) Post(url, body string, headers map[string]string) (*Response, error) {
	return c.Do(&Request{
		Method:  http.MethodPost,
		URL:     url,
		Headers: headers,
		Body:    body,
	})
}

func (c *Client) Put(url, body string, headers map[string]string) (*Response, error) {
	return c.Do(&Request{
		Method:  http.MethodPut,
		URL:     url,
		Headers: headers,
		Body:    body,
	})
}

func (c *Client) Delete(url string, headers map[string]string) (*Response, error) {
	return c.Do(&Request{
		Method:  http.MethodDelete,
		URL:     url,
		Headers: headers,
	})
}

func (c *Client) Patch(url, body string, headers map[string]string) (*Response, error) {
	return c.Do(&Request{
		Method:  http.MethodPatch,
		URL:     url,
		Headers: headers,
		Body:    body,
	})
}
