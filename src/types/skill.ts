export interface Skill {
  id: string;
  name: string;
  category: string;
  description?: string;
  level: SkillLevel;
  prerequisites: string[];
  dependents: string[];
  createdAt: string;
  updatedAt: string;
}

export type SkillLevel = 'beginner' | 'intermediate' | 'advanced' | 'expert';

export interface SkillNode {
  skillId: string;
  x: number;
  y: number;
  unlocked: boolean;
  mastery: number;
}

export interface SkillTree {
  id: string;
  name: string;
  description?: string;
  version: number;
  skills: Skill[];
  nodes: SkillNode[];
  connections: string[][];
  status: 'draft' | 'published' | 'archived';
  createdAt: string;
  updatedAt: string;
}

export interface Employee {
  id: string;
  name: string;
  email: string;
  department?: string;
  skills: EmployeeSkill[];
  goals: LearningGoal[];
  createdAt: string;
  updatedAt: string;
}

export interface EmployeeSkill {
  skillId: string;
  level: SkillLevel;
  proficiency: number;
  lastAssessedAt?: string;
  assessments: SkillAssessment[];
}

export interface SkillAssessment {
  id: string;
  skillId: string;
  employeeId: string;
  assessorId?: string;
  type: 'self' | 'manager' | 'peer' | 'test';
  score: number;
  feedback?: string;
  createdAt: string;
}

export interface LearningGoal {
  id: string;
  employeeId: string;
  skillId: string;
  targetLevel: SkillLevel;
  targetDate: string;
  status: 'not_started' | 'in_progress' | 'completed' | 'expired';
  progress: number;
  createdAt: string;
}

export interface LearningPath {
  id: string;
  name: string;
  description?: string;
  employeeId: string;
  skillTreeId: string;
  steps: LearningStep[];
  estimatedDurationHours: number;
  status: 'draft' | 'active' | 'completed';
  createdAt: string;
  updatedAt: string;
}

export interface LearningStep {
  id: string;
  skillId: string;
  order: number;
  resources: LearningResource[];
  estimatedHours: number;
  completed: boolean;
  completedAt?: string;
}

export interface LearningResource {
  id: string;
  type: 'course' | 'video' | 'article' | 'book' | 'practice';
  title: string;
  url?: string;
  durationMinutes?: number;
}

export interface SkillGapAnalysis {
  employeeId: string;
  requiredSkills: string[];
  currentSkills: EmployeeSkill[];
  gaps: SkillGap[];
  recommendations: string[];
  generatedAt: string;
}

export interface SkillGap {
  skillId: string;
  skillName: string;
  currentLevel: SkillLevel | null;
  requiredLevel: SkillLevel;
  priority: 'high' | 'medium' | 'low';
}
