package utils

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
)

func CalculateHash(data interface{}) string {
	bytes, err := json.Marshal(data)
	if err != nil {
		return ""
	}
	hash := sha256.Sum256(bytes)
	return hex.EncodeToString(hash[:])
}

func CalculateStringHash(s string) string {
	hash := sha256.Sum256([]byte(s))
	return hex.EncodeToString(hash[:])
}

func CalculateHashWithSalt(data interface{}, salt string) string {
	type hashData struct {
		Data interface{} `json:"data"`
		Salt string      `json:"salt"`
	}
	return CalculateHash(hashData{Data: data, Salt: salt})
}
