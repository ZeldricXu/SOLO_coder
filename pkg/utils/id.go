package utils

import (
	"crypto/rand"
	"encoding/hex"
	"sync"
	"time"
)

var (
	counter uint32
	mu      sync.Mutex
)

func GenerateID() string {
	mu.Lock()
	defer mu.Unlock()

	b := make([]byte, 12)
	counter++
	timestamp := uint32(time.Now().Unix())
	b[0] = byte(timestamp >> 24)
	b[1] = byte(timestamp >> 16)
	b[2] = byte(timestamp >> 8)
	b[3] = byte(timestamp)
	b[4] = byte(counter >> 24)
	b[5] = byte(counter >> 16)
	b[6] = byte(counter >> 8)
	b[7] = byte(counter)
	rand.Read(b[8:])
	return hex.EncodeToString(b)
}
