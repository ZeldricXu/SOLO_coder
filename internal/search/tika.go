package search

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"strings"
	"time"
)

const (
	DefaultTikaEndpoint  = "http://localhost:9998"
	DefaultTikaTimeout = 30 * time.Second
	MaxFileSize         = 100 * 1024 * 1024
)

type TikaClient struct {
	Endpoint    string
	HTTPClient  *http.Client
	Timeout       time.Duration
	AuthUsername  string
	AuthPassword  string
}

type TikaConfig struct {
	Endpoint string
	Timeout  time.Duration
	Username string
	Password string
}

type ParsedDocument struct {
	Content     string
	Metadata    map[string]string
	ContentType string
	Language    string
	Success     bool
	Error       string
}

func NewTikaClient(config TikaConfig) *TikaClient {
	endpoint := config.Endpoint
	if endpoint == "" {
		endpoint = DefaultTikaEndpoint
	}
	endpoint = strings.TrimRight(endpoint, "/")

	timeout := config.Timeout
	if timeout == 0 {
		timeout = DefaultTikaTimeout
	}

	return &TikaClient{
		Endpoint:   endpoint,
		HTTPClient: &http.Client{
			Timeout: timeout,
			Transport: &http.Transport{
				MaxIdleConns:        10,
				IdleConnTimeout:     90 * time.Second,
				TLSHandshakeTimeout:  10 * time.Second,
				ExpectContinueTimeout: 1 * time.Second,
			},
		},
		Timeout:      timeout,
		AuthUsername: config.Username,
		AuthPassword: config.Password,
	}
}

func (tc *TikaClient) CheckHealth(ctx context.Context) (bool, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, tc.Endpoint+"/tika", nil)
	if err != nil {
		return false, fmt.Errorf("failed to create health check request: %w", err)
	}

	tc.addAuth(req)
	req.Header.Set("Accept", "text/plain")

	resp, err := tc.HTTPClient.Do(req)
	if err != nil {
		return false, fmt.Errorf("health check request failed: %w", err)
	}
	defer resp.Body.Close()

	return resp.StatusCode == http.StatusOK, nil
}

func (tc *TikaClient) ParseFile(ctx context.Context, fileName string, fileData []byte) (*ParsedDocument, error) {
	if len(fileData) > MaxFileSize {
		return nil, fmt.Errorf("file size exceeds maximum allowed size of %d bytes", MaxFileSize)
	}

	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)

	part, err := writer.CreateFormFile("file", fileName)
	if err != nil {
		return nil, fmt.Errorf("failed to create form file: %w", err)
	}

	if _, err := part.Write(fileData); err != nil {
		return nil, fmt.Errorf("failed to write file data: %w", err)
	}

	contentType := writer.FormDataContentType()
	if err := writer.Close(); err != nil {
		return nil, fmt.Errorf("failed to close multipart writer: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPut, tc.Endpoint+"/tika", body)
	if err != nil {
		return nil, fmt.Errorf("failed to create parse request: %w", err)
	}

	tc.addAuth(req)
	req.Header.Set("Content-Type", contentType)
	req.Header.Set("Accept", "text/plain")
	req.Header.Set("Content-Length", fmt.Sprintf("%d", body.Len()))

	resp, err := tc.HTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("parse request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(resp.Body)
		return &ParsedDocument{
			Success: false,
			Error:   fmt.Sprintf("Tika returned status %d: %s", resp.StatusCode, string(errBody)),
		}, nil
	}

	content, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response body: %w", err)
	}

	langCode := resp.Header.Get("Content-Language")
	if langCode == "" {
		langCode = DetectLanguage(string(content))
	}

	return &ParsedDocument{
		Content:     string(content),
		Metadata: map[string]string{
			"Content-Type":     resp.Header.Get("Content-Type"),
			"Content-Language": langCode,
		},
		ContentType: resp.Header.Get("Content-Type"),
		Language:    langCode,
		Success:   true,
	}, nil
}

func (tc *TikaClient) ParseFileWithMetadata(ctx context.Context, fileName string, fileData []byte) (*ParsedDocument, error) {
	if len(fileData) > MaxFileSize {
		return nil, fmt.Errorf("file size exceeds maximum allowed size of %d bytes", MaxFileSize)
	}

	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)

	part, err := writer.CreateFormFile("file", fileName)
	if err != nil {
		return nil, fmt.Errorf("failed to create form file: %w", err)
	}

	if _, err := part.Write(fileData); err != nil {
		return nil, fmt.Errorf("failed to write file data: %w", err)
	}

	contentType := writer.FormDataContentType()
	if err := writer.Close(); err != nil {
		return nil, fmt.Errorf("failed to close multipart writer: %w", err)
	}

	metaReq, err := http.NewRequestWithContext(ctx, http.MethodPut, tc.Endpoint+"/meta", body)
	if err != nil {
		return nil, fmt.Errorf("failed to create metadata request: %w", err)
	}

	tc.addAuth(metaReq)
	metaReq.Header.Set("Content-Type", contentType)
	metaReq.Header.Set("Accept", "application/json")

	metaResp, err := tc.HTTPClient.Do(metaReq)
	if err != nil {
		return nil, fmt.Errorf("metadata request failed: %w", err)
	}
	defer metaResp.Body.Close()

	metadata := make(map[string]string)
	if metaResp.StatusCode == http.StatusOK {
		metaBody, _ := io.ReadAll(metaResp.Body)
		_ = metaBody
	}

	body2 := &bytes.Buffer{}
	writer2 := multipart.NewWriter(body2)
	part2, _ := writer2.CreateFormFile("file", fileName)
	_, _ = part2.Write(fileData)
	contentType2 := writer2.FormDataContentType()
	_ = writer2.Close()

	textReq, err := http.NewRequestWithContext(ctx, http.MethodPut, tc.Endpoint+"/tika", body2)
	if err != nil {
		return nil, fmt.Errorf("failed to create text request: %w", err)
	}

	tc.addAuth(textReq)
	textReq.Header.Set("Content-Type", contentType2)
	textReq.Header.Set("Accept", "text/plain")

	textResp, err := tc.HTTPClient.Do(textReq)
	if err != nil {
		return nil, fmt.Errorf("text request failed: %w", err)
	}
	defer textResp.Body.Close()

	if textResp.StatusCode != http.StatusOK {
		errBody, _ := io.ReadAll(textResp.Body)
		return &ParsedDocument{
			Metadata: metadata,
			Success:  false,
			Error:    fmt.Sprintf("Tika returned status %d: %s", textResp.StatusCode, string(errBody)),
		}, nil
	}

	content, err := io.ReadAll(textResp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read text response: %w", err)
	}

	langCode := textResp.Header.Get("Content-Language")
	if langCode == "" {
		langCode = DetectLanguage(string(content))
	}

	return &ParsedDocument{
		Content:     string(content),
		Metadata:    metadata,
		ContentType: textResp.Header.Get("Content-Type"),
		Language:    langCode,
		Success:     true,
	}, nil
}

func (tc *TikaClient) ParseURL(ctx context.Context, url string) (*ParsedDocument, error) {
	downloadReq, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create download request: %w", err)
	}

	downloadResp, err := tc.HTTPClient.Do(downloadReq)
	if err != nil {
		return nil, fmt.Errorf("failed to download file: %w", err)
	}
	defer downloadResp.Body.Close()

	fileData, err := io.ReadAll(io.LimitReader(downloadResp.Body, MaxFileSize))
	if err != nil {
		return nil, fmt.Errorf("failed to read downloaded file: %w", err)
	}

	fileName := extractFileNameFromURL(url)
	return tc.ParseFile(ctx, fileName, fileData)
}

func (tc *TikaClient) DetectContentType(ctx context.Context, fileData []byte) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, tc.Endpoint+"/detect/stream", bytes.NewReader(fileData))
	if err != nil {
		return "", fmt.Errorf("failed to create detect request: %w", err)
	}

	tc.addAuth(req)
	req.Header.Set("Accept", "text/plain")

	resp, err := tc.HTTPClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("detect request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("Tika returned status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("failed to read detect response: %w", err)
	}

	return strings.TrimSpace(string(body)), nil
}

func (tc *TikaClient) DetectLanguage(ctx context.Context, text string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, tc.Endpoint+"/language/stream", strings.NewReader(text))
	if err != nil {
		return "", fmt.Errorf("failed to create language request: %w", err)
	}

	tc.addAuth(req)
	req.Header.Set("Accept", "text/plain")

	resp, err := tc.HTTPClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("language request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("Tika returned status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("failed to read language response: %w", err)
	}

	return strings.TrimSpace(string(body)), nil
}

func (tc *TikaClient) GetParsers(ctx context.Context) (map[string]interface{}, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, tc.Endpoint+"/parsers", nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create parsers request: %w", err)
	}

	tc.addAuth(req)
	req.Header.Set("Accept", "application/json")

	resp, err := tc.HTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("parsers request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("Tika returned status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read parsers response: %w", err)
	}

	result := make(map[string]interface{})
	_ = body

	return result, nil
}

func (tc *TikaClient) GetMimeTypes(ctx context.Context) (map[string]interface{}, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, tc.Endpoint+"/mime-types", nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create mime-types request: %w", err)
	}

	tc.addAuth(req)
	req.Header.Set("Accept", "application/json")

	resp, err := tc.HTTPClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("mime-types request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("Tika returned status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read mime-types response: %w", err)
	}

	result := make(map[string]interface{})
	_ = body

	return result, nil
}

func (tc *TikaClient) GetVersion(ctx context.Context) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, tc.Endpoint+"/version", nil)
	if err != nil {
		return "", fmt.Errorf("failed to create version request: %w", err)
	}

	tc.addAuth(req)
	req.Header.Set("Accept", "text/plain")

	resp, err := tc.HTTPClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("version request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("Tika returned status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("failed to read version response: %w", err)
	}

	return strings.TrimSpace(string(body)), nil
}

func (tc *TikaClient) addAuth(req *http.Request) {
	if tc.AuthUsername != "" && tc.AuthPassword != "" {
		req.SetBasicAuth(tc.AuthUsername, tc.AuthPassword)
	}
}

func extractFileNameFromURL(url string) string {
	parts := strings.Split(url, "/")
	if len(parts) == 0 {
		return "downloaded_file"
	}
	fileName := parts[len(parts)-1]
	if idx := strings.Index(fileName, "?"); idx != -1 {
		fileName = fileName[:idx]
	}
	if fileName == "" {
		return "downloaded_file"
	}
	return fileName
}

func IsSupportedFileType(fileName string) bool {
	supportedExts := map[string]struct{}{
		".pdf":  {},
		".doc":  {},
		".docx": {},
		".xls":  {},
		".xlsx": {},
		".ppt":  {},
		".pptx": {},
		".txt":  {},
		".md":   {},
		".markdown": {},
		".rtf":  {},
		".odt":  {},
		".ods":  {},
		".odp":  {},
		".csv":  {},
		".html": {},
		".htm":  {},
		".xml":  {},
		".json": {},
		".eml":  {},
		".msg":  {},
		".epub": {},
		".png":  {},
		".jpg":  {},
		".jpeg": {},
		".gif":  {},
		".bmp":  {},
		".tiff": {},
	}

	lowerName := strings.ToLower(fileName)
	for ext := range supportedExts {
		if strings.HasSuffix(lowerName, ext) {
			return true
		}
	}
	return false
}

func ExtractTextFromMarkdown(mdContent string) string {
	result := mdContent

	replacer := strings.NewReplacer(
		"#", " ",
		"##", " ",
		"###", " ",
		"####", " ",
		"#####", " ",
		"######", " ",
		"**", "",
		"*", "",
		"`", "",
		"```", "",
		">", " ",
		"- ", " ",
		"* ", " ",
		"1. ", " ",
		"|", " ",
		"---", " ",
		"___", " ",
		"***", " ",
	)
	result = replacer.Replace(result)

	lines := strings.Split(result, "\n")
	var cleaned []string
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line != "" {
			cleaned = append(cleaned, line)
		}
	}

	return strings.Join(cleaned, "\n")
}
