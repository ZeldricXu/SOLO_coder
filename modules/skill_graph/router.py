from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, Query, Body
from sqlalchemy.ext.asyncio import AsyncSession

from core.database import get_db
from .models import (
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
    SkillCategory,
)
from .service import SkillTreeService, SkillAssessmentService, LearningPathService

router = APIRouter(prefix="/skills", tags=["技能图谱建模"])


@router.post("/tree", response_model=Dict[str, Any], status_code=201)
async def create_skill(
    skill_data: SkillNodeCreate,
    db: AsyncSession = Depends(get_db),
):
    service = SkillTreeService(db)
    skill = await service.create_skill(skill_data)
    return {
        "code": 201,
        "data": skill.model_dump(),
        "message": "技能创建成功",
    }


@router.get("/tree/{skill_id}", response_model=Dict[str, Any])
async def get_skill(
    skill_id: str,
    tenant_id: Optional[str] = Query(None),
    include_children: bool = Query(False),
    db: AsyncSession = Depends(get_db),
):
    service = SkillTreeService(db)
    skill = await service.get_skill(skill_id, tenant_id, include_children)
    if not skill:
        return {
            "code": 404,
            "data": None,
            "message": "技能不存在",
        }
    return {
        "code": 200,
        "data": skill.model_dump(),
        "message": "查询成功",
    }


@router.get("/tree", response_model=Dict[str, Any])
async def get_skill_tree(
    category: Optional[SkillCategory] = Query(None),
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = SkillTreeService(db)
    tree = await service.get_skill_tree(category, tenant_id)
    return {
        "code": 200,
        "data": [t.model_dump() for t in tree],
        "total": len(tree),
        "message": "查询成功",
    }


@router.get("", response_model=Dict[str, Any])
async def list_skills(
    category: Optional[SkillCategory] = Query(None),
    tenant_id: Optional[str] = Query(None),
    is_active: bool = Query(True),
    db: AsyncSession = Depends(get_db),
):
    service = SkillTreeService(db)
    skills = await service.list_skills(category, tenant_id, is_active)
    return {
        "code": 200,
        "data": [s.model_dump() for s in skills],
        "total": len(skills),
        "message": "查询成功",
    }


@router.post("/assessments", response_model=Dict[str, Any], status_code=201)
async def create_assessment(
    assessment_data: SkillAssessmentCreate,
    db: AsyncSession = Depends(get_db),
):
    service = SkillAssessmentService(db)
    assessment = await service.create_assessment(assessment_data)
    return {
        "code": 201,
        "data": assessment.model_dump(),
        "message": "技能评估创建成功",
    }


@router.get("/assessments/user/{user_id}", response_model=Dict[str, Any])
async def get_user_assessments(
    user_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = SkillAssessmentService(db)
    assessments = await service.get_user_assessments(user_id, tenant_id)
    return {
        "code": 200,
        "data": [a.model_dump() for a in assessments],
        "total": len(assessments),
        "message": "查询成功",
    }


@router.get("/profiles/{user_id}", response_model=Dict[str, Any])
async def get_user_skill_profile(
    user_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = SkillAssessmentService(db)
    profile = await service.get_user_skill_profile(user_id, tenant_id)
    return {
        "code": 200,
        "data": profile.model_dump(),
        "message": "查询成功",
    }


@router.post("/profiles/{user_id}/analyze-gaps", response_model=Dict[str, Any])
async def analyze_skill_gaps(
    user_id: str,
    target_skills: Dict[str, float] = Body(...),
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = SkillAssessmentService(db)
    profile = await service.analyze_skill_gaps(user_id, target_skills, tenant_id)
    return {
        "code": 200,
        "data": profile.model_dump(),
        "message": "技能差距分析完成",
    }


@router.post("/learning-paths", response_model=Dict[str, Any], status_code=201)
async def create_learning_path(
    path_data: LearningPathCreate,
    db: AsyncSession = Depends(get_db),
):
    service = LearningPathService(db)
    path = await service.create_learning_path(path_data)
    return {
        "code": 201,
        "data": path.model_dump(),
        "message": "学习路径创建成功",
    }


@router.get("/learning-paths/recommend", response_model=Dict[str, Any])
async def recommend_learning_path(
    user_id: str,
    target_skill_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = LearningPathService(db)
    paths = await service.recommend_learning_path(user_id, target_skill_id, tenant_id)
    return {
        "code": 200,
        "data": [p.model_dump() for p in paths],
        "total": len(paths),
        "message": "学习路径推荐完成",
    }


@router.get("/learning-paths/{path_id}", response_model=Dict[str, Any])
async def get_learning_path(
    path_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = LearningPathService(db)
    path = await service.get_learning_path(path_id, tenant_id)
    return {
        "code": 200,
        "data": path.model_dump(),
        "message": "查询成功",
    }


@router.post("/learning-progress", response_model=Dict[str, Any], status_code=201)
async def start_learning(
    progress_data: LearningProgressCreate,
    db: AsyncSession = Depends(get_db),
):
    service = LearningPathService(db)
    progress = await service.start_learning(progress_data)
    return {
        "code": 201,
        "data": progress.model_dump(),
        "message": "学习开始",
    }


@router.patch("/learning-progress/{progress_id}", response_model=Dict[str, Any])
async def update_learning_progress(
    progress_id: str,
    update_data: LearningProgressUpdate,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = LearningPathService(db)
    progress = await service.update_learning_progress(progress_id, update_data, tenant_id)
    return {
        "code": 200,
        "data": progress.model_dump(),
        "message": "学习进度更新成功",
    }


@router.get("/learning-progress/user/{user_id}", response_model=Dict[str, Any])
async def get_user_learning_progress(
    user_id: str,
    tenant_id: Optional[str] = Query(None),
    db: AsyncSession = Depends(get_db),
):
    service = LearningPathService(db)
    progress_list = await service.get_user_learning_progress(user_id, tenant_id)
    return {
        "code": 200,
        "data": [p.model_dump() for p in progress_list],
        "total": len(progress_list),
        "message": "查询成功",
    }
