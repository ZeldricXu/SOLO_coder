package cli

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/htest/htest/internal/engine/gql"
	"github.com/spf13/cobra"
)

func NewGraphQLCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "gql",
		Short: "Send GraphQL queries and mutations",
	}

	cmd.AddCommand(newGQLQueryCmd())
	cmd.AddCommand(newGQLMutateCmd())
	cmd.AddCommand(newGQLIntrospectCmd())

	return cmd
}

func newGQLQueryCmd() *cobra.Command {
	var endpoint string
	var query string
	var variables string
	var headers []string
	var timeout int

	cmd := &cobra.Command{
		Use:   "query",
		Short: "Execute a GraphQL query",
		Example: `  # Run a GraphQL query
  htest gql query -E https://api.example.com/graphql -q '{ users { id name } }'`,
		RunE: func(cmd *cobra.Command, args []string) error {
			return runGQL(endpoint, query, variables, headers, timeout, "query")
		},
	}

	cmd.Flags().StringVarP(&endpoint, "endpoint", "E", "", "GraphQL endpoint URL")
	cmd.Flags().StringVarP(&query, "query", "q", "", "GraphQL query string")
	cmd.Flags().StringVarP(&variables, "variables", "V", "", "JSON string of variables")
	cmd.Flags().StringArrayVarP(&headers, "header", "H", nil, "request headers (key:value)")
	cmd.Flags().IntVar(&timeout, "timeout", 0, "request timeout in seconds")
	cmd.MarkFlagRequired("endpoint")
	cmd.MarkFlagRequired("query")

	return cmd
}

func newGQLMutateCmd() *cobra.Command {
	var endpoint string
	var mutation string
	var variables string
	var headers []string
	var timeout int

	cmd := &cobra.Command{
		Use:   "mutate",
		Short: "Execute a GraphQL mutation",
		Example: `  # Run a GraphQL mutation
  htest gql mutate -E https://api.example.com/graphql -q 'mutation { createUser(name: "Alice") { id } }'`,
		RunE: func(cmd *cobra.Command, args []string) error {
			return runGQL(endpoint, mutation, variables, headers, timeout, "mutate")
		},
	}

	cmd.Flags().StringVarP(&endpoint, "endpoint", "E", "", "GraphQL endpoint URL")
	cmd.Flags().StringVarP(&mutation, "mutation", "q", "", "GraphQL mutation string")
	cmd.Flags().StringVarP(&variables, "variables", "V", "", "JSON string of variables")
	cmd.Flags().StringArrayVarP(&headers, "header", "H", nil, "request headers (key:value)")
	cmd.Flags().IntVar(&timeout, "timeout", 0, "request timeout in seconds")
	cmd.MarkFlagRequired("endpoint")
	cmd.MarkFlagRequired("mutation")

	return cmd
}

func newGQLIntrospectCmd() *cobra.Command {
	var endpoint string
	var timeout int

	cmd := &cobra.Command{
		Use:   "introspect",
		Short: "Introspect GraphQL schema",
		Example: `  # Introspect a GraphQL schema
  htest gql introspect -E https://api.example.com/graphql`,
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			t := timeout
			if t == 0 {
				t = AppInstance.Config.Settings.Timeout
			}

			headerMap := AppInstance.EnvMgr.AuthHeaders()

			client := gql.NewClient(endpoint, headerMap, t)

			ctx, cancel := context.WithTimeout(context.Background(), time.Duration(t)*time.Second)
			defer cancel()

			start := time.Now()
			schema, err := client.Introspect(ctx)
			duration := time.Since(start)

			if err != nil {
				return AppInstance.Out.FormatError(err)
			}

			resp := &gql.Response{
				Data: json.RawMessage(schema),
			}

			return AppInstance.Out.FormatGQL(resp, duration)
		},
	}

	cmd.Flags().StringVarP(&endpoint, "endpoint", "E", "", "GraphQL endpoint URL")
	cmd.Flags().IntVar(&timeout, "timeout", 0, "request timeout in seconds")
	cmd.MarkFlagRequired("endpoint")

	return cmd
}

func runGQL(endpoint, query, variables string, headers []string, timeout int, op string) error {
	if AppInstance == nil {
		return fmt.Errorf("app not initialized")
	}

	t := timeout
	if t == 0 {
		t = AppInstance.Config.Settings.Timeout
	}

	headerMap := AppInstance.EnvMgr.AuthHeaders()
	for _, h := range headers {
		parts := strings.SplitN(h, ":", 2)
		if len(parts) == 2 {
			headerMap[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
		}
	}

	var vars map[string]interface{}
	if variables != "" {
		if err := json.Unmarshal([]byte(variables), &vars); err != nil {
			return AppInstance.Out.FormatError(fmt.Errorf("invalid variables JSON: %w", err))
		}
	}

	client := gql.NewClient(endpoint, headerMap, t)

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(t)*time.Second)
	defer cancel()

	start := time.Now()
	var resp *gql.Response
	var err error
	if op == "query" {
		resp, err = client.Query(ctx, query, vars)
	} else {
		resp, err = client.Mutate(ctx, query, vars)
	}
	duration := time.Since(start)

	if err != nil {
		return AppInstance.Out.FormatError(err)
	}

	return AppInstance.Out.FormatGQL(resp, duration)
}
