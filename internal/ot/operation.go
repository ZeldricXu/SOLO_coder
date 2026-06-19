package ot

import (
	"fmt"
)

type OpType int

const (
	Insert OpType = iota
	Delete
	Retain
)

type Operation struct {
	Type     OpType
	Position int
	Text     string
	Length   int
}

func NewInsert(pos int, text string) *Operation {
	return &Operation{
		Type:     Insert,
		Position: pos,
		Text:     text,
		Length:   len([]rune(text)),
	}
}

func NewDelete(pos, length int) *Operation {
	return &Operation{
		Type:     Delete,
		Position: pos,
		Length:   length,
	}
}

func NewRetain(pos, length int) *Operation {
	return &Operation{
		Type:     Retain,
		Position: pos,
		Length:   length,
	}
}

func (op *Operation) String() string {
	switch op.Type {
	case Insert:
		return fmt.Sprintf("Insert(pos=%d, text=%q)", op.Position, op.Text)
	case Delete:
		return fmt.Sprintf("Delete(pos=%d, len=%d)", op.Position, op.Length)
	case Retain:
		return fmt.Sprintf("Retain(pos=%d, len=%d)", op.Position, op.Length)
	default:
		return fmt.Sprintf("Unknown(type=%d)", op.Type)
	}
}

func Apply(doc string, op *Operation) (string, error) {
	runes := []rune(doc)
	docLen := len(runes)

	switch op.Type {
	case Insert:
		if op.Position < 0 || op.Position > docLen {
			return "", fmt.Errorf("insert position %d out of range [0, %d]", op.Position, docLen)
		}
		insertRunes := []rune(op.Text)
		result := make([]rune, 0, docLen+len(insertRunes))
		result = append(result, runes[:op.Position]...)
		result = append(result, insertRunes...)
		result = append(result, runes[op.Position:]...)
		return string(result), nil

	case Delete:
		if op.Position < 0 || op.Position+op.Length > docLen {
			return "", fmt.Errorf("delete range [%d, %d) out of range [0, %d]", op.Position, op.Position+op.Length, docLen)
		}
		if op.Length < 0 {
			return "", fmt.Errorf("delete length %d is negative", op.Length)
		}
		result := make([]rune, 0, docLen-op.Length)
		result = append(result, runes[:op.Position]...)
		result = append(result, runes[op.Position+op.Length:]...)
		return string(result), nil

	case Retain:
		return doc, nil

	default:
		return "", fmt.Errorf("unknown operation type: %d", op.Type)
	}
}

func Invert(op *Operation) *Operation {
	switch op.Type {
	case Insert:
		return NewDelete(op.Position, len([]rune(op.Text)))
	case Delete:
		return NewInsert(op.Position, op.Text)
	case Retain:
		return NewRetain(op.Position, op.Length)
	default:
		return &Operation{Type: op.Type, Position: op.Position, Text: op.Text, Length: op.Length}
	}
}

func ApplyAll(doc string, ops []*Operation) (string, error) {
	var err error
	for _, op := range ops {
		doc, err = Apply(doc, op)
		if err != nil {
			return "", err
		}
	}
	return doc, nil
}
