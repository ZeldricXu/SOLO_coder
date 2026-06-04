use std::collections::HashMap;

use common::models::CacheRule;
use common::utils::{generate_cache_key, is_simple_pattern, is_simple_prefix};
use regex::Regex;

enum RuleMatcher {
    Simple(String),
    SimplePrefix(String),
    Regex(Regex),
}

fn part_to_regex(part: &str) -> String {
    let mut regex = String::new();
    let chars: Vec<char> = part.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        match chars[i] {
            '*' => {
                regex.push_str("[^/]*");
            }
            '.' | '+' | '(' | ')' | '|' | '[' | ']' | '{' | '}' | '^' | '$' | '\\' => {
                regex.push('\\');
                regex.push(chars[i]);
            }
            c => {
                regex.push(c);
            }
        }
        i += 1;
    }
    regex
}

fn glob_to_regex(pattern: &str) -> String {
    let mut regex = String::new();
    regex.push('^');
    
    let parts: Vec<&str> = pattern.split('/').collect();
    for (i, part) in parts.iter().enumerate() {
        if i > 0 {
            regex.push('/');
        }
        match *part {
            "**" => {
                regex.push_str(".*");
            }
            "*" => {
                regex.push_str("[^/]*");
            }
            _ => {
                regex.push_str(&part_to_regex(part));
            }
        }
    }
    
    regex.push('$');
    regex
}

pub struct CacheKeyGenerator {
    rules: Vec<CacheRule>,
    matchers: Vec<RuleMatcher>,
}

impl CacheKeyGenerator {
    pub fn new() -> Self {
        CacheKeyGenerator {
            rules: Vec::new(),
            matchers: Vec::new(),
        }
    }

    pub fn with_rules(rules: Vec<CacheRule>) -> Self {
        let mut gen = CacheKeyGenerator {
            rules: Vec::new(),
            matchers: Vec::new(),
        };
        for rule in rules {
            gen.add_rule(rule);
        }
        gen
    }

    fn create_matcher(pattern: &str) -> RuleMatcher {
        if is_simple_pattern(pattern) {
            RuleMatcher::Simple(pattern.to_string())
        } else if is_simple_prefix(pattern) {
            let prefix = &pattern[..pattern.len() - 2];
            RuleMatcher::SimplePrefix(prefix.to_string())
        } else {
            let regex_str = glob_to_regex(pattern);
            let regex = Regex::new(&regex_str).unwrap();
            RuleMatcher::Regex(regex)
        }
    }

    pub fn add_rule(&mut self, rule: CacheRule) {
        let matcher = Self::create_matcher(&rule.path_pattern);
        self.rules.push(rule);
        self.matchers.push(matcher);
        
        let mut paired: Vec<_> = self.rules.drain(..).zip(self.matchers.drain(..)).collect();
        paired.sort_by(|a, b| b.0.priority.cmp(&a.0.priority));
        for (rule, matcher) in paired {
            self.rules.push(rule);
            self.matchers.push(matcher);
        }
    }

    pub fn remove_rule(&mut self, rule_id: &uuid::Uuid) {
        if let Some(pos) = self.rules.iter().position(|r| &r.id == rule_id) {
            self.rules.remove(pos);
            self.matchers.remove(pos);
        }
    }

    pub fn get_matching_rule(&self, domain: &str, path: &str) -> Option<&CacheRule> {
        for (i, rule) in self.rules.iter().enumerate() {
            if rule.domain != domain {
                continue;
            }
            
            let matched = match &self.matchers[i] {
                RuleMatcher::Simple(pattern) => {
                    path == pattern || path.starts_with(&format!("{}/", pattern))
                }
                RuleMatcher::SimplePrefix(prefix) => {
                    if !path.starts_with(&format!("{}/", prefix)) {
                        false
                    } else {
                        let remaining = &path[prefix.len() + 1..];
                        !remaining.contains('/')
                    }
                }
                RuleMatcher::Regex(regex) => {
                    regex.is_match(path)
                }
            };
            
            if matched {
                return Some(rule);
            }
        }
        None
    }

    pub fn generate_key(
        &self,
        domain: &str,
        path: &str,
        query_params: &HashMap<String, String>,
        user_agent: Option<&str>,
        referer: Option<&str>,
    ) -> (String, u64) {
        let rule = self.get_matching_rule(domain, path);
        
        match rule {
            Some(r) => {
                let key = generate_cache_key(
                    domain,
                    path,
                    query_params,
                    &r.ignore_query_params,
                    r.vary_by_ua,
                    r.vary_by_referer,
                    user_agent,
                    referer,
                );
                (key, r.ttl_seconds)
            }
            None => {
                let key = generate_cache_key(
                    domain,
                    path,
                    query_params,
                    &[],
                    false,
                    false,
                    None,
                    None,
                );
                (key, 3600)
            }
        }
    }

    pub fn get_cache_config(&self, domain: &str, path: &str) -> Option<CacheConfig> {
        self.get_matching_rule(domain, path).map(|rule| CacheConfig {
            ttl_seconds: rule.ttl_seconds,
            max_size_bytes: rule.max_size_bytes,
            eviction_policy: rule.eviction_policy.clone(),
        })
    }

    pub fn rules(&self) -> &[CacheRule] {
        &self.rules
    }
}

impl Default for CacheKeyGenerator {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone)]
pub struct CacheConfig {
    pub ttl_seconds: u64,
    pub max_size_bytes: u64,
    pub eviction_policy: common::models::CacheEvictionPolicy,
}
