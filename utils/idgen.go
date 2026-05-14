package utils

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"time"
)

func GenerateUserID() string {
	return fmt.Sprintf("user_%s", generateRandomHex(6))
}

func GenerateRoleID() string {
	return fmt.Sprintf("role_%s", generateRandomHex(6))
}

func GenerateResourceID() string {
	return fmt.Sprintf("res_%s", generateRandomHex(6))
}

func GenerateSessionID() string {
	return fmt.Sprintf("session_%s", generateRandomHex(12))
}

func GenerateAuditID() string {
	return fmt.Sprintf("audit_%s", generateRandomHex(8))
}

func GenerateMFACode(length int) string {
	if length <= 0 {
		length = 6
	}
	result := ""
	for i := 0; i < length; i++ {
		b := make([]byte, 1)
		_, err := rand.Read(b)
		if err != nil {
			result += fmt.Sprintf("%d", time.Now().UnixNano()%10)
		} else {
			result += fmt.Sprintf("%d", b[0]%10)
		}
	}
	return result
}

func generateRandomHex(n int) string {
	b := make([]byte, n)
	_, err := rand.Read(b)
	if err != nil {
		return fmt.Sprintf("%x", time.Now().UnixNano())
	}
	return hex.EncodeToString(b)
}
