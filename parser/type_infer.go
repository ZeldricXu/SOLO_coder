package parser

import (
	"strconv"
	"strings"
	"time"

	"github.com/dataexplorer/store"
)

type TypeInferencer struct {
	DateFormat []string
	NullValues map[string]bool
}

func NewTypeInferencer() *TypeInferencer {
	return &TypeInferencer{
		DateFormat: []string{
			"2006-01-02",
			"2006/01/02",
			"01/02/2006",
			"2006-01-02T15:04:05",
			"2006-01-02 15:04:05",
			time.RFC3339,
		},
		NullValues: map[string]bool{
			"": true, "null": true, "NULL": true, "nil": true,
			"NA": true, "N/A": true, "na": true, "n/a": true,
		},
	}
}

func (ti *TypeInferencer) InferType(values []string) store.DataType {
	if len(values) == 0 {
		return store.TypeString
	}

	boolCount := 0
	intCount := 0
	floatCount := 0
	dateCount := 0
	stringCount := 0
	totalValid := 0

	for _, v := range values {
		v = strings.TrimSpace(v)
		if ti.NullValues[v] {
			continue
		}
		totalValid++
		dt := ti.detectSingleType(v)
		switch dt {
		case store.TypeBool:
			boolCount++
		case store.TypeInt:
			intCount++
		case store.TypeFloat:
			floatCount++
		case store.TypeDate:
			dateCount++
		case store.TypeString:
			stringCount++
		}
	}

	if totalValid == 0 {
		return store.TypeString
	}

	threshold := 0.6

	if float64(boolCount)/float64(totalValid) >= threshold {
		return store.TypeBool
	}
	if float64(intCount)/float64(totalValid) >= threshold {
		return store.TypeInt
	}
	if float64(intCount+floatCount)/float64(totalValid) >= threshold {
		return store.TypeFloat
	}
	if float64(dateCount)/float64(totalValid) >= threshold {
		return store.TypeDate
	}

	return store.TypeString
}

func (ti *TypeInferencer) detectSingleType(value string) store.DataType {
	if value == "" {
		return store.TypeString
	}

	switch strings.ToLower(value) {
	case "true", "false", "yes", "no", "y", "n", "t", "f", "1", "0":
		if strings.ToLower(value) == "true" || strings.ToLower(value) == "false" ||
			strings.ToLower(value) == "yes" || strings.ToLower(value) == "no" ||
			strings.ToLower(value) == "y" || strings.ToLower(value) == "n" {
			return store.TypeBool
		}
	}

	if _, err := strconv.ParseInt(value, 10, 64); err == nil {
		return store.TypeInt
	}

	if _, err := strconv.ParseFloat(value, 64); err == nil {
		return store.TypeFloat
	}

	for _, fmt := range ti.DateFormat {
		if _, err := time.Parse(fmt, value); err == nil {
			return store.TypeDate
		}
	}

	return store.TypeString
}

func (p *Parser) inferTypes(samples [][]string, totalRows int) []store.DataType {
	inferencer := NewTypeInferencer()
	types := make([]store.DataType, len(samples))
	for i, sample := range samples {
		types[i] = inferencer.InferType(sample)
	}
	return types
}

func InferColumnTypes(values []string) []store.DataType {
	inferencer := NewTypeInferencer()
	return []store.DataType{inferencer.InferType(values)}
}
