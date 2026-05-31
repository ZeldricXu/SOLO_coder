package timeseries

import (
	"bytes"
	"encoding/binary"
	"encoding/gob"
	"errors"
	"math"
	"time"
)

type CompressionAlgorithm string

const (
	CompressionGorilla   CompressionAlgorithm = "gorilla"
	CompressionDelta     CompressionAlgorithm = "delta"
	CompressionSimple8b  CompressionAlgorithm = "simple8b"
)

type CompressedSeries struct {
	Algorithm CompressionAlgorithm
	Data      []byte
	Count     int
	MinTime   time.Time
	MaxTime   time.Time
	MinValue  float64
	MaxValue  float64
}

type Compressor interface {
	Compress(points []DataPoint) (*CompressedSeries, error)
}

type Decompressor interface {
	Decompress(series *CompressedSeries) ([]DataPoint, error)
}

type CompressionCodec interface {
	Compressor
	Decompressor
	Algorithm() CompressionAlgorithm
}

type GorillaCodec struct {
	prevTimestamp int64
	prevValue     uint64
	prevDelta     int64
	buffer        *bytes.Buffer
	bitPos        int
}

func NewGorillaCodec() CompressionCodec {
	return &GorillaCodec{buffer: new(bytes.Buffer)}
}

func (c *GorillaCodec) Algorithm() CompressionAlgorithm {
	return CompressionGorilla
}

func (c *GorillaCodec) Compress(points []DataPoint) (*CompressedSeries, error) {
	if len(points) == 0 {
		return nil, errors.New("no data points to compress")
	}

	c.buffer.Reset()
	c.bitPos = 0

	var minVal, maxVal float64 = math.Inf(1), math.Inf(-1)

	for i, point := range points {
		if point.Value < minVal {
			minVal = point.Value
		}
		if point.Value > maxVal {
			maxVal = point.Value
		}

		if i == 0 {
			c.writeFirstPoint(point)
		} else {
			c.writeCompressedPoint(point)
		}
	}

	c.flushBits()

	return &CompressedSeries{
		Algorithm: CompressionGorilla,
		Data:      c.buffer.Bytes(),
		Count:     len(points),
		MinTime:   points[0].Timestamp,
		MaxTime:   points[len(points)-1].Timestamp,
		MinValue:  minVal,
		MaxValue:  maxVal,
	}, nil
}

func (c *GorillaCodec) Decompress(series *CompressedSeries) ([]DataPoint, error) {
	if series == nil {
		return nil, errors.New("compressed series is nil")
	}
	if series.Algorithm != CompressionGorilla {
		return nil, errors.New("invalid algorithm for gorilla decompressor")
	}
	if series.Count < 0 {
		return nil, errors.New("negative count in compressed series")
	}
	if len(series.Data) == 0 {
		return nil, errors.New("empty compressed data")
	}
	if series.Count > 0 && len(series.Data) < 16 {
		return nil, errors.New("compressed data too short for header")
	}

	d := &GorillaDecompressor{data: series.Data}
	return d.Decompress(series.Count)
}

func (c *GorillaCodec) writeFirstPoint(p DataPoint) {
	ts := p.Timestamp.UnixNano()
	val := math.Float64bits(p.Value)

	binary.Write(c.buffer, binary.BigEndian, ts)
	binary.Write(c.buffer, binary.BigEndian, val)

	c.prevTimestamp = ts
	c.prevValue = val
}

func (c *GorillaCodec) writeCompressedPoint(p DataPoint) {
	ts := p.Timestamp.UnixNano()
	delta := ts - c.prevTimestamp
	doubleDelta := delta - c.prevDelta

	if c.prevDelta == 0 {
		c.writeBit(1)
		c.writeInt64(delta, 64)
	} else if doubleDelta == 0 {
		c.writeBit(0)
	} else {
		c.writeBit(1)
		c.writeInt64(doubleDelta, 64)
	}

	c.prevTimestamp = ts
	c.prevDelta = delta

	val := math.Float64bits(p.Value)
	xor := c.prevValue ^ val

	if xor == 0 {
		c.writeBit(0)
	} else {
		c.writeBit(1)
		leading := leadingZeros(xor)
		trailing := trailingZeros(xor)
		c.writeInt64(int64(leading), 5)
		c.writeInt64(int64(64-leading-trailing), 6)
		c.writeBits(xor, 64-leading-trailing)
	}

	c.prevValue = val
}

func (c *GorillaCodec) writeBit(bit int) {
	if c.bitPos == 0 {
		c.buffer.WriteByte(0)
	}

	lastByte := c.buffer.Bytes()[c.buffer.Len()-1]
	if bit == 1 {
		lastByte |= 1 << (7 - c.bitPos)
		c.buffer.Bytes()[c.buffer.Len()-1] = lastByte
	}

	c.bitPos = (c.bitPos + 1) % 8
}

func (c *GorillaCodec) writeBits(value uint64, bits int) {
	for i := bits - 1; i >= 0; i-- {
		bit := (value >> i) & 1
		c.writeBit(int(bit))
	}
}

func (c *GorillaCodec) writeInt64(value int64, bits int) {
	c.writeBits(uint64(value), bits)
}

func (c *GorillaCodec) flushBits() {
	for c.bitPos != 0 {
		c.writeBit(0)
	}
}

type GorillaDecompressor struct {
	data   []byte
	offset int
	bitPos int
}

func NewGorillaDecompressor(data []byte) *GorillaDecompressor {
	return &GorillaDecompressor{data: data}
}

func (d *GorillaDecompressor) Decompress(count int) ([]DataPoint, error) {
	if count < 0 {
		return nil, errors.New("count cannot be negative")
	}
	if count == 0 {
		return []DataPoint{}, nil
	}
	if len(d.data) < 16 {
		return nil, errors.New("data too short for header")
	}

	points := make([]DataPoint, 0, count)

	prevTimestamp := int64(binary.BigEndian.Uint64(d.data[:8]))
	prevValue := binary.BigEndian.Uint64(d.data[8:16])
	d.offset = 16
	d.bitPos = 0

	points = append(points, DataPoint{
		Timestamp: time.Unix(0, prevTimestamp),
		Value:     math.Float64frombits(prevValue),
	})

	prevDelta := int64(0)
	safeLimit := count + 1000

	for i := 1; i < count; i++ {
		if d.offset >= len(d.data) && d.bitPos >= 8 {
			return points, errors.New("unexpected end of data")
		}
		if i > safeLimit {
			return points, errors.New("decompression loop exceeded safe limit")
		}

		if d.readBit() == 1 {
			prevDelta = d.readInt64(64)
		}
		doubleDelta := int64(0)
		if d.readBit() == 1 {
			doubleDelta = d.readInt64(64)
		}
		prevDelta += doubleDelta
		prevTimestamp += prevDelta

		var xor uint64
		if d.readBit() == 1 {
			leading := int(d.readInt64(5))
			length := int(d.readInt64(6))
			if leading < 0 || leading > 64 || length < 0 || length > 64 || leading+length > 64 {
				return points, errors.New("invalid bit length parameters")
			}
			xor = d.readBits(64-leading-length) << trailingZeros(xor)
		}
		prevValue ^= xor

		points = append(points, DataPoint{
			Timestamp: time.Unix(0, prevTimestamp),
			Value:     math.Float64frombits(prevValue),
		})
	}

	return points, nil
}

func (d *GorillaDecompressor) readBit() int {
	if d.offset >= len(d.data) {
		return 0
	}

	b := d.data[d.offset]
	bit := (b >> (7 - d.bitPos)) & 1
	d.bitPos++
	if d.bitPos == 8 {
		d.bitPos = 0
		d.offset++
	}
	return int(bit)
}

func (d *GorillaDecompressor) readBits(count int) uint64 {
	var result uint64
	for i := 0; i < count; i++ {
		result = (result << 1) | uint64(d.readBit())
	}
	return result
}

func (d *GorillaDecompressor) readInt64(count int) int64 {
	bits := d.readBits(count)
	if count < 64 && (bits&(1<<(count-1))) != 0 {
		bits |= ^((uint64(1) << count) - 1)
	}
	return int64(bits)
}

type DeltaCodec struct{}

func NewDeltaCodec() CompressionCodec {
	return &DeltaCodec{}
}

func (c *DeltaCodec) Algorithm() CompressionAlgorithm {
	return CompressionDelta
}

func (c *DeltaCodec) Compress(points []DataPoint) (*CompressedSeries, error) {
	if len(points) == 0 {
		return nil, errors.New("no data points to compress")
	}

	var buf bytes.Buffer
	enc := gob.NewEncoder(&buf)

	header := struct {
		Count    int
		FirstTs  int64
		FirstVal float64
	}{
		Count:    len(points),
		FirstTs:  points[0].Timestamp.UnixNano(),
		FirstVal: points[0].Value,
	}

	if err := enc.Encode(header); err != nil {
		return nil, err
	}

	prevTs := header.FirstTs
	prevVal := header.FirstVal

	tsDeltas := make([]int64, len(points)-1)
	valDeltas := make([]float64, len(points)-1)

	for i := 1; i < len(points); i++ {
		tsDeltas[i-1] = points[i].Timestamp.UnixNano() - prevTs
		valDeltas[i-1] = points[i].Value - prevVal
		prevTs = points[i].Timestamp.UnixNano()
		prevVal = points[i].Value
	}

	if err := enc.Encode(tsDeltas); err != nil {
		return nil, err
	}
	if err := enc.Encode(valDeltas); err != nil {
		return nil, err
	}

	var minVal, maxVal float64 = math.Inf(1), math.Inf(-1)
	for _, p := range points {
		if p.Value < minVal {
			minVal = p.Value
		}
		if p.Value > maxVal {
			maxVal = p.Value
		}
	}

	return &CompressedSeries{
		Algorithm: CompressionDelta,
		Data:      buf.Bytes(),
		Count:     len(points),
		MinTime:   points[0].Timestamp,
		MaxTime:   points[len(points)-1].Timestamp,
		MinValue:  minVal,
		MaxValue:  maxVal,
	}, nil
}

func (c *DeltaCodec) Decompress(series *CompressedSeries) ([]DataPoint, error) {
	if series == nil {
		return nil, errors.New("compressed series is nil")
	}
	if series.Algorithm != CompressionDelta {
		return nil, errors.New("invalid algorithm for delta decompressor")
	}
	if series.Count < 0 {
		return nil, errors.New("negative count in compressed series")
	}
	if len(series.Data) == 0 {
		return nil, errors.New("empty compressed data")
	}

	buf := bytes.NewBuffer(series.Data)
	dec := gob.NewDecoder(buf)

	var header struct {
		Count    int
		FirstTs  int64
		FirstVal float64
	}
	if err := dec.Decode(&header); err != nil {
		return nil, err
	}

	if header.Count < 0 {
		return nil, errors.New("invalid negative count in header")
	}
	if header.Count > 1e7 {
		return nil, errors.New("count exceeds maximum safe limit")
	}
	if header.Count == 0 {
		return []DataPoint{}, nil
	}

	var tsDeltas []int64
	if err := dec.Decode(&tsDeltas); err != nil {
		return nil, err
	}

	var valDeltas []float64
	if err := dec.Decode(&valDeltas); err != nil {
		return nil, err
	}

	if header.Count != len(tsDeltas)+1 || len(tsDeltas) != len(valDeltas) {
		return nil, errors.New("inconsistent data lengths")
	}

	points := make([]DataPoint, header.Count)
	points[0] = DataPoint{
		Timestamp: time.Unix(0, header.FirstTs),
		Value:     header.FirstVal,
	}

	prevTs := header.FirstTs
	prevVal := header.FirstVal

	for i := 0; i < len(tsDeltas); i++ {
		prevTs += tsDeltas[i]
		prevVal += valDeltas[i]
		points[i+1] = DataPoint{
			Timestamp: time.Unix(0, prevTs),
			Value:     prevVal,
		}
	}

	return points, nil
}

func leadingZeros(x uint64) int {
	if x == 0 {
		return 64
	}
	n := 0
	for (x & (1 << 63)) == 0 {
		n++
		x <<= 1
	}
	return n
}

func trailingZeros(x uint64) int {
	if x == 0 {
		return 64
	}
	n := 0
	for (x & 1) == 0 {
		n++
		x >>= 1
	}
	return n
}

type CodecFactory struct{}

func NewCodecFactory() *CodecFactory {
	return &CodecFactory{}
}

func (f *CodecFactory) Create(algo CompressionAlgorithm) (CompressionCodec, error) {
	switch algo {
	case CompressionGorilla:
		return NewGorillaCodec(), nil
	case CompressionDelta:
		return NewDeltaCodec(), nil
	default:
		return nil, errors.New("unsupported compression algorithm")
	}
}

type CompressionStats struct {
	OriginalSize   int
	CompressedSize int
	Ratio          float64
	PointCount     int
}

func CalculateStats(original []DataPoint, compressed *CompressedSeries) CompressionStats {
	originalSize := len(original) * 16
	compressedSize := len(compressed.Data)
	ratio := 0.0
	if originalSize > 0 {
		ratio = float64(compressedSize) / float64(originalSize)
	}

	return CompressionStats{
		OriginalSize:   originalSize,
		CompressedSize: compressedSize,
		Ratio:          ratio,
		PointCount:     len(original),
	}
}
