-- =====================================================
-- V2__business_indexes.sql
-- 业务查询性能优化索引
-- =====================================================

-- storage_pin 表索引优化
CREATE INDEX idx_storage_pin_status ON storage_pin(pin_status);
CREATE INDEX idx_storage_pin_type ON storage_pin(storage_type);
CREATE INDEX idx_storage_pin_cid ON storage_pin(cid);

-- cross_chain_lock 表索引优化
CREATE INDEX idx_cross_chain_lock_status ON cross_chain_lock(lock_status);
CREATE INDEX idx_cross_chain_lock_pair ON cross_chain_lock(source_chain, target_chain);
CREATE INDEX idx_cross_chain_lock_address ON cross_chain_lock(locker_address);
CREATE INDEX idx_cross_chain_lock_created ON cross_chain_lock(created_at);

-- cross_chain_mint 表索引优化
CREATE INDEX idx_cross_chain_mint_status ON cross_chain_mint(mint_status);
CREATE INDEX idx_cross_chain_mint_lock_id ON cross_chain_mint(lock_id);
CREATE INDEX idx_cross_chain_mint_target_chain ON cross_chain_mint(target_chain);

-- multisig_proposal 表索引优化
CREATE INDEX idx_multisig_proposal_wallet ON multisig_proposal(wallet_address);
CREATE INDEX idx_multisig_proposal_status ON multisig_proposal(status);
CREATE INDEX idx_multisig_proposal_target ON multisig_proposal(target_address);
CREATE INDEX idx_multisig_proposal_created ON multisig_proposal(created_at);

-- multisig_signature 表索引优化
CREATE INDEX idx_multisig_signature_proposal ON multisig_signature(proposal_id);
CREATE INDEX idx_multisig_signature_signer ON multisig_signature(signer_address);
CREATE INDEX idx_multisig_signature_signed ON multisig_signature(signed_at);

-- chain_block 表索引优化
CREATE INDEX idx_chain_block_chain_id ON chain_block(chain_id);
CREATE INDEX idx_chain_block_timestamp ON chain_block(timestamp);
CREATE INDEX idx_chain_block_indexed ON chain_block(indexed_at);

-- chain_transaction 表索引优化
CREATE INDEX idx_chain_tx_chain_block ON chain_transaction(chain_id, block_number);
CREATE INDEX idx_chain_tx_from ON chain_transaction(from_address);
CREATE INDEX idx_chain_tx_to ON chain_transaction(to_address);
CREATE INDEX idx_chain_tx_status ON chain_transaction(status);
CREATE INDEX idx_chain_tx_indexed ON chain_transaction(indexed_at);
CREATE INDEX idx_chain_tx_value ON chain_transaction(value);

-- address_entry 表索引优化
CREATE INDEX idx_address_entry_type ON address_entry(chain_type);
CREATE INDEX idx_address_entry_path ON address_entry(path);
CREATE INDEX idx_address_entry_hd_index ON address_entry(hd_index);

-- gas_estimate 表索引优化
CREATE INDEX idx_gas_estimate_chain ON gas_estimate(chain_id);
CREATE INDEX idx_gas_estimate_block ON gas_estimate(block_number);
CREATE INDEX idx_gas_estimate_recorded ON gas_estimate(recorded_at);
CREATE INDEX idx_gas_estimate_price ON gas_estimate(gas_price);
