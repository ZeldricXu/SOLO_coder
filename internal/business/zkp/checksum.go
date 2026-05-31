package zkp

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
)

func computeChecksum(resp *VerifyResponse) string {
	buf := new(bytes.Buffer)
	_ = binary.Write(buf, binary.LittleEndian, resp.ID)
	_ = binary.Write(buf, binary.LittleEndian, resp.Verified)
	_ = binary.Write(buf, binary.LittleEndian, resp.Result)
	_ = binary.Write(buf, binary.LittleEndian, resp.VerifiedAt.UnixNano())
	h := sha256.Sum256(buf.Bytes())
	return bytesToHex(h[:])
}

func bytesToHex(b []byte) string {
	const hexChars = "0123456789abcdef"
	result := make([]byte, len(b)*2)
	for i, v := range b {
		result[i*2] = hexChars[v>>4]
		result[i*2+1] = hexChars[v&0x0f]
	}
	return string(result)
}
