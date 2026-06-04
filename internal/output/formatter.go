package output

import (
	"encoding/json"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/htest/htest/internal/engine/gql"
	"github.com/htest/htest/internal/engine/rest"
	"github.com/htest/htest/internal/engine/ws"
	"github.com/htest/htest/internal/script"
	"github.com/htest/htest/pkg/colorjson"
)

const (
	ansiRed    = "\033[31m"
	ansiGreen  = "\033[32m"
	ansiYellow = "\033[33m"
	ansiCyan   = "\033[36m"
	ansiBold   = "\033[1m"
	ansiReset  = "\033[0m"
)

type Formatter struct {
	format string
	Writer io.Writer
}

func NewFormatter(format string, writer io.Writer) *Formatter {
	return &Formatter{
		format: format,
		Writer: writer,
	}
}

func (f *Formatter) FormatREST(resp *rest.Response) error {
	switch f.format {
	case "pretty":
		return f.formatRESTPretty(resp)
	case "json":
		return f.formatRESTJSON(resp)
	case "raw":
		fmt.Fprint(f.Writer, resp.Body)
		return nil
	default:
		return f.formatRESTPretty(resp)
	}
}

func (f *Formatter) formatRESTPretty(resp *rest.Response) error {
	statusColor := ansiGreen
	if resp.StatusCode >= 400 {
		statusColor = ansiRed
	} else if resp.StatusCode >= 300 {
		statusColor = ansiYellow
	}

	fmt.Fprintf(f.Writer, "\n%s%s%s %s%d %s%s\n", ansiBold, resp.Proto, ansiReset, statusColor, resp.StatusCode, resp.Status, ansiReset)
	fmt.Fprintf(f.Writer, "%s─── Headers ───%s\n", ansiCyan, ansiReset)

	for k, vals := range resp.Headers {
		for _, v := range vals {
			fmt.Fprintf(f.Writer, "  %s%s%s: %s\n", ansiCyan, k, ansiReset, v)
		}
	}

	fmt.Fprintf(f.Writer, "%s─── Body ───%s\n", ansiCyan, ansiReset)

	contentType := ""
	if ct, ok := resp.Headers["Content-Type"]; ok && len(ct) > 0 {
		contentType = ct[0]
	}

	if strings.Contains(contentType, "application/json") && resp.Body != "" {
		formatted, err := colorjson.Format([]byte(resp.Body), "  ")
		if err != nil {
			fmt.Fprintln(f.Writer, resp.Body)
		} else {
			fmt.Fprintln(f.Writer, formatted)
		}
	} else if resp.Body != "" {
		fmt.Fprintln(f.Writer, resp.Body)
	}

	fmt.Fprintf(f.Writer, "\n%sDuration: %s%v%s\n", ansiCyan, ansiYellow, resp.Duration, ansiReset)
	return nil
}

func (f *Formatter) formatRESTJSON(resp *rest.Response) error {
	out := map[string]interface{}{
		"status":   resp.StatusCode,
		"headers":  resp.Headers,
		"body":     resp.Body,
		"duration": resp.Duration.String(),
	}
	data, err := json.Marshal(out)
	if err != nil {
		return err
	}
	fmt.Fprintln(f.Writer, string(data))
	return nil
}

func (f *Formatter) FormatGRPC(responseJSON string, duration time.Duration) error {
	switch f.format {
	case "pretty":
		return f.formatGRPCPretty(responseJSON, duration)
	case "json":
		return f.formatGRPCJSON(responseJSON, duration)
	case "raw":
		fmt.Fprint(f.Writer, responseJSON)
		return nil
	default:
		return f.formatGRPCPretty(responseJSON, duration)
	}
}

func (f *Formatter) formatGRPCPretty(responseJSON string, duration time.Duration) error {
	fmt.Fprintf(f.Writer, "\n%s─── gRPC Response ───%s\n", ansiCyan, ansiReset)

	if responseJSON != "" {
		formatted, err := colorjson.Format([]byte(responseJSON), "  ")
		if err != nil {
			fmt.Fprintln(f.Writer, responseJSON)
		} else {
			fmt.Fprintln(f.Writer, formatted)
		}
	}

	fmt.Fprintf(f.Writer, "\n%sDuration: %s%v%s\n", ansiCyan, ansiYellow, duration, ansiReset)
	return nil
}

func (f *Formatter) formatGRPCJSON(responseJSON string, duration time.Duration) error {
	var body interface{}
	if err := json.Unmarshal([]byte(responseJSON), &body); err != nil {
		body = responseJSON
	}
	out := map[string]interface{}{
		"body":     body,
		"duration": duration.String(),
	}
	data, err := json.Marshal(out)
	if err != nil {
		return err
	}
	fmt.Fprintln(f.Writer, string(data))
	return nil
}

func (f *Formatter) FormatGQL(resp *gql.Response, duration time.Duration) error {
	switch f.format {
	case "pretty":
		return f.formatGQLPretty(resp, duration)
	case "json":
		return f.formatGQLJSON(resp, duration)
	case "raw":
		fmt.Fprint(f.Writer, string(resp.Data))
		return nil
	default:
		return f.formatGQLPretty(resp, duration)
	}
}

func (f *Formatter) formatGQLPretty(resp *gql.Response, duration time.Duration) error {
	fmt.Fprintf(f.Writer, "\n%s─── GraphQL Response ───%s\n", ansiCyan, ansiReset)

	if resp.Data != nil {
		formatted, err := colorjson.Format(resp.Data, "  ")
		if err != nil {
			fmt.Fprintln(f.Writer, string(resp.Data))
		} else {
			fmt.Fprintln(f.Writer, formatted)
		}
	}

	if len(resp.Errors) > 0 {
		fmt.Fprintf(f.Writer, "\n%s─── Errors ───%s\n", ansiRed, ansiReset)
		for _, e := range resp.Errors {
			fmt.Fprintf(f.Writer, "  %s%s%s\n", ansiRed, e.Message, ansiReset)
			for _, loc := range e.Locations {
				fmt.Fprintf(f.Writer, "    at line %d, column %d\n", loc.Line, loc.Column)
			}
		}
	}

	fmt.Fprintf(f.Writer, "\n%sDuration: %s%v%s\n", ansiCyan, ansiYellow, duration, ansiReset)
	return nil
}

func (f *Formatter) formatGQLJSON(resp *gql.Response, duration time.Duration) error {
	var data interface{}
	if resp.Data != nil {
		if err := json.Unmarshal(resp.Data, &data); err != nil {
			data = string(resp.Data)
		}
	}
	out := map[string]interface{}{
		"data":     data,
		"errors":   resp.Errors,
		"duration": duration.String(),
	}
	raw, err := json.Marshal(out)
	if err != nil {
		return err
	}
	fmt.Fprintln(f.Writer, string(raw))
	return nil
}

func (f *Formatter) FormatWS(messages []ws.Message) error {
	switch f.format {
	case "pretty":
		return f.formatWSPretty(messages)
	case "json":
		return f.formatWSJSON(messages)
	case "raw":
		for _, msg := range messages {
			fmt.Fprintln(f.Writer, msg.Content)
		}
		return nil
	default:
		return f.formatWSPretty(messages)
	}
}

func (f *Formatter) formatWSPretty(messages []ws.Message) error {
	fmt.Fprintf(f.Writer, "\n%s─── WebSocket Messages (%d) ───%s\n", ansiCyan, len(messages), ansiReset)

	for i, msg := range messages {
		dirColor := ansiGreen
		prefix := "←"
		if msg.Direction == "sent" {
			dirColor = ansiCyan
			prefix = "→"
		}

		fmt.Fprintf(f.Writer, "\n%s%s [%d]%s %s%s\n", dirColor, prefix, i+1, ansiReset, ansiYellow, msg.Timestamp.Format(time.RFC3339))
		fmt.Fprintf(f.Writer, "%s", ansiReset)

		formatted, err := colorjson.Format([]byte(msg.Content), "  ")
		if err != nil {
			fmt.Fprintln(f.Writer, msg.Content)
		} else {
			fmt.Fprintln(f.Writer, formatted)
		}
	}

	return nil
}

func (f *Formatter) formatWSJSON(messages []ws.Message) error {
	out := make([]map[string]interface{}, 0, len(messages))
	for _, msg := range messages {
		var content interface{}
		if err := json.Unmarshal([]byte(msg.Content), &content); err != nil {
			content = msg.Content
		}
		out = append(out, map[string]interface{}{
			"content":   content,
			"type":      msg.Type,
			"timestamp": msg.Timestamp.Format(time.RFC3339),
			"direction": msg.Direction,
		})
	}
	data, err := json.Marshal(out)
	if err != nil {
		return err
	}
	fmt.Fprintln(f.Writer, string(data))
	return nil
}

func (f *Formatter) FormatScriptResult(result *script.RunResult) error {
	switch f.format {
	case "pretty":
		return f.formatScriptResultPretty(result)
	case "json":
		return f.formatScriptResultJSON(result)
	case "raw":
		for _, step := range result.Steps {
			fmt.Fprintf(f.Writer, "%s: %s\n", step.StepName, step.Status)
		}
		return nil
	default:
		return f.formatScriptResultPretty(result)
	}
}

func (f *Formatter) formatScriptResultPretty(result *script.RunResult) error {
	statusColor := ansiGreen
	if result.Status == "fail" {
		statusColor = ansiRed
	}

	fmt.Fprintf(f.Writer, "\n%s─── Script: %s ───%s\n", ansiBold, result.ScriptName, ansiReset)
	fmt.Fprintf(f.Writer, "Status: %s%s%s\n", statusColor, result.Status, ansiReset)
	fmt.Fprintf(f.Writer, "Duration: %s%v%s\n\n", ansiYellow, result.TotalDuration, ansiReset)

	for i, step := range result.Steps {
		stepStatus := ansiGreen + "✓" + ansiReset
		if step.Status == "fail" || step.Status == "error" {
			stepStatus = ansiRed + "✗" + ansiReset
		}

		fmt.Fprintf(f.Writer, "  %s %s%d.%s %s\n", stepStatus, ansiBold, i+1, ansiReset, step.StepName)
		fmt.Fprintf(f.Writer, "    Duration: %s%v%s\n", ansiYellow, step.Duration, ansiReset)

		if step.Error != "" {
			fmt.Fprintf(f.Writer, "    %sError: %s%s\n", ansiRed, step.Error, ansiReset)
		}

		for _, ar := range step.Assertions {
			marker := ansiGreen + "✓" + ansiReset
			if !ar.Pass {
				marker = ansiRed + "✗" + ansiReset
			}
			fmt.Fprintf(f.Writer, "    %s %s", marker, ar.Assert.Type)
			if ar.Assert.JSONPath != "" {
				fmt.Fprintf(f.Writer, " (%s)", ar.Assert.JSONPath)
			}
			if ar.Pass {
				fmt.Fprint(f.Writer, ansiGreen)
				fmt.Fprintf(f.Writer, " — pass")
				fmt.Fprint(f.Writer, ansiReset)
			} else {
				fmt.Fprint(f.Writer, ansiRed)
				fmt.Fprintf(f.Writer, " — fail: %s", ar.Message)
				fmt.Fprint(f.Writer, ansiReset)
			}
			fmt.Fprintln(f.Writer)
		}

		if i < len(result.Steps)-1 {
			fmt.Fprintln(f.Writer)
		}
	}

	return nil
}

func (f *Formatter) formatScriptResultJSON(result *script.RunResult) error {
	data, err := json.Marshal(result)
	if err != nil {
		return err
	}
	fmt.Fprintln(f.Writer, string(data))
	return nil
}

func (f *Formatter) FormatError(err error) error {
	fmt.Fprintf(f.Writer, "%sError: %s%s\n", ansiRed, err.Error(), ansiReset)
	return nil
}
