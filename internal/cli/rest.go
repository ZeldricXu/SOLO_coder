package cli

import (
	"fmt"
	"strings"

	"github.com/htest/htest/internal/engine/rest"
	"github.com/spf13/cobra"
)

func NewRESTCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "rest",
		Short: "Send REST/HTTP requests",
	}

	cmd.AddCommand(newRESTSubCmd("get", "Send GET request"))
	cmd.AddCommand(newRESTSubCmd("post", "Send POST request"))
	cmd.AddCommand(newRESTSubCmd("put", "Send PUT request"))
	cmd.AddCommand(newRESTSubCmd("patch", "Send PATCH request"))
	cmd.AddCommand(newRESTSubCmd("delete", "Send DELETE request"))

	return cmd
}

func newRESTSubCmd(method, short string) *cobra.Command {
	var headers []string
	var body string
	var timeout int
	var queryParams []string

	cmd := &cobra.Command{
		Use:   fmt.Sprintf("%s [url]", method),
		Short: short,
		Example: fmt.Sprintf(`  # Send a %s request
  htest rest %s https://api.example.com/users -H "Authorization: Bearer token"`, method, method),
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			url := args[0]
			headerMap := AppInstance.EnvMgr.AuthHeaders()
			for _, h := range headers {
				parts := strings.SplitN(h, ":", 2)
				if len(parts) == 2 {
					headerMap[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
				}
			}

			if len(queryParams) > 0 {
				sep := "?"
				for _, qp := range queryParams {
					parts := strings.SplitN(qp, "=", 2)
					if len(parts) == 2 {
						url += fmt.Sprintf("%s%s=%s", sep, parts[0], parts[1])
						sep = "&"
					}
				}
			}

			t := timeout
			if t == 0 {
				t = AppInstance.Config.Settings.Timeout
			}

			client := rest.NewClient(AppInstance.EnvMgr.BaseURL(), headerMap, t)

			var resp *rest.Response
			var err error

			switch method {
			case "get":
				resp, err = client.Get(url, nil)
			case "post":
				resp, err = client.Post(url, body, nil)
			case "put":
				resp, err = client.Put(url, body, nil)
			case "patch":
				resp, err = client.Patch(url, body, nil)
			case "delete":
				resp, err = client.Delete(url, nil)
			}

			if err != nil {
				return AppInstance.Out.FormatError(err)
			}

			return AppInstance.Out.FormatREST(resp)
		},
	}

	cmd.Flags().StringArrayVarP(&headers, "header", "H", nil, "request headers (key:value)")
	cmd.Flags().StringVarP(&body, "data", "d", "", "request body")
	cmd.Flags().IntVar(&timeout, "timeout", 0, "request timeout in seconds")
	cmd.Flags().StringArrayVarP(&queryParams, "query-param", "q", nil, "query parameters (key=value)")

	if method == "get" || method == "delete" {
		cmd.Flags().MarkHidden("data")
	}

	return cmd
}
