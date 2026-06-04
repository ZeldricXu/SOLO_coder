package cron

import (
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"
)

type Schedule struct {
	Second     map[int]bool
	Minute     map[int]bool
	Hour       map[int]bool
	DayOfMonth map[int]bool
	Month      map[int]bool
	DayOfWeek  map[int]bool
}

func Parse(expr string) (*Schedule, error) {
	fields := strings.Fields(expr)
	if len(fields) != 5 && len(fields) != 6 {
		return nil, fmt.Errorf("invalid cron expression: expected 5 or 6 fields")
	}

	second := map[int]bool{0: true}
	var err error
	offset := 0

	if len(fields) == 6 {
		second, err = parseField(fields[0], 0, 59)
		if err != nil {
			return nil, fmt.Errorf("invalid second field: %w", err)
		}
		offset = 1
	}

	minute, err := parseField(fields[offset], 0, 59)
	if err != nil {
		return nil, fmt.Errorf("invalid minute field: %w", err)
	}

	hour, err := parseField(fields[offset+1], 0, 23)
	if err != nil {
		return nil, fmt.Errorf("invalid hour field: %w", err)
	}

	dayOfMonth, err := parseField(fields[offset+2], 1, 31)
	if err != nil {
		return nil, fmt.Errorf("invalid day of month field: %w", err)
	}

	month, err := parseField(fields[offset+3], 1, 12)
	if err != nil {
		return nil, fmt.Errorf("invalid month field: %w", err)
	}

	dayOfWeek, err := parseField(fields[offset+4], 0, 6)
	if err != nil {
		return nil, fmt.Errorf("invalid day of week field: %w", err)
	}

	return &Schedule{
		Second:     second,
		Minute:     minute,
		Hour:       hour,
		DayOfMonth: dayOfMonth,
		Month:      month,
		DayOfWeek:  dayOfWeek,
	}, nil
}

func parseField(field string, min, max int) (map[int]bool, error) {
	result := make(map[int]bool)

	if field == "*" {
		for i := min; i <= max; i++ {
			result[i] = true
		}
		return result, nil
	}

	parts := strings.Split(field, ",")
	for _, part := range parts {
		if strings.Contains(part, "/") {
			stepParts := strings.Split(part, "/")
			rangePart := stepParts[0]
			step, err := strconv.Atoi(stepParts[1])
			if err != nil {
				return nil, fmt.Errorf("invalid step: %s", stepParts[1])
			}

			var start, end int
			if rangePart == "*" {
				start = min
				end = max
			} else if strings.Contains(rangePart, "-") {
				rangeParts := strings.Split(rangePart, "-")
				start, err = strconv.Atoi(rangeParts[0])
				if err != nil {
					return nil, fmt.Errorf("invalid range start: %s", rangeParts[0])
				}
				end, err = strconv.Atoi(rangeParts[1])
				if err != nil {
					return nil, fmt.Errorf("invalid range end: %s", rangeParts[1])
				}
			} else {
				start, err = strconv.Atoi(rangePart)
				if err != nil {
					return nil, fmt.Errorf("invalid value: %s", rangePart)
				}
				end = max
			}

			for i := start; i <= end; i += step {
				if i >= min && i <= max {
					result[i] = true
				}
			}
		} else if strings.Contains(part, "-") {
			rangeParts := strings.Split(part, "-")
			start, err := strconv.Atoi(rangeParts[0])
			if err != nil {
				return nil, fmt.Errorf("invalid range start: %s", rangeParts[0])
			}
			end, err := strconv.Atoi(rangeParts[1])
			if err != nil {
				return nil, fmt.Errorf("invalid range end: %s", rangeParts[1])
			}

			for i := start; i <= end; i++ {
				if i >= min && i <= max {
					result[i] = true
				}
			}
		} else {
			val, err := strconv.Atoi(part)
			if err != nil {
				return nil, fmt.Errorf("invalid value: %s", part)
			}
			if val >= min && val <= max {
				result[val] = true
			}
		}
	}

	return result, nil
}

func (s *Schedule) Next(from time.Time) time.Time {
	t := from.Add(time.Second)
	t = t.Truncate(time.Second)

	for t.Year() < from.Year()+5 {
		if !s.Month[int(t.Month())] {
			t = time.Date(t.Year(), t.Month()+1, 1, 0, 0, 0, 0, t.Location())
			continue
		}

		if !s.DayOfMonth[t.Day()] || !s.DayOfWeek[int(t.Weekday())] {
			t = time.Date(t.Year(), t.Month(), t.Day()+1, 0, 0, 0, 0, t.Location())
			continue
		}

		if !s.Hour[t.Hour()] {
			t = t.Add(time.Hour)
			t = time.Date(t.Year(), t.Month(), t.Day(), t.Hour(), 0, 0, 0, t.Location())
			continue
		}

		if !s.Minute[t.Minute()] {
			t = t.Add(time.Minute)
			t = time.Date(t.Year(), t.Month(), t.Day(), t.Hour(), t.Minute(), 0, 0, t.Location())
			continue
		}

		if !s.Second[t.Second()] {
			t = t.Add(time.Second)
			continue
		}

		return t
	}

	return time.Time{}
}

func (s *Schedule) IsDue(t time.Time) bool {
	return s.Second[t.Second()] &&
		s.Minute[t.Minute()] &&
		s.Hour[t.Hour()] &&
		s.DayOfMonth[t.Day()] &&
		s.Month[int(t.Month())] &&
		s.DayOfWeek[int(t.Weekday())]
}

func GetShard(taskID string, shardCount int) int {
	hash := 0
	for _, c := range taskID {
		hash = (hash << 5) - hash + int(c)
	}
	return int(math.Abs(float64(hash))) % shardCount
}
