package wallet

import (
	"crypto/ecdsa"
	"gas-estimator/internal/domain"
	"sync"
)

type BatchWallet struct {
	baseWallet domain.WalletService
	mutex       sync.RWMutex
	maxBatchSize int
}

func NewBatchWallet(baseWallet domain.WalletService, maxBatchSize int) domain.BatchWalletService {
	if maxBatchSize <= 0 {
		maxBatchSize = 100
	}

	return &BatchWallet{
		baseWallet:   baseWallet,
		maxBatchSize: maxBatchSize,
		mutex:        sync.RWMutex{},
	}
}

func (b *BatchWallet) DeriveAddresses(indices []uint32, labels []string, tags [][]string) ([]*domain.WalletAddress, error) {
	if len(indices) == 0 {
		return []*domain.WalletAddress{}, nil
	}

	if len(indices) > b.maxBatchSize {
		indices = indices[:b.maxBatchSize]
	}

	for len(labels) < len(indices) {
		labels = append(labels, "")
	}

	for len(tags) < len(indices) {
		tags = append(tags, []string{})
	}

	results := make([]*domain.WalletAddress, len(indices))
	errChan := make(chan error, len(indices))
	var wg sync.WaitGroup

	for i, index := range indices {
		wg.Add(1)
		go func(idx int, addrIndex uint32, label string, addrTags []string) {
			defer wg.Done()
			addr, err := b.baseWallet.DeriveAddress(addrIndex, label, addrTags)
			if err != nil {
				errChan <- err
				return
			}
			b.mutex.Lock()
			results[idx] = addr
			b.mutex.Unlock()
		}(i, index, labels[i], tags[i])
	}

	wg.Wait()
	close(errChan)

	var firstErr error
	for err := range errChan {
		if firstErr == nil {
			firstErr = err
		}
	}

	if firstErr != nil {
		return nil, firstErr
	}

	return results, nil
}

func (b *BatchWallet) GetAddresses(addresses []string) ([]*domain.WalletAddress, error) {
	if len(addresses) == 0 {
		return []*domain.WalletAddress{}, nil
	}

	if len(addresses) > b.maxBatchSize {
		addresses = addresses[:b.maxBatchSize]
	}

	results := make([]*domain.WalletAddress, len(addresses))
	errChan := make(chan error, len(addresses))
	var wg sync.WaitGroup

	for i, addr := range addresses {
		wg.Add(1)
		go func(idx int, address string) {
			defer wg.Done()
			result, err := b.baseWallet.GetAddress(address)
			if err != nil {
				errChan <- err
				return
			}
			b.mutex.Lock()
			results[idx] = result
			b.mutex.Unlock()
		}(i, addr)
	}

	wg.Wait()
	close(errChan)

	var firstErr error
	for err := range errChan {
		if firstErr == nil {
			firstErr = err
		}
	}

	if firstErr != nil {
		return nil, firstErr
	}

	return results, nil
}

func (b *BatchWallet) GetAddressesByIndex(indices []uint32) ([]*domain.WalletAddress, error) {
	if len(indices) == 0 {
		return []*domain.WalletAddress{}, nil
	}

	if len(indices) > b.maxBatchSize {
		indices = indices[:b.maxBatchSize]
	}

	results := make([]*domain.WalletAddress, len(indices))
	errChan := make(chan error, len(indices))
	var wg sync.WaitGroup

	for i, index := range indices {
		wg.Add(1)
		go func(idx int, addrIndex uint32) {
			defer wg.Done()
			result, err := b.baseWallet.GetAddressByIndex(addrIndex)
			if err != nil {
				errChan <- err
				return
			}
			b.mutex.Lock()
			results[idx] = result
			b.mutex.Unlock()
		}(i, index)
	}

	wg.Wait()
	close(errChan)

	var firstErr error
	for err := range errChan {
		if firstErr == nil {
			firstErr = err
		}
	}

	if firstErr != nil {
		return nil, firstErr
	}

	return results, nil
}

func (b *BatchWallet) UpdateAddressLabels(labels map[string]string) map[string]error {
	errors := make(map[string]error)
	var wg sync.WaitGroup
	var mutex sync.Mutex

	for address, label := range labels {
		wg.Add(1)
		go func(addr string, lbl string) {
			defer wg.Done()
			err := b.baseWallet.UpdateAddressLabel(addr, lbl)
			if err != nil {
				mutex.Lock()
				errors[addr] = err
				mutex.Unlock()
			}
		}(address, label)
	}

	wg.Wait()

	return errors
}

func (b *BatchWallet) AddAddressTagsBatch(tags map[string][]string) map[string]error {
	errors := make(map[string]error)
	var wg sync.WaitGroup
	var mutex sync.Mutex

	for address, tagList := range tags {
		wg.Add(1)
		go func(addr string, tags []string) {
			defer wg.Done()
			err := b.baseWallet.AddAddressTags(addr, tags)
			if err != nil {
				mutex.Lock()
				errors[addr] = err
				mutex.Unlock()
			}
		}(address, tagList)
	}

	wg.Wait()

	return errors
}

func (b *BatchWallet) RemoveAddressTagsBatch(tags map[string][]string) map[string]error {
	errors := make(map[string]error)
	var wg sync.WaitGroup
	var mutex sync.Mutex

	for address, tagList := range tags {
		wg.Add(1)
		go func(addr string, tags []string) {
			defer wg.Done()
			err := b.baseWallet.RemoveAddressTags(addr, tags)
			if err != nil {
				mutex.Lock()
				errors[addr] = err
				mutex.Unlock()
			}
		}(address, tagList)
	}

	wg.Wait()

	return errors
}

func (b *BatchWallet) AddToAddressBookBatch(addresses map[string]*domain.WalletAddress) map[string]error {
	errors := make(map[string]error)
	var wg sync.WaitGroup
	var mutex sync.Mutex

	for address, addr := range addresses {
		wg.Add(1)
		go func(addrStr string, walletAddr *domain.WalletAddress) {
			defer wg.Done()
			err := b.baseWallet.AddToAddressBook(addrStr, walletAddr.Label, walletAddr.Tags)
			if err != nil {
				mutex.Lock()
				errors[addrStr] = err
				mutex.Unlock()
			}
		}(address, addr)
	}

	wg.Wait()

	return errors
}

func (b *BatchWallet) GetFromAddressBookBatch(addresses []string) []*domain.WalletAddress {
	if len(addresses) == 0 {
		return []*domain.WalletAddress{}
	}

	results := make([]*domain.WalletAddress, 0, len(addresses))
	var wg sync.WaitGroup
	var mutex sync.Mutex

	for _, address := range addresses {
		wg.Add(1)
		go func(addr string) {
			defer wg.Done()
			result, err := b.baseWallet.GetFromAddressBook(addr)
			if err == nil {
				mutex.Lock()
				results = append(results, result)
				mutex.Unlock()
			}
		}(address)
	}

	wg.Wait()

	return results
}

func (b *BatchWallet) RemoveFromAddressBookBatch(addresses []string) map[string]error {
	errors := make(map[string]error)
	var wg sync.WaitGroup
	var mutex sync.Mutex

	for _, address := range addresses {
		wg.Add(1)
		go func(addr string) {
			defer wg.Done()
			err := b.baseWallet.RemoveFromAddressBook(addr)
			if err != nil {
				mutex.Lock()
				errors[addr] = err
				mutex.Unlock()
			}
		}(address)
	}

	wg.Wait()

	return errors
}

func (b *BatchWallet) DeriveAddress(index uint32, label string, tags []string) (*domain.WalletAddress, error) {
	return b.baseWallet.DeriveAddress(index, label, tags)
}

func (b *BatchWallet) GetAddress(address string) (*domain.WalletAddress, error) {
	return b.baseWallet.GetAddress(address)
}

func (b *BatchWallet) GetAddressByIndex(index uint32) (*domain.WalletAddress, error) {
	return b.baseWallet.GetAddressByIndex(index)
}

func (b *BatchWallet) ListAddresses() []*domain.WalletAddress {
	return b.baseWallet.ListAddresses()
}

func (b *BatchWallet) UpdateAddressLabel(address string, label string) error {
	return b.baseWallet.UpdateAddressLabel(address, label)
}

func (b *BatchWallet) AddAddressTags(address string, tags []string) error {
	return b.baseWallet.AddAddressTags(address, tags)
}

func (b *BatchWallet) RemoveAddressTags(address string, tags []string) error {
	return b.baseWallet.RemoveAddressTags(address, tags)
}

func (b *BatchWallet) AddToAddressBook(address string, label string, tags []string) error {
	return b.baseWallet.AddToAddressBook(address, label, tags)
}

func (b *BatchWallet) GetFromAddressBook(address string) (*domain.WalletAddress, error) {
	return b.baseWallet.GetFromAddressBook(address)
}

func (b *BatchWallet) ListAddressBook() []*domain.WalletAddress {
	return b.baseWallet.ListAddressBook()
}

func (b *BatchWallet) RemoveFromAddressBook(address string) error {
	return b.baseWallet.RemoveFromAddressBook(address)
}

func (b *BatchWallet) SearchAddressBook(label string, tags []string) []*domain.WalletAddress {
	return b.baseWallet.SearchAddressBook(label, tags)
}

func (b *BatchWallet) GetPrivateKey(index uint32) (*ecdsa.PrivateKey, error) {
	return b.baseWallet.GetPrivateKey(index)
}
