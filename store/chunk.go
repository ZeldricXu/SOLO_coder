package store

import (
	"runtime"
	"sync"
)

const DefaultChunkSize = 10000

type Chunk struct {
	IntData   []int64
	FloatData []float64
	StrData   []string
	BoolData  []bool
	DateData  []int64
	NullMap   []bool
	DirtyMap  []bool
	Length    int
	loaded    bool
	mu        sync.RWMutex
}

func NewChunk(dt DataType, size int) *Chunk {
	c := &Chunk{
		Length: size,
		NullMap:  make([]bool, size),
		DirtyMap: make([]bool, size),
		loaded:   true,
	}
	switch dt {
	case TypeInt:
		c.IntData = make([]int64, size)
	case TypeFloat:
		c.FloatData = make([]float64, size)
	case TypeString:
		c.StrData = make([]string, size)
	case TypeBool:
		c.BoolData = make([]bool, size)
	case TypeDate:
		c.DateData = make([]int64, size)
	}
	return c
}

func (c *Chunk) SizeBytes(dt DataType) int {
	if !c.loaded {
		return 0
	}
	base := len(c.NullMap) + len(c.DirtyMap)
	switch dt {
	case TypeInt, TypeDate:
		return base*1 + len(c.IntData)*8
	case TypeFloat:
		return base*1 + len(c.FloatData)*8
	case TypeString:
		sz := base * 1
		for _, s := range c.StrData {
			sz += len(s) + 16
		}
		return sz
	case TypeBool:
		return base*1 + len(c.BoolData)
	}
	return base
}

func (c *Chunk) Unload(dt DataType) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.loaded {
		return
	}
	c.IntData = nil
	c.FloatData = nil
	c.StrData = nil
	c.BoolData = nil
	c.DateData = nil
	c.loaded = false
}

func (c *Chunk) EnsureLoaded(dt DataType) {
	c.mu.RLock()
	if c.loaded {
		c.mu.RUnlock()
		return
	}
	c.mu.RUnlock()

	c.mu.Lock()
	defer c.mu.Unlock()
	if c.loaded {
		return
	}

	size := c.Length
	c.NullMap = make([]bool, size)
	c.DirtyMap = make([]bool, size)
	switch dt {
	case TypeInt:
		c.IntData = make([]int64, size)
	case TypeFloat:
		c.FloatData = make([]float64, size)
	case TypeString:
		c.StrData = make([]string, size)
	case TypeBool:
		c.BoolData = make([]bool, size)
	case TypeDate:
		c.DateData = make([]int64, size)
	}
	c.loaded = true
}

type ChunkedColumn struct {
	Name       string
	DataType   DataType
	Chunks     []*Chunk
	Length     int
	ChunkSize  int
	TotalBytes int64
}

func NewChunkedColumn(name string, dt DataType, length int, chunkSize ...int) *ChunkedColumn {
	cs := DefaultChunkSize
	if len(chunkSize) > 0 && chunkSize[0] > 0 {
		cs = chunkSize[0]
	}

	cc := &ChunkedColumn{
		Name:      name,
		DataType:  dt,
		Length:    length,
		ChunkSize: cs,
	}

	if length > 0 {
		numChunks := (length + cs - 1) / cs
		cc.Chunks = make([]*Chunk, numChunks)
		for i := 0; i < numChunks; i++ {
			chunkLen := cs
			if i == numChunks-1 {
				chunkLen = length - i*cs
			}
			cc.Chunks[i] = NewChunk(dt, chunkLen)
		}
		cc.recalculateSize()
	}

	return cc
}

func (cc *ChunkedColumn) recalculateSize() {
	var total int64
	for _, c := range cc.Chunks {
		total += int64(c.SizeBytes(cc.DataType))
	}
	cc.TotalBytes = total
}

func (cc *ChunkedColumn) GetValue(idx int) interface{} {
	if idx < 0 || idx >= cc.Length {
		return nil
	}
	chunkIdx := idx / cc.ChunkSize
	offset := idx % cc.ChunkSize
	chunk := cc.Chunks[chunkIdx]
	chunk.EnsureLoaded(cc.DataType)

	if chunk.NullMap[offset] {
		return nil
	}
	switch cc.DataType {
	case TypeInt:
		return chunk.IntData[offset]
	case TypeFloat:
		return chunk.FloatData[offset]
	case TypeString:
		return chunk.StrData[offset]
	case TypeBool:
		return chunk.BoolData[offset]
	case TypeDate:
		return chunk.DateData[offset]
	}
	return nil
}

func (cc *ChunkedColumn) SetValue(idx int, val interface{}) {
	if idx < 0 || idx >= cc.Length {
		return
	}
	chunkIdx := idx / cc.ChunkSize
	offset := idx % cc.ChunkSize
	chunk := cc.Chunks[chunkIdx]
	chunk.EnsureLoaded(cc.DataType)

	if val == nil {
		chunk.NullMap[offset] = true
		return
	}
	chunk.NullMap[offset] = false

	switch cc.DataType {
	case TypeInt:
		switch v := val.(type) {
		case int64:
			chunk.IntData[offset] = v
		case int:
			chunk.IntData[offset] = int64(v)
		case float64:
			chunk.IntData[offset] = int64(v)
		default:
			chunk.NullMap[offset] = true
		}
	case TypeFloat:
		switch v := val.(type) {
		case float64:
			chunk.FloatData[offset] = v
		case int64:
			chunk.FloatData[offset] = float64(v)
		case int:
			chunk.FloatData[offset] = float64(v)
		default:
			chunk.NullMap[offset] = true
		}
	case TypeString:
		if v, ok := val.(string); ok {
			chunk.StrData[offset] = v
		} else {
			chunk.NullMap[offset] = true
		}
	case TypeBool:
		if v, ok := val.(bool); ok {
			chunk.BoolData[offset] = v
		} else {
			chunk.NullMap[offset] = true
		}
	case TypeDate:
		switch v := val.(type) {
		case int64:
			chunk.DateData[offset] = v
		default:
			chunk.NullMap[offset] = true
		}
	}
	cc.recalculateSize()
}

func (cc *ChunkedColumn) IsNull(idx int) bool {
	if idx < 0 || idx >= cc.Length {
		return true
	}
	chunkIdx := idx / cc.ChunkSize
	offset := idx % cc.ChunkSize
	chunk := cc.Chunks[chunkIdx]
	chunk.EnsureLoaded(cc.DataType)
	return chunk.NullMap[offset]
}

func (cc *ChunkedColumn) IsDirty(idx int) bool {
	if idx < 0 || idx >= cc.Length {
		return false
	}
	chunkIdx := idx / cc.ChunkSize
	offset := idx % cc.ChunkSize
	chunk := cc.Chunks[chunkIdx]
	chunk.EnsureLoaded(cc.DataType)
	return chunk.DirtyMap[offset]
}

func (cc *ChunkedColumn) SetDirty(idx int, dirty bool) {
	if idx < 0 || idx >= cc.Length {
		return
	}
	chunkIdx := idx / cc.ChunkSize
	offset := idx % cc.ChunkSize
	chunk := cc.Chunks[chunkIdx]
	chunk.EnsureLoaded(cc.DataType)
	chunk.DirtyMap[offset] = dirty
}

func (cc *ChunkedColumn) UnloadChunk(idx int) {
	if idx >= 0 && idx < len(cc.Chunks) {
		cc.Chunks[idx].Unload(cc.DataType)
		cc.recalculateSize()
	}
}

func (cc *ChunkedColumn) LoadChunk(idx int) {
	if idx >= 0 && idx < len(cc.Chunks) {
		cc.Chunks[idx].EnsureLoaded(cc.DataType)
		cc.recalculateSize()
	}
}

func (cc *ChunkedColumn) Stats() (min, max, mean float64, count, nullCount, dirtyCount int) {
	count = cc.Length
	for ci, chunk := range cc.Chunks {
		chunk.EnsureLoaded(cc.DataType)
		for i := 0; i < chunk.Length; i++ {
			if chunk.NullMap[i] {
				nullCount++
				continue
			}
			if chunk.DirtyMap[i] {
				dirtyCount++
			}
			switch cc.DataType {
			case TypeInt:
				v := float64(chunk.IntData[i])
				if nullCount == 0 && dirtyCount == 0 && min == 0 && max == 0 && mean == 0 && ci == 0 && i == 0 {
					min, max = v, v
				}
				if v < min {
					min = v
				}
				if v > max {
					max = v
				}
				mean += v
			case TypeFloat:
				v := chunk.FloatData[i]
				if nullCount == 0 && dirtyCount == 0 && min == 0 && max == 0 && mean == 0 && ci == 0 && i == 0 {
					min, max = v, v
				}
				if v < min {
					min = v
				}
				if v > max {
					max = v
				}
				mean += v
			}
		}
	}
	validCount := count - nullCount
	if validCount > 0 && (cc.DataType == TypeInt || cc.DataType == TypeFloat) {
		mean /= float64(validCount)
	}
	return
}

type MemoryBudgetController struct {
	totalMemory     uint64
	usedMemory      uint64
	threshold       float64
	chunkedMode     bool
	registeredCols  []*ChunkedColumn
}

func NewMemoryBudgetController() *MemoryBudgetController {
	var totalMem uint64
	sysInfo := &runtime.MemStats{}
	runtime.ReadMemStats(sysInfo)
	totalMem = sysInfo.Sys
	if totalMem == 0 {
		totalMem = 2 * 1024 * 1024 * 1024
	}

	return &MemoryBudgetController{
		totalMemory: totalMem,
		threshold:   0.7,
		chunkedMode: false,
	}
}

func (mbc *MemoryBudgetController) SetThreshold(t float64) {
	if t > 0 && t <= 1 {
		mbc.threshold = t
	}
}

func (mbc *MemoryBudgetController) EstimateMemory(rowCount int, columns []struct {
	Name     string
	DataType DataType
}) uint64 {
	var estimate uint64
	for _, col := range columns {
		perRow := uint64(2)
		switch col.DataType {
		case TypeInt, TypeFloat, TypeDate:
			perRow += 8
		case TypeBool:
			perRow += 1
		case TypeString:
			perRow += 24
		}
		estimate += perRow * uint64(rowCount)
	}
	return estimate
}

func (mbc *MemoryBudgetController) ShouldUseChunkedMode(estimated uint64) bool {
	threshold := uint64(float64(mbc.totalMemory) * mbc.threshold)
	return estimated > threshold
}

func (mbc *MemoryBudgetController) RegisterColumn(col *ChunkedColumn) {
	mbc.registeredCols = append(mbc.registeredCols, col)
	mbc.usedMemory += uint64(col.TotalBytes)
	mbc.checkMemoryPressure()
}

func (mbc *MemoryBudgetController) UnregisterColumn(col *ChunkedColumn) {
	for i, c := range mbc.registeredCols {
		if c == col {
			mbc.usedMemory -= uint64(col.TotalBytes)
			mbc.registeredCols = append(mbc.registeredCols[:i], mbc.registeredCols[i+1:]...)
			break
		}
	}
}

func (mbc *MemoryBudgetController) checkMemoryPressure() {
	threshold := uint64(float64(mbc.totalMemory) * mbc.threshold)
	if mbc.usedMemory > threshold && !mbc.chunkedMode {
		mbc.chunkedMode = true
		mbc.evictChunks()
	}
}

func (mbc *MemoryBudgetController) evictChunks() {
	for _, col := range mbc.registeredCols {
		for i := range col.Chunks {
			if i > 0 {
				col.UnloadChunk(i)
			}
		}
	}
	mbc.updateUsedMemory()
}

func (mbc *MemoryBudgetController) updateUsedMemory() {
	var used uint64
	for _, col := range mbc.registeredCols {
		used += uint64(col.TotalBytes)
	}
	mbc.usedMemory = used
}

func (mbc *MemoryBudgetController) IsChunkedMode() bool {
	return mbc.chunkedMode
}

func (mbc *MemoryBudgetController) GetUsagePercent() float64 {
	if mbc.totalMemory == 0 {
		return 0
	}
	return float64(mbc.usedMemory) / float64(mbc.totalMemory) * 100
}
