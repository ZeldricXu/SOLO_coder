import { Skill, SkillTree, SkillNode, Employee, EmployeeSkill, LearningPath, LearningStep, SkillAssessment, SkillLevel, SkillCategory } from '../../types/skill';
import { generateId, getCurrentTimestamp } from '../../common/utils';
import { NotFoundError, ValidationError, AppError } from '../../common/errors';

export class SkillGraphManager {
  private skills: Map<string, Skill>;
  private skillTrees: Map<string, SkillTree>;
  private employees: Map<string, Employee>;
  private employeeSkills: Map<string, EmployeeSkill[]>;
  private learningPaths: Map<string, LearningPath>;
  private assessments: Map<string, SkillAssessment>;

  constructor() {
    this.skills = new Map();
    this.skillTrees = new Map();
    this.employees = new Map();
    this.employeeSkills = new Map();
    this.learningPaths = new Map();
    this.assessments = new Map();
  }

  createSkill(
    name: string,
    description: string,
    category: SkillCategory,
    levels: SkillLevel[] = ['beginner', 'intermediate', 'advanced', 'expert'],
    prerequisites: string[] = [],
    tags: string[] = []
  ): Skill {
    for (const prerequisiteId of prerequisites) {
      if (!this.skills.has(prerequisiteId)) {
        throw new ValidationError(`前置技能不存在: ${prerequisiteId}`);
      }
    }

    const skill: Skill = {
      id: generateId('skill'),
      name,
      description,
      category,
      levels,
      prerequisites,
      tags,
      createdAt: getCurrentTimestamp(),
      updatedAt: getCurrentTimestamp()
    };

    this.skills.set(skill.id, skill);
    return skill;
  }

  getSkill(skillId: string): Skill {
    const skill = this.skills.get(skillId);
    if (!skill) {
      throw new NotFoundError(`技能不存在: ${skillId}`);
    }
    return skill;
  }

  updateSkill(skillId: string, updates: Partial<Omit<Skill, 'id' | 'createdAt'>>): Skill {
    const skill = this.getSkill(skillId);

    if (updates.prerequisites) {
      for (const prerequisiteId of updates.prerequisites) {
        if (prerequisiteId === skillId) {
          throw new ValidationError('技能不能依赖自身');
        }
        if (!this.skills.has(prerequisiteId)) {
          throw new ValidationError(`前置技能不存在: ${prerequisiteId}`);
        }
      }
    }

    const updated: Skill = {
      ...skill,
      ...updates,
      updatedAt: getCurrentTimestamp()
    };

    this.skills.set(skillId, updated);
    return updated;
  }

  deleteSkill(skillId: string): void {
    const treesUsingSkill = Array.from(this.skillTrees.values()).filter(tree =>
      tree.nodes.some(n => n.skillId === skillId)
    );

    if (treesUsingSkill.length > 0) {
      throw new AppError(
        `技能被 ${treesUsingSkill.length} 个技能树使用，无法删除`,
        'SKILL_IN_USE',
        400,
        { trees: treesUsingSkill.map(t => t.id) }
      );
    }

    this.skills.delete(skillId);
    for (const employeeId of this.employeeSkills.keys()) {
      this.employeeSkills.set(
        employeeId,
        this.employeeSkills.get(employeeId)!.filter(es => es.skillId !== skillId)
      );
    }
  }

  listSkills(filters?: {
    category?: SkillCategory;
    tag?: string;
    level?: SkillLevel;
  }): Skill[] {
    let skills = Array.from(this.skills.values());

    if (filters) {
      if (filters.category) {
        skills = skills.filter(s => s.category === filters.category);
      }
      if (filters.tag) {
        skills = skills.filter(s => s.tags.includes(filters.tag!));
      }
    }

    return skills.sort((a, b) =>
      new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    );
  }

  createSkillTree(
    name: string,
    description: string,
    category: SkillCategory,
    nodes: Omit<SkillNode, 'id'>[] = [],
    department?: string
  ): SkillTree {
    for (const node of nodes) {
      if (!this.skills.has(node.skillId)) {
        throw new ValidationError(`技能不存在: ${node.skillId}`);
      }
    }

    const tree: SkillTree = {
      id: generateId('tree'),
      name,
      description,
      category,
      nodes: nodes.map(n => ({ ...n, id: generateId('node') })),
      department,
      createdAt: getCurrentTimestamp(),
      updatedAt: getCurrentTimestamp()
    };

    this.skillTrees.set(tree.id, tree);
    return tree;
  }

  getSkillTree(treeId: string): SkillTree {
    const tree = this.skillTrees.get(treeId);
    if (!tree) {
      throw new NotFoundError(`技能树不存在: ${treeId}`);
    }
    return tree;
  }

  updateSkillTree(treeId: string, updates: Partial<Omit<SkillTree, 'id' | 'createdAt'>>): SkillTree {
    const tree = this.getSkillTree(treeId);

    if (updates.nodes) {
      for (const node of updates.nodes) {
        if (!this.skills.has(node.skillId)) {
          throw new ValidationError(`技能不存在: ${node.skillId}`);
        }
      }
    }

    const updated: SkillTree = {
      ...tree,
      ...updates,
      updatedAt: getCurrentTimestamp()
    };

    this.skillTrees.set(treeId, updated);
    return updated;
  }

  addSkillNode(treeId: string, node: Omit<SkillNode, 'id'>): SkillNode {
    const tree = this.getSkillTree(treeId);

    if (!this.skills.has(node.skillId)) {
      throw new ValidationError(`技能不存在: ${node.skillId}`);
    }

    const skillNode: SkillNode = {
      ...node,
      id: generateId('node')
    };

    tree.nodes.push(skillNode);
    tree.updatedAt = getCurrentTimestamp();
    this.skillTrees.set(treeId, tree);

    return skillNode;
  }

  removeSkillNode(treeId: string, nodeId: string): void {
    const tree = this.getSkillTree(treeId);
    tree.nodes = tree.nodes.filter(n => n.id !== nodeId);
    tree.updatedAt = getCurrentTimestamp();
    this.skillTrees.set(treeId, tree);
  }

  listSkillTrees(category?: SkillCategory): SkillTree[] {
    let trees = Array.from(this.skillTrees.values());
    if (category) {
      trees = trees.filter(t => t.category === category);
    }
    return trees.sort((a, b) =>
      new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    );
  }

  addEmployee(
    name: string,
    email: string,
    department?: string,
    position?: string,
    externalId?: string
  ): Employee {
    const existing = Array.from(this.employees.values()).find(e => e.email === email);
    if (existing) {
      throw new AppError(`邮箱已被使用: ${email}`, 'EMAIL_EXISTS', 400);
    }

    const employee: Employee = {
      id: generateId('emp'),
      name,
      email,
      department,
      position,
      externalId,
      createdAt: getCurrentTimestamp(),
      updatedAt: getCurrentTimestamp()
    };

    this.employees.set(employee.id, employee);
    this.employeeSkills.set(employee.id, []);

    return employee;
  }

  getEmployee(employeeId: string): Employee {
    const employee = this.employees.get(employeeId);
    if (!employee) {
      throw new NotFoundError(`员工不存在: ${employeeId}`);
    }
    return employee;
  }

  updateEmployee(employeeId: string, updates: Partial<Omit<Employee, 'id' | 'createdAt'>>): Employee {
    const employee = this.getEmployee(employeeId);

    if (updates.email) {
      const existing = Array.from(this.employees.values()).find(
        e => e.email === updates.email && e.id !== employeeId
      );
      if (existing) {
        throw new AppError(`邮箱已被使用: ${updates.email}`, 'EMAIL_EXISTS', 400);
      }
    }

    const updated: Employee = {
      ...employee,
      ...updates,
      updatedAt: getCurrentTimestamp()
    };

    this.employees.set(employeeId, updated);
    return updated;
  }

  listEmployees(department?: string): Employee[] {
    let employees = Array.from(this.employees.values());
    if (department) {
      employees = employees.filter(e => e.department === department);
    }
    return employees.sort((a, b) =>
      new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    );
  }

  setEmployeeSkill(
    employeeId: string,
    skillId: string,
    level: SkillLevel,
    assessor?: string,
    evidence?: string,
    notes?: string
  ): EmployeeSkill {
    this.getEmployee(employeeId);
    this.getSkill(skillId);

    const employeeSkills = this.employeeSkills.get(employeeId) || [];
    const existing = employeeSkills.find(es => es.skillId === skillId);

    const employeeSkill: EmployeeSkill = {
      id: existing?.id || generateId('emp_skill'),
      employeeId,
      skillId,
      level,
      assessor,
      evidence,
      notes,
      acquiredAt: existing?.acquiredAt || getCurrentTimestamp(),
      lastAssessedAt: getCurrentTimestamp()
    };

    if (existing) {
      const index = employeeSkills.findIndex(es => es.skillId === skillId);
      employeeSkills[index] = employeeSkill;
    } else {
      employeeSkills.push(employeeSkill);
    }

    this.employeeSkills.set(employeeId, employeeSkills);
    return employeeSkill;
  }

  getEmployeeSkills(employeeId: string): EmployeeSkill[] {
    this.getEmployee(employeeId);
    return this.employeeSkills.get(employeeId) || [];
  }

  getEmployeeSkillsWithDetails(employeeId: string): Array<EmployeeSkill & { skill: Skill }> {
    const skills = this.getEmployeeSkills(employeeId);
    return skills.map(es => ({
      ...es,
      skill: this.getSkill(es.skillId)
    }));
  }

  getEmployeeSkillLevel(employeeId: string, skillId: string): SkillLevel | null {
    const employeeSkills = this.employeeSkills.get(employeeId) || [];
    const found = employeeSkills.find(es => es.skillId === skillId);
    return found?.level || null;
  }

  assessSkill(
    employeeId: string,
    skillId: string,
    level: SkillLevel,
    assessor: string,
    score: number,
    notes?: string
  ): SkillAssessment {
    this.getEmployee(employeeId);
    this.getSkill(skillId);

    const assessment: SkillAssessment = {
      id: generateId('assess'),
      employeeId,
      skillId,
      level,
      assessor,
      score,
      notes,
      assessedAt: getCurrentTimestamp()
    };

    this.assessments.set(assessment.id, assessment);

    this.setEmployeeSkill(employeeId, skillId, level, assessor, assessment.id, notes);

    return assessment;
  }

  getAssessments(employeeId?: string, skillId?: string): SkillAssessment[] {
    let assessments = Array.from(this.assessments.values());

    if (employeeId) {
      assessments = assessments.filter(a => a.employeeId === employeeId);
    }
    if (skillId) {
      assessments = assessments.filter(a => a.skillId === skillId);
    }

    return assessments.sort((a, b) =>
      new Date(b.assessedAt).getTime() - new Date(a.assessedAt).getTime()
    );
  }

  createLearningPath(
    name: string,
    description: string,
    targetRole: string,
    steps: Omit<LearningStep, 'id' | 'order'>[] = [],
    estimatedDurationHours?: number
  ): LearningPath {
    for (const step of steps) {
      if (!this.skills.has(step.skillId)) {
        throw new ValidationError(`技能不存在: ${step.skillId}`);
      }
    }

    const orderedSteps: LearningStep[] = steps.map((s, i) => ({
      ...s,
      id: generateId('step'),
      order: i + 1
    }));

    const path: LearningPath = {
      id: generateId('path'),
      name,
      description,
      targetRole,
      steps: orderedSteps,
      estimatedDurationHours,
      createdAt: getCurrentTimestamp(),
      updatedAt: getCurrentTimestamp()
    };

    this.learningPaths.set(path.id, path);
    return path;
  }

  getLearningPath(pathId: string): LearningPath {
    const path = this.learningPaths.get(pathId);
    if (!path) {
      throw new NotFoundError(`学习路径不存在: ${pathId}`);
    }
    return path;
  }

  addLearningStep(
    pathId: string,
    step: Omit<LearningStep, 'id' | 'order'>
  ): LearningStep {
    const path = this.getLearningPath(pathId);

    if (!this.skills.has(step.skillId)) {
      throw new ValidationError(`技能不存在: ${step.skillId}`);
    }

    const learningStep: LearningStep = {
      ...step,
      id: generateId('step'),
      order: path.steps.length + 1
    };

    path.steps.push(learningStep);
    path.updatedAt = getCurrentTimestamp();
    this.learningPaths.set(pathId, path);

    return learningStep;
  }

  listLearningPaths(targetRole?: string): LearningPath[] {
    let paths = Array.from(this.learningPaths.values());
    if (targetRole) {
      paths = paths.filter(p => p.targetRole === targetRole);
    }
    return paths.sort((a, b) =>
      new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    );
  }

  recommendLearningPath(employeeId: string, targetRole: string): LearningPath | null {
    const paths = this.listLearningPaths(targetRole);
    if (paths.length === 0) return null;

    const employeeSkills = this.getEmployeeSkills(employeeId);
    const employeeSkillMap = new Map(employeeSkills.map(es => [es.skillId, es.level]));

    let bestPath = paths[0];
    let minGap = Infinity;

    for (const path of paths) {
      let gap = 0;
      for (const step of path.steps) {
        const currentLevel = employeeSkillMap.get(step.skillId) || 'beginner';
        const levelOrder = ['beginner', 'intermediate', 'advanced', 'expert'];
        const currentIndex = levelOrder.indexOf(currentLevel);
        const targetIndex = levelOrder.indexOf(step.targetLevel);
        gap += Math.max(0, targetIndex - currentIndex);
      }

      if (gap < minGap) {
        minGap = gap;
        bestPath = path;
      }
    }

    return bestPath;
  }

  getEmployeeProgress(employeeId: string, pathId: string): {
    completed: number;
    total: number;
    percentage: number;
    nextStep?: LearningStep;
  } {
    const path = this.getLearningPath(pathId);
    const employeeSkills = this.getEmployeeSkills(employeeId);
    const employeeSkillMap = new Map(employeeSkills.map(es => [es.skillId, es.level]));

    let completed = 0;
    let nextStep: LearningStep | undefined;

    for (const step of path.steps) {
      const currentLevel = employeeSkillMap.get(step.skillId);
      const levelOrder = ['beginner', 'intermediate', 'advanced', 'expert'];
      const currentIndex = currentLevel ? levelOrder.indexOf(currentLevel) : -1;
      const targetIndex = levelOrder.indexOf(step.targetLevel);

      if (currentIndex >= targetIndex) {
        completed++;
      } else if (!nextStep) {
        nextStep = step;
      }
    }

    return {
      completed,
      total: path.steps.length,
      percentage: path.steps.length > 0 ? (completed / path.steps.length) * 100 : 0,
      nextStep
    };
  }

  analyzeSkillGaps(employeeId: string, requiredSkills: { skillId: string; requiredLevel: SkillLevel }[]) {
    const employeeSkills = this.getEmployeeSkills(employeeId);
    const employeeSkillMap = new Map(employeeSkills.map(es => [es.skillId, es.level]));
    const levelOrder = ['beginner', 'intermediate', 'advanced', 'expert'];

    const gaps = [];

    for (const required of requiredSkills) {
      const currentLevel = employeeSkillMap.get(required.skillId);
      const currentIndex = currentLevel ? levelOrder.indexOf(currentLevel) : -1;
      const requiredIndex = levelOrder.indexOf(required.requiredLevel);

      if (currentIndex < requiredIndex) {
        gaps.push({
          skill: this.getSkill(required.skillId),
          currentLevel,
          requiredLevel: required.requiredLevel,
          gap: levelOrder.slice(currentIndex + 1, requiredIndex + 1)
        });
      }
    }

    return gaps;
  }

  getTeamSkillMatrix(employeeIds: string[]): {
    employeeId: string;
    employeeName: string;
    skills: { skillId: string; skillName: string; level: SkillLevel | null }[];
  }[] {
    const allSkills = this.listSkills();

    return employeeIds.map(employeeId => {
      const employee = this.getEmployee(employeeId);
      const employeeSkills = this.getEmployeeSkills(employeeId);
      const skillMap = new Map(employeeSkills.map(es => [es.skillId, es.level]));

      return {
        employeeId,
        employeeName: employee.name,
        skills: allSkills.map(skill => ({
          skillId: skill.id,
          skillName: skill.name,
          level: skillMap.get(skill.id) || null
        }))
      };
    });
  }

  getStats() {
    return {
      totalSkills: this.skills.size,
      totalSkillTrees: this.skillTrees.size,
      totalEmployees: this.employees.size,
      totalAssessments: this.assessments.size,
      totalLearningPaths: this.learningPaths.size,
      skillsByCategory: Array.from(this.skills.values()).reduce((acc, s) => {
        acc[s.category] = (acc[s.category] || 0) + 1;
        return acc;
      }, {} as Record<string, number>)
    };
  }
}
