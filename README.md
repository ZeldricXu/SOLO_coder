# DataExplorer

Interactive data analysis tool running entirely in the browser via Go → WebAssembly. No server required — all processing happens locally on your machine.

## Features

- **File Import**: CSV, JSON, NDJSON, Parquet with automatic type inference
- **Query Engine**: SQL-like DSL with SELECT, WHERE, GROUP BY, ORDER BY, LIMIT, JOIN
- **Charts**: Bar, Line, Scatter, Box Plot, Heatmap, Histogram (Vega-Lite)
- **Pivot Tables**: Drag-and-drop dimensions with 8 aggregation methods
- **Anomaly Detection**: IQR and Z-Score methods with visual highlighting
- **Visual Filters**: Click column headers for range sliders, checkboxes, date pickers
- **Data Export**: CSV, JSON, Excel, Chart PNG/SVG
- **Project Persistence**: Save/restore analysis state to IndexedDB
- **Electron Desktop App**: Optional desktop build with native file system access

## Quick Start

### Prerequisites

- Go 1.21+
- A modern browser (Chrome 89+, Firefox 89+, Edge 89+, Safari 15.2+)
- Make (for build commands)

### Build

```bash
make build
```

This compiles the WASM binary to `dist/main.wasm` and copies all web assets to `dist/`.

### Local Development

```bash
make serve
```

Starts a local HTTP server at `http://localhost:8080` with Cross-Origin isolation headers required for `SharedArrayBuffer` support. Open the URL in your browser.

### Run Tests

```bash
make test
```

## Build Targets

| Target | Description |
|--------|-------------|
| `make build` | Compile WASM + copy assets to `dist/` |
| `make build-tinygo` | Build with TinyGo for smaller binary (if available) |
| `make optimize` | Run `wasm-opt -Oz` + gzip pre-compression |
| `make serve` | Start dev server with COOP/COEP headers (port 8080) |
| `make test` | Run Go unit tests with race detector |
| `make test-wasm` | Run WASM integration tests in Node.js |
| `make release` | Build + optimize + package as zip |
| `make clean` | Remove `dist/` and build artifacts |

### Custom Serve Options

```bash
go run cmd/serve/main.go --port 3000 --dir dist
```

## WASM Deployment

### Cross-Origin Isolation Headers

For full functionality (multi-threading via `SharedArrayBuffer`), your web server must set these response headers:

```
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
Cross-Origin-Resource-Policy: cross-origin
```

Without these headers, the app automatically falls back to single-thread mode. All features still work, but large dataset processing may be slower.

### Server Configuration Examples

**Nginx:**

```nginx
location / {
    add_header Cross-Origin-Opener-Policy "same-origin";
    add_header Cross-Origin-Embedder-Policy "require-corp";
    add_header Cross-Origin-Resource-Policy "cross-origin";

    # Serve pre-compressed .wasm.gz if available
    gzip_static on;
}
```

**Apache (.htaccess):**

```apache
Header set Cross-Origin-Opener-Policy "same-origin"
Header set Cross-Origin-Embedder-Policy "require-corp"
Header set Cross-Origin-Resource-Policy "cross-origin"

# WASM MIME type
AddType application/wasm .wasm
```

**Netlify (_headers file):**

```
/*
    Cross-Origin-Opener-Policy: same-origin
    Cross-Origin-Embedder-Policy: require-corp
    Cross-Origin-Resource-Policy: cross-origin
```

**GitHub Pages:** Does not support custom headers. The app will run in single-thread mode. Consider using a Cloudflare Worker or Netlify for production.

**Vercel (vercel.json):**

```json
{
    "headers": [
        {
            "source": "/(.*)",
            "headers": [
                { "key": "Cross-Origin-Opener-Policy", "value": "same-origin" },
                { "key": "Cross-Origin-Embedder-Policy", "value": "require-corp" },
                { "key": "Cross-Origin-Resource-Policy", "value": "cross-origin" }
            ]
        }
    ]
}
```

### MIME Type

Ensure `.wasm` files are served with `Content-Type: application/wasm`. Most servers handle this correctly, but if WASM loading fails, check your MIME type configuration.

### Pre-compressed Delivery

After running `make optimize`, serve the `.wasm.gz` files with `Content-Encoding: gzip` for faster loading. The dev server (`make serve`) handles this automatically.

## Supported File Formats

| Format | Extensions | Max Recommended Size |
|--------|-----------|---------------------|
| CSV | `.csv` | ~500K rows |
| JSON Array | `.json` | ~200K rows |
| NDJSON | `.json` (newline-delimited) | ~300K rows |
| Parquet | `.parquet` | ~1M rows |

Actual limits depend on available browser memory. The WASM linear memory is typically 2-4 GB. The chunk-based storage system (10,000 rows per chunk) automatically activates when estimated memory exceeds 70% of available memory.

### Type Inference

The parser automatically detects column types:

| Pattern | Inferred Type |
|---------|--------------|
| Pure integers | `int` |
| Decimal numbers | `float` |
| ISO 8601 dates | `date` |
| `true`/`false` | `bool` |
| Mixed numbers + text | `string` (with dirty row marking) |

## Query DSL Reference

### Basic Syntax

```sql
SELECT columns FROM table_name [WHERE condition] [GROUP BY col agg_func(col)] [ORDER BY col [ASC|DESC]] [LIMIT n]
```

### SELECT

```sql
SELECT * FROM data
SELECT name, age FROM data
SELECT name, AVG(salary) FROM data GROUP BY department AVG(salary)
```

### WHERE Operators

| Operator | Example |
|----------|---------|
| `=` | `WHERE status = 'active'` |
| `!=` | `WHERE status != 'closed'` |
| `>` `<` `>=` `<=` | `WHERE age > 30` |
| `BETWEEN` | `WHERE age BETWEEN 20 AND 65` |
| `IN` | `WHERE status IN ('active', 'pending')` |
| `IS NULL` | `WHERE email IS NULL` |
| `IS NOT NULL` | `WHERE email IS NOT NULL` |
| `LIKE` | `WHERE name LIKE '%john%'` |
| `AND` / `OR` | `WHERE age > 30 AND status = 'active'` |

### Aggregate Functions

| Function | Description |
|----------|-------------|
| `SUM(col)` | Sum of values |
| `COUNT(col)` | Count of non-null values |
| `AVG(col)` | Average (mean) |
| `MIN(col)` | Minimum value |
| `MAX(col)` | Maximum value |
| `STDDEV(col)` | Standard deviation |
| `COUNT_DISTINCT(col)` | Count of unique values |
| `PERCENTILE(col, P50)` | Percentile (P50/P90/P95/P99) |

### GROUP BY

```sql
SELECT department, AVG(salary) FROM data GROUP BY department AVG(salary)
SELECT category, SUM(amount) FROM data GROUP BY category SUM(amount)
```

### ORDER BY

```sql
SELECT * FROM data ORDER BY age DESC
SELECT name, salary FROM data ORDER BY salary ASC LIMIT 10
```

### JOIN

```sql
SELECT orders.amount, users.level FROM orders JOIN users ON orders.user_id = users.id
SELECT * FROM orders LEFT JOIN users ON orders.user_id = users.id WHERE orders.amount > 100
```

| Join Type | Syntax | Behavior |
|-----------|--------|----------|
| INNER JOIN | `JOIN` / `INNER JOIN` | Only matching rows |
| LEFT JOIN | `LEFT JOIN` | All rows from left table, nulls for non-matching |

Join algorithm: Hash Join (builds HashMap from the smaller table, probes with the larger).

## Electron Desktop App

The optional Electron shell provides native file system access, bypassing the browser's `FileReader` API.

### Setup

```bash
cd electron
npm install
```

### Run in Development

```bash
# From project root, build WASM first
make build

# Then start Electron
cd electron
npm start
```

### Build Installers

```bash
cd electron
npm run dist           # All platforms (current OS)
npm run dist:mac       # macOS DMG + ZIP
npm run dist:win       # Windows NSIS + Portable
npm run dist:linux     # Linux AppImage + DEB
```

Output goes to `electron/release/`.

### Native File Access

When running in Electron, the `window.electronAPI` object is available:

```javascript
if (window.electronAPI?.isElectron()) {
    const filePath = await window.electronAPI.openFileDialog();
    const content = await window.electronAPI.readFile(filePath);
}
```

## Project Structure

```
├── cmd/serve/          Dev server with COOP/COEP headers
├── anomaly/            IQR and Z-Score anomaly detection
├── chart/              ChartSpecBuilder + Vega-Lite generation
├── css/                Application styles
├── electron/           Electron desktop shell
├── export/             CSV/JSON/Excel/PNG/SVG export
├── js/                 Frontend JavaScript (App, filters, UI)
├── parser/             CSV/JSON/Parquet parser + type inference
├── persist/            IndexedDB project persistence
├── pivot/              Pivot table with 8 aggregation methods
├── query/              Query DSL parser + Pipeline executor
├── store/              Columnar storage + chunk management + indexes
├── testdata/           Test datasets (iris, titanic)
├── index.html          Application shell
├── main.go             WASM entry point (syscall/js bridge)
├── Makefile            Build pipeline
└── go.mod              Go module definition
```

## Architecture

### Query Pipeline

The query engine uses the Volcano/Pipeline iterator pattern. Each operator implements a `Next()` method that returns a batch of up to 1,024 rows:

```
ScanOp → FilterOp → SelectOp → AggregateOp → SortOp → LimitOp
```

Operators are independently testable and composable — different query plans are built by chaining different operators.

### Memory Management

Columnar storage supports two modes:

- **Flat mode**: Standard Go slices (default for small datasets)
- **Chunked mode**: 10,000-row chunks with lazy loading/unloading

The `MemoryBudgetController` estimates memory before loading and automatically switches to chunked mode when the estimate exceeds 70% of available WASM memory.

### Chart Spec Generation

`ChartSpecBuilder` generates Vega-Lite specs from column metadata (name, type, min/max/mean, cardinality, null count), decoupled from query results. This enables:

- Independent unit testing of chart spec logic
- Chart preview without re-querying data
- Reusable spec generation for different data sources

## Browser Compatibility

| Browser | Multi-threading | Single-thread Fallback |
|---------|----------------|----------------------|
| Chrome 89+ | ✅ (with COOP/COEP) | ✅ |
| Firefox 89+ | ✅ (with COOP/COEP) | ✅ |
| Edge 89+ | ✅ (with COOP/COEP) | ✅ |
| Safari 15.2+ | ✅ (with COOP/COEP) | ✅ |
| GitHub Pages | ❌ | ✅ (auto-detected) |

When `SharedArrayBuffer` is unavailable, the status bar shows a warning and data processing runs on the main thread.

## License

MIT
