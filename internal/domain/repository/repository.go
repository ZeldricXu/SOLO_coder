package repository

import (
	"context"

	"github.com/gasestimator/platform/internal/domain/model"
)

type EntityRepository interface {
	Create(ctx context.Context, entity *model.Entity) error
	Update(ctx context.Context, entity *model.Entity) error
	GetByID(ctx context.Context, id string) (*model.Entity, error)
	List(ctx context.Context, filter map[string]interface{}, limit, offset int) ([]*model.Entity, int64, error)
	Delete(ctx context.Context, id string) error
}

type ConfigRepository interface {
	Create(ctx context.Context, config *model.Config) error
	Update(ctx context.Context, config *model.Config) error
	GetByID(ctx context.Context, id string) (*model.Config, error)
	GetLatest(ctx context.Context, namespace string) (*model.Config, error)
	List(ctx context.Context, namespace string, limit, offset int) ([]*model.Config, int64, error)
}

type RunInstanceRepository interface {
	Create(ctx context.Context, run *model.RunInstance) error
	Update(ctx context.Context, run *model.RunInstance) error
	GetByID(ctx context.Context, id string) (*model.RunInstance, error)
	ListByEntityID(ctx context.Context, entityID string) ([]*model.RunInstance, error)
}

type SnapshotRepository interface {
	Create(ctx context.Context, snapshot *model.Snapshot) error
	GetByID(ctx context.Context, id string) (*model.Snapshot, error)
	List(ctx context.Context, startTime, endTime int64, dimensions map[string]string) ([]*model.Snapshot, error)
}

type ZKPProofRepository interface {
	Create(ctx context.Context, proof *model.ZKPProof) error
	Update(ctx context.Context, proof *model.ZKPProof) error
	GetByID(ctx context.Context, id string) (*model.ZKPProof, error)
	List(ctx context.Context, circuitID string, verified *bool, limit, offset int) ([]*model.ZKPProof, int64, error)
}

type TransactionRepository interface {
	Create(ctx context.Context, tx *model.Transaction) error
	Update(ctx context.Context, tx *model.Transaction) error
	GetByID(ctx context.Context, id string) (*model.Transaction, error)
	GetByTxHash(ctx context.Context, txHash string) (*model.Transaction, error)
	List(ctx context.Context, chainID, address, status string, limit, offset int) ([]*model.Transaction, int64, error)
	ListPending(ctx context.Context, chainID string) ([]*model.Transaction, error)
}

type MultisigProposalRepository interface {
	Create(ctx context.Context, proposal *model.MultisigProposal) error
	Update(ctx context.Context, proposal *model.MultisigProposal) error
	GetByID(ctx context.Context, id string) (*model.MultisigProposal, error)
	ListByWalletID(ctx context.Context, walletID, status string) ([]*model.MultisigProposal, error)
	ListReadyToExecute(ctx context.Context) ([]*model.MultisigProposal, error)
}

type ContractEventRepository interface {
	Create(ctx context.Context, event *model.ContractEvent) error
	Update(ctx context.Context, event *model.ContractEvent) error
	GetByID(ctx context.Context, id string) (*model.ContractEvent, error)
	ListUnprocessed(ctx context.Context) ([]*model.ContractEvent, error)
	List(ctx context.Context, chainID, contractAddress, eventName string, limit, offset int) ([]*model.ContractEvent, int64, error)
}

type CrossChainTransferRepository interface {
	Create(ctx context.Context, transfer *model.CrossChainTransfer) error
	Update(ctx context.Context, transfer *model.CrossChainTransfer) error
	GetByID(ctx context.Context, id string) (*model.CrossChainTransfer, error)
	GetByLockTx(ctx context.Context, lockTxHash string) (*model.CrossChainTransfer, error)
	GetByMintTx(ctx context.Context, mintTxHash string) (*model.CrossChainTransfer, error)
	List(ctx context.Context, status string, limit, offset int) ([]*model.CrossChainTransfer, int64, error)
}

type GasEstimateRepository interface {
	Create(ctx context.Context, estimate *model.GasEstimate) error
	GetByID(ctx context.Context, id string) (*model.GasEstimate, error)
	GetLatest(ctx context.Context, chainID, contractAddress, methodSig string) (*model.GasEstimate, error)
	List(ctx context.Context, chainID string, limit, offset int) ([]*model.GasEstimate, int64, error)
}

type ChainRPCNodeRepository interface {
	Create(ctx context.Context, node *model.ChainRPCNode) error
	Update(ctx context.Context, node *model.ChainRPCNode) error
	GetByID(ctx context.Context, id string) (*model.ChainRPCNode, error)
	ListByChainID(ctx context.Context, chainID string) ([]*model.ChainRPCNode, error)
	ListActiveByChainID(ctx context.Context, chainID string) ([]*model.ChainRPCNode, error)
}

type HDWalletRepository interface {
	Create(ctx context.Context, wallet *model.HDWallet) error
	Update(ctx context.Context, wallet *model.HDWallet) error
	GetByID(ctx context.Context, id string) (*model.HDWallet, error)
	ListByUserID(ctx context.Context, userID string) ([]*model.HDWallet, error)
}

type DerivedAddressRepository interface {
	Create(ctx context.Context, addr *model.DerivedAddress) error
	Update(ctx context.Context, addr *model.DerivedAddress) error
	GetByID(ctx context.Context, id string) (*model.DerivedAddress, error)
	GetByAddress(ctx context.Context, address string) (*model.DerivedAddress, error)
	ListByWalletID(ctx context.Context, walletID string, chainID string) ([]*model.DerivedAddress, error)
	ListByLabels(ctx context.Context, labels []string) ([]*model.DerivedAddress, error)
}
