package mirror

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"net/url"

	"DF1-56/internal/models"
)

func cloneRequest(ctx *models.GatewayContext, targetURL string) (*http.Request, error) {
	if ctx == nil || ctx.Request == nil {
		return nil, fmt.Errorf("gateway context or request is nil")
	}

	parsedTargetURL, err := url.Parse(targetURL)
	if err != nil {
		return nil, fmt.Errorf("parse target url failed: %w", err)
	}

	originalURL := *ctx.Request.URL
	originalURL.Scheme = parsedTargetURL.Scheme
	originalURL.Host = parsedTargetURL.Host
	originalURL.Path = parsedTargetURL.Path

	if parsedTargetURL.RawQuery != "" {
		if originalURL.RawQuery != "" {
			originalURL.RawQuery = originalURL.RawQuery + "&" + parsedTargetURL.RawQuery
		} else {
			originalURL.RawQuery = parsedTargetURL.RawQuery
		}
	}

	var bodyBuffer bytes.Buffer
	if ctx.Request.Body != nil {
		bodyBytes, err := io.ReadAll(ctx.Request.Body)
		if err != nil {
			return nil, fmt.Errorf("read request body failed: %w", err)
		}
		ctx.Request.Body = io.NopCloser(bytes.NewReader(bodyBytes))
		bodyBuffer = *bytes.NewBuffer(bodyBytes)
	}

	req, err := http.NewRequestWithContext(ctx.Context, ctx.Request.Method, originalURL.String(), &bodyBuffer)
	if err != nil {
		return nil, fmt.Errorf("create cloned request failed: %w", err)
	}

	cloneHeaders(req.Header, ctx.Request.Header)

	req.Host = parsedTargetURL.Host
	req.RemoteAddr = ctx.Request.RemoteAddr

	return req, nil
}

func cloneHeaders(dst, src http.Header) {
	for key, values := range src {
		dst[key] = make([]string, len(values))
		copy(dst[key], values)
	}
}

func filterHeaders(headers http.Header, include, exclude []string) http.Header {
	filtered := make(http.Header)

	includeSet := make(map[string]bool)
	for _, h := range include {
		includeSet[http.CanonicalHeaderKey(h)] = true
	}

	excludeSet := make(map[string]bool)
	for _, h := range exclude {
		excludeSet[http.CanonicalHeaderKey(h)] = true
	}

	for key, values := range headers {
		canonicalKey := http.CanonicalHeaderKey(key)

		if len(include) > 0 && !includeSet[canonicalKey] {
			continue
		}

		if excludeSet[canonicalKey] {
			continue
		}

		filtered[key] = make([]string, len(values))
		copy(filtered[key], values)
	}

	return filtered
}

func cloneRequestWithFilter(ctx *models.GatewayContext, targetURL string, includeHeaders, excludeHeaders []string) (*http.Request, error) {
	req, err := cloneRequest(ctx, targetURL)
	if err != nil {
		return nil, err
	}

	req.Header = filterHeaders(ctx.Request.Header, includeHeaders, excludeHeaders)

	return req, nil
}
