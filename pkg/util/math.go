package util

import (
	"math"
)

func Min[T int | int64 | uint64 | float64](a, b T) T {
	if a < b {
		return a
	}
	return b
}

func Max[T int | int64 | uint64 | float64](a, b T) T {
	if a > b {
		return a
	}
	return b
}

func Abs[T int | int64 | float64](x T) T {
	if x < 0 {
		return -x
	}
	return x
}

func Clamp[T int | int64 | uint64 | float64](value, min, max T) T {
	if value < min {
		return min
	}
	if value > max {
		return max
	}
	return value
}

func Sum[T int | int64 | uint64 | float64](values []T) T {
	var sum T
	for _, v := range values {
		sum += v
	}
	return sum
}

func Average[T int | int64 | uint64 | float64](values []T) float64 {
	if len(values) == 0 {
		return 0
	}
	return float64(Sum(values)) / float64(len(values))
}

func Percentile[T int | int64 | uint64 | float64](values []T, p float64) float64 {
	if len(values) == 0 {
		return 0
	}
	if p < 0 {
		p = 0
	}
	if p > 100 {
		p = 100
	}

	sorted := make([]float64, len(values))
	for i, v := range values {
		sorted[i] = float64(v)
	}

	for i := 0; i < len(sorted)-1; i++ {
		for j := i + 1; j < len(sorted); j++ {
			if sorted[i] > sorted[j] {
				sorted[i], sorted[j] = sorted[j], sorted[i]
			}
		}
	}

	idx := (p / 100) * float64(len(sorted)-1)
	integer := int(math.Floor(idx))
	decimal := idx - float64(integer)

	if integer >= len(sorted)-1 {
		return sorted[len(sorted)-1]
	}

	return sorted[integer] + decimal*(sorted[integer+1]-sorted[integer])
}

func StdDev[T int | int64 | uint64 | float64](values []T) float64 {
	if len(values) == 0 {
		return 0
	}

	avg := Average(values)
	var sumSquares float64
	for _, v := range values {
		diff := float64(v) - avg
		sumSquares += diff * diff
	}

	variance := sumSquares / float64(len(values))
	return math.Sqrt(variance)
}

func Round(val float64, precision int) float64 {
	ratio := math.Pow(10, float64(precision))
	return math.Round(val*ratio) / ratio
}

func CeilDiv[T int | int64](a, b T) T {
	if b == 0 {
		return 0
	}
	return (a + b - 1) / b
}

func FloorDiv[T int | int64](a, b T) T {
	if b == 0 {
		return 0
	}
	return a / b
}

func Mod[T int | int64](a, b T) T {
	if b == 0 {
		return 0
	}
	return a % b
}

func Power(base, exp int) int64 {
	result := int64(1)
	for i := 0; i < exp; i++ {
		result *= int64(base)
	}
	return result
}

func GCD(a, b int64) int64 {
	for b != 0 {
		a, b = b, a%b
	}
	return a
}

func LCM(a, b int64) int64 {
	if a == 0 || b == 0 {
		return 0
	}
	return Abs(a*b) / GCD(a, b)
}

func IsPrime(n int64) bool {
	if n < 2 {
		return false
	}
	if n == 2 {
		return true
	}
	if n%2 == 0 {
		return false
	}
	for i := int64(3); i*i <= n; i += 2 {
		if n%i == 0 {
			return false
		}
	}
	return true
}

func NextPowerOf2(n int) int {
	if n <= 0 {
		return 1
	}
	n--
	n |= n >> 1
	n |= n >> 2
	n |= n >> 4
	n |= n >> 8
	n |= n >> 16
	n++
	return n
}

func IsPowerOf2(n int64) bool {
	return n > 0 && (n&(n-1)) == 0
}
