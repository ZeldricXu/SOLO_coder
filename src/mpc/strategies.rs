use crate::models::AppError;
use crate::mpc::{MpcOperation, MpcSession};
use num_bigint::BigUint;
use num_traits::One;
use rand::Rng;
use rand_chacha::ChaCha20Rng;
use rand::SeedableRng;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use crate::utils::sha256_hex;

pub trait MpcComputationStrategy: Send + Sync {
    fn name(&self) -> &'static str;
    fn description(&self) -> &'static str;
    fn compute(
        &self,
        session: &MpcSession,
        inputs: &[Vec<u8>],
    ) -> Result<Vec<u8>, AppError>;
    fn validate_inputs(&self, inputs: &[Vec<u8>], operation: &MpcOperation) -> Result<(), AppError>;
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StrategyInfo {
    pub name: String,
    pub description: String,
    pub is_active: bool,
}

pub struct DefaultMpcStrategy;

impl DefaultMpcStrategy {
    pub fn new() -> Self {
        Self
    }
}

impl MpcComputationStrategy for DefaultMpcStrategy {
    fn name(&self) -> &'static str {
        "default"
    }

    fn description(&self) -> &'static str {
        "Default MPC strategy with balanced security and performance"
    }

    fn compute(
        &self,
        session: &MpcSession,
        inputs: &[Vec<u8>],
    ) -> Result<Vec<u8>, AppError> {
        self.validate_inputs(inputs, &session.operation)?;

        match session.operation {
            MpcOperation::Add => self.compute_add(inputs),
            MpcOperation::Multiply => self.compute_multiply(inputs),
            MpcOperation::Compare => self.compute_compare(inputs),
            MpcOperation::Custom => self.compute_custom(inputs, &session.metadata),
        }
    }

    fn validate_inputs(&self, inputs: &[Vec<u8>], operation: &MpcOperation) -> Result<(), AppError> {
        match operation {
            MpcOperation::Add | MpcOperation::Custom => {
                if inputs.is_empty() {
                    return Err(AppError::Validation("No inputs provided".to_string()));
                }
            }
            MpcOperation::Multiply | MpcOperation::Compare => {
                if inputs.len() < 2 {
                    return Err(AppError::Validation(format!(
                        "{:?} operation requires at least 2 inputs",
                        operation
                    )));
                }
            }
        }
        Ok(())
    }
}

impl DefaultMpcStrategy {
    fn compute_add(&self, inputs: &[Vec<u8>]) -> Result<Vec<u8>, AppError> {
        let mut rng = ChaCha20Rng::from_entropy();
        let random: [u8; 32] = rng.gen();
        
        let mut result = vec![0u8; 32];
        for (i, byte) in random.iter().enumerate() {
            result[i] = *byte;
        }

        for input in inputs {
            for (r, &i) in result.iter_mut().zip(input.iter().chain(std::iter::repeat(&0u8))) {
                *r = r.wrapping_add(i);
            }
        }

        Ok(result)
    }

    fn compute_multiply(&self, inputs: &[Vec<u8>]) -> Result<Vec<u8>, AppError> {
        let mut rng = ChaCha20Rng::from_entropy();
        let random: [u8; 32] = rng.gen();

        let big_ints: Vec<BigUint> = inputs
            .iter()
            .map(|bytes| BigUint::from_bytes_be(bytes))
            .collect();

        let mut product = BigUint::one();
        for bi in big_ints {
            product *= bi;
        }

        let mut result = product.to_bytes_be();
        if result.len() < 32 {
            let mut padded = vec![0u8; 32 - result.len()];
            padded.extend(result);
            result = padded;
        }
        result.truncate(32);

        for (i, byte) in random.iter().enumerate() {
            result[i] ^= *byte;
        }

        Ok(result)
    }

    fn compute_compare(&self, inputs: &[Vec<u8>]) -> Result<Vec<u8>, AppError> {
        let a = BigUint::from_bytes_be(&inputs[0]);
        let b = BigUint::from_bytes_be(&inputs[1]);

        let result = if a > b {
            vec![1u8]
        } else if a < b {
            vec![255u8]
        } else {
            vec![0u8]
        };

        Ok(result)
    }

    fn compute_custom(
        &self,
        inputs: &[Vec<u8>],
        metadata: &serde_json::Value,
    ) -> Result<Vec<u8>, AppError> {
        let op = metadata
            .get("custom_op")
            .and_then(|v| v.as_str())
            .unwrap_or("concat");

        match op {
            "concat" => {
                let mut result = Vec::new();
                for input in inputs {
                    result.extend_from_slice(input);
                }
                Ok(sha256_hex(&result).into_bytes())
            }
            "xor" => {
                let mut result = inputs[0].clone();
                for input in &inputs[1..] {
                    for (r, &i) in result.iter_mut().zip(input.iter()) {
                        *r ^= i;
                    }
                }
                Ok(result)
            }
            "sum" => self.compute_add(inputs),
            _ => Err(AppError::Validation(format!("Unknown custom operation: {}", op))),
        }
    }
}

pub struct SecureMpcStrategy;

impl SecureMpcStrategy {
    pub fn new() -> Self {
        Self
    }
}

impl MpcComputationStrategy for SecureMpcStrategy {
    fn name(&self) -> &'static str {
        "secure"
    }

    fn description(&self) -> &'static str {
        "High-security MPC strategy with additional encryption layers"
    }

    fn compute(
        &self,
        session: &MpcSession,
        inputs: &[Vec<u8>],
    ) -> Result<Vec<u8>, AppError> {
        self.validate_inputs(inputs, &session.operation)?;

        let enhanced_inputs: Vec<Vec<u8>> = inputs
            .iter()
            .map(|input| {
                let mut enhanced = Vec::with_capacity(input.len() + 32);
                let salt = crate::utils::random_bytes(16);
                let hash = crate::utils::sha256_hash(input);
                enhanced.extend_from_slice(&salt);
                enhanced.extend_from_slice(input);
                enhanced.extend_from_slice(&hash);
                enhanced
            })
            .collect();

        let default = DefaultMpcStrategy::new();
        let mut result = default.compute(session, &enhanced_inputs)?;

        let additional_seed = crate::utils::random_u64();
        for (i, byte) in result.iter_mut().enumerate() {
            *byte ^= ((additional_seed >> (i % 64)) & 0xFF) as u8;
        }

        Ok(result)
    }

    fn validate_inputs(&self, inputs: &[Vec<u8>], operation: &MpcOperation) -> Result<(), AppError> {
        for (idx, input) in inputs.iter().enumerate() {
            if input.len() < 1 {
                return Err(AppError::Validation(format!("Input {} is empty", idx)));
            }
            if input.iter().all(|&b| b == 0) {
                return Err(AppError::Validation(format!("Input {} is all zeros", idx)));
            }
        }
        DefaultMpcStrategy::new().validate_inputs(inputs, operation)
    }
}

pub struct FastMpcStrategy;

impl FastMpcStrategy {
    pub fn new() -> Self {
        Self
    }
}

impl MpcComputationStrategy for FastMpcStrategy {
    fn name(&self) -> &'static str {
        "fast"
    }

    fn description(&self) -> &'static str {
        "High-performance MPC strategy with minimal overhead"
    }

    fn compute(
        &self,
        session: &MpcSession,
        inputs: &[Vec<u8>],
    ) -> Result<Vec<u8>, AppError> {
        self.validate_inputs(inputs, &session.operation)?;

        match session.operation {
            MpcOperation::Add => self.fast_add(inputs),
            MpcOperation::Multiply => self.fast_multiply(inputs),
            MpcOperation::Compare => self.fast_compare(inputs),
            MpcOperation::Custom => self.fast_custom(inputs, &session.metadata),
        }
    }

    fn validate_inputs(&self, inputs: &[Vec<u8>], operation: &MpcOperation) -> Result<(), AppError> {
        DefaultMpcStrategy::new().validate_inputs(inputs, operation)
    }
}

impl FastMpcStrategy {
    fn fast_add(&self, inputs: &[Vec<u8>]) -> Result<Vec<u8>, AppError> {
        let max_len = inputs.iter().map(|v| v.len()).max().unwrap_or(0);
        let mut result = vec![0u8; max_len.max(32)];
        
        for input in inputs {
            for (i, &byte) in input.iter().enumerate() {
                if i < result.len() {
                    result[i] = result[i].wrapping_add(byte);
                }
            }
        }
        
        if result.len() < 32 {
            result.resize(32, 0);
        }
        
        Ok(result)
    }

    fn fast_multiply(&self, inputs: &[Vec<u8>]) -> Result<Vec<u8>, AppError> {
        let big_ints: Vec<BigUint> = inputs
            .iter()
            .map(|bytes| BigUint::from_bytes_be(bytes))
            .collect();

        let mut product = BigUint::one();
        for bi in big_ints {
            product *= bi;
        }

        let mut result = product.to_bytes_be();
        if result.len() < 32 {
            let mut padded = vec![0u8; 32 - result.len()];
            padded.extend(result);
            result = padded;
        }
        result.truncate(32);

        Ok(result)
    }

    fn fast_compare(&self, inputs: &[Vec<u8>]) -> Result<Vec<u8>, AppError> {
        let a = BigUint::from_bytes_be(&inputs[0]);
        let b = BigUint::from_bytes_be(&inputs[1]);
        Ok(vec![if a > b { 1 } else if a < b { 255 } else { 0 }])
    }

    fn fast_custom(
        &self,
        inputs: &[Vec<u8>],
        metadata: &serde_json::Value,
    ) -> Result<Vec<u8>, AppError> {
        let op = metadata
            .get("custom_op")
            .and_then(|v| v.as_str())
            .unwrap_or("concat");

        match op {
            "concat" => {
                let mut result = Vec::new();
                for input in inputs {
                    result.extend_from_slice(input);
                }
                if result.len() < 32 {
                    result.resize(32, 0);
                }
                Ok(result)
            }
            "xor" => {
                let mut result = inputs[0].clone();
                for input in &inputs[1..] {
                    for (r, &i) in result.iter_mut().zip(input.iter()) {
                        *r ^= i;
                    }
                }
                Ok(result)
            }
            "sum" => self.fast_add(inputs),
            _ => Err(AppError::Validation(format!("Unknown custom operation: {}", op))),
        }
    }
}

pub type StrategyBox = Arc<dyn MpcComputationStrategy>;

pub struct StrategyRegistry {
    strategies: std::collections::HashMap<String, StrategyBox>,
    active_strategy: String,
}

impl StrategyRegistry {
    pub fn new() -> Self {
        let mut registry = Self {
            strategies: std::collections::HashMap::new(),
            active_strategy: "default".to_string(),
        };

        registry.register(Arc::new(DefaultMpcStrategy::new()));
        registry.register(Arc::new(SecureMpcStrategy::new()));
        registry.register(Arc::new(FastMpcStrategy::new()));
        registry
    }

    pub fn register(&mut self, strategy: StrategyBox) {
        let name = strategy.name().to_string();
        self.strategies.insert(name, strategy);
    }

    pub fn get_active(&self) -> Option<&StrategyBox> {
        self.strategies.get(&self.active_strategy)
    }

    pub fn get(&self, name: &str) -> Option<&StrategyBox> {
        self.strategies.get(name)
    }

    pub fn set_active(&mut self, name: &str) -> Result<(), AppError> {
        if self.strategies.contains_key(name) {
            self.active_strategy = name.to_string();
            Ok(())
        } else {
            Err(AppError::Validation(format!("Strategy not found: {}", name)))
        }
    }

    pub fn list_strategies(&self) -> Vec<StrategyInfo> {
        self.strategies
            .iter()
            .map(|(name, strategy)| StrategyInfo {
                name: name.clone(),
                description: strategy.description().to_string(),
                is_active: *name == self.active_strategy,
            })
            .collect()
    }

    pub fn active_strategy_name(&self) -> &str {
        &self.active_strategy
    }
}

impl Default for StrategyRegistry {
    fn default() -> Self {
        Self::new()
    }
}
