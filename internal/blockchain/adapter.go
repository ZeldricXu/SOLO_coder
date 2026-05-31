package blockchain

import (
	"bytes"
	"encoding/json"
	"errors"
	"gas-estimator/internal/domain"
	"io/ioutil"
	"math/big"
	"net/http"
	"sync"
	"time"
)

var (
	ErrChainNotSupported    = errors.New("chain not supported")
	ErrRPCConnectionFailed  = errors.New("rpc connection failed")
	ErrTransactionNotFound  = errors.New("transaction not found")
	ErrBlockNotFound        = errors.New("block not found")
)

type rpcClient struct {
	config     domain.ChainConfig
	rpcURLs    []string
	currentURL int
	httpClient *http.Client
}

type evmBlockchainService struct {
	chains       map[string]*rpcClient
	currentChain string
	mutex        sync.RWMutex
}

type RPCRequest struct {
	JSONRPC string        `json:"jsonrpc"`
	Method  string        `json:"method"`
	Params  []interface{} `json:"params"`
	ID      int           `json:"id"`
}

type RPCResponse struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      int             `json:"id"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   *RPCError       `json:"error,omitempty"`
}

type RPCError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func NewEVMBlockchainService(chains []domain.ChainConfig) domain.BlockchainService {
	service := &evmBlockchainService{
		chains: make(map[string]*rpcClient),
		mutex:  sync.RWMutex{},
	}

	for _, chainCfg := range chains {
		client := &rpcClient{
			config:     chainCfg,
			rpcURLs:    chainCfg.RPCURLs,
			currentURL: 0,
			httpClient: &http.Client{
				Timeout: 30 * time.Second,
			},
		}

		service.chains[chainCfg.Name] = client

		if service.currentChain == "" {
			service.currentChain = chainCfg.Name
		}
	}

	return service
}

func (ca *evmBlockchainService) SwitchChain(chainName string) error {
	ca.mutex.RLock()
	_, exists := ca.chains[chainName]
	ca.mutex.RUnlock()

	if !exists {
		return ErrChainNotSupported
	}

	ca.mutex.Lock()
	ca.currentChain = chainName
	ca.mutex.Unlock()

	return nil
}

func (ca *evmBlockchainService) GetCurrentChain() string {
	ca.mutex.RLock()
	defer ca.mutex.RUnlock()
	return ca.currentChain
}

func (ca *evmBlockchainService) GetBlockByNumber(blockNumber uint64) (*domain.Block, error) {
	client, err := ca.getCurrentClient()
	if err != nil {
		return nil, err
	}

	blockHex := "0x" + big.NewInt(int64(blockNumber)).Text(16)

	response, err := client.callRPC("eth_getBlockByNumber", []interface{}{blockHex, true})
	if err != nil {
		return nil, err
	}

	var blockData map[string]interface{}
	if err := json.Unmarshal(response, &blockData); err != nil {
		return nil, err
	}

	return ca.parseBlock(blockData)
}

func (ca *evmBlockchainService) GetLatestBlock() (*domain.Block, error) {
	client, err := ca.getCurrentClient()
	if err != nil {
		return nil, err
	}

	response, err := client.callRPC("eth_getBlockByNumber", []interface{}{"latest", true})
	if err != nil {
		return nil, err
	}

	var blockData map[string]interface{}
	if err := json.Unmarshal(response, &blockData); err != nil {
		return nil, err
	}

	return ca.parseBlock(blockData)
}

func (ca *evmBlockchainService) GetBlockByHash(blockHash []byte) (*domain.Block, error) {
	client, err := ca.getCurrentClient()
	if err != nil {
		return nil, err
	}

	hashHex := "0x" + string(blockHash)

	response, err := client.callRPC("eth_getBlockByHash", []interface{}{hashHex, true})
	if err != nil {
		return nil, err
	}

	var blockData map[string]interface{}
	if err := json.Unmarshal(response, &blockData); err != nil {
		return nil, err
	}

	return ca.parseBlock(blockData)
}

func (ca *evmBlockchainService) GetTransactionByHash(txHash []byte) (*domain.Transaction, error) {
	client, err := ca.getCurrentClient()
	if err != nil {
		return nil, err
	}

	hashHex := "0x" + string(txHash)

	response, err := client.callRPC("eth_getTransactionByHash", []interface{}{hashHex})
	if err != nil {
		return nil, err
	}

	var txData map[string]interface{}
	if err := json.Unmarshal(response, &txData); err != nil {
		return nil, err
	}

	if txData == nil {
		return nil, ErrTransactionNotFound
	}

	return ca.parseTransaction(txData)
}

func (ca *evmBlockchainService) GetTransactionReceipt(txHash []byte) (map[string]interface{}, error) {
	client, err := ca.getCurrentClient()
	if err != nil {
		return nil, err
	}

	hashHex := "0x" + string(txHash)

	response, err := client.callRPC("eth_getTransactionReceipt", []interface{}{hashHex})
	if err != nil {
		return nil, err
	}

	var receipt map[string]interface{}
	if err := json.Unmarshal(response, &receipt); err != nil {
		return nil, err
	}

	return receipt, nil
}

func (ca *evmBlockchainService) SendTransaction(tx *domain.Transaction) ([]byte, error) {
	client, err := ca.getCurrentClient()
	if err != nil {
		return nil, err
	}

	txRaw, err := ca.serializeTransaction(tx)
	if err != nil {
		return nil, err
	}

	response, err := client.callRPC("eth_sendRawTransaction", []interface{}{"0x" + string(txRaw)})
	if err != nil {
		return nil, err
	}

	var txHash string
	if err := json.Unmarshal(response, &txHash); err != nil {
		return nil, err
	}

	return []byte(txHash), nil
}

func (ca *evmBlockchainService) EstimateGas(tx *domain.Transaction) (uint64, error) {
	client, err := ca.getCurrentClient()
	if err != nil {
		return 0, err
	}

	txData := map[string]interface{}{
		"to":    "0x" + string(tx.To),
		"value": "0x" + tx.Value.Text(16),
		"data":  "0x" + string(tx.Data),
	}

	response, err := client.callRPC("eth_estimateGas", []interface{}{txData})
	if err != nil {
		return 0, err
	}

	var gasHex string
	if err := json.Unmarshal(response, &gasHex); err != nil {
		return 0, err
	}

	gas := new(big.Int)
	gas.SetString(gasHex[2:], 16)

	return gas.Uint64(), nil
}

func (ca *evmBlockchainService) GetGasPrice() (*big.Int, error) {
	client, err := ca.getCurrentClient()
	if err != nil {
		return nil, err
	}

	response, err := client.callRPC("eth_gasPrice", []interface{}{})
	if err != nil {
		return nil, err
	}

	var priceHex string
	if err := json.Unmarshal(response, &priceHex); err != nil {
		return nil, err
	}

	price := new(big.Int)
	price.SetString(priceHex[2:], 16)

	return price, nil
}

func (ca *evmBlockchainService) GetBalance(address string, blockNumber string) (*big.Int, error) {
	client, err := ca.getCurrentClient()
	if err != nil {
		return nil, err
	}

	if blockNumber == "" {
		blockNumber = "latest"
	}

	response, err := client.callRPC("eth_getBalance", []interface{}{address, blockNumber})
	if err != nil {
		return nil, err
	}

	var balanceHex string
	if err := json.Unmarshal(response, &balanceHex); err != nil {
		return nil, err
	}

	balance := new(big.Int)
	balance.SetString(balanceHex[2:], 16)

	return balance, nil
}

func (ca *evmBlockchainService) GetNonce(address string, blockNumber string) (uint64, error) {
	client, err := ca.getCurrentClient()
	if err != nil {
		return 0, err
	}

	if blockNumber == "" {
		blockNumber = "latest"
	}

	response, err := client.callRPC("eth_getTransactionCount", []interface{}{address, blockNumber})
	if err != nil {
		return 0, err
	}

	var nonceHex string
	if err := json.Unmarshal(response, &nonceHex); err != nil {
		return 0, err
	}

	nonce := new(big.Int)
	nonce.SetString(nonceHex[2:], 16)

	return nonce.Uint64(), nil
}

func (ca *evmBlockchainService) CallContract(to string, data []byte, blockNumber string) ([]byte, error) {
	client, err := ca.getCurrentClient()
	if err != nil {
		return nil, err
	}

	if blockNumber == "" {
		blockNumber = "latest"
	}

	callData := map[string]interface{}{
		"to":   to,
		"data": "0x" + string(data),
	}

	response, err := client.callRPC("eth_call", []interface{}{callData, blockNumber})
	if err != nil {
		return nil, err
	}

	var result string
	if err := json.Unmarshal(response, &result); err != nil {
		return nil, err
	}

	return []byte(result[2:]), nil
}

func (ca *evmBlockchainService) getCurrentClient() (*rpcClient, error) {
	ca.mutex.RLock()
	defer ca.mutex.RUnlock()

	client, exists := ca.chains[ca.currentChain]
	if !exists {
		return nil, ErrChainNotSupported
	}

	return client, nil
}

func (cc *rpcClient) callRPC(method string, params []interface{}) (json.RawMessage, error) {
	for i := 0; i < len(cc.rpcURLs); i++ {
		url := cc.rpcURLs[cc.currentURL]

		request := RPCRequest{
			JSONRPC: "2.0",
			Method:  method,
			Params:  params,
			ID:      1,
		}

		requestBody, err := json.Marshal(request)
		if err != nil {
			return nil, err
		}

		httpReq, err := http.NewRequest("POST", url, bytes.NewBuffer(requestBody))
		if err != nil {
			return nil, err
		}

		httpReq.Header.Set("Content-Type", "application/json")

		response, err := cc.httpClient.Do(httpReq)
		if err != nil {
			cc.currentURL = (cc.currentURL + 1) % len(cc.rpcURLs)
			continue
		}
		defer response.Body.Close()

		body, err := ioutil.ReadAll(response.Body)
		if err != nil {
			cc.currentURL = (cc.currentURL + 1) % len(cc.rpcURLs)
			continue
		}

		var rpcResponse RPCResponse
		if err := json.Unmarshal(body, &rpcResponse); err != nil {
			cc.currentURL = (cc.currentURL + 1) % len(cc.rpcURLs)
			continue
		}

		if rpcResponse.Error != nil {
			return nil, errors.New(rpcResponse.Error.Message)
		}

		return rpcResponse.Result, nil
	}

	return nil, ErrRPCConnectionFailed
}

func (ca *evmBlockchainService) parseBlock(data map[string]interface{}) (*domain.Block, error) {
	if data == nil {
		return nil, ErrBlockNotFound
	}

	block := &domain.Block{}

	if numberHex, ok := data["number"].(string); ok {
		number := new(big.Int)
		number.SetString(numberHex[2:], 16)
		block.Number = number.Uint64()
	}

	if hashHex, ok := data["hash"].(string); ok {
		block.Hash = []byte(hashHex[2:])
	}

	if parentHashHex, ok := data["parentHash"].(string); ok {
		block.ParentHash = []byte(parentHashHex[2:])
	}

	if timestampHex, ok := data["timestamp"].(string); ok {
		timestamp := new(big.Int)
		timestamp.SetString(timestampHex[2:], 16)
		block.Timestamp = time.Unix(timestamp.Int64(), 0)
	}

	if gasUsedHex, ok := data["gasUsed"].(string); ok {
		gasUsed := new(big.Int)
		gasUsed.SetString(gasUsedHex[2:], 16)
		block.GasUsed = gasUsed.Uint64()
	}

	if gasLimitHex, ok := data["gasLimit"].(string); ok {
		gasLimit := new(big.Int)
		gasLimit.SetString(gasLimitHex[2:], 16)
		block.GasLimit = gasLimit.Uint64()
	}

	if baseFeeHex, ok := data["baseFeePerGas"].(string); ok {
		baseFee := new(big.Int)
		baseFee.SetString(baseFeeHex[2:], 16)
		block.BaseFee = baseFee
	}

	if txs, ok := data["transactions"].([]interface{}); ok {
		block.Transactions = make([]domain.Transaction, 0, len(txs))

		for _, txItem := range txs {
			if txData, ok := txItem.(map[string]interface{}); ok {
				tx, err := ca.parseTransaction(txData)
				if err == nil {
					block.Transactions = append(block.Transactions, tx)
				}
			}
		}
	}

	return block, nil
}

func (ca *evmBlockchainService) parseTransaction(data map[string]interface{}) (domain.Transaction, error) {
	tx := domain.Transaction{}

	ca.mutex.RLock()
	client, exists := ca.chains[ca.currentChain]
	ca.mutex.RUnlock()

	if exists {
		tx.ChainID = big.NewInt(client.config.ChainID)
	}

	if nonceHex, ok := data["nonce"].(string); ok {
		nonce := new(big.Int)
		nonce.SetString(nonceHex[2:], 16)
		tx.Nonce = nonce.Uint64()
	}

	if gasPriceHex, ok := data["gasPrice"].(string); ok {
		gasPrice := new(big.Int)
		gasPrice.SetString(gasPriceHex[2:], 16)
		tx.GasPrice = gasPrice
	}

	if gasHex, ok := data["gas"].(string); ok {
		gas := new(big.Int)
		gas.SetString(gasHex[2:], 16)
		tx.GasLimit = gas.Uint64()
	}

	if toHex, ok := data["to"].(string); ok {
		tx.To = []byte(toHex[2:])
	}

	if valueHex, ok := data["value"].(string); ok {
		value := new(big.Int)
		value.SetString(valueHex[2:], 16)
		tx.Value = value
	}

	if dataHex, ok := data["input"].(string); ok {
		tx.Data = []byte(dataHex[2:])
	}

	return tx, nil
}

func (ca *evmBlockchainService) serializeTransaction(tx *domain.Transaction) ([]byte, error) {
	return tx.Data, nil
}
