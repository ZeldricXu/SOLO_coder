package compression

import (
	"bytes"
	"encoding/binary"
	"math"
)

type CompressionAlgorithm interface {
	Encode(points []float64) ([]byte, error)
	Decode(data []byte) ([]float64, error)
	Name() string
}

type GorillaCompressor struct{}

func NewGorillaCompressor() *GorillaCompressor {
	return &GorillaCompressor{}
}

func (g *GorillaCompressor) Name() string {
	return "gorilla"
}

func (g *GorillaCompressor) Encode(points []float64) ([]byte, error) {
	if len(points) == 0 {
		return []byte{}, nil
	}

	var buf bytes.Buffer
	_ = binary.Write(&buf, binary.LittleEndian, uint32(len(points)))

	prev := points[0]
	_ = binary.Write(&buf, binary.LittleEndian, prev)

	for i := 1; i < len(points); i++ {
		curr := points[i]
		xor := math.Float64bits(prev) ^ math.Float64bits(curr)
		_ = binary.Write(&buf, binary.LittleEndian, xor)
		prev = curr
	}

	return buf.Bytes(), nil
}

func (g *GorillaCompressor) Decode(data []byte) ([]float64, error) {
	if len(data) == 0 {
		return []float64{}, nil
	}

	buf := bytes.NewReader(data)
	var count uint32
	_ = binary.Read(buf, binary.LittleEndian, &count)

	points := make([]float64, count)
	if count == 0 {
		return points, nil
	}

	_ = binary.Read(buf, binary.LittleEndian, &points[0])
	prev := points[0]

	for i := 1; i < int(count); i++ {
		var xor uint64
		_ = binary.Read(buf, binary.LittleEndian, &xor)
		curr := math.Float64frombits(math.Float64bits(prev) ^ xor)
		points[i] = curr
		prev = curr
	}

	return points, nil
}

type DeltaCompressor struct{}

func NewDeltaCompressor() *DeltaCompressor {
	return &DeltaCompressor{}
}

func (d *DeltaCompressor) Name() string {
	return "delta"
}

func (d *DeltaCompressor) Encode(points []float64) ([]byte, error) {
	if len(points) == 0 {
		return []byte{}, nil
	}

	var buf bytes.Buffer
	_ = binary.Write(&buf, binary.LittleEndian, uint32(len(points)))

	prev := points[0]
	_ = binary.Write(&buf, binary.LittleEndian, prev)

	for i := 1; i < len(points); i++ {
		delta := points[i] - prev
		_ = binary.Write(&buf, binary.LittleEndian, delta)
		prev = points[i]
	}

	return buf.Bytes(), nil
}

func (d *DeltaCompressor) Decode(data []byte) ([]float64, error) {
	if len(data) == 0 {
		return []float64{}, nil
	}

	buf := bytes.NewReader(data)
	var count uint32
	_ = binary.Read(buf, binary.LittleEndian, &count)

	points := make([]float64, count)
	if count == 0 {
		return points, nil
	}

	_ = binary.Read(buf, binary.LittleEndian, &points[0])

	for i := 1; i < int(count); i++ {
		var delta float64
		_ = binary.Read(buf, binary.LittleEndian, &delta)
		points[i] = points[i-1] + delta
	}

	return points, nil
}

type Simple8bCompressor struct{}

func NewSimple8bCompressor() *Simple8bCompressor {
	return &Simple8bCompressor{}
}

func (s *Simple8bCompressor) Name() string {
	return "simple8b"
}

func (s *Simple8bCompressor) Encode(points []float64) ([]byte, error) {
	var buf bytes.Buffer
	_ = binary.Write(&buf, binary.LittleEndian, uint32(len(points)))
	for _, p := range points {
		_ = binary.Write(&buf, binary.LittleEndian, p)
	}
	return buf.Bytes(), nil
}

func (s *Simple8bCompressor) Decode(data []byte) ([]float64, error) {
	if len(data) == 0 {
		return []float64{}, nil
	}

	buf := bytes.NewReader(data)
	var count uint32
	_ = binary.Read(buf, binary.LittleEndian, &count)

	points := make([]float64, count)
	for i := 0; i < int(count); i++ {
		_ = binary.Read(buf, binary.LittleEndian, &points[i])
	}

	return points, nil
}
