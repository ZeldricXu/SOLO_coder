package router

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"

	"DF1-56/internal/models"
)

type ProtocolConverter struct {
	jsonpbMarshaller   func(interface{}) ([]byte, error)
	jsonpbUnmarshaller func([]byte, interface{}) error
}

type GRPCRequest struct {
	Service string                 `json:"service"`
	Method  string                 `json:"method"`
	Message map[string]interface{} `json:"message"`
}

type GRPCResponse struct {
	Message map[string]interface{} `json:"message"`
	Status  int                    `json:"status"`
	Error   string                 `json:"error,omitempty"`
}

func NewProtocolConverter() *ProtocolConverter {
	return &ProtocolConverter{}
}

func (c *ProtocolConverter) HTTPToGRPC(ctx *models.GatewayContext) error {
	if ctx == nil {
		return fmt.Errorf("gateway context cannot be nil")
	}
	if ctx.Request == nil {
		return fmt.Errorf("HTTP request cannot be nil")
	}

	bodyBytes, err := io.ReadAll(ctx.Request.Body)
	if err != nil {
		return fmt.Errorf("failed to read request body: %w", err)
	}
	defer ctx.Request.Body.Close()

	grpcReq, err := c.convertHTTPBodyToGRPC(bodyBytes, ctx.Request)
	if err != nil {
		return fmt.Errorf("failed to convert HTTP body to gRPC: %w", err)
	}

	grpcBytes, err := json.Marshal(grpcReq)
	if err != nil {
		return fmt.Errorf("failed to marshal gRPC request: %w", err)
	}

	ctx.Request.Body = io.NopCloser(bytes.NewReader(grpcBytes))
	ctx.Request.ContentLength = int64(len(grpcBytes))
	ctx.Request.Header.Set("Content-Type", "application/grpc+json")
	ctx.Request.Header.Set("X-Protocol-Converted", "http-to-grpc")

	ctx.Set("original_content_type", ctx.Request.Header.Get("Content-Type"))
	ctx.Set("original_body", bodyBytes)

	return nil
}

func (c *ProtocolConverter) GRPCToHTTP(ctx *models.GatewayContext) error {
	if ctx == nil {
		return fmt.Errorf("gateway context cannot be nil")
	}
	if ctx.Request == nil {
		return fmt.Errorf("HTTP request cannot be nil")
	}

	bodyBytes, err := io.ReadAll(ctx.Request.Body)
	if err != nil {
		return fmt.Errorf("failed to read gRPC request body: %w", err)
	}
	defer ctx.Request.Body.Close()

	var grpcReq GRPCRequest
	if err := json.Unmarshal(bodyBytes, &grpcReq); err != nil {
		return fmt.Errorf("failed to unmarshal gRPC request: %w", err)
	}

	httpBody, err := json.Marshal(grpcReq.Message)
	if err != nil {
		return fmt.Errorf("failed to marshal HTTP body: %w", err)
	}

	ctx.Request.Body = io.NopCloser(bytes.NewReader(httpBody))
	ctx.Request.ContentLength = int64(len(httpBody))
	ctx.Request.Header.Set("Content-Type", "application/json")
	ctx.Request.Header.Set("X-Protocol-Converted", "grpc-to-http")

	ctx.Set("grpc_service", grpcReq.Service)
	ctx.Set("grpc_method", grpcReq.Method)

	return nil
}

func (c *ProtocolConverter) convertHTTPBodyToGRPC(body []byte, req *http.Request) (*GRPCRequest, error) {
	contentType := req.Header.Get("Content-Type")

	var message map[string]interface{}

	if len(body) > 0 {
		if strings.Contains(contentType, "application/json") {
			if err := json.Unmarshal(body, &message); err != nil {
				return nil, fmt.Errorf("failed to unmarshal JSON body: %w", err)
			}
		} else if strings.Contains(contentType, "application/x-www-form-urlencoded") {
			values, err := parseFormData(body)
			if err != nil {
				return nil, fmt.Errorf("failed to parse form data: %w", err)
			}
			message = values
		} else {
			message = make(map[string]interface{})
			message["raw_body"] = string(body)
		}
	} else {
		message = make(map[string]interface{})
	}

	queryParams := make(map[string]interface{})
	for key, values := range req.URL.Query() {
		if len(values) == 1 {
			queryParams[key] = values[0]
		} else {
			queryParams[key] = values
		}
	}
	if len(queryParams) > 0 {
		message["query_params"] = queryParams
	}

	for key, values := range req.Header {
		if strings.HasPrefix(key, "X-Grpc-") {
			grpcKey := strings.TrimPrefix(key, "X-Grpc-")
			if len(values) == 1 {
				message[strings.ToLower(grpcKey)] = values[0]
			} else {
				message[strings.ToLower(grpcKey)] = values
			}
		}
	}

	pathParts := strings.Split(strings.Trim(req.URL.Path, "/"), "/")
	service := ""
	method := ""

	if len(pathParts) >= 2 {
		service = pathParts[len(pathParts)-2]
		method = pathParts[len(pathParts)-1]
	} else if len(pathParts) == 1 {
		method = pathParts[0]
	}

	if req.Header.Get("X-Grpc-Service") != "" {
		service = req.Header.Get("X-Grpc-Service")
	}
	if req.Header.Get("X-Grpc-Method") != "" {
		method = req.Header.Get("X-Grpc-Method")
	}

	return &GRPCRequest{
		Service: service,
		Method:  method,
		Message: message,
	}, nil
}

func (c *ProtocolConverter) ConvertGRPCResponseToHTTP(ctx *models.GatewayContext, grpcResp []byte) ([]byte, error) {
	var grpcResponse GRPCResponse
	if err := json.Unmarshal(grpcResp, &grpcResponse); err != nil {
		return nil, fmt.Errorf("failed to unmarshal gRPC response: %w", err)
	}

	if grpcResponse.Error != "" {
		errorResp := map[string]interface{}{
			"error": grpcResponse.Error,
			"code":  grpcResponse.Status,
		}
		return json.Marshal(errorResp)
	}

	return json.Marshal(grpcResponse.Message)
}

func (c *ProtocolConverter) ConvertHTTPResponseToGRPC(ctx *models.GatewayContext, httpResp []byte, statusCode int) ([]byte, error) {
	grpcResp := GRPCResponse{
		Status: statusCode,
	}

	if len(httpResp) > 0 {
		var message map[string]interface{}
		if err := json.Unmarshal(httpResp, &message); err != nil {
			grpcResp.Message = map[string]interface{}{
				"raw_response": string(httpResp),
			}
		} else {
			grpcResp.Message = message
		}
	}

	if statusCode >= 400 {
		if grpcResp.Message != nil {
			if errMsg, ok := grpcResp.Message["error"].(string); ok {
				grpcResp.Error = errMsg
			}
		}
		if grpcResp.Error == "" {
			grpcResp.Error = http.StatusText(statusCode)
		}
	}

	return json.Marshal(grpcResp)
}

func (c *ProtocolConverter) DetectConversion(ctx *models.GatewayContext) (from, to models.ProtocolType, needConvert bool) {
	if ctx == nil || ctx.Route == nil {
		return "", "", false
	}

	requestProtocol := c.detectRequestProtocol(ctx.Request)
	targetProtocol := ctx.Route.Protocol

	if requestProtocol != targetProtocol {
		return requestProtocol, targetProtocol, true
	}

	return requestProtocol, targetProtocol, false
}

func (c *ProtocolConverter) detectRequestProtocol(req *http.Request) models.ProtocolType {
	if req == nil {
		return models.ProtocolHTTP
	}

	contentType := req.Header.Get("Content-Type")
	if strings.Contains(contentType, "application/grpc") {
		return models.ProtocolGRPC
	}

	if req.ProtoMajor == 2 && req.TLS != nil {
		return models.ProtocolHTTP2
	}

	return models.ProtocolHTTP
}

func (c *ProtocolConverter) AutoConvert(ctx *models.GatewayContext) error {
	from, to, needConvert := c.DetectConversion(ctx)
	if !needConvert {
		return nil
	}

	switch {
	case from == models.ProtocolHTTP && to == models.ProtocolGRPC:
		return c.HTTPToGRPC(ctx)
	case from == models.ProtocolGRPC && to == models.ProtocolHTTP:
		return c.GRPCToHTTP(ctx)
	default:
		return fmt.Errorf("unsupported protocol conversion: %s -> %s", from, to)
	}
}

func parseFormData(body []byte) (map[string]interface{}, error) {
	result := make(map[string]interface{})

	bodyStr := string(body)
	pairs := strings.Split(bodyStr, "&")

	for _, pair := range pairs {
		if pair == "" {
			continue
		}

		parts := strings.SplitN(pair, "=", 2)
		if len(parts) != 2 {
			continue
		}

		key := parts[0]
		value := parts[1]

		if strings.Contains(key, "%") {
			decodedKey, err := urlDecode(key)
			if err == nil {
				key = decodedKey
			}
		}

		decodedValue, err := urlDecode(value)
		if err == nil {
			value = decodedValue
		}

		if existing, ok := result[key]; ok {
			switch v := existing.(type) {
			case []string:
				result[key] = append(v, value)
			case string:
				result[key] = []string{v, value}
			}
		} else {
			result[key] = value
		}
	}

	return result, nil
}

func urlDecode(s string) (string, error) {
	var buf bytes.Buffer
	for i := 0; i < len(s); i++ {
		switch s[i] {
		case '+':
			buf.WriteByte(' ')
		case '%':
			if i+2 >= len(s) {
				return "", fmt.Errorf("invalid URL encoding")
			}
			b, err := hexToByte(s[i+1], s[i+2])
			if err != nil {
				return "", err
			}
			buf.WriteByte(b)
			i += 2
		default:
			buf.WriteByte(s[i])
		}
	}
	return buf.String(), nil
}

func hexToByte(a, b byte) (byte, error) {
	ha, ok := hexValue(a)
	if !ok {
		return 0, fmt.Errorf("invalid hex character: %c", a)
	}
	hb, ok := hexValue(b)
	if !ok {
		return 0, fmt.Errorf("invalid hex character: %c", b)
	}
	return ha<<4 | hb, nil
}

func hexValue(c byte) (byte, bool) {
	switch {
	case '0' <= c && c <= '9':
		return c - '0', true
	case 'a' <= c && c <= 'f':
		return c - 'a' + 10, true
	case 'A' <= c && c <= 'F':
		return c - 'A' + 10, true
	default:
		return 0, false
	}
}
