# htest

A unified CLI tool for testing REST, gRPC, GraphQL, and WebSocket APIs. Stop switching between curl, grpcurl, GraphQL Playground, and wscat — one command does it all.

## Features

- **Multi-protocol**: REST (HTTP/1.1 & HTTP/2), gRPC (reflection & auto-discovery), GraphQL (introspection), WebSocket
- **Scriptable**: `.htest` YAML format for multi-step test chains with variable extraction, assertions, and loop control
- **Environment management**: dev/staging/prod profiles with independent URLs, tokens, and headers
- **Template variables**: `{{.token | default "abc"}}` — default values, upper/lower, base64, env lookups
- **Debug mode**: `--debug` shows request/response details; `--interactive` pauses before each step
- **Performance testing**: Concurrent execution with QPS, P50/P90/P99 latency, error rate, live terminal report
- **Report diff**: Compare two benchmark reports and see performance changes
- **Export**: Postman Collection and HAR format export
- **Shell completions**: bash, zsh, fish, PowerShell

## Installation

### go install (Go developers)

```bash
go install github.com/htest/htest@latest
```

### Homebrew (macOS)

```bash
brew tap htest/tap
brew install htest
```

### Binary download

Download the latest release from [GitHub Releases](https://github.com/htest/htest/releases).

## Quick Start

### Step 1: Install htest

```bash
go install github.com/htest/htest@latest
```

### Step 2: Create environment configuration

```bash
mkdir -p ~/.config/htest
cat > ~/.config/htest/config.yaml << 'EOF'
default_env: dev

settings:
  timeout: 30
  tls_skip_verify: false
  output_format: pretty

environments:
  dev:
    base_url: "http://localhost:8080"
    token: "dev-secret-token"
    variables:
      db: "dev_db"
  staging:
    base_url: "https://staging.api.example.com"
    token: "staging-secret-token"
    variables:
      db: "staging_db"
  prod:
    base_url: "https://api.example.com"

variables:
  app: "htest"
EOF
```

### Step 3: Send your first request

```bash
# REST GET
htest rest get https://httpbin.org/get

# With custom headers
htest rest get https://httpbin.org/headers -H "X-Custom: hello"

# POST with JSON body
htest rest post https://httpbin.org/post -d '{"name":"Alice"}'

# Use environment
htest rest get /api/health -e dev
```

### Step 4: Write a test script

Create `test.htest`:

```yaml
name: User API Test
description: Create and verify a user

variables:
  user_id:
    value: ""

steps:
  - name: Create User
    protocol: rest
    request:
      method: POST
      url: "{{.base_url}}/users"
      headers:
        Content-Type: application/json
        Authorization: "Bearer {{.token}}"
      body: '{"name":"Alice","email":"alice@example.com"}'
    extract:
      user_id:
        from: body
        jsonpath: "$.id"
      auth_token:
        from: header
        header: "X-Auth-Token"
    assert:
      - type: status
        expected: 201
      - type: json
        jsonpath: "$.name"
        expected: "Alice"
        operator: eq

  - name: Get User
    protocol: rest
    request:
      method: GET
      url: "{{.base_url}}/users/{{.user_id}}"
      headers:
        Authorization: "Bearer {{.token}}"
    assert:
      - type: status
        expected: 200
      - type: json
        jsonpath: "$.id"
        expected: "{{.user_id}}"
        operator: eq

  - name: Delay
    delay: 500ms
```

Run the script:

```bash
htest run test.htest
htest run test.htest --debug
htest run test.htest --interactive
htest run test.htest -e staging
```

### Step 5: Run a benchmark

```bash
htest bench test.htest -n 50 -d 30s --rps 100
htest bench test.htest -n 100 -d 1m --report result.json
htest report diff baseline.json result.json
```

## Usage

### REST

```bash
htest rest get <url> [-H header] [-q query_param]
htest rest post <url> [-d body] [-H header]
htest rest put <url> [-d body]
htest rest patch <url> [-d body]
htest rest delete <url>
```

### gRPC

```bash
# List services
htest grpc list -t localhost:50051

# Describe service (methods + message types)
htest grpc describe -t localhost:50051 -s mypackage.MyService

# Invoke method
htest grpc invoke -t localhost:50051 -s mypackage.MyService -m MyMethod -d '{"key":"value"}'
```

### GraphQL

```bash
# Query
htest gql query -E https://api.example.com/graphql -q '{ users { id name } }'

# Mutation
htest gql mutate -E https://api.example.com/graphql -q 'mutation { createUser(name:"A") { id } }'

# Introspect schema
htest gql introspect -E https://api.example.com/graphql
```

### WebSocket

```bash
# Connect and send
htest ws connect wss://echo.example.com/ws
htest ws send wss://echo.example.com/ws -m '{"action":"ping"}'
```

### Environment Management

```bash
# List environments
htest env list

# Show current environment
htest env show

# Switch environment
htest env set staging

# Manage variables
htest env var set mykey=myvalue
htest env var get mykey
htest env var list
```

### Script Format

`.htest` files use YAML with the following structure:

| Field | Description |
|-------|-------------|
| `name` | Script name |
| `description` | Script description |
| `env` | Default environment |
| `variables` | Script-level variables |
| `steps[].name` | Step name |
| `steps[].protocol` | `rest`, `grpc`, `gql`, `ws`, or empty (delay-only) |
| `steps[].request` | Request definition (method, url, headers, body) |
| `steps[].extract` | Variable extraction (from: body/header/status, jsonpath, regex) |
| `steps[].assert` | Assertions (type: status/headers/body/json/latency, operator: eq/neq/contains/gt/lt/gte/lte) |
| `steps[].delay` | Delay duration (e.g., `500ms`, `2s`) |
| `steps[].loop` | Loop control (count, interval, while) |

### Template Functions

Variables support `text/template` syntax with pipeline functions:

```
{{.token | default "abc"}}          # Default value if empty
{{.name | upper}}                    # Uppercase
{{.name | lower}}                    # Lowercase
{{.data | base64}}                   # Base64 encode
{{.encoded | base64decode}}          # Base64 decode
{{env "HOME"}}                       # OS environment variable
{{.host | required "host needed"}}   # Error if empty
```

### Configuration

Default config path: `~/.config/htest/config.yaml`

Environment variable overrides:

| Variable | Overrides |
|----------|-----------|
| `APICALL_ENV` | Current environment (`default_env`) |
| `APICALL_TOKEN` | Authentication token for current environment |

Settings section:

```yaml
settings:
  timeout: 30            # Default request timeout (seconds)
  tls_skip_verify: false # Skip TLS certificate verification
  output_format: pretty  # Output format: pretty, json, raw
```

### Shell Completions

```bash
# Bash
htest completion bash > /etc/bash_completion.d/htest

# Zsh
htest completion zsh > "${fpath[1]}/_htest"

# Fish
htest completion fish > ~/.config/fish/completions/htest.fish

# Generate man pages
htest manpage --dir /usr/local/share/man/man1
```

## License

MIT
