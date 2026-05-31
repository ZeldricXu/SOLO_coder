package policy

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"io"
	"sync"
)

type ProcessContext struct {
	Context      context.Context
	TenantID     string
	BucketName   string
	ObjectKey    string
	ContentType  string
	Metadata     map[string]string
	OriginalSize int64
	Tags         map[string]string
}

type StoragePolicy interface {
	Name() string
	Enabled() bool
	Priority() int
	BeforeUpload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error)
	AfterUpload(ctx *ProcessContext, data []byte) error
	BeforeDownload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error)
	AfterDownload(ctx *ProcessContext, data []byte) error
	Validate(data []byte) error
}

type CompressionPolicy struct {
	enabled      bool
	level        int
	minSize      int64
	contentTypes map[string]bool
}

func NewCompressionPolicy(level int, minSize int64) *CompressionPolicy {
	return &CompressionPolicy{
		enabled: true,
		level:   level,
		minSize: minSize,
		contentTypes: map[string]bool{
			"text/plain":      true,
			"text/html":       true,
			"text/css":        true,
			"text/javascript": true,
			"application/json": true,
			"application/xml": true,
		},
	}
}

func (p *CompressionPolicy) Name() string { return "compression" }
func (p *CompressionPolicy) Enabled() bool { return p.enabled }
func (p *CompressionPolicy) Priority() int { return 100 }
func (p *CompressionPolicy) SetEnabled(enabled bool) { p.enabled = enabled }

func (p *CompressionPolicy) BeforeUpload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error) {
	if !p.enabled {
		return data, ctx, nil
	}
	if int64(len(data)) < p.minSize {
		return data, ctx, nil
	}
	if !p.contentTypes[ctx.ContentType] {
		return data, ctx, nil
	}
	var buf bytes.Buffer
	writer, err := gzip.NewWriterLevel(&buf, p.level)
	if err != nil {
		return data, ctx, err
	}
	if _, err := writer.Write(data); err != nil {
		writer.Close()
		return data, ctx, err
	}
	writer.Close()
	compressed := buf.Bytes()
	if len(compressed) >= len(data) {
		return data, ctx, nil
	}
	ctx.Metadata["Content-Encoding"] = "gzip"
	ctx.Metadata["Original-Size"] = string(rune(len(data)))
	return compressed, ctx, nil
}

func (p *CompressionPolicy) AfterUpload(ctx *ProcessContext, data []byte) error {
	return nil
}

func (p *CompressionPolicy) BeforeDownload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error) {
	return data, ctx, nil
}

func (p *CompressionPolicy) AfterDownload(ctx *ProcessContext, data []byte) error {
	if ctx.Metadata["Content-Encoding"] != "gzip" {
		return nil
	}
	return nil
}

func (p *CompressionPolicy) Validate(data []byte) error {
	return nil
}

func (p *CompressionPolicy) Decompress(data []byte) ([]byte, error) {
	reader, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	return io.ReadAll(reader)
}

type EncryptionPolicy struct {
	enabled     bool
	encryptionKey []byte
	mu          sync.RWMutex
}

func NewEncryptionPolicy(key string) *EncryptionPolicy {
	keyBytes := []byte(key)
	if len(keyBytes) < 32 {
		padded := make([]byte, 32)
		copy(padded, keyBytes)
		keyBytes = padded
	}
	return &EncryptionPolicy{
		enabled:       true,
		encryptionKey: keyBytes,
	}
}

func (p *EncryptionPolicy) Name() string { return "encryption" }
func (p *EncryptionPolicy) Enabled() bool { return p.enabled }
func (p *EncryptionPolicy) Priority() int { return 50 }
func (p *EncryptionPolicy) SetEnabled(enabled bool) { p.enabled = enabled }

func (p *EncryptionPolicy) BeforeUpload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error) {
	if !p.enabled {
		return data, ctx, nil
	}
	encrypted, err := p.encrypt(data)
	if err != nil {
		return data, ctx, err
	}
	ctx.Metadata["X-Encrypted"] = "aes-256-gcm"
	return encrypted, ctx, nil
}

func (p *EncryptionPolicy) AfterUpload(ctx *ProcessContext, data []byte) error {
	return nil
}

func (p *EncryptionPolicy) BeforeDownload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error) {
	if ctx.Metadata["X-Encrypted"] != "aes-256-gcm" {
		return data, ctx, nil
	}
	decrypted, err := p.decrypt(data)
	if err != nil {
		return data, ctx, err
	}
	delete(ctx.Metadata, "X-Encrypted")
	return decrypted, ctx, nil
}

func (p *EncryptionPolicy) AfterDownload(ctx *ProcessContext, data []byte) error {
	return nil
}

func (p *EncryptionPolicy) Validate(data []byte) error {
	return nil
}

func (p *EncryptionPolicy) encrypt(plaintext []byte) ([]byte, error) {
	block, err := aes.NewCipher(p.encryptionKey)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	ciphertext := gcm.Seal(nonce, nonce, plaintext, nil)
	return []byte(base64.StdEncoding.EncodeToString(ciphertext)), nil
}

func (p *EncryptionPolicy) decrypt(ciphertext []byte) ([]byte, error) {
	decoded, err := base64.StdEncoding.DecodeString(string(ciphertext))
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(p.encryptionKey)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonceSize := gcm.NonceSize()
	if len(decoded) < nonceSize {
		return nil, errors.New("ciphertext too short")
	}
	nonce, ct := decoded[:nonceSize], decoded[nonceSize:]
	return gcm.Open(nil, nonce, ct, nil)
}

type DeduplicationPolicy struct {
	enabled bool
	store   map[string]int64
	mu      sync.RWMutex
}

func NewDeduplicationPolicy() *DeduplicationPolicy {
	return &DeduplicationPolicy{
		enabled: true,
		store:   make(map[string]int64),
	}
}

func (p *DeduplicationPolicy) Name() string { return "deduplication" }
func (p *DeduplicationPolicy) Enabled() bool { return p.enabled }
func (p *DeduplicationPolicy) Priority() int { return 10 }
func (p *DeduplicationPolicy) SetEnabled(enabled bool) { p.enabled = enabled }

func (p *DeduplicationPolicy) BeforeUpload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error) {
	if !p.enabled {
		return data, ctx, nil
	}
	etag := ctx.Metadata["etag"]
	if etag == "" {
		return data, ctx, nil
	}
	p.mu.RLock()
	refCount, exists := p.store[etag]
	p.mu.RUnlock()
	if exists {
		ctx.Metadata["X-Dedup"] = "true"
		ctx.Metadata["X-Ref-Count"] = string(rune(refCount + 1))
		p.mu.Lock()
		p.store[etag]++
		p.mu.Unlock()
		return nil, ctx, nil
	}
	p.mu.Lock()
	p.store[etag] = 1
	p.mu.Unlock()
	return data, ctx, nil
}

func (p *DeduplicationPolicy) AfterUpload(ctx *ProcessContext, data []byte) error {
	return nil
}

func (p *DeduplicationPolicy) BeforeDownload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error) {
	return data, ctx, nil
}

func (p *DeduplicationPolicy) AfterDownload(ctx *ProcessContext, data []byte) error {
	return nil
}

func (p *DeduplicationPolicy) Validate(data []byte) error {
	return nil
}

type CachePolicy struct {
	enabled    bool
	cache      map[string][]byte
	maxSize    int64
	currentSize int64
	mu         sync.RWMutex
}

func NewCachePolicy(maxSize int64) *CachePolicy {
	return &CachePolicy{
		enabled: true,
		cache:   make(map[string][]byte),
		maxSize: maxSize,
	}
}

func (p *CachePolicy) Name() string { return "cache" }
func (p *CachePolicy) Enabled() bool { return p.enabled }
func (p *CachePolicy) Priority() int { return 200 }
func (p *CachePolicy) SetEnabled(enabled bool) { p.enabled = enabled }

func (p *CachePolicy) BeforeUpload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error) {
	return data, ctx, nil
}

func (p *CachePolicy) AfterUpload(ctx *ProcessContext, data []byte) error {
	if !p.enabled {
		return nil
	}
	key := ctx.TenantID + ":" + ctx.BucketName + ":" + ctx.ObjectKey
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.currentSize+int64(len(data)) > p.maxSize {
		p.evict()
	}
	p.cache[key] = data
	p.currentSize += int64(len(data))
	return nil
}

func (p *CachePolicy) BeforeDownload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error) {
	if !p.enabled {
		return data, ctx, nil
	}
	key := ctx.TenantID + ":" + ctx.BucketName + ":" + ctx.ObjectKey
	p.mu.RLock()
	cached, exists := p.cache[key]
	p.mu.RUnlock()
	if exists {
		ctx.Metadata["X-Cache-Hit"] = "true"
		return cached, ctx, nil
	}
	ctx.Metadata["X-Cache-Hit"] = "false"
	return data, ctx, nil
}

func (p *CachePolicy) AfterDownload(ctx *ProcessContext, data []byte) error {
	if !p.enabled || ctx.Metadata["X-Cache-Hit"] == "true" {
		return nil
	}
	key := ctx.TenantID + ":" + ctx.BucketName + ":" + ctx.ObjectKey
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.currentSize+int64(len(data)) > p.maxSize {
		p.evict()
	}
	p.cache[key] = data
	p.currentSize += int64(len(data))
	return nil
}

func (p *CachePolicy) Validate(data []byte) error {
	return nil
}

func (p *CachePolicy) evict() {
	for k, v := range p.cache {
		delete(p.cache, k)
		p.currentSize -= int64(len(v))
		if p.currentSize < p.maxSize/2 {
			break
		}
	}
}

func (p *CachePolicy) Invalidate(tenantID, bucketName, objectKey string) {
	key := tenantID + ":" + bucketName + ":" + objectKey
	p.mu.Lock()
	defer p.mu.Unlock()
	if data, exists := p.cache[key]; exists {
		delete(p.cache, key)
		p.currentSize -= int64(len(data))
	}
}

type PolicyManager interface {
	AddPolicy(policy StoragePolicy)
	RemovePolicy(name string)
	GetPolicy(name string) StoragePolicy
	ListPolicies() []StoragePolicy
	SetPolicyEnabled(name string, enabled bool) error
	ApplyBeforeUpload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error)
	ApplyAfterUpload(ctx *ProcessContext, data []byte) error
	ApplyBeforeDownload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error)
	ApplyAfterDownload(ctx *ProcessContext, data []byte) error
}

type policyManager struct {
	mu       sync.RWMutex
	policies map[string]StoragePolicy
	ordered  []StoragePolicy
}

func NewPolicyManager() PolicyManager {
	return &policyManager{
		policies: make(map[string]StoragePolicy),
	}
}

func (m *policyManager) AddPolicy(policy StoragePolicy) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.policies[policy.Name()] = policy
	m.reorderPolicies()
}

func (m *policyManager) RemovePolicy(name string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.policies, name)
	m.reorderPolicies()
}

func (m *policyManager) GetPolicy(name string) StoragePolicy {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.policies[name]
}

func (m *policyManager) ListPolicies() []StoragePolicy {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]StoragePolicy, len(m.ordered))
	copy(result, m.ordered)
	return result
}

func (m *policyManager) SetPolicyEnabled(name string, enabled bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	policy, exists := m.policies[name]
	if !exists {
		return errors.New("policy not found")
	}
	if e, ok := policy.(interface{ SetEnabled(bool) }); ok {
		e.SetEnabled(enabled)
	}
	return nil
}

func (m *policyManager) reorderPolicies() {
	m.ordered = make([]StoragePolicy, 0, len(m.policies))
	for _, p := range m.policies {
		m.ordered = append(m.ordered, p)
	}
	for i := range m.ordered {
		for j := i + 1; j < len(m.ordered); j++ {
			if m.ordered[i].Priority() > m.ordered[j].Priority() {
				m.ordered[i], m.ordered[j] = m.ordered[j], m.ordered[i]
			}
		}
	}
}

func (m *policyManager) ApplyBeforeUpload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var err error
	for _, policy := range m.ordered {
		if !policy.Enabled() {
			continue
		}
		data, ctx, err = policy.BeforeUpload(ctx, data)
		if err != nil {
			return data, ctx, err
		}
	}
	return data, ctx, nil
}

func (m *policyManager) ApplyAfterUpload(ctx *ProcessContext, data []byte) error {
	m.mu.RLock()
	defer m.mu.RUnlock()
	for _, policy := range m.ordered {
		if !policy.Enabled() {
			continue
		}
		if err := policy.AfterUpload(ctx, data); err != nil {
			return err
		}
	}
	return nil
}

func (m *policyManager) ApplyBeforeDownload(ctx *ProcessContext, data []byte) ([]byte, *ProcessContext, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var err error
	for i := len(m.ordered) - 1; i >= 0; i-- {
		policy := m.ordered[i]
		if !policy.Enabled() {
			continue
		}
		data, ctx, err = policy.BeforeDownload(ctx, data)
		if err != nil {
			return data, ctx, err
		}
	}
	return data, ctx, nil
}

func (m *policyManager) ApplyAfterDownload(ctx *ProcessContext, data []byte) error {
	m.mu.RLock()
	defer m.mu.RUnlock()
	for i := len(m.ordered) - 1; i >= 0; i-- {
		policy := m.ordered[i]
		if !policy.Enabled() {
			continue
		}
		if err := policy.AfterDownload(ctx, data); err != nil {
			return err
		}
	}
	return nil
}
