package cli

import (
	"context"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/c-bata/go-prompt"
	"github.com/spf13/cobra"
	googlegrpc "google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"

	"github.com/htest/htest/internal/engine/gql"
	"github.com/htest/htest/internal/engine/grpc"
	"github.com/htest/htest/internal/engine/rest"
	"github.com/htest/htest/internal/engine/ws"
	"github.com/htest/htest/internal/script"
)

var replSuggestions = []prompt.Suggest{
	{Text: "rest", Description: "Send REST requests"},
	{Text: "grpc", Description: "Send gRPC requests"},
	{Text: "gql", Description: "Send GraphQL queries"},
	{Text: "ws", Description: "Send WebSocket messages"},
	{Text: "env", Description: "Manage environments"},
	{Text: "var", Description: "Manage variables"},
	{Text: "run", Description: "Run test script"},
	{Text: "help", Description: "Show help"},
	{Text: "exit", Description: "Exit REPL"},
	{Text: "clear", Description: "Clear screen"},
}

func NewREPLCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "repl",
		Short: "Start interactive REPL mode",
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}
			fmt.Println("htest REPL - type 'help' for commands, 'exit' to quit")
			fmt.Printf("Current env: %s", AppInstance.EnvMgr.GetEnv())
			fmt.Println()
			p := prompt.New(
				replExecutor,
				replCompleter,
				prompt.OptionTitle("htest"),
				prompt.OptionPrefix(">>> "),
				prompt.OptionHistory([]string{}),
			)
			p.Run()
			return nil
		},
	}
}

func replExecutor(in string) {
	in = strings.TrimSpace(in)
	if in == "" {
		return
	}

	parts := strings.Fields(in)
	cmd := parts[0]

	switch cmd {
	case "exit", "quit":
		fmt.Println("Goodbye!")
		os.Exit(0)
	case "clear":
		fmt.Print("\033[2J\033[H")
		return
	case "help":
		printReplHelp()
		return
	case "env":
		handleREPLEnv(parts)
		return
	case "var":
		handleREPLVar(parts)
		return
	case "rest":
		handleREPLRest(parts)
		return
	case "grpc":
		handleREPLGRPC(parts)
		return
	case "gql":
		handleREPLGQL(parts)
		return
	case "ws":
		handleREPLWS(parts)
		return
	case "run":
		handleREPLRun(parts)
		return
	default:
		fmt.Printf("Unknown command: %s. Type 'help' for available commands.\n", cmd)
	}
}

func replCompleter(d prompt.Document) []prompt.Suggest {
	s := []prompt.Suggest{}
	parts := strings.Fields(d.CurrentLine())
	if len(parts) <= 1 {
		return prompt.FilterHasPrefix(replSuggestions, d.GetWordBeforeCursor(), true)
	}
	return s
}

func printReplHelp() {
	fmt.Println("Available commands:")
	fmt.Println("  rest get <url>                 - Send GET request")
	fmt.Println("  rest post <url> <body>         - Send POST request")
	fmt.Println("  rest put <url> <body>         - Send PUT request")
	fmt.Println("  rest delete <url>             - Send DELETE request")
	fmt.Println("  grpc list <target>            - List gRPC services")
	fmt.Println("  grpc invoke <target> <service> <method> <json> - Invoke gRPC method")
	fmt.Println("  gql query <endpoint> <query> - Execute GraphQL query")
	fmt.Println("  ws connect <url>             - Connect WebSocket")
	fmt.Println("  env list                      - List environments")
	fmt.Println("  env set <name>                - Switch environment")
	fmt.Println("  var set <key=value>           - Set variable")
	fmt.Println("  var get <key>                 - Get variable")
	fmt.Println("  var list                      - List all variables")
	fmt.Println("  run <script.htest>          - Run test script")
	fmt.Println("  clear                         - Clear screen")
	fmt.Println("  exit                          - Exit REPL")
}

func handleREPLEnv(parts []string) {
	if len(parts) < 2 {
		fmt.Println("Usage: env list|set <name>")
		return
	}
	sub := parts[1]
	switch sub {
	case "list":
		names := make([]string, 0, len(AppInstance.Config.Environments))
		for name := range AppInstance.Config.Environments {
			names = append(names, name)
		}
		fmt.Println("Environments:")
		for _, name := range names {
			marker := "  "
			if name == AppInstance.EnvMgr.GetEnv() {
				marker = "* "
			}
			fmt.Printf("%s%s\n", marker, name)
		}
	case "set":
		if len(parts) < 3 {
			fmt.Println("Usage: env set <name>")
			return
		}
		if err := AppInstance.EnvMgr.SetEnv(parts[2]); err != nil {
			fmt.Printf("Error: %v\n", err)
			return
		}
		fmt.Printf("Switched to environment: %s\n", parts[2])
	default:
		fmt.Printf("Unknown env subcommand: %s\n", sub)
	}
}

func handleREPLVar(parts []string) {
	if len(parts) < 2 {
		fmt.Println("Usage: var set <key=value | get <key> | list")
		return
	}
	sub := parts[1]
	switch sub {
	case "set":
		if len(parts) < 3 {
			fmt.Println("Usage: var set <key=value>")
			return
		}
		kv := strings.SplitN(parts[2], "=", 2)
		if len(kv) != 2 {
			fmt.Println("Invalid format, use key=value")
			return
		}
		AppInstance.EnvMgr.SetVar(kv[0], kv[1])
		fmt.Printf("Set %s=%s\n", kv[0], kv[1])
	case "get":
		if len(parts) < 3 {
			fmt.Println("Usage: var get <key>")
			return
		}
		val := AppInstance.EnvMgr.GetVar(parts[2])
		fmt.Printf("%s=%s\n", parts[2], val)
	case "list":
		vars := AppInstance.EnvMgr.AllVars()
		fmt.Println("Variables:")
		for k, v := range vars {
			fmt.Printf("  %s=%s\n", k, v)
		}
	default:
		fmt.Printf("Unknown var subcommand: %s\n", sub)
	}
}

func handleREPLRest(parts []string) {
	if len(parts) < 3 {
		fmt.Println("Usage: rest <method> <url> [body]")
		return
	}
	method := parts[1]
	url := parts[2]
	body := ""
	if len(parts) > 3 {
		body = strings.Join(parts[3:], " ")
	}

	client := rest.NewClient(AppInstance.EnvMgr.BaseURL(), AppInstance.EnvMgr.AuthHeaders(), AppInstance.Config.Settings.Timeout)

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
	default:
		fmt.Printf("Unknown method: %s\n", method)
		return
	}
	if err != nil {
		AppInstance.Out.FormatError(err)
		return
	}
	AppInstance.Out.FormatREST(resp)
}

func handleREPLGRPC(parts []string) {
	if len(parts) < 3 {
		fmt.Println("Usage: grpc list <target> | invoke <target> <service> <method> <json>")
		return
	}
	sub := parts[1]
	target := parts[2]

	client, err := grpc.NewClient(target, googlegrpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		AppInstance.Out.FormatError(err)
		return
	}
	defer client.Close()

	switch sub {
	case "list":
		if len(parts) == 4 {
			methods, err := client.ListMethods(parts[3])
			if err != nil {
				AppInstance.Out.FormatError(err)
				return
			}
			fmt.Printf("Service: %s\n", parts[3])
			for _, m := range methods {
				fmt.Printf("  - %s\n", m)
			}
		} else {
			services, err := client.ListServices()
			if err != nil {
				AppInstance.Out.FormatError(err)
				return
			}
			fmt.Println("Services:")
			for _, s := range services {
				fmt.Printf("  - %s\n", s)
			}
		}
	case "invoke":
		if len(parts) < 6 {
			fmt.Println("Usage: grpc invoke <target> <service> <method> <json>")
			return
		}
		service := parts[3]
		method := parts[4]
		reqJSON := strings.Join(parts[5:], " ")
		ctx := context.Background()
		resp, err := client.Invoke(ctx, service, method, reqJSON)
		if err != nil {
			AppInstance.Out.FormatError(err)
			return
		}
		AppInstance.Out.FormatGRPC(resp, 0)
	default:
		fmt.Printf("Unknown grpc subcommand: %s\n", sub)
	}
}

func handleREPLGQL(parts []string) {
	if len(parts) < 4 {
		fmt.Println("Usage: gql query <endpoint> <query> | mutate <endpoint> <mutation> | introspect <endpoint>")
		return
	}
	sub := parts[1]
	endpoint := parts[2]
	query := strings.Join(parts[3:], " ")

	client := gql.NewClient(endpoint, AppInstance.EnvMgr.AuthHeaders(), AppInstance.Config.Settings.Timeout)
	ctx := context.Background()

	var resp *gql.Response
	var err error

	switch sub {
	case "query":
		resp, err = client.Query(ctx, query, nil)
	case "mutate":
		resp, err = client.Mutate(ctx, query, nil)
	case "introspect":
		schema, err := client.Introspect(ctx)
		if err != nil {
			AppInstance.Out.FormatError(err)
			return
		}
		fmt.Println(schema)
		return
	default:
		fmt.Printf("Unknown gql subcommand: %s\n", sub)
		return
	}
	if err != nil {
		AppInstance.Out.FormatError(err)
		return
	}
	AppInstance.Out.FormatGQL(resp, 0)
}

func handleREPLWS(parts []string) {
	if len(parts) < 3 {
		fmt.Println("Usage: ws connect <url> | send <url> <message>")
		return
	}
	sub := parts[1]
	url := parts[2]

	client := ws.NewClient(url, AppInstance.EnvMgr.AuthHeaders())
	ctx := context.Background()

	if err := client.Connect(ctx); err != nil {
		AppInstance.Out.FormatError(err)
		return
	}
	defer client.Close()

	switch sub {
	case "connect":
		fmt.Printf("Connected to %s (listening for 10 seconds)\n", url)
		msgCh, err := client.Receive()
		if err != nil {
			AppInstance.Out.FormatError(err)
			return
		}
		var messages []ws.Message
		timeout := time.After(10 * time.Second)
		for {
			select {
			case msg, ok := <-msgCh:
				if !ok {
					AppInstance.Out.FormatWS(messages)
					return
				}
				messages = append(messages, msg)
				fmt.Printf("Received: %s\n", msg.Content)
			case <-timeout:
				AppInstance.Out.FormatWS(messages)
				return
			}
		}
	case "send":
		if len(parts) < 4 {
			fmt.Println("Usage: ws send <url> <message>")
			return
		}
		message := strings.Join(parts[3:], " ")
		if err := client.Send(message); err != nil {
			AppInstance.Out.FormatError(err)
			return
		}
		fmt.Println("Message sent")
	default:
		fmt.Printf("Unknown ws subcommand: %s\n", sub)
	}
}

func handleREPLRun(parts []string) {
	if len(parts) < 2 {
		fmt.Println("Usage: run <script.htest>")
		return
	}
	scriptPath := parts[1]

	testScript, err := script.ParseFile(scriptPath)
	if err != nil {
		AppInstance.Out.FormatError(err)
		return
	}
	if err := script.Validate(testScript); err != nil {
		AppInstance.Out.FormatError(err)
		return
	}
	if testScript.Env != "" {
		AppInstance.EnvMgr.SetEnv(testScript.Env)
	}
	executor := script.NewExecutor(AppInstance.EnvMgr)
	ctx := context.Background()
	result, err := executor.Execute(ctx, testScript)
	if err != nil {
		AppInstance.Out.FormatError(err)
		return
	}
	AppInstance.Out.FormatScriptResult(result)
}
