package parser

import (
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"

	"github.com/dataexplorer/store"
)

type ParseResult struct {
	Table     *store.Table
	Errors    []ParseError
	Stats     ParseStats
}

type ParseError struct {
	Row     int
	Col     string
	Message string
	Value   string
}

type ParseStats struct {
	TotalRows    int
	ValidRows    int
	DirtyRows    int
	Columns      int
	ParseTimeMs  int64
}

type Parser struct {
	DateFormat []string
	NullValues map[string]bool
}

func NewParser() *Parser {
	return &Parser{
		DateFormat: []string{
			"2006-01-02",
			"2006/01/02",
			"01/02/2006",
			"2006-01-02T15:04:05",
			"2006-01-02 15:04:05",
			time.RFC3339,
		},
		NullValues: map[string]bool{
			"":      true,
			"null":  true,
			"NULL":  true,
			"nil":   true,
			"NA":    true,
			"N/A":   true,
			"na":    true,
			"n/a":   true,
			"None":  true,
			"none":  true,
			"-":     true,
		},
	}
}

func (p *Parser) ParseCSV(reader io.Reader, tableName string) *ParseResult {
	start := time.Now()
	result := &ParseResult{}

	csvReader := csv.NewReader(reader)
	csvReader.LazyQuotes = true
	csvReader.TrimLeadingSpace = true
	csvReader.FieldsPerRecord = -1

	headers, err := csvReader.Read()
	if err != nil {
		result.Errors = append(result.Errors, ParseError{Row: 0, Message: "failed to read headers: " + err.Error()})
		return result
	}

	headers = trimHeaders(headers)

	rows, err := readAllCSV(csvReader)
	if err != nil {
		result.Errors = append(result.Errors, ParseError{Row: 0, Message: "failed to read rows: " + err.Error()})
	}

	result.Stats.TotalRows = len(rows)
	result.Stats.Columns = len(headers)

	samples := p.collectSamples(rows, headers)
	types := p.inferTypes(samples, len(rows))

	table := store.NewTable(tableName)
	table.RowCount = len(rows)

	for i, name := range headers {
		col := table.AddColumn(name, types[i])
		for j, row := range rows {
			if i < len(row) {
				p.setFieldValue(col, j, row[i])
			} else {
				col.NullMap[j] = true
			}
		}
	}

	dirtyRows := make([]bool, len(rows))
	for i, row := range rows {
		for j, val := range row {
			if j < len(headers) {
				if p.isDirtyValue(val, types[j]) {
					dirtyRows[i] = true
					col := table.Columns[j]
					col.DirtyMap[i] = true
				}
			}
		}
	}

	validCount := 0
	for _, dirty := range dirtyRows {
		if !dirty {
			validCount++
		}
	}
	result.Stats.ValidRows = validCount
	result.Stats.DirtyRows = len(rows) - validCount
	result.Stats.ParseTimeMs = time.Since(start).Milliseconds()

	result.Table = table
	return result
}

func (p *Parser) ParseJSON(reader io.Reader, tableName string) *ParseResult {
	start := time.Now()
	result := &ParseResult{}

	var data interface{}
	decoder := json.NewDecoder(reader)
	if err := decoder.Decode(&data); err != nil {
		result.Errors = append(result.Errors, ParseError{Row: 0, Message: "failed to parse JSON: " + err.Error()})
		return result
	}

	var records []map[string]interface{}
	switch d := data.(type) {
	case []interface{}:
		for _, item := range d {
			if m, ok := item.(map[string]interface{}); ok {
				records = append(records, m)
			}
		}
	case map[string]interface{}:
		if arr, ok := d["data"].([]interface{}); ok {
			for _, item := range arr {
				if m, ok := item.(map[string]interface{}); ok {
					records = append(records, m)
				}
			}
		} else {
			records = append(records, d)
		}
	}

	if len(records) == 0 {
		result.Errors = append(result.Errors, ParseError{Message: "no records found in JSON"})
		return result
	}

	result.Stats.TotalRows = len(records)

	headers := p.jsonHeaders(records)
	result.Stats.Columns = len(headers)

	samples := p.jsonSamples(records, headers)
	types := p.inferTypes(samples, len(records))

	table := store.NewTable(tableName)
	table.RowCount = len(records)

	for i, name := range headers {
		col := table.AddColumn(name, types[i])
		for j, rec := range records {
			if val, ok := rec[name]; ok {
				p.setJSONFieldValue(col, j, val, types[i])
			} else {
				col.NullMap[j] = true
			}
		}
	}

	result.Stats.ValidRows = len(records)
	result.Stats.ParseTimeMs = time.Since(start).Milliseconds()
	result.Table = table
	return result
}

func (p *Parser) ParseParquet(reader io.Reader, tableName string) *ParseResult {
	start := time.Now()
	result := &ParseResult{}

	data, err := io.ReadAll(reader)
	if err != nil {
		result.Errors = append(result.Errors, ParseError{Message: "failed to read parquet data: " + err.Error()})
		return result
	}

	if len(data) < 4 || string(data[:4]) != "PAR1" {
		result.Errors = append(result.Errors, ParseError{Message: "not a valid Parquet file (missing PAR1 magic)"})
		return result
	}

	result.Stats.ParseTimeMs = time.Since(start).Milliseconds()
	result.Errors = append(result.Errors, ParseError{Message: "Parquet support requires server-side processing; use CSV/JSON in browser WASM mode"})
	return result
}

func (p *Parser) Parse(data []byte, format string, tableName string) *ParseResult {
	reader := strings.NewReader(string(data))
	switch strings.ToLower(format) {
	case "csv":
		return p.ParseCSV(reader, tableName)
	case "json":
		return p.ParseJSON(reader, tableName)
	case "parquet":
		return p.ParseParquet(reader, tableName)
	default:
		if isJSONData(data) {
			return p.ParseJSON(reader, tableName)
		}
		return p.ParseCSV(reader, tableName)
	}
}

func (p *Parser) setFieldValue(col *store.Column, row int, value string) {
	if p.NullValues[strings.TrimSpace(value)] {
		col.NullMap[row] = true
		return
	}

	value = strings.TrimSpace(value)

	switch col.DataType {
	case store.TypeInt:
		if v, err := strconv.ParseInt(value, 10, 64); err == nil {
			col.IntData[row] = v
		} else if v, err := strconv.ParseFloat(value, 64); err == nil {
			col.IntData[row] = int64(v)
			col.DirtyMap[row] = true
		} else {
			col.NullMap[row] = true
			col.DirtyMap[row] = true
		}
	case store.TypeFloat:
		if v, err := strconv.ParseFloat(value, 64); err == nil {
			col.FloatData[row] = v
		} else {
			col.NullMap[row] = true
			col.DirtyMap[row] = true
		}
	case store.TypeBool:
		switch strings.ToLower(value) {
		case "true", "1", "yes", "y", "t":
			col.BoolData[row] = true
		case "false", "0", "no", "n", "f":
			col.BoolData[row] = false
		default:
			col.NullMap[row] = true
			col.DirtyMap[row] = true
		}
	case store.TypeDate:
		parsed := false
		for _, fmt := range p.DateFormat {
			if t, err := time.Parse(fmt, value); err == nil {
				col.DateData[row] = t.Unix()
				parsed = true
				break
			}
		}
		if !parsed {
			col.NullMap[row] = true
			col.DirtyMap[row] = true
		}
	case store.TypeString:
		col.StrData[row] = value
	}
}

func (p *Parser) setJSONFieldValue(col *store.Column, row int, value interface{}, dt store.DataType) {
	if value == nil {
		col.NullMap[row] = true
		return
	}

	switch dt {
	case store.TypeInt:
		switch v := value.(type) {
		case float64:
			if v == float64(int64(v)) {
				col.IntData[row] = int64(v)
			} else {
				col.IntData[row] = int64(v)
				col.DirtyMap[row] = true
			}
		case string:
			if n, err := strconv.ParseInt(v, 10, 64); err == nil {
				col.IntData[row] = n
			} else {
				col.NullMap[row] = true
				col.DirtyMap[row] = true
			}
		default:
			col.NullMap[row] = true
		}
	case store.TypeFloat:
		switch v := value.(type) {
		case float64:
			col.FloatData[row] = v
		case string:
			if f, err := strconv.ParseFloat(v, 64); err == nil {
				col.FloatData[row] = f
			} else {
				col.NullMap[row] = true
				col.DirtyMap[row] = true
			}
		default:
			col.NullMap[row] = true
		}
	case store.TypeBool:
		switch v := value.(type) {
		case bool:
			col.BoolData[row] = v
		case string:
			col.BoolData[row] = strings.ToLower(v) == "true"
		default:
			col.NullMap[row] = true
		}
	case store.TypeDate:
		switch v := value.(type) {
		case string:
			parsed := false
			for _, fmt := range p.DateFormat {
				if t, err := time.Parse(fmt, v); err == nil {
					col.DateData[row] = t.Unix()
					parsed = true
					break
				}
			}
			if !parsed {
				col.NullMap[row] = true
				col.DirtyMap[row] = true
			}
		case float64:
			col.DateData[row] = int64(v)
		default:
			col.NullMap[row] = true
		}
	case store.TypeString:
		switch v := value.(type) {
		case string:
			col.StrData[row] = v
		default:
			col.StrData[row] = fmt.Sprintf("%v", v)
		}
	}
}

func (p *Parser) collectSamples(rows [][]string, headers []string) [][]string {
	sampleSize := 1000
	if len(rows) < sampleSize {
		sampleSize = len(rows)
	}

	samples := make([][]string, len(headers))
	for i := range headers {
		samples[i] = make([]string, 0, sampleSize)
	}

	step := 1
	if len(rows) > sampleSize {
		step = len(rows) / sampleSize
	}

	for i := 0; i < len(rows); i += step {
		for j := range headers {
			if j < len(rows[i]) {
				samples[j] = append(samples[j], rows[i][j])
			}
		}
	}

	return samples
}

func (p *Parser) jsonHeaders(records []map[string]interface{}) []string {
	headerSet := make(map[string]bool)
	var headers []string
	for _, rec := range records {
		for k := range rec {
			if !headerSet[k] {
				headerSet[k] = true
				headers = append(headers, k)
			}
		}
	}
	return headers
}

func (p *Parser) jsonSamples(records []map[string]interface{}, headers []string) [][]string {
	sampleSize := 1000
	if len(records) < sampleSize {
		sampleSize = len(records)
	}

	samples := make([][]string, len(headers))
	for i := range headers {
		samples[i] = make([]string, 0, sampleSize)
	}

	step := 1
	if len(records) > sampleSize {
		step = len(records) / sampleSize
	}

	for i := 0; i < len(records); i += step {
		for j, h := range headers {
			if v, ok := records[i][h]; ok {
				samples[j] = append(samples[j], jsonValToStr(v))
			} else {
				samples[j] = append(samples[j], "")
			}
		}
	}

	return samples
}

func (p *Parser) isDirtyValue(value string, dt store.DataType) bool {
	value = strings.TrimSpace(value)
	if p.NullValues[value] {
		return false
	}

	switch dt {
	case store.TypeInt:
		_, err := strconv.ParseInt(value, 10, 64)
		if err != nil {
			if _, err2 := strconv.ParseFloat(value, 64); err2 == nil {
				return true
			}
			return true
		}
	case store.TypeFloat:
		_, err := strconv.ParseFloat(value, 64)
		if err != nil {
			return true
		}
	case store.TypeBool:
		switch strings.ToLower(value) {
		case "true", "false", "1", "0", "yes", "no", "y", "n", "t", "f":
			return false
		default:
			return true
		}
	case store.TypeDate:
		for _, fmt := range p.DateFormat {
			if _, err := time.Parse(fmt, value); err == nil {
				return false
			}
		}
		return true
	}

	return false
}

func trimHeaders(headers []string) []string {
	result := make([]string, len(headers))
	for i, h := range headers {
		result[i] = strings.TrimSpace(h)
		if result[i] == "" {
			result[i] = fmt.Sprintf("col_%d", i)
		}
	}
	return result
}

func readAllCSV(r *csv.Reader) ([][]string, error) {
	var rows [][]string
	for {
		record, err := r.Read()
		if err == io.EOF {
			break
		}
		if err != nil {
			if len(rows) > 0 {
				continue
			}
			return nil, err
		}
		rows = append(rows, record)
	}
	return rows, nil
}

func jsonValToStr(v interface{}) string {
	if v == nil {
		return ""
	}
	switch val := v.(type) {
	case string:
		return val
	case float64:
		if val == float64(int64(val)) {
			return strconv.FormatInt(int64(val), 10)
		}
		return strconv.FormatFloat(val, 'f', -1, 64)
	case bool:
		if val {
			return "true"
		}
		return "false"
	default:
		return fmt.Sprintf("%v", val)
	}
}

func isJSONData(data []byte) bool {
	if len(data) == 0 {
		return false
	}
	first := data[0]
	return first == '{' || first == '['
}
