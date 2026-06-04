package engine

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/htest/htest/internal/engine/gql"
)

type GQLAdapter struct {
	endpoint string
	headers  map[string]string
	timeout  int
}

func NewGQLAdapter(endpoint string, headers map[string]string, timeout int) *GQLAdapter {
	return &GQLAdapter{endpoint: endpoint, headers: headers, timeout: timeout}
}

func (a *GQLAdapter) Execute(ctx context.Context, req *ProtocolRequest) (*ProtocolResponse, error) {
	client := gql.NewClient(a.endpoint, a.headers, a.timeout)

	var variables map[string]interface{}
	if req.Body != "" {
		if err := json.Unmarshal([]byte(req.Body), &variables); err != nil {
			variables = nil
		}
	}

	resp, err := client.Query(ctx, req.Query, variables)
	if err != nil {
		return nil, err
	}

	bodyBytes, _ := json.Marshal(resp)
	statusCode := 200
	if len(resp.Errors) > 0 {
		statusCode = 400
	}

	return &ProtocolResponse{
		StatusCode: statusCode,
		Status:     fmt.Sprintf("%d OK", statusCode),
		Headers:    map[string][]string{"Content-Type": {"application/json"}},
		Body:       string(bodyBytes),
		Raw:        resp,
	}, nil
}
