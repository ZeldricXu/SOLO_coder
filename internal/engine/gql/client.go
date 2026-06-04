package gql

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

type Client struct {
	endpoint   string
	httpClient *http.Client
	headers    map[string]string
}

type Response struct {
	Data   json.RawMessage
	Errors []GraphQLError
}

type GraphQLError struct {
	Message   string
	Locations []Location
}

type Location struct {
	Line   int
	Column int
}

type graphqlRequest struct {
	Query     string                 `json:"query"`
	Variables map[string]interface{} `json:"variables,omitempty"`
}

func NewClient(endpoint string, headers map[string]string, timeout int) *Client {
	return &Client{
		endpoint: endpoint,
		httpClient: &http.Client{
			Timeout: time.Duration(timeout) * time.Second,
		},
		headers: headers,
	}
}

func (c *Client) doRequest(ctx context.Context, query string, variables map[string]interface{}) (*Response, error) {
	reqBody := graphqlRequest{
		Query:     query,
		Variables: variables,
	}

	bodyBytes, err := json.Marshal(reqBody)
	if err != nil {
		return nil, fmt.Errorf("marshaling request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, c.endpoint, bytes.NewReader(bodyBytes))
	if err != nil {
		return nil, fmt.Errorf("creating request: %w", err)
	}

	httpReq.Header.Set("Content-Type", "application/json")
	for k, v := range c.headers {
		httpReq.Header.Set(k, v)
	}

	httpResp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("executing request: %w", err)
	}
	defer httpResp.Body.Close()

	var resp Response
	err = json.NewDecoder(httpResp.Body).Decode(&resp)
	if err != nil {
		return nil, fmt.Errorf("decoding response: %w", err)
	}

	return &resp, nil
}

func (c *Client) Query(ctx context.Context, query string, variables map[string]interface{}) (*Response, error) {
	return c.doRequest(ctx, query, variables)
}

func (c *Client) Mutate(ctx context.Context, mutation string, variables map[string]interface{}) (*Response, error) {
	return c.doRequest(ctx, mutation, variables)
}

func (c *Client) Introspect(ctx context.Context) (string, error) {
	introspectionQuery := `
query IntrospectionQuery {
  __schema {
    queryType { name }
    mutationType { name }
    subscriptionType { name }
    types {
      ...FullType
    }
    directives {
      name
      description
      locations
      args {
        ...InputValue
      }
    }
  }
}
fragment FullType on __Type {
  kind
  name
  description
  fields(includeDeprecated: true) {
    name
    description
    args {
      ...InputValue
    }
    type {
      ...TypeRef
    }
    isDeprecated
    deprecationReason
  }
  inputFields {
    ...InputValue
  }
  interfaces {
    ...TypeRef
  }
  enumValues(includeDeprecated: true) {
    name
    description
    isDeprecated
    deprecationReason
  }
  possibleTypes {
    ...TypeRef
  }
}
fragment InputValue on __InputValue {
  name
  description
  type {
    ...TypeRef
  }
  defaultValue
}
fragment TypeRef on __Type {
  kind
  name
  ofType {
    kind
    name
    ofType {
      kind
      name
      ofType {
        kind
        name
        ofType {
          kind
          name
          ofType {
            kind
            name
            ofType {
              kind
              name
            }
          }
        }
      }
    }
  }
}`

	resp, err := c.doRequest(ctx, introspectionQuery, nil)
	if err != nil {
		return "", fmt.Errorf("executing introspection query: %w", err)
	}

	if len(resp.Errors) > 0 {
		return "", fmt.Errorf("introspection query returned errors: %s", resp.Errors[0].Message)
	}

	var formatted bytes.Buffer
	err = json.Indent(&formatted, resp.Data, "", "  ")
	if err != nil {
		return "", fmt.Errorf("formatting schema: %w", err)
	}

	return formatted.String(), nil
}
