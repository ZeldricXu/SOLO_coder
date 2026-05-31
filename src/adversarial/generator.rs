use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use rand::Rng;
use crate::utils::error::Result;

use super::strategies::{AttackStrategy, AttackTemplate, AttackType, get_all_strategies};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AdversarialPrompt {
    pub id: String,
    pub attack_type: AttackType,
    pub template_id: String,
    pub prompt: String,
    pub parameters: HashMap<String, String>,
    pub severity: super::strategies::AttackSeverity,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GenerationConfig {
    pub attack_types: Option<Vec<AttackType>>,
    pub severity_filter: Option<super::strategies::AttackSeverity>,
    pub max_prompts_per_strategy: usize,
    pub custom_parameters: Option<HashMap<String, String>>,
}

impl Default for GenerationConfig {
    fn default() -> Self {
        Self {
            attack_types: None,
            severity_filter: None,
            max_prompts_per_strategy: 5,
            custom_parameters: None,
        }
    }
}

pub struct AdversarialGenerator {
    strategies: Vec<AttackStrategy>,
}

impl AdversarialGenerator {
    pub fn new() -> Self {
        Self {
            strategies: get_all_strategies(),
        }
    }

    pub fn with_strategies(strategies: Vec<AttackStrategy>) -> Self {
        Self { strategies }
    }

    pub fn generate(&self, config: GenerationConfig) -> Result<Vec<AdversarialPrompt>> {
        let mut prompts = Vec::new();
        let mut rng = rand::thread_rng();

        for strategy in &self.strategies {
            if !strategy.enabled {
                continue;
            }

            if let Some(filter) = &config.attack_types {
                if !filter.contains(&strategy.attack_type) {
                    continue;
                }
            }

            if let Some(severity) = &config.severity_filter {
                if &strategy.severity != severity {
                    continue;
                }
            }

            let count = std::cmp::min(config.max_prompts_per_strategy, strategy.templates.len());
            let selected_templates = if strategy.templates.len() <= count {
                strategy.templates.clone()
            } else {
                let mut indices: Vec<usize> = (0..strategy.templates.len()).collect();
                for i in (1..indices.len()).rev() {
                    let j = rng.gen_range(0..=i);
                    indices.swap(i, j);
                }
                indices.into_iter()
                    .take(count)
                    .map(|i| strategy.templates[i].clone())
                    .collect()
            };

            for template in selected_templates {
                let prompt = self.render_template(&template, &config.custom_parameters)?;
                let parameters = self.extract_parameters(&template, &config.custom_parameters);
                
                prompts.push(AdversarialPrompt {
                    id: format!("adv_{}", crate::utils::id::generate_id()),
                    attack_type: strategy.attack_type.clone(),
                    template_id: template.id.clone(),
                    prompt,
                    parameters,
                    severity: strategy.severity.clone(),
                    created_at: chrono::Utc::now(),
                });
            }
        }

        Ok(prompts)
    }

    pub fn generate_for_attack_type(
        &self,
        attack_type: AttackType,
        count: usize,
    ) -> Result<Vec<AdversarialPrompt>> {
        let config = GenerationConfig {
            attack_types: Some(vec![attack_type]),
            max_prompts_per_strategy: count,
            ..Default::default()
        };
        self.generate(config)
    }

    fn render_template(
        &self,
        template: &AttackTemplate,
        custom_params: &Option<HashMap<String, String>>,
    ) -> Result<String> {
        let mut result = template.prompt_template.clone();

        for (key, param) in &template.parameters {
            let value = if let Some(custom) = custom_params {
                custom.get(key).cloned().unwrap_or_else(|| {
                    self.select_parameter_value(param)
                })
            } else {
                self.select_parameter_value(param)
            };

            let placeholder = format!("{{{}}}", key);
            result = result.replace(&placeholder, &value);
        }

        Ok(result)
    }

    fn select_parameter_value(&self, param: &super::strategies::TemplateParameter) -> String {
        if let Some(values) = &param.values {
            let mut rng = rand::thread_rng();
            let idx = rng.gen_range(0..values.len());
            values[idx].clone()
        } else {
            param.default_value.clone()
        }
    }

    fn extract_parameters(
        &self,
        template: &AttackTemplate,
        custom_params: &Option<HashMap<String, String>>,
    ) -> HashMap<String, String> {
        let mut params = HashMap::new();

        for (key, param) in &template.parameters {
            let value = if let Some(custom) = custom_params {
                custom.get(key).cloned().unwrap_or_else(|| {
                    self.select_parameter_value(param)
                })
            } else {
                self.select_parameter_value(param)
            };
            params.insert(key.clone(), value);
        }

        params
    }

    pub fn get_strategies(&self) -> &[AttackStrategy] {
        &self.strategies
    }

    pub fn get_strategy_by_type(&self, attack_type: &AttackType) -> Option<&AttackStrategy> {
        self.strategies.iter().find(|s| &s.attack_type == attack_type)
    }
}

impl Default for AdversarialGenerator {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use super::super::strategies::{AttackType, AttackSeverity};

    #[test]
    fn test_generator_creation() {
        let generator = AdversarialGenerator::new();
        assert!(!generator.get_strategies().is_empty());
    }

    #[test]
    fn test_generate_all() {
        let generator = AdversarialGenerator::new();
        let config = GenerationConfig::default();
        let prompts = generator.generate(config).unwrap();
        
        assert!(!prompts.is_empty());
        for prompt in &prompts {
            assert!(!prompt.id.is_empty());
            assert!(!prompt.prompt.is_empty());
        }
    }

    #[test]
    fn test_generate_by_attack_type() {
        let generator = AdversarialGenerator::new();
        let prompts = generator.generate_for_attack_type(AttackType::PromptInjection, 3).unwrap();
        
        assert!(!prompts.is_empty());
        for prompt in &prompts {
            assert_eq!(prompt.attack_type, AttackType::PromptInjection);
        }
    }

    #[test]
    fn test_generate_with_severity_filter() {
        let generator = AdversarialGenerator::new();
        let config = GenerationConfig {
            severity_filter: Some(AttackSeverity::Critical),
            ..Default::default()
        };
        let prompts = generator.generate(config).unwrap();
        
        for prompt in &prompts {
            assert_eq!(prompt.severity, AttackSeverity::Critical);
        }
    }

    #[test]
    fn test_template_rendering() {
        let generator = AdversarialGenerator::new();
        let strategy = generator.get_strategy_by_type(&AttackType::PromptInjection).unwrap();
        let template = &strategy.templates[0];
        
        let mut custom_params = HashMap::new();
        custom_params.insert("malicious_command".to_string(), "TEST_COMMAND".to_string());
        
        let prompt = generator.render_template(template, &Some(custom_params)).unwrap();
        assert!(prompt.contains("TEST_COMMAND"));
    }

    #[test]
    fn test_parameter_extraction() {
        let generator = AdversarialGenerator::new();
        let strategy = generator.get_strategy_by_type(&AttackType::Jailbreak).unwrap();
        let template = &strategy.templates[0];
        
        let params = generator.extract_parameters(template, &None);
        assert!(params.contains_key("harmful_action"));
    }
}
