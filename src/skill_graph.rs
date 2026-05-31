use std::collections::{HashMap, HashSet};

use anyhow::{anyhow, Result};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use parking_lot::RwLock;
use petgraph::graph::{DiGraph, NodeIndex};
use petgraph::visit::EdgeRef;
use serde::{Deserialize, Serialize};
use tracing::{debug, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Skill {
    pub skill_id: String,
    pub name: String,
    pub description: String,
    pub category: String,
    pub level: SkillLevel,
    pub prerequisites: Vec<String>,
    pub estimated_hours_to_learn: u32,
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
pub enum SkillLevel {
    Beginner,
    Intermediate,
    Advanced,
    Expert,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillTree {
    pub tree_id: String,
    pub name: String,
    pub description: String,
    pub root_skills: Vec<String>,
    pub skills: HashMap<String, Skill>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmployeeSkillAssessment {
    pub employee_id: String,
    pub skill_id: String,
    pub proficiency: f64,
    pub last_assessed: DateTime<Utc>,
    pub assessment_method: AssessmentMethod,
    pub evidence: Vec<AssessmentEvidence>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum AssessmentMethod {
    SelfAssessment,
    ManagerReview,
    Test,
    ProjectEvaluation,
    Certification,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AssessmentEvidence {
    pub evidence_id: String,
    pub evidence_type: String,
    pub description: String,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LearningPath {
    pub path_id: String,
    pub employee_id: String,
    pub target_skill_id: String,
    pub steps: Vec<LearningStep>,
    pub estimated_total_hours: u32,
    pub priority: PathPriority,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LearningStep {
    pub skill_id: String,
    pub step_order: u32,
    pub estimated_hours: u32,
    pub resources: Vec<LearningResource>,
    pub dependencies: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LearningResource {
    pub resource_id: String,
    pub resource_type: String,
    pub title: String,
    pub url: String,
    pub estimated_hours: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum PathPriority {
    Low,
    Medium,
    High,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillGap {
    pub skill_id: String,
    pub current_proficiency: f64,
    pub required_proficiency: f64,
    pub gap: f64,
}

pub struct SkillGraphManager {
    skill_trees: DashMap<String, SkillTree>,
    assessments: DashMap<String, DashMap<String, EmployeeSkillAssessment>>,
    learning_paths: DashMap<String, DashMap<String, LearningPath>>,
    graphs: RwLock<HashMap<String, DiGraph<Skill, u32>>>,
    listeners: RwLock<Vec<Arc<dyn Fn(SkillEvent) -> Result<()> + Send + Sync>>>,
}

use std::sync::Arc;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillEvent {
    pub event_id: String,
    pub event_type: SkillEventType,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum SkillEventType {
    SkillAdded(String),
    SkillUpdated(String),
    SkillDeleted(String),
    AssessmentAdded(String, String),
    LearningPathCreated(String, String),
}

impl SkillGraphManager {
    pub fn new() -> Self {
        Self {
            skill_trees: DashMap::new(),
            assessments: DashMap::new(),
            learning_paths: DashMap::new(),
            graphs: RwLock::new(HashMap::new()),
            listeners: RwLock::new(Vec::new()),
        }
    }

    pub fn register_listener<F>(&self, listener: F)
    where
        F: Fn(SkillEvent) -> Result<()> + Send + Sync + 'static,
    {
        self.listeners.write().push(Arc::new(listener));
    }

    fn notify_listeners(&self, event: SkillEvent) {
        let listeners = self.listeners.read();
        for listener in listeners.iter() {
            let event = event.clone();
            let listener = listener.clone();
            tokio::spawn(async move {
                if let Err(e) = listener(event) {
                    tracing::error!(error = %e, "Skill event listener failed");
                }
            });
        }
    }

    pub fn create_skill_tree(&self, name: String, description: String) -> SkillTree {
        let tree_id = format!("tree_{}", Uuid::new_v4().simple());
        let now = Utc::now();
        
        let tree = SkillTree {
            tree_id: tree_id.clone(),
            name,
            description,
            root_skills: Vec::new(),
            skills: HashMap::new(),
            created_at: now,
            updated_at: now,
        };
        
        self.skill_trees.insert(tree_id.clone(), tree.clone());
        self.graphs.write().insert(tree_id, DiGraph::new());
        
        info!("Created skill tree: {}", tree.name);
        tree
    }

    pub fn add_skill(&self, tree_id: &str, skill: Skill) -> Result<()> {
        let mut tree = self.skill_trees.get_mut(tree_id)
            .ok_or_else(|| anyhow!("Skill tree not found: {}", tree_id))?;
        
        let skill_id = skill.skill_id.clone();
        tree.skills.insert(skill_id.clone(), skill.clone());
        tree.updated_at = Utc::now();
        
        let mut graphs = self.graphs.write();
        let graph = graphs.get_mut(tree_id)
            .ok_or_else(|| anyhow!("Graph not found for tree: {}", tree_id))?;
        
        let node_idx = graph.add_node(skill);
        
        for prereq in &tree.skills.get(&skill_id).unwrap().prerequisites {
            if let Some(prereq_skill) = tree.skills.get(prereq) {
                let prereq_idx = graph.add_node(prereq_skill.clone());
                graph.add_edge(prereq_idx, node_idx, 1);
            }
        }
        
        drop(tree);
        drop(graphs);
        
        self.notify_listeners(SkillEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            event_type: SkillEventType::SkillAdded(skill_id),
            timestamp: Utc::now(),
        });
        
        Ok(())
    }

    pub fn get_skill(&self, tree_id: &str, skill_id: &str) -> Option<Skill> {
        self.skill_trees.get(tree_id)
            .and_then(|tree| tree.skills.get(skill_id).cloned())
    }

    pub fn get_skill_tree(&self, tree_id: &str) -> Option<SkillTree> {
        self.skill_trees.get(tree_id).map(|t| t.clone())
    }

    pub fn list_skill_trees(&self) -> Vec<SkillTree> {
        self.skill_trees.iter().map(|t| t.clone()).collect()
    }

    pub fn add_assessment(&self, employee_id: &str, assessment: EmployeeSkillAssessment) -> Result<()> {
        let employee_assessments = self.assessments
            .entry(employee_id.to_string())
            .or_insert_with(DashMap::new);
        
        employee_assessments.insert(assessment.skill_id.clone(), assessment.clone());
        
        self.notify_listeners(SkillEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            event_type: SkillEventType::AssessmentAdded(
                employee_id.to_string(),
                assessment.skill_id.clone(),
            ),
            timestamp: Utc::now(),
        });
        
        debug!(
            "Added assessment for employee {} on skill {}",
            employee_id, assessment.skill_id
        );
        
        Ok(())
    }

    pub fn get_employee_assessments(&self, employee_id: &str) -> Vec<EmployeeSkillAssessment> {
        self.assessments.get(employee_id)
            .map(|assessments| {
                assessments.iter().map(|a| a.clone()).collect()
            })
            .unwrap_or_default()
    }

    pub fn get_employee_skill_proficiency(&self, employee_id: &str, skill_id: &str) -> f64 {
        self.assessments.get(employee_id)
            .and_then(|assessments| assessments.get(skill_id).map(|a| a.proficiency))
            .unwrap_or(0.0)
    }

    pub fn identify_skill_gaps(
        &self,
        employee_id: &str,
        required_skills: &HashMap<String, f64>,
    ) -> Vec<SkillGap> {
        let mut gaps = Vec::new();
        
        for (skill_id, required_proficiency) in required_skills {
            let current_proficiency = self.get_employee_skill_proficiency(employee_id, skill_id);
            let gap = required_proficiency - current_proficiency;
            
            if gap > 0.0 {
                gaps.push(SkillGap {
                    skill_id: skill_id.clone(),
                    current_proficiency,
                    required_proficiency: *required_proficiency,
                    gap,
                });
            }
        }
        
        gaps.sort_by(|a, b| b.gap.partial_cmp(&a.gap).unwrap_or(std::cmp::Ordering::Equal));
        gaps
    }

    pub fn generate_learning_path(
        &self,
        tree_id: &str,
        employee_id: &str,
        target_skill_id: &str,
        priority: PathPriority,
    ) -> Result<LearningPath> {
        let tree = self.skill_trees.get(tree_id)
            .ok_or_else(|| anyhow!("Skill tree not found: {}", tree_id))?;
        
        let graphs = self.graphs.read();
        let graph = graphs.get(tree_id)
            .ok_or_else(|| anyhow!("Graph not found for tree: {}", tree_id))?;
        
        let target_skill = tree.skills.get(target_skill_id)
            .ok_or_else(|| anyhow!("Target skill not found: {}", target_skill_id))?;
        
        let mut steps = Vec::new();
        let mut visited = HashSet::new();
        let mut queue = vec![target_skill.clone()];
        
        while let Some(skill) = queue.pop() {
            if !visited.insert(skill.skill_id.clone()) {
                continue;
            }
            
            let current_proficiency = self.get_employee_skill_proficiency(employee_id, &skill.skill_id);
            
            if current_proficiency < 1.0 {
                steps.push(LearningStep {
                    skill_id: skill.skill_id.clone(),
                    step_order: steps.len() as u32,
                    estimated_hours: ((1.0 - current_proficiency) * skill.estimated_hours_to_learn as f64) as u32,
                    resources: Self::suggest_resources(&skill),
                    dependencies: skill.prerequisites.clone(),
                });
            }
            
            for prereq_id in &skill.prerequisites {
                if let Some(prereq_skill) = tree.skills.get(prereq_id) {
                    queue.push(prereq_skill.clone());
                }
            }
        }
        
        steps.reverse();
        
        let total_hours = steps.iter().map(|s| s.estimated_hours).sum();
        
        let path = LearningPath {
            path_id: format!("path_{}", Uuid::new_v4().simple()),
            employee_id: employee_id.to_string(),
            target_skill_id: target_skill_id.to_string(),
            steps,
            estimated_total_hours: total_hours,
            priority,
            created_at: Utc::now(),
        };
        
        let employee_paths = self.learning_paths
            .entry(employee_id.to_string())
            .or_insert_with(DashMap::new);
        
        employee_paths.insert(path.path_id.clone(), path.clone());
        
        self.notify_listeners(SkillEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            event_type: SkillEventType::LearningPathCreated(
                employee_id.to_string(),
                path.path_id.clone(),
            ),
            timestamp: Utc::now(),
        });
        
        info!(
            "Generated learning path for employee {} to skill {}",
            employee_id, target_skill_id
        );
        
        Ok(path)
    }

    fn suggest_resources(skill: &Skill) -> Vec<LearningResource> {
        let mut resources = Vec::new();
        
        let resource_types = vec![
            ("documentation", "Official Documentation"),
            ("course", "Online Course"),
            ("tutorial", "Tutorial"),
            ("book", "Recommended Book"),
            ("project", "Hands-on Project"),
        ];
        
        for (i, (rtype, title)) in resource_types.iter().enumerate() {
            resources.push(LearningResource {
                resource_id: format!("res_{}_{}", skill.skill_id, i),
                resource_type: rtype.to_string(),
                title: format!("{}: {}", title, skill.name),
                url: format!("https://example.com/resources/{}/{}", rtype, skill.skill_id),
                estimated_hours: (skill.estimated_hours_to_learn / 5).max(1),
            });
        }
        
        resources
    }

    pub fn get_learning_path(&self, employee_id: &str, path_id: &str) -> Option<LearningPath> {
        self.learning_paths.get(employee_id)
            .and_then(|paths| paths.get(path_id).map(|p| p.clone()))
    }

    pub fn get_employee_learning_paths(&self, employee_id: &str) -> Vec<LearningPath> {
        self.learning_paths.get(employee_id)
            .map(|paths| paths.iter().map(|p| p.clone()).collect())
            .unwrap_or_default()
    }

    pub fn get_prerequisite_chain(&self, tree_id: &str, skill_id: &str) -> Result<Vec<Skill>> {
        let tree = self.skill_trees.get(tree_id)
            .ok_or_else(|| anyhow!("Skill tree not found: {}", tree_id))?;
        
        let mut chain = Vec::new();
        let mut visited = HashSet::new();
        let mut stack = vec![skill_id.to_string()];
        
        while let Some(current_id) = stack.pop() {
            if !visited.insert(current_id.clone()) {
                continue;
            }
            
            if let Some(skill) = tree.skills.get(&current_id) {
                chain.push(skill.clone());
                
                for prereq in &skill.prerequisites {
                    stack.push(prereq.clone());
                }
            }
        }
        
        chain.reverse();
        Ok(chain)
    }

    pub fn get_skill_dependents(&self, tree_id: &str, skill_id: &str) -> Result<Vec<Skill>> {
        let tree = self.skill_trees.get(tree_id)
            .ok_or_else(|| anyhow!("Skill tree not found: {}", tree_id))?;
        
        let mut dependents = Vec::new();
        
        for skill in tree.skills.values() {
            if skill.prerequisites.contains(&skill_id.to_string()) {
                dependents.push(skill.clone());
            }
        }
        
        Ok(dependents)
    }

    pub fn calculate_skill_readiness(&self, tree_id: &str, employee_id: &str, skill_id: &str) -> Result<f64> {
        let prereqs = self.get_prerequisite_chain(tree_id, skill_id)?;
        
        if prereqs.is_empty() {
            return Ok(1.0);
        }
        
        let total: f64 = prereqs.iter()
            .map(|s| self.get_employee_skill_proficiency(employee_id, &s.skill_id))
            .sum();
        
        Ok(total / prereqs.len() as f64)
    }
}

impl Default for SkillGraphManager {
    fn default() -> Self {
        Self::new()
    }
}
