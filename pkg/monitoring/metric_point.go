package monitoring

type MetricPoint struct {
	Name       string
	Value      float64
	Dimensions map[string]string
	Timestamp  int64
}

func NewMetricPoint(name string, value float64, dimensions map[string]string, timestamp int64) *MetricPoint {
	dims := make(map[string]string, len(dimensions))
	for k, v := range dimensions {
		dims[k] = v
	}

	return &MetricPoint{
		Name:       name,
		Value:      value,
		Dimensions: dims,
		Timestamp:  timestamp,
	}
}

func (p *MetricPoint) CopyDimensions() map[string]string {
	result := make(map[string]string, len(p.Dimensions))
	for k, v := range p.Dimensions {
		result[k] = v
	}
	return result
}

func (p *MetricPoint) BuildKey() string {
	key := p.Name
	for k, v := range p.Dimensions {
		key += "|" + k + "=" + v
	}
	return key
}

func (p *MetricPoint) MatchDimensions(filter map[string]string) bool {
	for k, v := range filter {
		if p.Dimensions[k] != v {
			return false
		}
	}
	return true
}
