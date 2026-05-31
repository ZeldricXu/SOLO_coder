package bridge

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"gas-estimator/internal/chain"
	"gas-estimator/pkg/models"
	"math/big"
	"sync"
	"time"
)

var (
	ErrBridgeMessageNotFound = errors.New("bridge message not found")
	ErrInvalidProof          = errors.New("invalid proof")
	ErrMessageAlreadyProcessed = errors.New("message already processed")
	ErrInsufficientBalance    = errors.New("insufficient balance")
	ErrChainNotSupported      = errors.New("chain not supported")
)

type CrossChainBridge struct {
	chainAdapter    *chain.ChainAdapter
	messages        map[string]*models.BridgeMessage
	messagesByNonce map[string]map[uint64]*models.BridgeMessage
	lockedAssets    map[string]map[string]*big.Int
	confirmedMessages map[string]bool
	nonceCounter    map[string]uint64
	mutex           sync.RWMutex
}

type BridgeConfig struct {
	SupportedChains []string
	MinConfirmations int
}

func NewCrossChainBridge(chainAdapter *chain.ChainAdapter) *CrossChainBridge {
	return &CrossChainBridge{
		chainAdapter:    chainAdapter,
		messages:        make(map[string]*models.BridgeMessage),
		messagesByNonce: make(map[string]map[uint64]*models.BridgeMessage),
		lockedAssets:    make(map[string]map[string]*big.Int),
		confirmedMessages: make(map[string]bool),
		nonceCounter:    make(map[string]uint64),
		mutex:           sync.RWMutex{},
	}
}

func (b *CrossChainBridge) CreateBridgeMessage(
	sourceChain, destChain string,
	sourceAddr, destAddr string,
	amount *big.Int,
	tokenAddr string,
) (*models.BridgeMessage, error) {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	
	chainKey := sourceChain + "-" + destChain
	
	if _, ok := b.messagesByNonce[chainKey]; !ok {
		b.messagesByNonce[chainKey] = make(map[uint64]*models.BridgeMessage)
	}
	
	if _, ok := b.nonceCounter[chainKey]; !ok {
		b.nonceCounter[chainKey] = 0
	}
	
	nonce := b.nonceCounter[chainKey]
	b.nonceCounter[chainKey]++
	
	messageID := b.generateMessageID(sourceChain, destChain, sourceAddr, destAddr, amount, nonce)
	
	message := &models.BridgeMessage{
		ID:              messageID,
		SourceChain:     sourceChain,
		DestinationChain: destChain,
		SourceAddress:   sourceAddr,
		DestAddress:     destAddr,
		Amount:          new(big.Int).Set(amount),
		TokenAddress:    tokenAddr,
		Nonce:           nonce,
		Status:          "pending",
		CreatedAt:       time.Now(),
	}
	
	b.messages[messageID] = message
	b.messagesByNonce[chainKey][nonce] = message
	
	return message, nil
}

func (b *CrossChainBridge) LockAssets(messageID string) error {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	
	message, ok := b.messages[messageID]
	if !ok {
		return ErrBridgeMessageNotFound
	}
	
	if message.Status != "pending" {
		return ErrMessageAlreadyProcessed
	}
	
	if _, ok := b.lockedAssets[message.SourceChain]; !ok {
		b.lockedAssets[message.SourceChain] = make(map[string]*big.Int)
	}
	
	lockedKey := message.SourceAddress + "-" + message.TokenAddress
	
	if currentLocked, ok := b.lockedAssets[message.SourceChain][lockedKey]; ok {
		b.lockedAssets[message.SourceChain][lockedKey] = new(big.Int).Add(currentLocked, message.Amount)
	} else {
		b.lockedAssets[message.SourceChain][lockedKey] = new(big.Int).Set(message.Amount)
	}
	
	message.Status = "locked"
	message.Proof = b.generateProof(message)
	
	return nil
}

func (b *CrossChainBridge) VerifyProof(messageID string, proof []byte) (bool, error) {
	b.mutex.RLock()
	defer b.mutex.RUnlock()
	
	message, ok := b.messages[messageID]
	if !ok {
		return false, ErrBridgeMessageNotFound
	}
	
	expectedProof := b.generateProof(message)
	
	return hex.EncodeToString(proof) == hex.EncodeToString(expectedProof), nil
}

func (b *CrossChainBridge) MintAssets(messageID string) error {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	
	message, ok := b.messages[messageID]
	if !ok {
		return ErrBridgeMessageNotFound
	}
	
	if message.Status != "locked" {
		return errors.New("assets not locked yet")
	}
	
	if b.confirmedMessages[messageID] {
		return ErrMessageAlreadyProcessed
	}
	
	valid, err := b.VerifyProof(messageID, message.Proof)
	if err != nil {
		return err
	}
	
	if !valid {
		return ErrInvalidProof
	}
	
	message.Status = "minted"
	b.confirmedMessages[messageID] = true
	
	return nil
}

func (b *CrossChainBridge) ExecuteBridge(messageID string) error {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	
	message, ok := b.messages[messageID]
	if !ok {
		return ErrBridgeMessageNotFound
	}
	
	if message.Status == "minted" {
		message.Status = "completed"
		return nil
	}
	
	return errors.New("bridge message not ready for execution")
}

func (b *CrossChainBridge) GetMessage(messageID string) (*models.BridgeMessage, error) {
	b.mutex.RLock()
	defer b.mutex.RUnlock()
	
	message, ok := b.messages[messageID]
	if !ok {
		return nil, ErrBridgeMessageNotFound
	}
	
	return message, nil
}

func (b *CrossChainBridge) GetMessageByNonce(sourceChain, destChain string, nonce uint64) (*models.BridgeMessage, error) {
	b.mutex.RLock()
	defer b.mutex.RUnlock()
	
	chainKey := sourceChain + "-" + destChain
	
	messages, ok := b.messagesByNonce[chainKey]
	if !ok {
		return nil, ErrBridgeMessageNotFound
	}
	
	message, ok := messages[nonce]
	if !ok {
		return nil, ErrBridgeMessageNotFound
	}
	
	return message, nil
}

func (b *CrossChainBridge) ListMessages(status string) []*models.BridgeMessage {
	b.mutex.RLock()
	defer b.mutex.RUnlock()
	
	messages := make([]*models.BridgeMessage, 0)
	
	for _, msg := range b.messages {
		if status == "" || msg.Status == status {
			messages = append(messages, msg)
		}
	}
	
	return messages
}

func (b *CrossChainBridge) GetLockedBalance(chain, address, tokenAddr string) (*big.Int, error) {
	b.mutex.RLock()
	defer b.mutex.RUnlock()
	
	chainAssets, ok := b.lockedAssets[chain]
	if !ok {
		return big.NewInt(0), nil
	}
	
	lockedKey := address + "-" + tokenAddr
	if balance, ok := chainAssets[lockedKey]; ok {
		return new(big.Int).Set(balance), nil
	}
	
	return big.NewInt(0), nil
}

func (b *CrossChainBridge) UpdateMessageStatus(messageID, status string) error {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	
	message, ok := b.messages[messageID]
	if !ok {
		return ErrBridgeMessageNotFound
	}
	
	message.Status = status
	return nil
}

func (b *CrossChainBridge) GetBridgeStats() map[string]interface{} {
	b.mutex.RLock()
	defer b.mutex.RUnlock()
	
	stats := map[string]interface{}{
		"total_messages":    len(b.messages),
		"pending_messages":  0,
		"locked_messages":   0,
		"minted_messages":   0,
		"completed_messages": 0,
		"failed_messages":   0,
	}
	
	for _, msg := range b.messages {
		switch msg.Status {
		case "pending":
			stats["pending_messages"] = stats["pending_messages"].(int) + 1
		case "locked":
			stats["locked_messages"] = stats["locked_messages"].(int) + 1
		case "minted":
			stats["minted_messages"] = stats["minted_messages"].(int) + 1
		case "completed":
			stats["completed_messages"] = stats["completed_messages"].(int) + 1
		case "failed":
			stats["failed_messages"] = stats["failed_messages"].(int) + 1
		}
	}
	
	return stats
}

func (b *CrossChainBridge) VerifyAtomicity(messageID string) (bool, error) {
	b.mutex.RLock()
	defer b.mutex.RUnlock()
	
	message, ok := b.messages[messageID]
	if !ok {
		return false, ErrBridgeMessageNotFound
	}
	
	if message.Status != "completed" {
		return false, nil
	}
	
	if !b.confirmedMessages[messageID] {
		return false, nil
	}
	
	return true, nil
}

func (b *CrossChainBridge) RetryMessage(messageID string) error {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	
	message, ok := b.messages[messageID]
	if !ok {
		return ErrBridgeMessageNotFound
	}
	
	if message.Status == "completed" {
		return ErrMessageAlreadyProcessed
	}
	
	message.Status = "pending"
	return nil
}

func (b *CrossChainBridge) CancelMessage(messageID string) error {
	b.mutex.Lock()
	defer b.mutex.Unlock()
	
	message, ok := b.messages[messageID]
	if !ok {
		return ErrBridgeMessageNotFound
	}
	
	if message.Status != "pending" {
		return errors.New("cannot cancel message in current state")
	}
	
	message.Status = "cancelled"
	return nil
}

func (b *CrossChainBridge) generateMessageID(sourceChain, destChain, sourceAddr, destAddr string, amount *big.Int, nonce uint64) string {
	data := sourceChain + destChain + sourceAddr + destAddr + amount.String() + string(nonce)
	hash := sha256.Sum256([]byte(data))
	return "bridge_" + hex.EncodeToString(hash[:16])
}

func (b *CrossChainBridge) generateProof(message *models.BridgeMessage) []byte {
	data := message.ID + message.SourceChain + message.DestinationChain + 
		message.SourceAddress + message.DestAddress + message.Amount.String() + 
		string(message.Nonce)
	
	hash := sha256.Sum256([]byte(data))
	return hash[:]
}

func (b *CrossChainBridge) GetNextNonce(sourceChain, destChain string) uint64 {
	b.mutex.RLock()
	defer b.mutex.RUnlock()
	
	chainKey := sourceChain + "-" + destChain
	return b.nonceCounter[chainKey]
}
