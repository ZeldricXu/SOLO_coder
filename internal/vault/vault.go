package vault

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/multicloud/cli/internal/common"
)

type Vault struct {
	mu                sync.RWMutex
	credentials       map[common.CloudProvider]*common.Credential
	storagePath       string
	masterKey         []byte
	keyChain          KeychainBackend
	rotationAlertDays int
}

type KeychainBackend interface {
	Get(service, account string) (string, error)
	Set(service, account, secret string) error
	Delete(service, account string) error
	Exists(service, account string) bool
}

type CredentialMetadata struct {
	CreatedAt      time.Time `json:"created_at"`
	UpdatedAt      time.Time `json:"updated_at"`
	LastRotatedAt  time.Time `json:"last_rotated_at,omitempty"`
	NextRotationAt time.Time `json:"next_rotation_at,omitempty"`
	RotationPeriod int       `json:"rotation_period_days"`
	Encrypted      bool      `json:"encrypted"`
	Source         string    `json:"source"`
}

type storedCredential struct {
	Credential *common.Credential  `json:"credential"`
	Metadata   *CredentialMetadata `json:"metadata"`
}

type FileKeychain struct {
	path string
}

func NewFileKeychain(path string) *FileKeychain {
	return &FileKeychain{path: path}
}

func (k *FileKeychain) Get(service, account string) (string, error) {
	data, err := os.ReadFile(k.path)
	if err != nil {
		return "", err
	}
	var entries map[string]map[string]string
	if err := json.Unmarshal(data, &entries); err != nil {
		return "", err
	}
	if svc, ok := entries[service]; ok {
		if val, ok := svc[account]; ok {
			return val, nil
		}
	}
	return "", fmt.Errorf("not found")
}

func (k *FileKeychain) Set(service, account, secret string) error {
	var entries map[string]map[string]string
	data, err := os.ReadFile(k.path)
	if err == nil {
		json.Unmarshal(data, &entries)
	}
	if entries == nil {
		entries = make(map[string]map[string]string)
	}
	if entries[service] == nil {
		entries[service] = make(map[string]string)
	}
	entries[service][account] = secret
	data, _ = json.MarshalIndent(entries, "", "  ")
	if err := common.EnsureDir(k.path); err != nil {
		return err
	}
	return os.WriteFile(k.path, data, 0600)
}

func (k *FileKeychain) Delete(service, account string) error {
	data, err := os.ReadFile(k.path)
	if err != nil {
		return err
	}
	var entries map[string]map[string]string
	if err := json.Unmarshal(data, &entries); err != nil {
		return err
	}
	if svc, ok := entries[service]; ok {
		delete(svc, account)
	}
	data, _ = json.MarshalIndent(entries, "", "  ")
	return os.WriteFile(k.path, data, 0600)
}

func (k *FileKeychain) Exists(service, account string) bool {
	data, err := os.ReadFile(k.path)
	if err != nil {
		return false
	}
	var entries map[string]map[string]string
	if err := json.Unmarshal(data, &entries); err != nil {
		return false
	}
	if svc, ok := entries[service]; ok {
		_, ok := svc[account]
		return ok
	}
	return false
}

func NewVault(storagePath string) (*Vault, error) {
	absPath, err := filepath.Abs(storagePath)
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, "failed to resolve vault path", err)
	}

	masterKey := deriveMasterKey()
	keychainPath := filepath.Join(filepath.Dir(absPath), "keychain.json")

	return &Vault{
		credentials:       make(map[common.CloudProvider]*common.Credential),
		storagePath:       absPath,
		masterKey:         masterKey,
		keyChain:          NewFileKeychain(keychainPath),
		rotationAlertDays: 7,
	}, nil
}

func deriveMasterKey() []byte {
	salt := common.GetEnv("MULTICLOUD_VAULT_SALT", "multicloud-vault-salt")
	hash := sha256.Sum256([]byte(salt))
	return hash[:]
}

func (v *Vault) encrypt(data []byte) (string, error) {
	block, err := aes.NewCipher(v.masterKey)
	if err != nil {
		return "", common.NewError(common.ErrOperationFailed, "failed to create cipher", err)
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", common.NewError(common.ErrOperationFailed, "failed to create GCM", err)
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", common.NewError(common.ErrOperationFailed, "failed to generate nonce", err)
	}

	ciphertext := gcm.Seal(nonce, nonce, data, nil)
	return base64.StdEncoding.EncodeToString(ciphertext), nil
}

func (v *Vault) decrypt(encrypted string) ([]byte, error) {
	data, err := base64.StdEncoding.DecodeString(encrypted)
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, "failed to decode encrypted data", err)
	}

	block, err := aes.NewCipher(v.masterKey)
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, "failed to create cipher", err)
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, "failed to create GCM", err)
	}

	nonceSize := gcm.NonceSize()
	if len(data) < nonceSize {
		return nil, common.NewError(common.ErrStateCorrupted, "encrypted data too short")
	}

	nonce, ciphertext := data[:nonceSize], data[nonceSize:]
	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return nil, common.NewError(common.ErrOperationFailed, "failed to decrypt data", err)
	}

	return plaintext, nil
}

func (v *Vault) Load() error {
	v.mu.Lock()
	defer v.mu.Unlock()

	if _, err := os.Stat(v.storagePath); os.IsNotExist(err) {
		v.credentials = make(map[common.CloudProvider]*common.Credential)
		return nil
	}

	data, err := os.ReadFile(v.storagePath)
	if err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to read vault file", err)
	}

	var encryptedData map[string]string
	if err := json.Unmarshal(data, &encryptedData); err != nil {
		return common.NewError(common.ErrStateCorrupted, "failed to parse vault file", err)
	}

	v.credentials = make(map[common.CloudProvider]*common.Credential)

	for providerStr, encryptedCred := range encryptedData {
		provider := common.CloudProvider(providerStr)

		var stored storedCredential
		if useEnvVars(provider) {
			cred, err := v.loadFromEnv(provider)
			if err == nil {
				v.credentials[provider] = cred
				continue
			}
		}

		decrypted, err := v.decrypt(encryptedCred)
		if err != nil {
			return err
		}

		if err := json.Unmarshal(decrypted, &stored); err != nil {
			return common.NewError(common.ErrStateCorrupted, "failed to parse credential", err)
		}

		if stored.Credential != nil && stored.Credential.SecretKey != "" {
			if v.keyChain.Exists("multicloud", string(provider)) {
				if secret, err := v.keyChain.Get("multicloud", string(provider)); err == nil {
					stored.Credential.SecretKey = secret
				}
			}
		}

		v.credentials[provider] = stored.Credential
	}

	return nil
}

func useEnvVars(provider common.CloudProvider) bool {
	switch provider {
	case common.ProviderAWS:
		return os.Getenv("AWS_ACCESS_KEY_ID") != ""
	case common.ProviderAzure:
		return os.Getenv("AZURE_TENANT_ID") != ""
	case common.ProviderGCP:
		return os.Getenv("GCP_PROJECT_ID") != ""
	}
	return false
}

func (v *Vault) loadFromEnv(provider common.CloudProvider) (*common.Credential, error) {
	cred := &common.Credential{
		Provider: provider,
	}

	switch provider {
	case common.ProviderAWS:
		cred.AccessKey = os.Getenv("AWS_ACCESS_KEY_ID")
		cred.SecretKey = os.Getenv("AWS_SECRET_ACCESS_KEY")
		cred.SessionToken = os.Getenv("AWS_SESSION_TOKEN")
		cred.Region = os.Getenv("AWS_DEFAULT_REGION")
		if cred.AccessKey == "" || cred.SecretKey == "" {
			return nil, common.NewError(common.ErrUnauthorized, "incomplete AWS credentials in environment")
		}
	case common.ProviderAzure:
		cred.TenantID = os.Getenv("AZURE_TENANT_ID")
		cred.SubscriptionID = os.Getenv("AZURE_SUBSCRIPTION_ID")
		cred.ClientID = os.Getenv("AZURE_CLIENT_ID")
		cred.ClientSecret = os.Getenv("AZURE_CLIENT_SECRET")
		cred.Region = os.Getenv("AZURE_REGION")
		if cred.TenantID == "" || cred.SubscriptionID == "" {
			return nil, common.NewError(common.ErrUnauthorized, "incomplete Azure credentials in environment")
		}
	case common.ProviderGCP:
		cred.ProjectID = os.Getenv("GCP_PROJECT_ID")
		cred.AccessKey = os.Getenv("GCP_SERVICE_ACCOUNT_KEY")
		cred.Region = os.Getenv("GCP_REGION")
		if cred.ProjectID == "" {
			return nil, common.NewError(common.ErrUnauthorized, "incomplete GCP credentials in environment")
		}
	}

	return cred, nil
}

func (v *Vault) Save() error {
	v.mu.Lock()
	defer v.mu.Unlock()

	encryptedData := make(map[string]string)

	for provider, cred := range v.credentials {
		metadata := &CredentialMetadata{
			CreatedAt:      time.Now(),
			UpdatedAt:      time.Now(),
			RotationPeriod: 90,
			NextRotationAt: time.Now().AddDate(0, 0, 90),
			Encrypted:      true,
			Source:         "vault",
		}

		secretKey := cred.SecretKey
		if secretKey != "" && v.keyChain != nil {
			if err := v.keyChain.Set("multicloud", string(provider), secretKey); err != nil {
				return err
			}
			cred.SecretKey = ""
		}

		stored := storedCredential{
			Credential: cred,
			Metadata:   metadata,
		}

		data, err := json.Marshal(stored)
		if err != nil {
			return common.NewError(common.ErrOperationFailed, "failed to marshal credential", err)
		}

		encrypted, err := v.encrypt(data)
		if err != nil {
			return err
		}

		encryptedData[string(provider)] = encrypted
		cred.SecretKey = secretKey
	}

	data, err := json.MarshalIndent(encryptedData, "", "  ")
	if err != nil {
		return common.NewError(common.ErrOperationFailed, "failed to marshal vault data", err)
	}

	if err := common.EnsureDir(v.storagePath); err != nil {
		return err
	}

	return os.WriteFile(v.storagePath, data, 0600)
}

func (v *Vault) GetCredential(provider common.CloudProvider) (*common.Credential, error) {
	v.mu.RLock()
	defer v.mu.RUnlock()

	cred, exists := v.credentials[provider]
	if !exists {
		return nil, common.NewError(common.ErrNotFound, fmt.Sprintf("credentials for %s not found", provider))
	}

	if cred.ExpiresAt != nil && cred.ExpiresAt.Before(time.Now()) {
		return nil, common.NewError(common.ErrCredentialExpired, fmt.Sprintf("credentials for %s have expired", provider))
	}

	return cred, nil
}

func (v *Vault) SetCredential(provider common.CloudProvider, cred *common.Credential) error {
	v.mu.Lock()
	defer v.mu.Unlock()

	cred.Provider = provider
	v.credentials[provider] = cred
	return nil
}

func (v *Vault) DeleteCredential(provider common.CloudProvider) error {
	v.mu.Lock()
	defer v.mu.Unlock()

	if _, exists := v.credentials[provider]; !exists {
		return common.NewError(common.ErrNotFound, fmt.Sprintf("credentials for %s not found", provider))
	}

	delete(v.credentials, provider)

	if v.keyChain != nil {
		v.keyChain.Delete("multicloud", string(provider))
	}

	return nil
}

func (v *Vault) ListCredentials() []common.CloudProvider {
	v.mu.RLock()
	defer v.mu.RUnlock()

	providers := make([]common.CloudProvider, 0, len(v.credentials))
	for p := range v.credentials {
		providers = append(providers, p)
	}
	return providers
}

func (v *Vault) CheckRotation() []common.CloudProvider {
	v.mu.RLock()
	defer v.mu.RUnlock()

	var needsRotation []common.CloudProvider
	threshold := time.Now().AddDate(0, 0, v.rotationAlertDays)

	for provider := range v.credentials {
		metadata, err := v.getMetadata(provider)
		if err != nil {
			continue
		}
		if metadata.NextRotationAt.Before(threshold) {
			needsRotation = append(needsRotation, provider)
		}
	}

	return needsRotation
}

func (v *Vault) getMetadata(provider common.CloudProvider) (*CredentialMetadata, error) {
	metadata := &CredentialMetadata{
		CreatedAt:      time.Now(),
		UpdatedAt:      time.Now(),
		RotationPeriod: 90,
		NextRotationAt: time.Now().AddDate(0, 0, 90),
		Source:         "vault",
	}

	return metadata, nil
}

func (v *Vault) RotateCredential(provider common.CloudProvider, newCred *common.Credential) error {
	v.mu.Lock()
	defer v.mu.Unlock()

	if _, exists := v.credentials[provider]; !exists {
		return common.NewError(common.ErrNotFound, fmt.Sprintf("credentials for %s not found", provider))
	}

	newCred.Provider = provider
	v.credentials[provider] = newCred

	return nil
}

func (v *Vault) GetMetadata(provider common.CloudProvider) (*CredentialMetadata, error) {
	v.mu.RLock()
	defer v.mu.RUnlock()
	return v.getMetadata(provider)
}

func (v *Vault) SetKeychainBackend(backend KeychainBackend) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.keyChain = backend
}

func (v *Vault) SetRotationAlertDays(days int) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.rotationAlertDays = days
}

func (v *Vault) ValidateCredentials() map[common.CloudProvider]error {
	v.mu.RLock()
	defer v.mu.RUnlock()

	results := make(map[common.CloudProvider]error)

	for provider, cred := range v.credentials {
		switch provider {
		case common.ProviderAWS:
			if cred.AccessKey == "" || cred.SecretKey == "" {
				results[provider] = common.NewError(common.ErrUnauthorized, "AWS access key or secret key missing")
			}
		case common.ProviderAzure:
			if cred.TenantID == "" || cred.SubscriptionID == "" {
				results[provider] = common.NewError(common.ErrUnauthorized, "Azure tenant ID or subscription ID missing")
			}
		case common.ProviderGCP:
			if cred.ProjectID == "" {
				results[provider] = common.NewError(common.ErrUnauthorized, "GCP project ID missing")
			}
		default:
			results[provider] = common.NewError(common.ErrNotFound, "unsupported provider")
		}
	}

	return results
}

func (v *Vault) Export(provider common.CloudProvider) (map[string]string, error) {
	cred, err := v.GetCredential(provider)
	if err != nil {
		return nil, err
	}

	envVars := make(map[string]string)

	switch provider {
	case common.ProviderAWS:
		envVars["AWS_ACCESS_KEY_ID"] = cred.AccessKey
		envVars["AWS_SECRET_ACCESS_KEY"] = cred.SecretKey
		if cred.SessionToken != "" {
			envVars["AWS_SESSION_TOKEN"] = cred.SessionToken
		}
		if cred.Region != "" {
			envVars["AWS_DEFAULT_REGION"] = cred.Region
		}
	case common.ProviderAzure:
		envVars["AZURE_TENANT_ID"] = cred.TenantID
		envVars["AZURE_SUBSCRIPTION_ID"] = cred.SubscriptionID
		if cred.ClientID != "" {
			envVars["AZURE_CLIENT_ID"] = cred.ClientID
		}
		if cred.ClientSecret != "" {
			envVars["AZURE_CLIENT_SECRET"] = cred.ClientSecret
		}
		if cred.Region != "" {
			envVars["AZURE_REGION"] = cred.Region
		}
	case common.ProviderGCP:
		envVars["GCP_PROJECT_ID"] = cred.ProjectID
		if cred.AccessKey != "" {
			envVars["GCP_SERVICE_ACCOUNT_KEY"] = cred.AccessKey
		}
		if cred.Region != "" {
			envVars["GCP_REGION"] = cred.Region
		}
	}

	return envVars, nil
}

func (v *Vault) GetRotationAlertDays() int {
	v.mu.RLock()
	defer v.mu.RUnlock()
	return v.rotationAlertDays
}
