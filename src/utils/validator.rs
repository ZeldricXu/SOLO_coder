use crate::models::{ErrorDetail, StreamSQLError, ValidationResponse};

pub trait Validate {
    fn validate(&self) -> Result<(), ValidationResponse>;
}

pub fn validate_required<T>(value: &Option<T>, field: &str) -> Result<(), ValidationResponse> {
    if value.is_none() {
        Err(ValidationResponse::single(field, "field is required"))
    } else {
        Ok(())
    }
}

pub fn validate_not_empty(value: &str, field: &str) -> Result<(), ValidationResponse> {
    if value.trim().is_empty() {
        Err(ValidationResponse::single(field, "field cannot be empty"))
    } else {
        Ok(())
    }
}

pub fn validate_range<T: PartialOrd>(
    value: T,
    min: T,
    max: T,
    field: &str,
) -> Result<(), ValidationResponse> {
    if value < min || value > max {
        Err(ValidationResponse::single(
            field,
            format!("value must be between {:?} and {:?}", min, max),
        ))
    } else {
        Ok(())
    }
}

pub fn validate_pattern(value: &str, pattern: &regex::Regex, field: &str) -> Result<(), ValidationResponse> {
    if !pattern.is_match(value) {
        Err(ValidationResponse::single(
            field,
            format!("value does not match pattern"),
        ))
    } else {
        Ok(())
    }
}

pub fn validate_positive<T: PartialOrd + From<i32>>(value: T, field: &str) -> Result<(), ValidationResponse> {
    if value <= T::from(0) {
        Err(ValidationResponse::single(field, "value must be positive"))
    } else {
        Ok(())
    }
}

pub fn validate_list_not_empty<T>(list: &[T], field: &str) -> Result<(), ValidationResponse> {
    if list.is_empty() {
        Err(ValidationResponse::single(field, "list cannot be empty"))
    } else {
        Ok(())
    }
}

pub fn validate_max_length(value: &str, max: usize, field: &str) -> Result<(), ValidationResponse> {
    if value.len() > max {
        Err(ValidationResponse::single(
            field,
            format!("value exceeds maximum length of {}", max),
        ))
    } else {
        Ok(())
    }
}

pub fn run_validations(validators: Vec<Result<(), ValidationResponse>>) -> Result<(), ValidationResponse> {
    let errors: Vec<ErrorDetail> = validators
        .into_iter()
        .filter_map(|r| r.err())
        .flat_map(|resp| resp.errors)
        .collect();

    if errors.is_empty() {
        Ok(())
    } else {
        Err(ValidationResponse::new("Validation failed", errors))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_validate_not_empty() {
        assert!(validate_not_empty("test", "field").is_ok());
        assert!(validate_not_empty("", "field").is_err());
        assert!(validate_not_empty("   ", "field").is_err());
    }

    #[test]
    fn test_validate_range() {
        assert!(validate_range(5, 0, 10, "count").is_ok());
        assert!(validate_range(15, 0, 10, "count").is_err());
        assert!(validate_range(-1, 0, 10, "count").is_err());
    }

    #[test]
    fn test_validate_positive() {
        assert!(validate_positive(42, "value").is_ok());
        assert!(validate_positive(0, "value").is_err());
        assert!(validate_positive(-1, "value").is_err());
    }

    #[test]
    fn test_run_validations() {
        let result = run_validations(vec![
            validate_not_empty("test", "field1"),
            validate_positive(42, "field2"),
        ]);
        assert!(result.is_ok());

        let result = run_validations(vec![
            validate_not_empty("", "field1"),
            validate_positive(-1, "field2"),
        ]);
        let err = result.err().unwrap();
        assert_eq!(err.errors.len(), 2);
    }
}
