package export

import (
	"bytes"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"

	"github.com/dataexplorer/store"
)

func FormatValue(col *store.Column, row int) string {
	if col.IsNull(row) {
		return ""
	}
	switch col.DataType {
	case store.TypeInt:
		return fmt.Sprintf("%d", col.IntData[row])
	case store.TypeFloat:
		return fmt.Sprintf("%g", col.FloatData[row])
	case store.TypeString:
		return col.StrData[row]
	case store.TypeBool:
		if col.BoolData[row] {
			return "true"
		}
		return "false"
	case store.TypeDate:
		return fmt.Sprintf("%d", col.DateData[row])
	default:
		return ""
	}
}

func ExportCSV(table *store.Table) ([]byte, error) {
	var buf bytes.Buffer
	w := csv.NewWriter(&buf)

	headers := make([]string, len(table.Columns))
	for i, col := range table.Columns {
		headers[i] = col.Name
	}
	if err := w.Write(headers); err != nil {
		return nil, fmt.Errorf("writing CSV headers: %w", err)
	}

	for i := 0; i < table.RowCount; i++ {
		row := make([]string, len(table.Columns))
		for j, col := range table.Columns {
			row[j] = FormatValue(col, i)
		}
		if err := w.Write(row); err != nil {
			return nil, fmt.Errorf("writing CSV row %d: %w", i, err)
		}
	}

	w.Flush()
	if err := w.Error(); err != nil {
		return nil, fmt.Errorf("flushing CSV writer: %w", err)
	}
	return buf.Bytes(), nil
}

func ExportJSON(table *store.Table) ([]byte, error) {
	data := table.ToJSON(0, table.RowCount)
	b, err := json.MarshalIndent(data, "", "  ")
	if err != nil {
		return nil, fmt.Errorf("marshaling JSON: %w", err)
	}
	return b, nil
}

func ExportExcel(table *store.Table) ([]byte, error) {
	var buf bytes.Buffer

	buf.WriteString(`<?xml version="1.0"?>`)
	buf.WriteString(`<?mso-application progid="Excel.Sheet"?>`)
	buf.WriteString(`<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet"`)
	buf.WriteString(` xmlns:o="urn:schemas-microsoft-com:office:office"`)
	buf.WriteString(` xmlns:x="urn:schemas-microsoft-com:office:excel"`)
	buf.WriteString(` xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet"`)
	buf.WriteString(` xmlns:html="http://www.w3.org/TR/REC-html40">`)
	buf.WriteString(`<Worksheet ss:Name="Sheet1"><Table>`)

	for range table.Columns {
		buf.WriteString(`<Column ss:Width="100"/>`)
	}

	buf.WriteString(`<Row>`)
	for _, col := range table.Columns {
		buf.WriteString(`<Cell><Data ss:Type="String">`)
		buf.WriteString(escapeXML(col.Name))
		buf.WriteString(`</Data></Cell>`)
	}
	buf.WriteString(`</Row>`)

	for i := 0; i < table.RowCount; i++ {
		buf.WriteString(`<Row>`)
		for _, col := range table.Columns {
			if col.IsNull(i) {
				buf.WriteString(`<Cell><Data ss:Type="String"></Data></Cell>`)
				continue
			}
			switch col.DataType {
			case store.TypeInt:
				buf.WriteString(`<Cell><Data ss:Type="Number">`)
				buf.WriteString(strconv.FormatInt(col.IntData[i], 10))
				buf.WriteString(`</Data></Cell>`)
			case store.TypeFloat:
				buf.WriteString(`<Cell><Data ss:Type="Number">`)
				buf.WriteString(strconv.FormatFloat(col.FloatData[i], 'g', -1, 64))
				buf.WriteString(`</Data></Cell>`)
			case store.TypeString:
				buf.WriteString(`<Cell><Data ss:Type="String">`)
				buf.WriteString(escapeXML(col.StrData[i]))
				buf.WriteString(`</Data></Cell>`)
			case store.TypeBool:
				buf.WriteString(`<Cell><Data ss:Type="Boolean">`)
				if col.BoolData[i] {
					buf.WriteString("1")
				} else {
					buf.WriteString("0")
				}
				buf.WriteString(`</Data></Cell>`)
			case store.TypeDate:
				buf.WriteString(`<Cell><Data ss:Type="DateTime">`)
				buf.WriteString(strconv.FormatInt(col.DateData[i], 10))
				buf.WriteString(`</Data></Cell>`)
			default:
				buf.WriteString(`<Cell><Data ss:Type="String"></Data></Cell>`)
			}
		}
		buf.WriteString(`</Row>`)
	}

	buf.WriteString(`</Table></Worksheet></Workbook>`)
	return buf.Bytes(), nil
}

func escapeXML(s string) string {
	r := strings.NewReplacer(
		"&", "&amp;",
		"<", "&lt;",
		">", "&gt;",
		`"`, "&quot;",
		"'", "&apos;",
	)
	return r.Replace(s)
}

func ChartExportPNG(vegaSpec string) string {
	js := `(function(){` +
		`var spec = ` + vegaSpec + `;` +
		`vegaEmbed('#vis', spec, {renderer: 'canvas'}).then(function(result) {` +
		`return result.view.toImageURL('png');` +
		`}).then(function(url) {` +
		`var a = document.createElement('a');` +
		`a.href = url;` +
		`a.download = 'chart.png';` +
		`document.body.appendChild(a);` +
		`a.click();` +
		`document.body.removeChild(a);` +
		`});` +
		`})()`
	return js
}

func ChartExportSVG(vegaSpec string) string {
	js := `(function(){` +
		`var spec = ` + vegaSpec + `;` +
		`vegaEmbed('#vis', spec).then(function(result) {` +
		`return result.view.toSVG();` +
		`}).then(function(svg) {` +
		`var blob = new Blob([svg], {type: 'image/svg+xml'});` +
		`var url = URL.createObjectURL(blob);` +
		`var a = document.createElement('a');` +
		`a.href = url;` +
		`a.download = 'chart.svg';` +
		`document.body.appendChild(a);` +
		`a.click();` +
		`document.body.removeChild(a);` +
		`URL.revokeObjectURL(url);` +
		`});` +
		`})()`
	return js
}
