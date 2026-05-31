from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Tuple

from sqlalchemy import select, and_
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from core.exceptions import ValidationError, NotFoundError, ConflictError
from core.utils import validate_params, utc_now
from .models import (
    SkillNode,
    SkillNodeCreate,
    SkillNodeResponse,
    SkillAssessment,
    SkillAssessmentCreate,
    SkillAssessmentResponse,
    UserSkillProfile,
    UserSkillProfileResponse,
    LearningPath,
    LearningPathCreate,
    LearningPathResponse,
    UserLearningProgress,
    LearningProgressCreate,
    LearningProgressUpdate,
    LearningProgressResponse,
    SkillCategory,
    ProficiencyLevel,
    AssessmentStatus,
    LearningStatus,
)


PROFICIENCY_LEVEL_VALUES = {
    ProficiencyLevel.BEGINNER: 1.0,
    ProficiencyLevel.INTERMEDIATE: 2.0,
    ProficiencyLevel.ADVANCED: 3.0,
    ProficiencyLevel.EXPERT: 4.0,
    ProficiencyLevel.MASTER: 5.0,
}


class SkillTreeService:
    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_skill(self, skill_data: SkillNodeCreate) -> SkillNodeResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
            "category": lambda x: x is not None,
        }
        validate_params(skill_data.model_dump(), validation_rules)

        if skill_data.parent_skill_id:
            parent = await self.get_skill(skill_data.parent_skill_id, skill_data.tenant_id)
            if not parent:
                raise NotFoundError(f"父技能 {skill_data.parent_skill_id} 不存在")

        skill = SkillNode(**skill_data.model_dump())
        self.db.add(skill)
        await self.db.flush()

        return await self._build_skill_tree_response(skill)

    async def get_skill(
        self, skill_id: str, tenant_id: Optional[str] = None, include_children: bool = False
    ) -> Optional[SkillNodeResponse]:
        query = select(SkillNode).where(SkillNode.skill_id == skill_id)
        if tenant_id:
            query = query.where(SkillNode.tenant_id == tenant_id)
        if include_children:
            query = query.options(selectinload(SkillNode.children))

        result = await self.db.execute(query)
        skill = result.scalar_one_or_none()

        if not skill:
            return None

        return await self._build_skill_tree_response(skill, include_children)

    async def _build_skill_tree_response(
        self, skill: SkillNode, include_children: bool = False
    ) -> SkillNodeResponse:
        children = []
        if include_children and skill.children:
            children = [
                await self._build_skill_tree_response(child, True)
                for child in skill.children
            ]

        return SkillNodeResponse(
            skill_id=skill.skill_id,
            name=skill.name,
            description=skill.description,
            category=skill.category,
            parent_skill_id=skill.parent_skill_id,
            prerequisites=skill.prerequisites,
            weight=skill.weight,
            is_active=skill.is_active,
            tenant_id=skill.tenant_id,
            created_at=skill.created_at,
            children=children,
        )

    async def get_skill_tree(
        self, category: Optional[SkillCategory] = None, tenant_id: Optional[str] = None
    ) -> List[SkillNodeResponse]:
        query = select(SkillNode).where(
            and_(SkillNode.parent_skill_id.is_(None), SkillNode.is_active == True)
        )
        if category:
            query = query.where(SkillNode.category == category)
        if tenant_id:
            query = query.where(SkillNode.tenant_id == tenant_id)

        query = query.options(selectinload(SkillNode.children))
        result = await self.db.execute(query)
        root_skills = result.scalars().all()

        return [await self._build_skill_tree_response(skill, True) for skill in root_skills]

    async def list_skills(
        self,
        category: Optional[SkillCategory] = None,
        tenant_id: Optional[str] = None,
        is_active: bool = True,
    ) -> List[SkillNodeResponse]:
        query = select(SkillNode).where(SkillNode.is_active == is_active)
        if category:
            query = query.where(SkillNode.category == category)
        if tenant_id:
            query = query.where(SkillNode.tenant_id == tenant_id)

        result = await self.db.execute(query)
        skills = result.scalars().all()

        return [await self._build_skill_tree_response(s) for s in skills]


class SkillAssessmentService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.skill_service = SkillTreeService(db)

    async def create_assessment(
        self, assessment_data: SkillAssessmentCreate
    ) -> SkillAssessmentResponse:
        validation_rules = {
            "user_id": lambda x: x is not None and len(x) > 0,
            "skill_id": lambda x: x is not None and len(x) > 0,
            "score": lambda x: 0 <= x <= assessment_data.max_score,
        }
        validate_params(assessment_data.model_dump(), validation_rules)

        skill = await self.skill_service.get_skill(
            assessment_data.skill_id, assessment_data.tenant_id
        )
        if not skill:
            raise NotFoundError(f"技能 {assessment_data.skill_id} 不存在")

        assessment = SkillAssessment(
            **assessment_data.model_dump(),
            status=AssessmentStatus.COMPLETED,
            assessed_at=utc_now(),
        )
        self.db.add(assessment)
        await self.db.flush()

        await self._update_user_skill_profile(assessment)

        return SkillAssessmentResponse.model_validate(assessment)

    async def _update_user_skill_profile(self, assessment: SkillAssessment) -> None:
        query = select(UserSkillProfile).where(UserSkillProfile.user_id == assessment.user_id)
        if assessment.tenant_id:
            query = query.where(UserSkillProfile.tenant_id == assessment.tenant_id)

        result = await self.db.execute(query)
        profile = result.scalar_one_or_none()

        if not profile:
            profile = UserSkillProfile(
                user_id=assessment.user_id,
                tenant_id=assessment.tenant_id,
                skills={},
            )
            self.db.add(profile)
            await self.db.flush()

        current_value = PROFICIENCY_LEVEL_VALUES.get(assessment.proficiency_level, 0.0)
        normalized_score = (assessment.score / assessment.max_score) * current_value if assessment.max_score > 0 else 0.0

        profile.skills[assessment.skill_id] = max(
            profile.skills.get(assessment.skill_id, 0.0), normalized_score
        )

        if profile.skills:
            profile.overall_score = sum(profile.skills.values()) / len(profile.skills)

        self.db.add(profile)
        await self.db.flush()

    async def get_user_assessments(
        self, user_id: str, tenant_id: Optional[str] = None
    ) -> List[SkillAssessmentResponse]:
        query = select(SkillAssessment).where(SkillAssessment.user_id == user_id)
        if tenant_id:
            query = query.where(SkillAssessment.tenant_id == tenant_id)

        query = query.order_by(SkillAssessment.created_at.desc())
        result = await self.db.execute(query)
        assessments = result.scalars().all()

        return [SkillAssessmentResponse.model_validate(a) for a in assessments]

    async def get_user_skill_profile(
        self, user_id: str, tenant_id: Optional[str] = None
    ) -> UserSkillProfileResponse:
        query = select(UserSkillProfile).where(UserSkillProfile.user_id == user_id)
        if tenant_id:
            query = query.where(UserSkillProfile.tenant_id == tenant_id)

        result = await self.db.execute(query)
        profile = result.scalar_one_or_none()

        if not profile:
            raise NotFoundError(f"用户 {user_id} 的技能档案不存在")

        return UserSkillProfileResponse.model_validate(profile)

    async def analyze_skill_gaps(
        self,
        user_id: str,
        target_skills: Dict[str, float],
        tenant_id: Optional[str] = None,
    ) -> UserSkillProfileResponse:
        profile = await self.get_user_skill_profile(user_id, tenant_id)

        gaps = []
        for skill_id, target_level in target_skills.items():
            current_level = profile.skills.get(skill_id, 0.0)
            if current_level < target_level:
                gaps.append(
                    {
                        "skill_id": skill_id,
                        "current_level": current_level,
                        "target_level": target_level,
                        "gap": target_level - current_level,
                        "priority": "high" if target_level - current_level > 1.5 else "medium",
                    }
                )

        gaps.sort(key=lambda x: x["gap"], reverse=True)

        query = select(UserSkillProfile).where(UserSkillProfile.user_id == user_id)
        result = await self.db.execute(query)
        profile_obj = result.scalar_one()
        profile_obj.skill_gaps = gaps
        self.db.add(profile_obj)
        await self.db.flush()

        return UserSkillProfileResponse.model_validate(profile_obj)


class LearningPathService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.skill_service = SkillTreeService(db)
        self.assessment_service = SkillAssessmentService(db)

    async def create_learning_path(
        self, path_data: LearningPathCreate
    ) -> LearningPathResponse:
        validation_rules = {
            "name": lambda x: x is not None and len(x.strip()) > 0,
            "target_skill_id": lambda x: x is not None and len(x) > 0,
            "target_proficiency": lambda x: x is not None,
        }
        validate_params(path_data.model_dump(), validation_rules)

        skill = await self.skill_service.get_skill(
            path_data.target_skill_id, path_data.tenant_id
        )
        if not skill:
            raise NotFoundError(f"技能 {path_data.target_skill_id} 不存在")

        path = LearningPath(**path_data.model_dump())
        self.db.add(path)
        await self.db.flush()

        return LearningPathResponse.model_validate(path)

    async def recommend_learning_path(
        self, user_id: str, target_skill_id: str, tenant_id: Optional[str] = None
    ) -> List[LearningPathResponse]:
        profile = await self.assessment_service.get_user_skill_profile(user_id, tenant_id)

        query = select(LearningPath).where(
            and_(LearningPath.target_skill_id == target_skill_id, LearningPath.is_active == True)
        )
        if tenant_id:
            query = query.where(LearningPath.tenant_id == tenant_id)

        result = await self.db.execute(query)
        paths = result.scalars().all()

        recommended = []
        user_skills = set(profile.skills.keys())

        for path in paths:
            prerequisites_met = all(
                prereq in user_skills and profile.skills[prereq] >= 1.0
                for prereq in path.prerequisites
            )

            if prerequisites_met or not path.prerequisites:
                recommended.append(LearningPathResponse.model_validate(path))

        recommended.sort(key=lambda p: p.estimated_duration_hours)
        return recommended

    async def start_learning(
        self, progress_data: LearningProgressCreate
    ) -> LearningProgressResponse:
        path = await self.get_learning_path(
            progress_data.path_id, progress_data.tenant_id
        )

        query = select(UserLearningProgress).where(
            and_(
                UserLearningProgress.user_id == progress_data.user_id,
                UserLearningProgress.path_id == progress_data.path_id,
            )
        )
        result = await self.db.execute(query)
        if result.scalar_one_or_none():
            raise ConflictError("该学习路径已开始")

        progress = UserLearningProgress(
            **progress_data.model_dump(),
            status=LearningStatus.IN_PROGRESS,
            started_at=utc_now(),
            total_steps=len(path.steps),
        )
        self.db.add(progress)
        await self.db.flush()

        return LearningProgressResponse.model_validate(progress)

    async def get_learning_path(
        self, path_id: str, tenant_id: Optional[str] = None
    ) -> LearningPathResponse:
        query = select(LearningPath).where(LearningPath.path_id == path_id)
        if tenant_id:
            query = query.where(LearningPath.tenant_id == tenant_id)

        result = await self.db.execute(query)
        path = result.scalar_one_or_none()

        if not path:
            raise NotFoundError(f"学习路径 {path_id} 不存在")

        return LearningPathResponse.model_validate(path)

    async def update_learning_progress(
        self,
        progress_id: str,
        update_data: LearningProgressUpdate,
        tenant_id: Optional[str] = None,
    ) -> LearningProgressResponse:
        query = select(UserLearningProgress).where(
            UserLearningProgress.progress_id == progress_id
        )
        if tenant_id:
            query = query.where(UserLearningProgress.tenant_id == tenant_id)

        result = await self.db.execute(query)
        progress = result.scalar_one_or_none()

        if not progress:
            raise NotFoundError(f"学习进度 {progress_id} 不存在")

        if update_data.status:
            progress.status = update_data.status
            if update_data.status == LearningStatus.COMPLETED:
                progress.completed_at = utc_now()
                progress.progress_percent = 100.0

        if update_data.completed_step is not None:
            if update_data.completed_step not in progress.completed_steps:
                progress.completed_steps.append(update_data.completed_step)
                progress.current_step = max(progress.current_step, update_data.completed_step + 1)

            if progress.total_steps > 0:
                progress.progress_percent = (
                    len(progress.completed_steps) / progress.total_steps
                ) * 100

        if update_data.time_spent_hours:
            progress.time_spent_hours += update_data.time_spent_hours

        self.db.add(progress)
        await self.db.flush()

        return LearningProgressResponse.model_validate(progress)

    async def get_user_learning_progress(
        self, user_id: str, tenant_id: Optional[str] = None
    ) -> List[LearningProgressResponse]:
        query = select(UserLearningProgress).where(UserLearningProgress.user_id == user_id)
        if tenant_id:
            query = query.where(UserLearningProgress.tenant_id == tenant_id)

        query = query.order_by(UserLearningProgress.last_updated.desc())
        result = await self.db.execute(query)
        progress_list = result.scalars().all()

        return [LearningProgressResponse.model_validate(p) for p in progress_list]
