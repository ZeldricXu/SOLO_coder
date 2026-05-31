package storage

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"gas-estimator/pkg/config"
	"gas-estimator/pkg/models"
	"io/ioutil"
	"net/http"
	"sync"
	"time"
)

var (
	ErrStorageNetworkUnavailable = errors.New("storage network unavailable")
	ErrContentNotFound          = errors.New("content not found")
	ErrPinFailed                = errors.New("pin operation failed")
	ErrUnpinFailed              = errors.New("unpin operation failed")
)

type DecentralizedStorage struct {
	config      config.StorageConfig
	ipfsClients []*IPFSClient
	contents    map[string]*models.StorageContent
	pinStatus   map[string]map[string]bool
	mutex       sync.RWMutex
}

type IPFSClient struct {
	Endpoint   string
	HTTPClient *http.Client
}

type IPFSPinResponse struct {
	Pins []string `json:"pins"`
}

type IPFSAddResponse struct {
	Name string `json:"Name"`
	Hash string `json:"Hash"`
	Size string `json:"Size"`
}

func NewDecentralizedStorage(cfg config.StorageConfig) *DecentralizedStorage {
	clients := make([]*IPFSClient, 0, len(cfg.IPFSEndpoints))
	
	for _, endpoint := range cfg.IPFSEndpoints {
		clients = append(clients, &IPFSClient{
			Endpoint: endpoint,
			HTTPClient: &http.Client{
				Timeout: time.Duration(cfg.Timeout) * time.Second,
			},
		})
	}
	
	return &DecentralizedStorage{
		config:    cfg,
		ipfsClients: clients,
		contents:  make(map[string]*models.StorageContent),
		pinStatus: make(map[string]map[string]bool),
		mutex:     sync.RWMutex{},
	}
}

func (ds *DecentralizedStorage) Store(data []byte, networks []string) (*models.StorageContent, error) {
	ds.mutex.Lock()
	defer ds.mutex.Unlock()
	
	cid := ds.calculateCID(data)
	
	if existing, ok := ds.contents[cid]; ok {
		return existing, nil
	}
	
	content := &models.StorageContent{
		CID:       cid,
		Data:      data,
		Size:      uint64(len(data)),
		PinStatus: "unpinned",
		Networks:  networks,
		Timestamp: time.Now(),
	}
	
	if len(networks) == 0 {
		networks = []string{"ipfs"}
	}
	
	for _, network := range networks {
		switch network {
		case "ipfs":
			err := ds.storeToIPFS(data, cid)
			if err == nil {
				if _, ok := ds.pinStatus[cid]; !ok {
					ds.pinStatus[cid] = make(map[string]bool)
				}
				ds.pinStatus[cid]["ipfs"] = true
				content.PinStatus = "pinned"
			}
		case "arweave":
			err := ds.storeToArweave(data, cid)
			if err == nil {
				if _, ok := ds.pinStatus[cid]; !ok {
					ds.pinStatus[cid] = make(map[string]bool)
				}
				ds.pinStatus[cid]["arweave"] = true
			}
		}
	}
	
	ds.contents[cid] = content
	
	return content, nil
}

func (ds *DecentralizedStorage) Retrieve(cid string) (*models.StorageContent, error) {
	ds.mutex.RLock()
	if content, ok := ds.contents[cid]; ok {
		ds.mutex.RUnlock()
		return content, nil
	}
	ds.mutex.RUnlock()
	
	data, err := ds.retrieveFromIPFS(cid)
	if err != nil {
		data, err = ds.retrieveFromArweave(cid)
		if err != nil {
			return nil, ErrContentNotFound
		}
	}
	
	ds.mutex.Lock()
	defer ds.mutex.Unlock()
	
	content := &models.StorageContent{
		CID:       cid,
		Data:      data,
		Size:      uint64(len(data)),
		PinStatus: "retrieved",
		Timestamp: time.Now(),
	}
	
	ds.contents[cid] = content
	
	return content, nil
}

func (ds *DecentralizedStorage) Pin(cid string, network string) error {
	ds.mutex.Lock()
	defer ds.mutex.Unlock()
	
	if _, ok := ds.contents[cid]; !ok {
		return ErrContentNotFound
	}
	
	if network == "" {
		network = "ipfs"
	}
	
	if _, ok := ds.pinStatus[cid]; !ok {
		ds.pinStatus[cid] = make(map[string]bool)
	}
	
	var err error
	
	switch network {
	case "ipfs":
		err = ds.pinToIPFS(cid)
	case "arweave":
		err = ds.pinToArweave(cid)
	default:
		err = ErrStorageNetworkUnavailable
	}
	
	if err != nil {
		return ErrPinFailed
	}
	
	ds.pinStatus[cid][network] = true
	
	if content, ok := ds.contents[cid]; ok {
		content.PinStatus = "pinned"
		if !contains(content.Networks, network) {
			content.Networks = append(content.Networks, network)
		}
	}
	
	return nil
}

func (ds *DecentralizedStorage) Unpin(cid string, network string) error {
	ds.mutex.Lock()
	defer ds.mutex.Unlock()
	
	if _, ok := ds.contents[cid]; !ok {
		return ErrContentNotFound
	}
	
	if network == "" {
		network = "ipfs"
	}
	
	if _, ok := ds.pinStatus[cid]; !ok {
		return nil
	}
	
	var err error
	
	switch network {
	case "ipfs":
		err = ds.unpinFromIPFS(cid)
	case "arweave":
		err = ds.unpinFromArweave(cid)
	default:
		err = ErrStorageNetworkUnavailable
	}
	
	if err != nil {
		return ErrUnpinFailed
	}
	
	ds.pinStatus[cid][network] = false
	
	if content, ok := ds.contents[cid]; ok {
		allUnpinned := true
		for _, pinned := range ds.pinStatus[cid] {
			if pinned {
				allUnpinned = false
				break
			}
		}
		
		if allUnpinned {
			content.PinStatus = "unpinned"
		}
	}
	
	return nil
}

func (ds *DecentralizedStorage) GetContent(cid string) (*models.StorageContent, error) {
	ds.mutex.RLock()
	defer ds.mutex.RUnlock()
	
	if content, ok := ds.contents[cid]; ok {
		return content, nil
	}
	
	return nil, ErrContentNotFound
}

func (ds *DecentralizedStorage) ListContents() []*models.StorageContent {
	ds.mutex.RLock()
	defer ds.mutex.RUnlock()
	
	contents := make([]*models.StorageContent, 0, len(ds.contents))
	
	for _, content := range ds.contents {
		contents = append(contents, content)
	}
	
	return contents
}

func (ds *DecentralizedStorage) GetPinStatus(cid string) map[string]bool {
	ds.mutex.RLock()
	defer ds.mutex.RUnlock()
	
	status := make(map[string]bool)
	
	if pinStatus, ok := ds.pinStatus[cid]; ok {
		for network, pinned := range pinStatus {
			status[network] = pinned
		}
	}
	
	return status
}

func (ds *DecentralizedStorage) VerifyContent(cid string, data []byte) bool {
	expectedCID := ds.calculateCID(data)
	return expectedCID == cid
}

func (ds *DecentralizedStorage) StoreJSON(data interface{}, networks []string) (*models.StorageContent, error) {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return nil, err
	}
	
	return ds.Store(jsonData, networks)
}

func (ds *DecentralizedStorage) RetrieveJSON(cid string, result interface{}) error {
	content, err := ds.Retrieve(cid)
	if err != nil {
		return err
	}
	
	return json.Unmarshal(content.Data, result)
}

func (ds *DecentralizedStorage) GetStorageStats() map[string]interface{} {
	ds.mutex.RLock()
	defer ds.mutex.RUnlock()
	
	stats := map[string]interface{}{
		"total_contents":  len(ds.contents),
		"total_size":      uint64(0),
		"pinned_contents": 0,
		"ipfs_endpoints":  len(ds.ipfsClients),
	}
	
	totalSize := uint64(0)
	pinnedCount := 0
	
	for _, content := range ds.contents {
		totalSize += content.Size
		if content.PinStatus == "pinned" {
			pinnedCount++
		}
	}
	
	stats["total_size"] = totalSize
	stats["pinned_contents"] = pinnedCount
	
	return stats
}

func (ds *DecentralizedStorage) calculateCID(data []byte) string {
	hash := sha256.Sum256(data)
	return "Qm" + hex.EncodeToString(hash[:])[:44]
}

func (ds *DecentralizedStorage) storeToIPFS(data []byte, cid string) error {
	for _, client := range ds.ipfsClients {
		url := client.Endpoint + "/api/v0/add"
		
		body := bytes.NewReader(data)
		req, err := http.NewRequest("POST", url, body)
		if err != nil {
			continue
		}
		
		req.Header.Set("Content-Type", "application/octet-stream")
		
		resp, err := client.HTTPClient.Do(req)
		if err != nil {
			continue
		}
		defer resp.Body.Close()
		
		if resp.StatusCode == http.StatusOK {
			return nil
		}
	}
	
	return ErrStorageNetworkUnavailable
}

func (ds *DecentralizedStorage) retrieveFromIPFS(cid string) ([]byte, error) {
	for _, client := range ds.ipfsClients {
		url := client.Endpoint + "/api/v0/cat?arg=" + cid
		
		resp, err := client.HTTPClient.Get(url)
		if err != nil {
			continue
		}
		defer resp.Body.Close()
		
		if resp.StatusCode == http.StatusOK {
			return ioutil.ReadAll(resp.Body)
		}
	}
	
	return nil, ErrContentNotFound
}

func (ds *DecentralizedStorage) pinToIPFS(cid string) error {
	for _, client := range ds.ipfsClients {
		url := client.Endpoint + "/api/v0/pin/add?arg=" + cid
		
		resp, err := client.HTTPClient.Post(url, "application/json", nil)
		if err != nil {
			continue
		}
		defer resp.Body.Close()
		
		if resp.StatusCode == http.StatusOK {
			return nil
		}
	}
	
	return ErrPinFailed
}

func (ds *DecentralizedStorage) unpinFromIPFS(cid string) error {
	for _, client := range ds.ipfsClients {
		url := client.Endpoint + "/api/v0/pin/rm?arg=" + cid
		
		resp, err := client.HTTPClient.Post(url, "application/json", nil)
		if err != nil {
			continue
		}
		defer resp.Body.Close()
		
		if resp.StatusCode == http.StatusOK {
			return nil
		}
	}
	
	return ErrUnpinFailed
}

func (ds *DecentralizedStorage) storeToArweave(data []byte, cid string) error {
	if ds.config.ArweaveNode == "" {
		return ErrStorageNetworkUnavailable
	}
	
	return nil
}

func (ds *DecentralizedStorage) retrieveFromArweave(cid string) ([]byte, error) {
	if ds.config.ArweaveNode == "" {
		return nil, ErrStorageNetworkUnavailable
	}
	
	return nil, ErrContentNotFound
}

func (ds *DecentralizedStorage) pinToArweave(cid string) error {
	if ds.config.ArweaveNode == "" {
		return ErrStorageNetworkUnavailable
	}
	
	return nil
}

func (ds *DecentralizedStorage) unpinFromArweave(cid string) error {
	if ds.config.ArweaveNode == "" {
		return ErrStorageNetworkUnavailable
	}
	
	return nil
}

func contains(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}
