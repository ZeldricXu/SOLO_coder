package adapter

import (
	"context"
	"github.com/solocoder/session147/internal/zkp/domain"
	"github.com/solocoder/session147/internal/zkp/ports"
	"gorm.io/gorm"
)

type gormZKPRepo struct {
	db *gorm.DB
}

func NewGormZKPRepository(db *gorm.DB) ports.ZKPRepository {
	return &gormZKPRepo{db: db}
}

func (r *gormZKPRepo) StoreProof(ctx context.Context, proof *domain.ZKPProof) error {
	return r.db.WithContext(ctx).Create(proof).Error
}

func (r *gormZKPRepo) GetProof(ctx context.Context, id string) (*domain.ZKPProof, error) {
	var proof domain.ZKPProof
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&proof).Error
	if err != nil {
		return nil, err
	}
	return &proof, nil
}

func (r *gormZKPRepo) ListProofs(ctx context.Context, filter map[string]interface{}, page, pageSize int) ([]domain.ZKPProof, int64, error) {
	var proofs []domain.ZKPProof
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.ZKPProof{})
	for k, v := range filter {
		query = query.Where(k+" = ?", v)
	}

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&proofs).Error
	return proofs, total, err
}

func (r *gormZKPRepo) UpdateProof(ctx context.Context, proof *domain.ZKPProof) error {
	return r.db.WithContext(ctx).Save(proof).Error
}

func (r *gormZKPRepo) StoreCircuit(ctx context.Context, circuit *domain.Circuit) error {
	return r.db.WithContext(ctx).Create(circuit).Error
}

func (r *gormZKPRepo) GetCircuit(ctx context.Context, id string) (*domain.Circuit, error) {
	var circuit domain.Circuit
	err := r.db.WithContext(ctx).Where("id = ?", id).First(&circuit).Error
	if err != nil {
		return nil, err
	}
	return &circuit, nil
}

func (r *gormZKPRepo) ListCircuits(ctx context.Context, page, pageSize int) ([]domain.Circuit, int64, error) {
	var circuits []domain.Circuit
	var total int64

	query := r.db.WithContext(ctx).Model(&domain.Circuit{})
	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&circuits).Error
	return circuits, total, err
}

func (r *gormZKPRepo) UpdateCircuit(ctx context.Context, circuit *domain.Circuit) error {
	return r.db.WithContext(ctx).Save(circuit).Error
}
