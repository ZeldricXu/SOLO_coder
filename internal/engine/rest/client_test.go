package rest

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewClient(t *testing.T) {
	headers := map[string]string{
		"Authorization": "Bearer token",
		"Accept":        "application/json",
	}
	client := NewClient("http://example.com/", headers, 30)

	assert.Equal(t, "http://example.com", client.baseURL)
	assert.Equal(t, headers, client.defaultHeaders)
	assert.NotNil(t, client.httpClient)
}

func TestClient_Do_GET(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ok"}`))
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 10)
	resp, err := client.Do(&Request{
		Method: http.MethodGet,
		URL:    server.URL,
	})

	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Contains(t, resp.Body, "ok")
	assert.Greater(t, resp.Duration, time.Duration(0))
}

func TestClient_Do_POST_WithBody(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		fmt.Fprintf(w, `{"echo":%s}`, string(body))
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 10)
	resp, err := client.Do(&Request{
		Method:  http.MethodPost,
		URL:     server.URL,
		Body:    `{"name":"test"}`,
		Headers: map[string]string{"Content-Type": "application/json"},
	})

	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Contains(t, resp.Body, `"name"`)
	assert.Contains(t, resp.Body, `"test"`)
}

func TestClient_Do_Put(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPut, r.Method)
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		fmt.Fprintf(w, `{"echo":%s}`, string(body))
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 10)
	resp, err := client.Do(&Request{
		Method:  http.MethodPut,
		URL:     server.URL,
		Body:    `{"id":1}`,
		Headers: map[string]string{"Content-Type": "application/json"},
	})

	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Contains(t, resp.Body, `"id"`)
}

func TestClient_Do_Delete(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodDelete, r.Method)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 10)
	resp, err := client.Do(&Request{
		Method: http.MethodDelete,
		URL:    server.URL,
	})

	require.NoError(t, err)
	assert.Equal(t, http.StatusNoContent, resp.StatusCode)
	assert.Empty(t, resp.Body)
}

func TestClient_Do_Patch(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPatch, r.Method)
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		fmt.Fprintf(w, `{"echo":%s}`, string(body))
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 10)
	resp, err := client.Do(&Request{
		Method:  http.MethodPatch,
		URL:     server.URL,
		Body:    `{"field":"value"}`,
		Headers: map[string]string{"Content-Type": "application/json"},
	})

	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Contains(t, resp.Body, `"field"`)
	assert.Contains(t, resp.Body, `"value"`)
}

func TestClient_Do_WithHeaders(t *testing.T) {
	var receivedHeader string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedHeader = r.Header.Get("X-Custom")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 10)
	resp, err := client.Do(&Request{
		Method:  http.MethodGet,
		URL:     server.URL,
		Headers: map[string]string{"X-Custom": "test-value"},
	})

	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Equal(t, "test-value", receivedHeader)
}

func TestClient_Do_DefaultHeaders(t *testing.T) {
	var receivedAuth string
	var receivedAccept string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedAuth = r.Header.Get("Authorization")
		receivedAccept = r.Header.Get("Accept")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	}))
	defer server.Close()

	defaultHeaders := map[string]string{
		"Authorization": "Bearer token123",
		"Accept":        "application/json",
	}
	client := NewClient(server.URL, defaultHeaders, 10)
	resp, err := client.Do(&Request{
		Method: http.MethodGet,
		URL:    server.URL,
	})

	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Equal(t, "Bearer token123", receivedAuth)
	assert.Equal(t, "application/json", receivedAccept)
}

func TestClient_Do_StatusCode201(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusCreated)
		w.Write([]byte(`{"id":1}`))
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 10)
	resp, err := client.Do(&Request{
		Method: http.MethodPost,
		URL:    server.URL,
	})

	require.NoError(t, err)
	assert.Equal(t, http.StatusCreated, resp.StatusCode)
	assert.Equal(t, "201 Created", resp.Status)
}

func TestClient_Do_StatusCode4xx(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(`{"error":"bad request"}`))
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 10)
	resp, err := client.Do(&Request{
		Method: http.MethodGet,
		URL:    server.URL,
	})

	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)
	assert.Contains(t, resp.Body, "bad request")
}

func TestClient_Do_StatusCode5xx(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte(`{"error":"internal server error"}`))
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 10)
	resp, err := client.Do(&Request{
		Method: http.MethodGet,
		URL:    server.URL,
	})

	require.NoError(t, err)
	assert.Equal(t, http.StatusInternalServerError, resp.StatusCode)
}

func TestClient_Do_Timeout(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(2 * time.Second)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 1)
	_, err := client.Do(&Request{
		Method: http.MethodGet,
		URL:    server.URL,
	})

	require.Error(t, err)
	assert.True(t, strings.Contains(err.Error(), "executing request") || strings.Contains(err.Error(), context.DeadlineExceeded.Error()) || strings.Contains(err.Error(), "timeout"))
}

func TestClient_Do_BaseURL(t *testing.T) {
	var requestedPath string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestedPath = r.URL.Path
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 10)
	resp, err := client.Do(&Request{
		Method: http.MethodGet,
		URL:    "/api/v1/resource",
	})

	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Equal(t, "/api/v1/resource", requestedPath)
}

func TestClient_Do_ContentType(t *testing.T) {
	var receivedContentType string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedContentType = r.Header.Get("Content-Type")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 10)
	resp, err := client.Do(&Request{
		Method:  http.MethodPost,
		URL:     server.URL,
		Body:    `{"key":"value"}`,
		Headers: map[string]string{"Content-Type": "application/json"},
	})

	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Equal(t, "application/json", receivedContentType)
}

func TestClient_Do_ConvenienceMethods(t *testing.T) {
	var lastMethod string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		lastMethod = r.Method
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("ok"))
	}))
	defer server.Close()

	client := NewClient(server.URL, nil, 10)

	resp, err := client.Get(server.URL, nil)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Equal(t, http.MethodGet, lastMethod)

	resp, err = client.Post(server.URL, `{"data":"post"}`, map[string]string{"Content-Type": "application/json"})
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Equal(t, http.MethodPost, lastMethod)

	resp, err = client.Put(server.URL, `{"data":"put"}`, map[string]string{"Content-Type": "application/json"})
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Equal(t, http.MethodPut, lastMethod)

	resp, err = client.Delete(server.URL, nil)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Equal(t, http.MethodDelete, lastMethod)

	resp, err = client.Patch(server.URL, `{"data":"patch"}`, map[string]string{"Content-Type": "application/json"})
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
	assert.Equal(t, http.MethodPatch, lastMethod)
}
