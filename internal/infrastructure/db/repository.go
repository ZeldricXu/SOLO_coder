package db

import (
	"context"
	"errors"
	"strings"

	"github.com/gasestimator/platform/internal/domain/model"
	"github.com/gasestimator/platform/internal/domain/repository"
	"gorm.io/gorm"
)

type GormRepository struct {
	db *gorm.DB
}

func NewGormRepository(db *gorm.DB) *GormRepository {
	return &GormRepository{db: db}
}

func (r *GormRepository) AutoMigrate() error {
	return r.db.AutoMigrate(
		&model.Entity{},
		&model.Config{},
		&model.RunInstance{},
		&model.Snapshot{},
		&model.ZKPProof{},
		&model.Transaction{},
		&model.MultisigProposal{},
		&model.ContractEvent{},
		&model.CrossChainTransfer{},
		&model.GasEstimate{},
		&model.ChainRPCNode{},
		&model.HDWallet{},
		&model.DerivedAddress{},
	)
}

type entityRepo struct{ *GormRepository }

func (r *GormRepository) Entity() repository.EntityRepository { return &entityRepo{r} }

func (r *entityRepo) Create(ctx context.Context, e *model.Entity) error {
	return r.db.WithContext(ctx).Create(e).Error
}
func (r *entityRepo) Update(ctx context.Context, e *model.Entity) error {
	return r.db.WithContext(ctx).Save(e).Error
}
func (r *entityRepo) GetByID(ctx context.Context, id string) (*model.Entity, error) {
	var e model.Entity
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&e).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &e, err
}
func (r *entityRepo) List(ctx context.Context, filter map[string]interface{}, limit, offset int) ([]*model.Entity, int64, error) {
	var entities []*model.Entity
	var total int64
	q := r.db.WithContext(ctx).Model(&model.Entity{})
	for k, v := range filter {
		q = q.Where(k+" = ?", v)
	}
	q.Count(&total)
	err := q.Limit(limit).Offset(offset).Find(&entities).Error
	return entities, total, err
}
func (r *entityRepo) Delete(ctx context.Context, id string) error {
	return r.db.WithContext(ctx).Delete(&model.Entity{}, "id = ?", id).Error
}

type configRepo struct{ *GormRepository }

func (r *GormRepository) Config() repository.ConfigRepository { return &configRepo{r} }

func (r *configRepo) Create(ctx context.Context, c *model.Config) error { return r.db.WithContext(ctx).Create(c).Error }
func (r *configRepo) Update(ctx context.Context, c *model.Config) error { return r.db.WithContext(ctx).Save(c).Error }
func (r *configRepo) GetByID(ctx context.Context, id string) (*model.Config, error) {
	var c model.Config
	err := r.db.WithContext(ctx).Where("config_id = ?", id).First(&c).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &c, err
}
func (r *configRepo) GetLatest(ctx context.Context, ns string) (*model.Config, error) {
	var c model.Config
	err := r.db.WithContext(ctx).Where("namespace = ?", ns).Order("version desc").First(&c).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &c, err
}
func (r *configRepo) List(ctx context.Context, ns string, limit, offset int) ([]*model.Config, int64, error) {
	var configs []*model.Config
	var total int64
	q := r.db.WithContext(ctx).Model(&model.Config{}).Where("namespace = ?", ns)
	q.Count(&total)
	err := q.Order("version desc").Limit(limit).Offset(offset).Find(&configs).Error
	return configs, total, err
}

type runRepo struct{ *GormRepository }

func (r *GormRepository) RunInstance() repository.RunInstanceRepository { return &runRepo{r} }

func (r *runRepo) Create(ctx context.Context, rn *model.RunInstance) error { return r.db.WithContext(ctx).Create(rn).Error }
func (r *runRepo) Update(ctx context.Context, rn *model.RunInstance) error { return r.db.WithContext(ctx).Save(rn).Error }
func (r *runRepo) GetByID(ctx context.Context, id string) (*model.RunInstance, error) {
	var rn model.RunInstance
	err := r.db.WithContext(ctx).Where("run_id = ?", id).First(&rn).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &rn, err
}
func (r *runRepo) ListByEntityID(ctx context.Context, eid string) ([]*model.RunInstance, error) {
	var runs []*model.RunInstance
	err := r.db.WithContext(ctx).Where("entity_id = ?", eid).Order("started_at desc").Find(&runs).Error
	return runs, err
}

type snapshotRepo struct{ *GormRepository }

func (r *GormRepository) Snapshot() repository.SnapshotRepository { return &snapshotRepo{r} }

func (r *snapshotRepo) Create(ctx context.Context, s *model.Snapshot) error { return r.db.WithContext(ctx).Create(s).Error }
func (r *snapshotRepo) GetByID(ctx context.Context, id string) (*model.Snapshot, error) {
	var s model.Snapshot
	err := r.db.WithContext(ctx).Where("snapshot_id = ?", id).First(&s).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &s, err
}
func (r *snapshotRepo) List(ctx context.Context, startTime, endTime int64, dims map[string]string) ([]*model.Snapshot, error) {
	var snapshots []*model.Snapshot
	q := r.db.WithContext(ctx).Where("timestamp >= to_timestamp(?) AND timestamp <= to_timestamp(?)", startTime, endTime)
	for k, v := range dims {
		q = q.Where("dimensions->>? = ?", k, v)
	}
	err := q.Order("timestamp desc").Find(&snapshots).Error
	return snapshots, err
}

type zkpRepo struct{ *GormRepository }

func (r *GormRepository) ZKPProof() repository.ZKPProofRepository { return &zkpRepo{r} }

func (r *zkpRepo) Create(ctx context.Context, p *model.ZKPProof) error { return r.db.WithContext(ctx).Create(p).Error }
func (r *zkpRepo) Update(ctx context.Context, p *model.ZKPProof) error { return r.db.WithContext(ctx).Save(p).Error }
func (r *zkpRepo) GetByID(ctx context.Context, id string) (*model.ZKPProof, error) {
	var p model.ZKPProof
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&p).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &p, err
}
func (r *zkpRepo) List(ctx context.Context, cid string, verified *bool, limit, offset int) ([]*model.ZKPProof, int64, error) {
	var proofs []*model.ZKPProof
	var total int64
	q := r.db.WithContext(ctx).Model(&model.ZKPProof{})
	if cid != "" {
		q = q.Where("circuit_id = ?", cid)
	}
	if verified != nil {
		q = q.Where("verified = ?", *verified)
	}
	q.Count(&total)
	err := q.Order("created_at desc").Limit(limit).Offset(offset).Find(&proofs).Error
	return proofs, total, err
}

type txRepo struct{ *GormRepository }

func (r *GormRepository) Transaction() repository.TransactionRepository { return &txRepo{r} }

func (r *txRepo) Create(ctx context.Context, t *model.Transaction) error { return r.db.WithContext(ctx).Create(t).Error }
func (r *txRepo) Update(ctx context.Context, t *model.Transaction) error { return r.db.WithContext(ctx).Save(t).Error }
func (r *txRepo) GetByID(ctx context.Context, id string) (*model.Transaction, error) {
	var t model.Transaction
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&t).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &t, err
}
func (r *txRepo) GetByTxHash(ctx context.Context, h string) (*model.Transaction, error) {
	var t model.Transaction
	err := r.db.WithContext(ctx).Where("tx_hash = ?", h).First(&t).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &t, err
}
func (r *txRepo) List(ctx context.Context, cid, addr, status string, limit, offset int) ([]*model.Transaction, int64, error) {
	var txs []*model.Transaction
	var total int64
	q := r.db.WithContext(ctx).Model(&model.Transaction{})
	if cid != "" {
		q = q.Where("chain_id = ?", cid)
	}
	if addr != "" {
		q = q.Where("from_address = ? OR to_address = ?", addr, addr)
	}
	if status != "" {
		q = q.Where("status = ?", status)
	}
	q.Count(&total)
	err := q.Order("created_at desc").Limit(limit).Offset(offset).Find(&txs).Error
	return txs, total, err
}
func (r *txRepo) ListPending(ctx context.Context, cid string) ([]*model.Transaction, error) {
	var txs []*model.Transaction
	err := r.db.WithContext(ctx).Where("chain_id = ? AND status IN (?)", cid, []string{"created", "partially_signed", "ready", "submitted"}).Find(&txs).Error
	return txs, err
}

type multisigRepo struct{ *GormRepository }

func (r *GormRepository) MultisigProposal() repository.MultisigProposalRepository { return &multisigRepo{r} }

func (r *multisigRepo) Create(ctx context.Context, p *model.MultisigProposal) error { return r.db.WithContext(ctx).Create(p).Error }
func (r *multisigRepo) Update(ctx context.Context, p *model.MultisigProposal) error { return r.db.WithContext(ctx).Save(p).Error }
func (r *multisigRepo) GetByID(ctx context.Context, id string) (*model.MultisigProposal, error) {
	var p model.MultisigProposal
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&p).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &p, err
}
func (r *multisigRepo) ListByWalletID(ctx context.Context, wid, status string) ([]*model.MultisigProposal, error) {
	var ps []*model.MultisigProposal
	q := r.db.WithContext(ctx).Where("wallet_id = ?", wid)
	if status != "" {
		q = q.Where("status = ?", status)
	}
	err := q.Order("created_at desc").Find(&ps).Error
	return ps, err
}
func (r *multisigRepo) ListReadyToExecute(ctx context.Context) ([]*model.MultisigProposal, error) {
	var ps []*model.MultisigProposal
	err := r.db.WithContext(ctx).Where("status = 'approved' AND approved_count >= threshold").Find(&ps).Error
	return ps, err
}

type eventRepo struct{ *GormRepository }

func (r *GormRepository) ContractEvent() repository.ContractEventRepository { return &eventRepo{r} }

func (r *eventRepo) Create(ctx context.Context, e *model.ContractEvent) error { return r.db.WithContext(ctx).Create(e).Error }
func (r *eventRepo) Update(ctx context.Context, e *model.ContractEvent) error { return r.db.WithContext(ctx).Save(e).Error }
func (r *eventRepo) GetByID(ctx context.Context, id string) (*model.ContractEvent, error) {
	var e model.ContractEvent
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&e).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &e, err
}
func (r *eventRepo) ListUnprocessed(ctx context.Context) ([]*model.ContractEvent, error) {
	var es []*model.ContractEvent
	err := r.db.WithContext(ctx).Where("processed = ?", false).Order("created_at asc").Find(&es).Error
	return es, err
}
func (r *eventRepo) List(ctx context.Context, cid, ca, en string, limit, offset int) ([]*model.ContractEvent, int64, error) {
	var es []*model.ContractEvent
	var total int64
	q := r.db.WithContext(ctx).Model(&model.ContractEvent{})
	if cid != "" {
		q = q.Where("chain_id = ?", cid)
	}
	if ca != "" {
		q = q.Where("contract_address = ?", ca)
	}
	if en != "" {
		q = q.Where("event_name = ?", en)
	}
	q.Count(&total)
	err := q.Order("created_at desc").Limit(limit).Offset(offset).Find(&es).Error
	return es, total, err
}

type bridgeRepo struct{ *GormRepository }

func (r *GormRepository) CrossChainTransfer() repository.CrossChainTransferRepository { return &bridgeRepo{r} }

func (r *bridgeRepo) Create(ctx context.Context, t *model.CrossChainTransfer) error { return r.db.WithContext(ctx).Create(t).Error }
func (r *bridgeRepo) Update(ctx context.Context, t *model.CrossChainTransfer) error { return r.db.WithContext(ctx).Save(t).Error }
func (r *bridgeRepo) GetByID(ctx context.Context, id string) (*model.CrossChainTransfer, error) {
	var t model.CrossChainTransfer
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&t).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &t, err
}
func (r *bridgeRepo) GetByLockTx(ctx context.Context, h string) (*model.CrossChainTransfer, error) {
	var t model.CrossChainTransfer
	err := r.db.WithContext(ctx).Where("lock_tx_hash = ?", h).First(&t).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &t, err
}
func (r *bridgeRepo) GetByMintTx(ctx context.Context, h string) (*model.CrossChainTransfer, error) {
	var t model.CrossChainTransfer
	err := r.db.WithContext(ctx).Where("mint_tx_hash = ?", h).First(&t).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &t, err
}
func (r *bridgeRepo) List(ctx context.Context, status string, limit, offset int) ([]*model.CrossChainTransfer, int64, error) {
	var ts []*model.CrossChainTransfer
	var total int64
	q := r.db.WithContext(ctx).Model(&model.CrossChainTransfer{})
	if status != "" {
		q = q.Where("status = ?", status)
	}
	q.Count(&total)
	err := q.Order("created_at desc").Limit(limit).Offset(offset).Find(&ts).Error
	return ts, total, err
}

type gasRepo struct{ *GormRepository }

func (r *GormRepository) GasEstimate() repository.GasEstimateRepository { return &gasRepo{r} }

func (r *gasRepo) Create(ctx context.Context, g *model.GasEstimate) error { return r.db.WithContext(ctx).Create(g).Error }
func (r *gasRepo) GetByID(ctx context.Context, id string) (*model.GasEstimate, error) {
	var g model.GasEstimate
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&g).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &g, err
}
func (r *gasRepo) GetLatest(ctx context.Context, cid, ca, ms string) (*model.GasEstimate, error) {
	var g model.GasEstimate
	q := r.db.WithContext(ctx).Where("chain_id = ?", cid)
	if ca != "" {
		q = q.Where("contract_address = ?", ca)
	}
	if ms != "" {
		q = q.Where("method_sig = ?", ms)
	}
	err := q.Order("created_at desc").First(&g).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &g, err
}
func (r *gasRepo) List(ctx context.Context, cid string, limit, offset int) ([]*model.GasEstimate, int64, error) {
	var gs []*model.GasEstimate
	var total int64
	q := r.db.WithContext(ctx).Model(&model.GasEstimate{})
	if cid != "" {
		q = q.Where("chain_id = ?", cid)
	}
	q.Count(&total)
	err := q.Order("created_at desc").Limit(limit).Offset(offset).Find(&gs).Error
	return gs, total, err
}

type chainRepo struct{ *GormRepository }

func (r *GormRepository) ChainRPCNode() repository.ChainRPCNodeRepository { return &chainRepo{r} }

func (r *chainRepo) Create(ctx context.Context, n *model.ChainRPCNode) error { return r.db.WithContext(ctx).Create(n).Error }
func (r *chainRepo) Update(ctx context.Context, n *model.ChainRPCNode) error { return r.db.WithContext(ctx).Save(n).Error }
func (r *chainRepo) GetByID(ctx context.Context, id string) (*model.ChainRPCNode, error) {
	var n model.ChainRPCNode
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&n).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &n, err
}
func (r *chainRepo) ListByChainID(ctx context.Context, cid string) ([]*model.ChainRPCNode, error) {
	var ns []*model.ChainRPCNode
	q := r.db.WithContext(ctx)
	if cid != "" {
		q = q.Where("chain_id = ?", cid)
	}
	err := q.Order("priority asc").Find(&ns).Error
	return ns, err
}
func (r *chainRepo) ListActiveByChainID(ctx context.Context, cid string) ([]*model.ChainRPCNode, error) {
	var ns []*model.ChainRPCNode
	q := r.db.WithContext(ctx).Where("status = 'active'")
	if cid != "" {
		q = q.Where("chain_id = ?", cid)
	}
	err := q.Order("priority asc, latency_ms asc").Find(&ns).Error
	return ns, err
}

type walletRepo struct{ *GormRepository }

func (r *GormRepository) HDWallet() repository.HDWalletRepository { return &walletRepo{r} }

func (r *walletRepo) Create(ctx context.Context, w *model.HDWallet) error { return r.db.WithContext(ctx).Create(w).Error }
func (r *walletRepo) Update(ctx context.Context, w *model.HDWallet) error { return r.db.WithContext(ctx).Save(w).Error }
func (r *walletRepo) GetByID(ctx context.Context, id string) (*model.HDWallet, error) {
	var w model.HDWallet
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&w).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &w, err
}
func (r *walletRepo) ListByUserID(ctx context.Context, uid string) ([]*model.HDWallet, error) {
	var ws []*model.HDWallet
	err := r.db.WithContext(ctx).Where("user_id = ?", uid).Order("created_at desc").Find(&ws).Error
	return ws, err
}

type addrRepo struct{ *GormRepository }

func (r *GormRepository) DerivedAddress() repository.DerivedAddressRepository { return &addrRepo{r} }

func (r *addrRepo) Create(ctx context.Context, a *model.DerivedAddress) error { return r.db.WithContext(ctx).Create(a).Error }
func (r *addrRepo) Update(ctx context.Context, a *model.DerivedAddress) error { return r.db.WithContext(ctx).Save(a).Error }
func (r *addrRepo) GetByID(ctx context.Context, id string) (*model.DerivedAddress, error) {
	var a model.DerivedAddress
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&a).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &a, err
}
func (r *addrRepo) GetByAddress(ctx context.Context, addr string) (*model.DerivedAddress, error) {
	var a model.DerivedAddress
	err := r.db.WithContext(ctx).Where("lower(address) = lower(?)", strings.ToLower(addr)).First(&a).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, errors.New("not found")
	}
	return &a, err
}
func (r *addrRepo) ListByWalletID(ctx context.Context, wid, cid string) ([]*model.DerivedAddress, error) {
	var as []*model.DerivedAddress
	q := r.db.WithContext(ctx).Where("wallet_id = ?", wid)
	if cid != "" {
		q = q.Where("chain_id = ?", cid)
	}
	err := q.Order("address_index asc").Find(&as).Error
	return as, err
}
func (r *addrRepo) ListByLabels(ctx context.Context, labels []string) ([]*model.DerivedAddress, error) {
	var as []*model.DerivedAddress
	q := r.db.WithContext(ctx)
	for _, label := range labels {
		q = q.Where("labels @> ?", "{"+label+"}")
	}
	err := q.Find(&as).Error
	return as, err
}
