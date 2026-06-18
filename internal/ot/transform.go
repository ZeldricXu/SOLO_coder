package ot

import (
	"encoding/json"
	"fmt"
)

type TransformResult struct {
	Op1Prime OperationList
	Op2Prime OperationList
}

func Transform(op1, op2 OperationList) (TransformResult, error) {
	result := TransformResult{
		Op1Prime: make(OperationList, 0, len(op1)+len(op2)),
		Op2Prime: make(OperationList, 0, len(op1)+len(op2)),
	}

	i, j := 0, 0
	offset1, offset2 := 0, 0

	for i < len(op1) || j < len(op2) {
		var a, b *Operation
		var aRem, bRem int

		if i < len(op1) {
			a = &op1[i]
			if a.IsInsert() {
				aRem = len([]rune(a.Content)) - offset1
			} else {
				aRem = a.Length - offset1
			}
		}

		if j < len(op2) {
			b = &op2[j]
			if b.IsInsert() {
				bRem = len([]rune(b.Content)) - offset2
			} else {
				bRem = b.Length - offset2
			}
		}

		switch {
		case a != nil && a.IsInsert():
			result.Op1Prime = append(result.Op1Prime, sliceOp(*a, offset1, aRem))
			result.Op2Prime = append(result.Op2Prime, NewRetain(aRem))
			offset1 += aRem
			if offset1 >= totalLen(*a) {
				i++
				offset1 = 0
			}

		case b != nil && b.IsInsert():
			result.Op1Prime = append(result.Op1Prime, NewRetain(bRem))
			result.Op2Prime = append(result.Op2Prime, sliceOp(*b, offset2, bRem))
			offset2 += bRem
			if offset2 >= totalLen(*b) {
				j++
				offset2 = 0
			}

		default:
			if a == nil || b == nil {
				return result, fmt.Errorf("invalid operation pair: a=%v, b=%v", a, b)
			}

			n := aRem
			if bRem < n {
				n = bRem
			}

			switch {
			case a.IsRetain() && b.IsRetain():
				result.Op1Prime = append(result.Op1Prime, NewRetain(n))
				result.Op2Prime = append(result.Op2Prime, NewRetain(n))

			case a.IsRetain() && b.IsDelete():
				result.Op2Prime = append(result.Op2Prime, NewDelete(n))

			case a.IsDelete() && b.IsRetain():
				result.Op1Prime = append(result.Op1Prime, NewDelete(n))

			case a.IsDelete() && b.IsDelete():

			default:
				return result, fmt.Errorf("unexpected op types: a=%s, b=%s", a.Type, b.Type)
			}

			offset1 += n
			offset2 += n

			if !a.IsInsert() {
				if offset1 >= a.Length {
					i++
					offset1 = 0
				}
			}
			if !b.IsInsert() {
				if offset2 >= b.Length {
					j++
					offset2 = 0
				}
			}
		}
	}

	result.Op1Prime = Normalize(result.Op1Prime)
	result.Op2Prime = Normalize(result.Op2Prime)

	return result, nil
}

func totalLen(op Operation) int {
	if op.IsInsert() {
		return len([]rune(op.Content))
	}
	return op.Length
}

func sliceOp(op Operation, offset, length int) Operation {
	switch op.Type {
	case OpInsert:
		content := string([]rune(op.Content)[offset : offset+length])
		return NewInsert(content, op.Attrs)
	case OpDelete:
		return NewDelete(length)
	case OpRetain:
		return NewRetain(length, op.Attrs)
	}
	return Operation{}
}

func TransformAgainstServer(op OperationList, history []OperationList) (OperationList, error) {
	result := make(OperationList, len(op))
	copy(result, op)

	for _, serverOp := range history {
		tr, err := Transform(result, serverOp)
		if err != nil {
			return nil, err
		}
		result = tr.Op1Prime
	}

	return result, nil
}

func ProseMirrorStepToOperations(step map[string]interface{}) (OperationList, error) {
	ops := make(OperationList, 0)

	stepType, _ := step["stepType"].(string)
	switch stepType {
	case "replace", "replaceAround":
		from, _ := toInt(step["from"])
		to, _ := toInt(step["to"])
		structure, ok := step["structure"].(map[string]interface{})

		if from > 0 {
			ops = append(ops, NewRetain(from))
		}

		if to > from {
			ops = append(ops, NewDelete(to-from))
		}

		if ok && structure != nil {
			if content, ok := structure["content"].(string); ok && content != "" {
				ops = append(ops, NewInsert(content))
			}
		}

	case "addMark":
		from, _ := toInt(step["from"])
		to, _ := toInt(step["to"])
		mark, _ := step["mark"].(map[string]interface{})

		if from > 0 {
			ops = append(ops, NewRetain(from))
		}

		attrs := map[string]interface{}{"marks": mark}
		ops = append(ops, NewRetain(to-from, attrs))

	case "removeMark":
		from, _ := toInt(step["from"])
		to, _ := toInt(step["to"])

		if from > 0 {
			ops = append(ops, NewRetain(from))
		}
		ops = append(ops, NewRetain(to-from))

	case "setDocType":
		return nil, nil

	default:
		if from, ok := toInt(step["from"]); ok {
			if from > 0 {
				ops = append(ops, NewRetain(from))
			}
		}
	}

	return Normalize(ops), nil
}

func toInt(v interface{}) (int, bool) {
	switch val := v.(type) {
	case int:
		return val, true
	case float64:
		return int(val), true
	case json.Number:
		n, err := val.Int64()
		return int(n), err == nil
	}
	return 0, false
}

func ProseMirrorStepsToOperations(steps []map[string]interface{}) (OperationList, error) {
	var combined OperationList
	for _, step := range steps {
		ops, err := ProseMirrorStepToOperations(step)
		if err != nil {
			return nil, err
		}
		if len(combined) == 0 {
			combined = ops
		} else {
			composed, err := Compose(combined, ops)
			if err != nil {
				return nil, err
			}
			combined = composed
		}
	}
	return combined, nil
}
