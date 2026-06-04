package store

type DataType int

const (
	TypeUnknown DataType = iota
	TypeInt
	TypeFloat
	TypeString
	TypeBool
	TypeDate
)

func (dt DataType) String() string {
	switch dt {
	case TypeInt:
		return "int"
	case TypeFloat:
		return "float"
	case TypeString:
		return "string"
	case TypeBool:
		return "bool"
	case TypeDate:
		return "date"
	default:
		return "unknown"
	}
}

type Column struct {
	Name       string
	DataType   DataType
	IntData    []int64
	FloatData  []float64
	StrData    []string
	BoolData   []bool
	DateData   []int64
	NullMap    []bool
	DirtyMap   []bool
	Length     int
	Chunked    *ChunkedColumn
	MemoryCtrl *MemoryBudgetController
}

func NewColumn(name string, dt DataType, length int) *Column {
	c := &Column{
		Name:     name,
		DataType: dt,
		Length:   length,
		NullMap:  make([]bool, length),
		DirtyMap: make([]bool, length),
	}
	switch dt {
	case TypeInt:
		c.IntData = make([]int64, length)
	case TypeFloat:
		c.FloatData = make([]float64, length)
	case TypeString:
		c.StrData = make([]string, length)
	case TypeBool:
		c.BoolData = make([]bool, length)
	case TypeDate:
		c.DateData = make([]int64, length)
	}
	return c
}

func NewColumnWithChunking(name string, dt DataType, length int, mbc *MemoryBudgetController) *Column {
	cc := NewChunkedColumn(name, dt, length)
	c := &Column{
		Name:       name,
		DataType:   dt,
		Length:     length,
		Chunked:    cc,
		MemoryCtrl: mbc,
		NullMap:    make([]bool, 0),
		DirtyMap:   make([]bool, 0),
	}
	if mbc != nil {
		mbc.RegisterColumn(cc)
	}
	return c
}

func (c *Column) IsChunked() bool {
	return c.Chunked != nil
}

func (c *Column) EnsureFallback() {
	if !c.IsChunked() {
		return
	}
	if len(c.IntData) == c.Length || len(c.FloatData) == c.Length || len(c.StrData) == c.Length {
		return
	}
	c.NullMap = make([]bool, c.Length)
	c.DirtyMap = make([]bool, c.Length)
	switch c.DataType {
	case TypeInt:
		c.IntData = make([]int64, c.Length)
	case TypeFloat:
		c.FloatData = make([]float64, c.Length)
	case TypeString:
		c.StrData = make([]string, c.Length)
	case TypeBool:
		c.BoolData = make([]bool, c.Length)
	case TypeDate:
		c.DateData = make([]int64, c.Length)
	}
	if c.Chunked != nil {
		chunked := c.Chunked
		c.Chunked = nil
		for i := 0; i < c.Length; i++ {
			v := chunked.GetValue(i)
			if v == nil {
				c.NullMap[i] = true
			} else {
				c.SetValue(i, v)
			}
		}
	}
}

func (c *Column) GetInt(i int) int64 {
	if c.IsChunked() {
		if v, ok := c.Chunked.GetValue(i).(int64); ok {
			return v
		}
		return 0
	}
	if c.DataType == TypeInt {
		return c.IntData[i]
	}
	return 0
}

func (c *Column) GetFloat(i int) float64 {
	if c.IsChunked() {
		switch v := c.Chunked.GetValue(i).(type) {
		case float64:
			return v
		case int64:
			return float64(v)
		}
		return 0
	}
	switch c.DataType {
	case TypeFloat:
		return c.FloatData[i]
	case TypeInt:
		return float64(c.IntData[i])
	}
	return 0
}

func (c *Column) GetString(i int) string {
	if c.IsChunked() {
		if v, ok := c.Chunked.GetValue(i).(string); ok {
			return v
		}
		return ""
	}
	if c.DataType == TypeString {
		return c.StrData[i]
	}
	return ""
}

func (c *Column) GetBool(i int) bool {
	if c.IsChunked() {
		if v, ok := c.Chunked.GetValue(i).(bool); ok {
			return v
		}
		return false
	}
	if c.DataType == TypeBool {
		return c.BoolData[i]
	}
	return false
}

func (c *Column) IsNull(i int) bool {
	if c.IsChunked() {
		return c.Chunked.IsNull(i)
	}
	if i < 0 || i >= c.Length {
		return true
	}
	return c.NullMap[i]
}

func (c *Column) IsDirty(i int) bool {
	if c.IsChunked() {
		return c.Chunked.IsDirty(i)
	}
	if i < 0 || i >= c.Length {
		return false
	}
	return c.DirtyMap[i]
}

func (c *Column) SetValue(i int, val interface{}) {
	if c.IsChunked() {
		c.Chunked.SetValue(i, val)
		return
	}
	if i < 0 || i >= c.Length {
		return
	}
	switch c.DataType {
	case TypeInt:
		if v, ok := val.(int64); ok {
			c.IntData[i] = v
		} else if v, ok := val.(int); ok {
			c.IntData[i] = int64(v)
		} else if v, ok := val.(float64); ok {
			c.IntData[i] = int64(v)
		} else {
			c.NullMap[i] = true
		}
	case TypeFloat:
		if v, ok := val.(float64); ok {
			c.FloatData[i] = v
		} else if v, ok := val.(int64); ok {
			c.FloatData[i] = float64(v)
		} else if v, ok := val.(int); ok {
			c.FloatData[i] = float64(v)
		} else {
			c.NullMap[i] = true
		}
	case TypeString:
		if v, ok := val.(string); ok {
			c.StrData[i] = v
		} else {
			c.NullMap[i] = true
		}
	case TypeBool:
		if v, ok := val.(bool); ok {
			c.BoolData[i] = v
		} else {
			c.NullMap[i] = true
		}
	case TypeDate:
		if v, ok := val.(int64); ok {
			c.DateData[i] = v
		} else {
			c.NullMap[i] = true
		}
	}
}

func (c *Column) UniqueValues() []interface{} {
	seen := make(map[interface{}]bool)
	var result []interface{}
	for i := 0; i < c.Length; i++ {
		if c.NullMap[i] {
			continue
		}
		var v interface{}
		switch c.DataType {
		case TypeInt:
			v = c.IntData[i]
		case TypeFloat:
			v = c.FloatData[i]
		case TypeString:
			v = c.StrData[i]
		case TypeBool:
			v = c.BoolData[i]
		case TypeDate:
			v = c.DateData[i]
		}
		if !seen[v] {
			seen[v] = true
			result = append(result, v)
		}
	}
	return result
}

func (c *Column) Stats() (min, max, mean float64, count, nullCount, dirtyCount int) {
	if c.IsChunked() {
		return c.Chunked.Stats()
	}
	count = c.Length
	for i := 0; i < c.Length; i++ {
		if c.NullMap[i] {
			nullCount++
			continue
		}
		if c.DirtyMap[i] {
			dirtyCount++
		}
		switch c.DataType {
		case TypeInt:
			v := float64(c.IntData[i])
			if nullCount == 0 && dirtyCount == 0 && min == 0 && max == 0 && mean == 0 {
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
			v := c.FloatData[i]
			if nullCount == 0 && dirtyCount == 0 && min == 0 && max == 0 && mean == 0 {
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
	validCount := count - nullCount
	if validCount > 0 && (c.DataType == TypeInt || c.DataType == TypeFloat) {
		mean /= float64(validCount)
	}
	return
}

func (c *Column) Clone() *Column {
	if c.IsChunked() {
		c.EnsureFallback()
	}
	clone := &Column{
		Name:     c.Name,
		DataType: c.DataType,
		Length:   c.Length,
		NullMap:  make([]bool, c.Length),
		DirtyMap: make([]bool, c.Length),
	}
	copy(clone.NullMap, c.NullMap)
	copy(clone.DirtyMap, c.DirtyMap)
	switch c.DataType {
	case TypeInt:
		clone.IntData = make([]int64, c.Length)
		copy(clone.IntData, c.IntData)
	case TypeFloat:
		clone.FloatData = make([]float64, c.Length)
		copy(clone.FloatData, c.FloatData)
	case TypeString:
		clone.StrData = make([]string, c.Length)
		copy(clone.StrData, c.StrData)
	case TypeBool:
		clone.BoolData = make([]bool, c.Length)
		copy(clone.BoolData, c.BoolData)
	case TypeDate:
		clone.DateData = make([]int64, c.Length)
		copy(clone.DateData, c.DateData)
	}
	return clone
}
