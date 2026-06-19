package ot

import (
	"fmt"
	"math/rand"
)

func Transform(op1, op2 *Operation) (*Operation, *Operation) {
	if op1 == nil || op2 == nil {
		return op1, op2
	}

	op1Prime := cloneOp(op1)
	op2Prime := cloneOp(op2)

	switch {
	case op1.Type == Insert && op2.Type == Insert:
		transformInsertInsert(op1, op2, op1Prime, op2Prime)

	case op1.Type == Insert && op2.Type == Delete:
		transformInsertDelete(op1, op2, op1Prime, op2Prime)

	case op1.Type == Insert && op2.Type == Retain:
		op2Prime = cloneOp(op2)
		if op1.Position <= op2.Position {
			op2Prime.Position += runeLen(op1.Text)
		}

	case op1.Type == Delete && op2.Type == Insert:
		transformDeleteInsert(op1, op2, op1Prime, op2Prime)

	case op1.Type == Delete && op2.Type == Delete:
		transformDeleteDelete(op1, op2, op1Prime, op2Prime)

	case op1.Type == Delete && op2.Type == Retain:
		op2Prime = cloneOp(op2)
		delEnd := op1.Position + op1.Length
		retainEnd := op2.Position + op2.Length
		if delEnd <= op2.Position {
			op2Prime.Position -= op1.Length
		} else if op1.Position >= retainEnd {
		} else {
			overlapStart := op1.Position
			if op2.Position > overlapStart {
				overlapStart = op2.Position
			}
			overlapEnd := delEnd
			if retainEnd < overlapEnd {
				overlapEnd = retainEnd
			}
			overlap := overlapEnd - overlapStart
			if overlap < 0 {
				overlap = 0
			}
			if op2.Position < op1.Position {
				op2Prime.Length = op1.Position - op2.Position
			} else {
				op2Prime.Position = op1.Position
				op2Prime.Length = op2.Length - overlap
				if op2Prime.Length < 0 {
					op2Prime.Length = 0
				}
			}
		}

	case op1.Type == Retain && op2.Type == Insert:
		op1Prime = cloneOp(op1)
		if op2.Position <= op1.Position {
			op1Prime.Position += runeLen(op2.Text)
		}

	case op1.Type == Retain && op2.Type == Delete:
		op1Prime = cloneOp(op1)
		delEnd := op2.Position + op2.Length
		retainEnd := op1.Position + op1.Length
		if delEnd <= op1.Position {
			op1Prime.Position -= op2.Length
		} else if op2.Position >= retainEnd {
		} else {
			overlapStart := op2.Position
			if op1.Position > overlapStart {
				overlapStart = op1.Position
			}
			overlapEnd := delEnd
			if retainEnd < overlapEnd {
				overlapEnd = retainEnd
			}
			overlap := overlapEnd - overlapStart
			if overlap < 0 {
				overlap = 0
			}
			if op1.Position < op2.Position {
				op1Prime.Length = op2.Position - op1.Position
			} else {
				op1Prime.Position = op2.Position
				op1Prime.Length = op1.Length - overlap
				if op1Prime.Length < 0 {
					op1Prime.Length = 0
				}
			}
		}

	case op1.Type == Retain && op2.Type == Retain:
	}

	return op1Prime, op2Prime
}

func cloneOp(op *Operation) *Operation {
	return &Operation{
		Type:     op.Type,
		Position: op.Position,
		Text:     op.Text,
		Length:   op.Length,
	}
}

func runeLen(s string) int { return len([]rune(s)) }

func transformInsertInsert(op1, op2, op1p, op2p *Operation) {
	len1 := runeLen(op1.Text)

	if op1.Position < op2.Position {
		op2p.Position += len1
	} else if op1.Position > op2.Position {
		op1p.Position += runeLen(op2.Text)
	} else {
		op2p.Position += len1
	}
}

func transformInsertDelete(op1, op2, op1p, op2p *Operation) {
	len1 := runeLen(op1.Text)
	delEnd := op2.Position + op2.Length

	if op1.Position <= op2.Position {
		op2p.Position += len1
	} else if op1.Position >= delEnd {
	} else {
		op1p.Position = op2.Position
		op2p.Length = delEnd - op1.Position
		op2p.Position += len1
	}
}

func transformDeleteInsert(op1, op2, op1p, op2p *Operation) {
	len2 := runeLen(op2.Text)
	delEnd := op1.Position + op1.Length

	if op2.Position <= op1.Position {
		op1p.Position += len2
	} else if op2.Position >= delEnd {
	} else {
		op2p.Position = op1.Position
		op1p.Length = delEnd - op2.Position
	}
}

func transformDeleteDelete(op1, op2, op1p, op2p *Operation) {
	end1 := op1.Position + op1.Length
	end2 := op2.Position + op2.Length

	if end1 <= op2.Position {
		op2p.Position -= op1.Length
	} else if end2 <= op1.Position {
		op1p.Position -= op2.Length
	} else {
		overlapStart := op1.Position
		if op2.Position > overlapStart {
			overlapStart = op2.Position
		}
		overlapEnd := end1
		if end2 < overlapEnd {
			overlapEnd = end2
		}
		overlap := overlapEnd - overlapStart
		if overlap < 0 {
			overlap = 0
		}

		if op1.Position <= op2.Position {
			op1p.Position = op1.Position
			op1p.Length = op1.Length
			if op2.Position < end1 {
				op2p.Position = op1.Position
				op2p.Length = op2.Length - overlap
			} else {
				op2p.Position = op2.Position - op1.Length
				op2p.Length = op2.Length
			}
		} else {
			op2p.Position = op2.Position
			op2p.Length = op2.Length
			if op1.Position < end2 {
				op1p.Position = op2.Position
				op1p.Length = op1.Length - overlap
			} else {
				op1p.Position = op1.Position - op2.Length
				op1p.Length = op1.Length
			}
		}

		if op1p.Length < 0 {
			op1p.Length = 0
		}
		if op2p.Length < 0 {
			op2p.Length = 0
		}
	}
}

func TransformPair(opsA, opsB []*Operation) ([]*Operation, []*Operation) {
	opsAPrime := make([]*Operation, 0, len(opsA))
	opsBPrime := make([]*Operation, 0, len(opsB))

	workingB := cloneOps(opsB)

	for _, a := range opsA {
		curA := cloneOp(a)
		newWorkingB := make([]*Operation, 0, len(workingB))
		for _, b := range workingB {
			aP, bP := Transform(curA, b)
			newWorkingB = append(newWorkingB, bP)
			curA = aP
		}
		opsAPrime = append(opsAPrime, curA)
		workingB = newWorkingB
	}

	workingA := cloneOps(opsA)
	for _, b := range opsB {
		curB := cloneOp(b)
		newWorkingA := make([]*Operation, 0, len(workingA))
		for _, a := range workingA {
			bP, aP := Transform(curB, a)
			newWorkingA = append(newWorkingA, aP)
			curB = bP
		}
		opsBPrime = append(opsBPrime, curB)
		workingA = newWorkingA
	}

	return opsAPrime, opsBPrime
}

func cloneOps(ops []*Operation) []*Operation {
	if ops == nil {
		return nil
	}
	result := make([]*Operation, len(ops))
	for i, op := range ops {
		result[i] = cloneOp(op)
	}
	return result
}

func Compose(ops []*Operation) ([]*Operation, error) {
	if len(ops) <= 1 {
		return cloneOps(ops), nil
	}

	result := make([]*Operation, 0, len(ops))
	result = append(result, cloneOp(ops[0]))

	for i := 1; i < len(ops); i++ {
		op := ops[i]
		last := result[len(result)-1]

		if last.Type == Insert && op.Type == Insert &&
			last.Position+runeLen(last.Text) == op.Position {
			last.Text += op.Text
			last.Length = runeLen(last.Text)
			continue
		}

		if last.Type == Delete && op.Type == Delete &&
			op.Position+op.Length == last.Position {
			last.Position = op.Position
			last.Length += op.Length
			last.Text = op.Text + last.Text
			continue
		}

		if last.Type == Delete && op.Type == Delete &&
			last.Position == op.Position {
			last.Length += op.Length
			last.Text += op.Text
			continue
		}

		if last.Type == Retain && op.Type == Retain &&
			last.Position+last.Length == op.Position {
			last.Length += op.Length
			continue
		}

		result = append(result, cloneOp(op))
	}

	return result, nil
}

func RandomOperation(doc string, rng *rand.Rand) *Operation {
	runes := []rune(doc)
	docLen := len(runes)

	opType := Insert

	switch opType {
	case Insert:
		pos := 0
		if docLen > 0 {
			pos = rng.Intn(docLen + 1)
		}
		textLen := rng.Intn(3) + 1
		textRunes := make([]rune, textLen)
		for i := range textRunes {
			textRunes[i] = rune('a' + rng.Intn(26))
		}
		return NewInsert(pos, string(textRunes))

	default:
		return NewInsert(0, "a")
	}
}

func Converges(doc string, opsA, opsB []*Operation) (bool, string, string, error) {
	opsAPrime, opsBPrime := TransformPair(opsA, opsB)

	docA := doc
	var err error
	for _, op := range opsA {
		docA, err = Apply(docA, op)
		if err != nil {
			return false, "", "", fmt.Errorf("apply opsA: %w", err)
		}
	}
	for _, op := range opsBPrime {
		if op.Type == Retain {
			continue
		}
		if op.Type == Delete && op.Length == 0 {
			continue
		}
		docA, err = Apply(docA, op)
		if err != nil {
			return false, "", "", fmt.Errorf("apply opsBPrime (%v) on doc %q len=%d: %w", op, docA, len([]rune(docA)), err)
		}
	}

	docB := doc
	for _, op := range opsB {
		docB, err = Apply(docB, op)
		if err != nil {
			return false, "", "", fmt.Errorf("apply opsB: %w", err)
		}
	}
	for _, op := range opsAPrime {
		if op.Type == Retain {
			continue
		}
		if op.Type == Delete && op.Length == 0 {
			continue
		}
		docB, err = Apply(docB, op)
		if err != nil {
			return false, "", "", fmt.Errorf("apply opsAPrime (%v) on doc %q len=%d: %w", op, docB, len([]rune(docB)), err)
		}
	}

	return docA == docB, docA, docB, nil
}
