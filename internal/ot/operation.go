package ot

import (
	"encoding/json"
	"fmt"
	"strings"
)

type OpType string

const (
	OpInsert OpType = "insert"
	OpDelete OpType = "delete"
	OpRetain OpType = "retain"
)

type Operation struct {
	Type    OpType        `json:"type"`
	Length  int           `json:"length,omitempty"`
	Content string        `json:"content,omitempty"`
	Attrs   map[string]interface{} `json:"attrs,omitempty"`
}

type OperationList []Operation

type Step struct {
	Operations OperationList `json:"operations"`
	DocumentID string        `json:"document_id,omitempty"`
	ClientID   string        `json:"client_id,omitempty"`
}

type OperationEnvelope struct {
	Version    int64  `json:"version"`
	Operations Step   `json:"operations"`
	UserID     string `json:"user_id,omitempty"`
	Timestamp  int64  `json:"timestamp"`
	ID         string `json:"id"`
}

func NewInsert(content string, attrs ...map[string]interface{}) Operation {
	op := Operation{
		Type:    OpInsert,
		Content: content,
		Length:  len([]rune(content)),
	}
	if len(attrs) > 0 {
		op.Attrs = attrs[0]
	}
	return op
}

func NewDelete(length int) Operation {
	return Operation{
		Type:   OpDelete,
		Length: length,
	}
}

func NewRetain(length int, attrs ...map[string]interface{}) Operation {
	op := Operation{
		Type:   OpRetain,
		Length: length,
	}
	if len(attrs) > 0 {
		op.Attrs = attrs[0]
	}
	return op
}

func (o Operation) IsInsert() bool { return o.Type == OpInsert }
func (o Operation) IsDelete() bool { return o.Type == OpDelete }
func (o Operation) IsRetain() bool { return o.Type == OpRetain }

func (o Operation) TotalLength() int {
	if o.IsInsert() {
		return len([]rune(o.Content))
	}
	return o.Length
}

func (o Operation) MarshalBinary() ([]byte, error) {
	return json.Marshal(o)
}

func (o *Operation) UnmarshalBinary(data []byte) error {
	return json.Unmarshal(data, o)
}

func (ol OperationList) MarshalBinary() ([]byte, error) {
	return json.Marshal(ol)
}

func (ol *OperationList) UnmarshalBinary(data []byte) error {
	return json.Unmarshal(data, ol)
}

func (ol OperationList) TotalLength() int {
	total := 0
	for _, op := range ol {
		if op.IsInsert() {
			total += len([]rune(op.Content))
		} else if op.IsRetain() {
			total += op.Length
		}
	}
	return total
}

func (ol OperationList) DeleteLength() int {
	total := 0
	for _, op := range ol {
		if op.IsDelete() {
			total += op.Length
		}
	}
	return total
}

func (ol OperationList) String() string {
	var parts []string
	for _, op := range ol {
		switch op.Type {
		case OpInsert:
			parts = append(parts, fmt.Sprintf("Ins(%q)", op.Content))
		case OpDelete:
			parts = append(parts, fmt.Sprintf("Del(%d)", op.Length))
		case OpRetain:
			parts = append(parts, fmt.Sprintf("Ret(%d)", op.Length))
		}
	}
	return strings.Join(parts, ", ")
}

func (s Step) MarshalBinary() ([]byte, error) {
	return json.Marshal(s)
}

func (s *Step) UnmarshalBinary(data []byte) error {
	return json.Unmarshal(data, s)
}

func Apply(doc string, ops OperationList) (string, error) {
	runes := []rune(doc)
	result := make([]rune, 0, len(runes)+ops.TotalLength())
	pos := 0

	for _, op := range ops {
		switch op.Type {
		case OpRetain:
			if pos+op.Length > len(runes) {
				return "", fmt.Errorf("retain out of bounds: pos=%d, len=%d, docLen=%d", pos, op.Length, len(runes))
			}
			result = append(result, runes[pos:pos+op.Length]...)
			pos += op.Length

		case OpInsert:
			result = append(result, []rune(op.Content)...)

		case OpDelete:
			if pos+op.Length > len(runes) {
				return "", fmt.Errorf("delete out of bounds: pos=%d, len=%d, docLen=%d", pos, op.Length, len(runes))
			}
			pos += op.Length
		}
	}

	if pos < len(runes) {
		result = append(result, runes[pos:]...)
	}

	return string(result), nil
}

func Normalize(ops OperationList) OperationList {
	if len(ops) == 0 {
		return ops
	}

	result := make(OperationList, 0, len(ops))
	for _, op := range ops {
		if op.TotalLength() == 0 {
			continue
		}

		if len(result) == 0 {
			result = append(result, op)
			continue
		}

		last := &result[len(result)-1]
		if last.Type == op.Type && attrsEqual(last.Attrs, op.Attrs) {
			switch op.Type {
			case OpRetain, OpDelete:
				last.Length += op.Length
			case OpInsert:
				last.Content += op.Content
			}
			continue
		}

		result = append(result, op)
	}

	trimTrailingRetain := func(ops OperationList) OperationList {
		for len(ops) > 0 && ops[len(ops)-1].IsRetain() {
			ops = ops[:len(ops)-1]
		}
		return ops
	}

	result = trimTrailingRetain(result)
	return result
}

func attrsEqual(a, b map[string]interface{}) bool {
	if len(a) != len(b) {
		return false
	}
	for k, v1 := range a {
		v2, ok := b[k]
		if !ok || !fmtEqual(v1, v2) {
			return false
		}
	}
	return true
}

func fmtEqual(a, b interface{}) bool {
	return fmt.Sprintf("%v", a) == fmt.Sprintf("%v", b)
}

func Compose(a, b OperationList) (OperationList, error) {
	if len(a) == 0 {
		return b, nil
	}
	if len(b) == 0 {
		return a, nil
	}

	result := make(OperationList, 0, len(a)+len(b))
	ai, bi := 0, 0
	aOffset, bOffset := 0, 0

	nextA := func() (Operation, int) {
		op := a[ai]
		if op.IsInsert() {
			remaining := len([]rune(op.Content)) - aOffset
			return op, remaining
		}
		remaining := op.Length - aOffset
		return op, remaining
	}

	nextB := func() (Operation, int) {
		op := b[bi]
		if op.IsInsert() {
			remaining := len([]rune(op.Content)) - bOffset
			return op, remaining
		}
		remaining := op.Length - bOffset
		return op, remaining
	}

	_ = nextA
	_ = nextB

	for ai < len(a) && bi < len(b) {
		aOp := a[ai]
		bOp := b[bi]

		if aOp.IsInsert() && aOffset == 0 {
			result = append(result, aOp)
			ai++
			continue
		}

		if bOp.IsInsert() && bOffset == 0 {
			result = append(result, bOp)
			bi++
			continue
		}

		var aLen, bLen int
		if aOp.IsInsert() {
			aLen = len([]rune(aOp.Content)) - aOffset
		} else {
			aLen = aOp.Length - aOffset
		}
		if bOp.IsInsert() {
			bLen = len([]rune(bOp.Content)) - bOffset
		} else {
			bLen = bOp.Length - bOffset
		}

		minLen := aLen
		if bLen < minLen {
			minLen = bLen
		}

		if aOp.IsDelete() {
			aOffset += minLen
		} else if aOp.IsRetain() {
			if bOp.IsRetain() {
				result = append(result, NewRetain(minLen, mergeAttrs(aOp.Attrs, bOp.Attrs)))
				aOffset += minLen
				bOffset += minLen
			} else if bOp.IsDelete() {
				result = append(result, NewDelete(minLen))
				aOffset += minLen
				bOffset += minLen
			}
		} else if aOp.IsInsert() {
			if bOp.IsRetain() {
				content := string([]rune(aOp.Content)[aOffset : aOffset+minLen])
				result = append(result, NewInsert(content, mergeAttrs(aOp.Attrs, bOp.Attrs)))
				aOffset += minLen
				bOffset += minLen
			} else if bOp.IsDelete() {
				aOffset += minLen
				bOffset += minLen
			}
		}

		if !aOp.IsInsert() {
			if aOffset >= aOp.Length {
				ai++
				aOffset = 0
			}
		} else {
			if aOffset >= len([]rune(aOp.Content)) {
				ai++
				aOffset = 0
			}
		}
		if !bOp.IsInsert() {
			if bOffset >= bOp.Length {
				bi++
				bOffset = 0
			}
		} else {
			if bOffset >= len([]rune(bOp.Content)) {
				bi++
				bOffset = 0
			}
		}
	}

	for ai < len(a) {
		aOp := a[ai]
		result = append(result, aOp)
		ai++
	}

	result = Normalize(result)
	return result, nil
}

func mergeAttrs(a, b map[string]interface{}) map[string]interface{} {
	if len(a) == 0 && len(b) == 0 {
		return nil
	}
	result := make(map[string]interface{})
	for k, v := range a {
		result[k] = v
	}
	for k, v := range b {
		result[k] = v
	}
	return result
}
