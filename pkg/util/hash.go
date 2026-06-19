package util

import (
	"crypto/md5"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"hash/fnv"
	"sort"
	"strconv"
	"strings"

	"github.com/cespare/xxhash/v2"
)

func HashParams(params map[string]interface{}) string {
	keys := make([]string, 0, len(params))
	for k := range params {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	var sb strings.Builder
	for _, k := range keys {
		sb.WriteString(k)
		sb.WriteString("=")
		sb.WriteString(toString(params[k]))
		sb.WriteString("&")
	}
	joined := strings.TrimSuffix(sb.String(), "&")

	h := sha256.Sum256([]byte(joined))
	return hex.EncodeToString(h[:])
}

func HashString(s string) string {
	h := xxhash.Sum64([]byte(s))
	return strconv.FormatUint(h, 10)
}

func HashStringMD5(s string) string {
	h := md5.Sum([]byte(s))
	return hex.EncodeToString(h[:])
}

func HashStringSHA1(s string) string {
	h := sha1.Sum([]byte(s))
	return hex.EncodeToString(h[:])
}

func HashStringSHA256(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

func HashBytes(b []byte) uint64 {
	return xxhash.Sum64(b)
}

func HashBytesFNV(b []byte) uint64 {
	h := fnv.New64a()
	h.Write(b)
	return h.Sum64()
}

func HashInt(i int64) uint64 {
	b := make([]byte, 8)
	for idx := 7; idx >= 0; idx-- {
		b[idx] = byte(i)
		i >>= 8
	}
	return HashBytes(b)
}

func HashCombine(hashes ...uint64) uint64 {
	var result uint64 = 17
	for _, h := range hashes {
		result = result*31 + h
	}
	return result
}

func ConsistentHash(key string, buckets int) int {
	if buckets <= 0 {
		return 0
	}
	h := HashBytes([]byte(key))
	return int(h % uint64(buckets))
}

func toString(v interface{}) string {
	switch val := v.(type) {
	case string:
		return val
	case int:
		return strconv.Itoa(val)
	case int64:
		return strconv.FormatInt(val, 10)
	case uint64:
		return strconv.FormatUint(val, 10)
	case float64:
		return strconv.FormatFloat(val, 'f', -1, 64)
	case bool:
		return strconv.FormatBool(val)
	case []byte:
		return hex.EncodeToString(val)
	case fmt.Stringer:
		return val.String()
	default:
		return fmt.Sprintf("%v", val)
	}
}
