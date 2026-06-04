package output

import (
	"encoding/json"
	"fmt"
	"os"
	"time"

	"github.com/htest/htest/internal/engine/rest"
)

type PostmanCollection struct {
	Info PostmanInfo    `json:"info"`
	Item []PostmanItem  `json:"item"`
}

type PostmanInfo struct {
	Name      string `json:"name"`
	Schema    string `json:"schema"`
	PostmanID string `json:"_postman_id"`
}

type PostmanItem struct {
	Name    string         `json:"name"`
	Request PostmanRequest `json:"request"`
}

type PostmanRequest struct {
	Method string         `json:"method"`
	URL    PostmanURL     `json:"url"`
	Header []PostmanHeader `json:"header"`
	Body   PostmanBody    `json:"body"`
}

type PostmanURL struct {
	Raw      string `json:"raw"`
	Protocol string `json:"protocol"`
}

type PostmanHeader struct {
	Key   string `json:"key"`
	Value string `json:"value"`
}

type PostmanBody struct {
	Mode string `json:"mode"`
	Raw  string `json:"raw"`
}

func ExportPostman(results []*rest.Response, requests []*rest.Request, names []string) ([]byte, error) {
	collection := PostmanCollection{
		Info: PostmanInfo{
			Name:      "htest Export",
			Schema:    "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
			PostmanID: generateID(),
		},
		Item: make([]PostmanItem, 0, len(requests)),
	}

	for i, req := range requests {
		name := fmt.Sprintf("Request %d", i+1)
		if i < len(names) && names[i] != "" {
			name = names[i]
		}

		protocol := "https"
		if len(req.URL) >= 5 && req.URL[:5] == "http:" {
			protocol = "http"
		}

		headers := make([]PostmanHeader, 0)
		for k, v := range req.Headers {
			headers = append(headers, PostmanHeader{Key: k, Value: v})
		}

		body := PostmanBody{}
		if req.Body != "" {
			body.Mode = "raw"
			body.Raw = req.Body
		}

		item := PostmanItem{
			Name: name,
			Request: PostmanRequest{
				Method: req.Method,
				URL: PostmanURL{
					Raw:      req.URL,
					Protocol: protocol,
				},
				Header: headers,
				Body:   body,
			},
		}

		collection.Item = append(collection.Item, item)
	}

	data, err := json.MarshalIndent(collection, "", "  ")
	if err != nil {
		return nil, fmt.Errorf("marshaling postman collection: %w", err)
	}

	return data, nil
}

type HARLog struct {
	Version string      `json:"version"`
	Creator HARCreator  `json:"creator"`
	Entries []HAREntry  `json:"entries"`
}

type HAREntry struct {
	Request         HARRequest  `json:"request"`
	Response        HARResponse `json:"response"`
	Time            float64     `json:"time"`
	StartedDateTime string      `json:"startedDateTime"`
}

type HARRequest struct {
	Method   string        `json:"method"`
	URL      string        `json:"url"`
	Headers  []HARHeader   `json:"headers"`
	PostData *HARPostData  `json:"postData,omitempty"`
}

type HARResponse struct {
	Status     int         `json:"status"`
	StatusText string      `json:"statusText"`
	Headers    []HARHeader `json:"headers"`
	Content    HARContent  `json:"content"`
}

type HARHeader struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}

type HARContent struct {
	MimeType string `json:"mimeType"`
	Text     string `json:"text"`
}

type HARPostData struct {
	MimeType string `json:"mimeType"`
	Text     string `json:"text"`
}

type HARCreator struct {
	Name    string `json:"name"`
	Version string `json:"version"`
}

func ExportHAR(results []*rest.Response, requests []*rest.Request) ([]byte, error) {
	log := HARLog{
		Version: "1.2",
		Creator: HARCreator{
			Name:    "htest",
			Version: "1.0.0",
		},
		Entries: make([]HAREntry, 0),
	}

	count := len(results)
	if len(requests) < count {
		count = len(requests)
	}

	for i := 0; i < count; i++ {
		req := requests[i]
		resp := results[i]

		reqHeaders := make([]HARHeader, 0)
		for k, v := range req.Headers {
			reqHeaders = append(reqHeaders, HARHeader{Name: k, Value: v})
		}

		var postData *HARPostData
		if req.Body != "" {
			mimeType := "application/json"
			if ct, ok := req.Headers["Content-Type"]; ok {
				mimeType = ct
			}
			postData = &HARPostData{
				MimeType: mimeType,
				Text:     req.Body,
			}
		}

		respHeaders := make([]HARHeader, 0)
		for k, vals := range resp.Headers {
			for _, v := range vals {
				respHeaders = append(respHeaders, HARHeader{Name: k, Value: v})
			}
		}

		mimeType := ""
		if ct, ok := resp.Headers["Content-Type"]; ok && len(ct) > 0 {
			mimeType = ct[0]
		}

		entry := HAREntry{
			Request: HARRequest{
				Method:   req.Method,
				URL:      req.URL,
				Headers:  reqHeaders,
				PostData: postData,
			},
			Response: HARResponse{
				Status:     resp.StatusCode,
				StatusText: resp.Status,
				Headers:    respHeaders,
				Content: HARContent{
					MimeType: mimeType,
					Text:     resp.Body,
				},
			},
			Time:            float64(resp.Duration.Microseconds()) / 1000.0,
			StartedDateTime: time.Now().Format(time.RFC3339),
		}

		log.Entries = append(log.Entries, entry)
	}

	har := map[string]interface{}{
		"log": log,
	}

	data, err := json.MarshalIndent(har, "", "  ")
	if err != nil {
		return nil, fmt.Errorf("marshaling HAR: %w", err)
	}

	return data, nil
}

func WriteFile(data []byte, path string) error {
	return os.WriteFile(path, data, 0644)
}

func generateID() string {
	now := time.Now().UnixNano()
	return fmt.Sprintf("%x", now)
}
