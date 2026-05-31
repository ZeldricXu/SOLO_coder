from .models import (
    SkillNode,
    SkillAssessment,
    UserSkillProfile,
    LearningPath,
    UserLearningProgress,
    SkillCategory,
    ProficiencyLevel,
    AssessmentStatus,
    LearningStatus,
    SkillNodeCreate,
    SkillNodeResponse,
    SkillAssessmentCreate,
    SkillAssessmentResponse,
    UserSkillProfileResponse,
    LearningPathCreate,
    LearningPathResponse,
    LearningProgressCreate,
    LearningProgressUpdate,
    LearningProgressResponse,
)
from .service import SkillTreeService, SkillAssessmentService, LearningPathService
from .router import router

__all__ = [
    "SkillNode",
    "SkillAssessment",
    "UserSkillProfile",
    "LearningPath",
    "UserLearningProgress",
    "SkillCategory",
    "ProficiencyLevel",
    "AssessmentStatus",
    "LearningStatus",
    "SkillNodeCreate",
    "SkillNodeResponse",
    "SkillAssessmentCreate",
    "SkillAssessmentResponse",
    "UserSkillProfileResponse",
    "LearningPathCreate",
    "LearningPathResponse",
    "LearningProgressCreate",
    "LearningProgressUpdate",
    "LearningProgressResponse",
    "SkillTreeService",
    "SkillAssessmentService",
    "LearningPathService",
    "router",
]


class SkillGraphModule:
    name = "skill_graph"
    description = "技能树定义、员工能力评估与学习路径推荐模块"
    router = router

    def __init__(self):
        pass
