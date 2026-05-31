use std::collections::HashMap;
use serde_json::Value;
use anyhow::{Result, anyhow};
use crate::models::{ProjectTemplate, InteractiveQuestion, TemplateParameter, ParamType};

pub struct QuestionFlow;

impl QuestionFlow {
    pub fn get_questions(template: &ProjectTemplate) -> Vec<InteractiveQuestion> {
        template.parameters.iter().map(|param| {
            InteractiveQuestion {
                id: param.name.clone(),
                text: param.description.clone(),
                param_name: param.name.clone(),
                param_type: param.param_type.clone(),
            }
        }).collect()
    }

    pub fn validate_answers(answers: &HashMap<String, Value>, params: &[TemplateParameter]) -> Result<()> {
        for param in params {
            let value = answers.get(&param.name);

            if param.required && value.is_none() {
                return Err(anyhow!("Parameter '{}' is required", param.name));
            }

            if let Some(val) = value {
                Self::validate_param_type(val, param)?;
            }
        }

        Ok(())
    }

    fn validate_param_type(value: &Value, param: &TemplateParameter) -> Result<()> {
        match param.param_type {
            ParamType::String => {
                if !value.is_string() {
                    return Err(anyhow!("Parameter '{}' must be a string", param.name));
                }
            }
            ParamType::Boolean => {
                if !value.is_boolean() {
                    return Err(anyhow!("Parameter '{}' must be a boolean", param.name));
                }
            }
            ParamType::Number => {
                if !value.is_number() {
                    return Err(anyhow!("Parameter '{}' must be a number", param.name));
                }
            }
            ParamType::Choice => {
                if let Some(choices) = &param.choices {
                    let value_str = value.as_str()
                        .ok_or_else(|| anyhow!("Parameter '{}' must be a string for choice type", param.name))?;
                    
                    if !choices.iter().any(|c| c == value_str) {
                        return Err(anyhow!(
                            "Parameter '{}' must be one of: {:?}",
                            param.name,
                            choices
                        ));
                    }
                }
            }
        }

        Ok(())
    }
}
