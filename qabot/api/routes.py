from typing import Optional, List, Dict, Any
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from qabot.models import (
    Database, KnowledgeCreate, KnowledgeUpdate, QARecordCreate,
    ApiResponse, IntentCreate, ReplyTemplateCreate, QAResponse,
    QAStatsResponse, KnowledgeInList, QARecord, ReplyTemplate, Intent, Knowledge
)
from qabot.modules import (
    KnowledgeModule, RetrievalModule, ReplyModule, AnalysisModule,
    RecommendModule, HistoryModule, EvaluationModule, IntentModule, UpdateModule
)
from qabot.config import settings


class AskRequest(BaseModel):
    user_id: str
    question: str
    reply_type: Optional[str] = None
    use_async_recommend: bool = False


class FeedbackRequest(BaseModel):
    qa_id: str
    satisfaction: int


class ReplyTypeConfigRequest(BaseModel):
    reply_type: str
    name: Optional[str] = None
    description: Optional[str] = None
    enabled: Optional[bool] = None
    priority: Optional[int] = None
    requires_match: Optional[bool] = None
    template_id: Optional[str] = None


class AddReplyTypeRequest(BaseModel):
    reply_type: str
    name: str
    description: str = ""
    enabled: bool = True
    priority: int = 99
    requires_match: bool = True
    template_id: Optional[str] = None


router = APIRouter()
db = Database()

knowledge_module = KnowledgeModule(db)
retrieval_module = RetrievalModule(db)
reply_module = ReplyModule(db)
analysis_module = AnalysisModule(db)
recommend_module = RecommendModule(db)
history_module = HistoryModule(db)
evaluation_module = EvaluationModule(db)
intent_module = IntentModule(db)
update_module = UpdateModule(db)


@router.post("/qa/ask", response_model=ApiResponse)
async def ask_question(request: AskRequest):
    qa_create = QARecordCreate(user_id=request.user_id, question=request.question)
    
    intent_category = intent_module.recognize_intent(request.question)
    
    best_match = retrieval_module.get_best_match(request.question, intent_category)
    
    matched_knowledge = None
    match_score = None
    matched_knowledge_id = None
    
    if best_match:
        matched_knowledge = best_match.knowledge
        match_score = round(best_match.combined_score, 2)
        matched_knowledge_id = matched_knowledge.knowledge_id
        update_module.increment_view_count(matched_knowledge_id)
    
    if request.reply_type:
        reply_content, reply_type = reply_module.generate_reply(
            matched_knowledge,
            preferred_reply_type=request.reply_type
        )
    else:
        reply_content, reply_type = reply_module.generate_reply(matched_knowledge)
    
    is_matched = matched_knowledge is not None
    analysis_module.record_question(is_matched)
    
    qa_record = history_module.record_qa(
        qa_create,
        reply_content=reply_content,
        reply_type=reply_type,
        matched_knowledge=matched_knowledge_id,
        match_score=match_score,
        intent_category=intent_category
    )
    
    recommendations = recommend_module.generate_recommendations(
        matched_knowledge=matched_knowledge,
        qa_id=qa_record.qa_id,
        use_async=request.use_async_recommend
    )
    
    qa_response = QAResponse(
        reply=reply_content,
        reply_type=reply_type,
        match_score=match_score,
        matched_knowledge_id=matched_knowledge_id,
        intent_category=intent_category,
        recommendations=recommendations
    )
    
    return ApiResponse(code=200, data=qa_response, message="success")


@router.post("/qa/feedback", response_model=ApiResponse)
async def submit_feedback(request: FeedbackRequest):
    if request.satisfaction < 1 or request.satisfaction > 5:
        raise HTTPException(status_code=400, detail="满意度必须在1-5之间")
    
    record = evaluation_module.evaluate_quality(request.qa_id, request.satisfaction)
    if not record:
        raise HTTPException(status_code=404, detail="问答记录不存在")
    
    if request.satisfaction <= 2 and record.matched_knowledge:
        update_module.mark_for_update(record.matched_knowledge)
    
    return ApiResponse(code=200, data={"qa_id": record.qa_id, "satisfaction": record.satisfaction}, message="反馈已提交")


@router.post("/qa/feedback/auto-optimize", response_model=ApiResponse)
async def run_auto_optimization(knowledge_id: Optional[str] = Query(None, description="指定知识ID，不指定则优化所有需要优化的知识")):
    results = evaluation_module.run_auto_optimization(knowledge_id)
    return ApiResponse(code=200, data={"optimizations": results}, message="优化完成")


@router.get("/qa/feedback/stats", response_model=ApiResponse)
async def get_feedback_stats():
    stats = evaluation_module.get_feedback_statistics()
    return ApiResponse(code=200, data=stats, message="success")


@router.get("/qa/feedback/history", response_model=ApiResponse)
async def get_optimization_history(knowledge_id: Optional[str] = Query(None, description="知识ID，可选")):
    history = evaluation_module.get_optimization_history(knowledge_id)
    return ApiResponse(code=200, data=history, message="success")


@router.get("/qa/history", response_model=ApiResponse)
async def get_history(
    user_id: Optional[str] = Query(None, description="用户ID"),
    limit: int = Query(100, ge=1, le=1000)
):
    if user_id:
        records = history_module.list_user_history(user_id, limit)
    else:
        records = history_module.list_all_history(limit)
    return ApiResponse(code=200, data=records, message="success")


@router.get("/qa/stats", response_model=ApiResponse)
async def get_stats(
    stat_date: Optional[str] = Query(None, description="统计日期，格式YYYY-MM-DD")
):
    stats = analysis_module.get_stats(stat_date)
    if not stats:
        stats = QAStatsResponse(
            stat_date=stat_date or "今天",
            total_questions=0,
            matched_questions=0,
            unmatched_questions=0,
            avg_satisfaction=None,
            matched_rate=0.0
        )
    return ApiResponse(code=200, data={"stats": stats}, message="success")


@router.post("/knowledge/create", response_model=ApiResponse)
async def create_knowledge(data: KnowledgeCreate):
    knowledge = knowledge_module.create_knowledge(data)
    return ApiResponse(code=200, data={"knowledge_id": knowledge.knowledge_id}, message="知识创建成功")


@router.get("/knowledge/{knowledge_id}", response_model=ApiResponse)
async def get_knowledge(knowledge_id: str):
    knowledge = knowledge_module.get_knowledge(knowledge_id)
    if not knowledge:
        raise HTTPException(status_code=404, detail="知识不存在")
    return ApiResponse(code=200, data=knowledge, message="success")


@router.get("/knowledge", response_model=ApiResponse)
async def list_knowledges(
    category: Optional[str] = Query(None, description="知识分类")
):
    knowledges = knowledge_module.list_knowledges(category)
    return ApiResponse(code=200, data=knowledges, message="success")


@router.get("/knowledge/categories/list", response_model=ApiResponse)
async def list_categories():
    categories = knowledge_module.list_categories()
    return ApiResponse(code=200, data=categories, message="success")


@router.put("/knowledge/{knowledge_id}", response_model=ApiResponse)
async def update_knowledge(knowledge_id: str, data: KnowledgeUpdate):
    knowledge = update_module.update_knowledge(knowledge_id, data)
    if not knowledge:
        raise HTTPException(status_code=404, detail="知识不存在")
    return ApiResponse(code=200, data=knowledge, message="知识更新成功")


@router.get("/knowledge/needs-update/list", response_model=ApiResponse)
async def get_knowledge_needing_update():
    knowledges = update_module.get_knowledge_needing_update()
    return ApiResponse(code=200, data=knowledges, message="success")


@router.get("/knowledge/activity/report", response_model=ApiResponse)
async def get_knowledge_activity_report():
    report = update_module.get_activity_report()
    return ApiResponse(code=200, data=report, message="success")


@router.get("/templates", response_model=ApiResponse)
async def list_templates():
    templates = reply_module.list_templates()
    return ApiResponse(code=200, data=templates, message="success")


@router.post("/templates/create", response_model=ApiResponse)
async def create_template(data: ReplyTemplateCreate):
    template = reply_module.create_template(data)
    return ApiResponse(code=200, data={"template_id": template.template_id}, message="模板创建成功")


@router.get("/reply-types", response_model=ApiResponse)
async def list_reply_types():
    types = reply_module.list_reply_types()
    return ApiResponse(code=200, data=types, message="success")


@router.post("/reply-types", response_model=ApiResponse)
async def add_reply_type(data: AddReplyTypeRequest):
    success = reply_module.add_reply_type(
        reply_type=data.reply_type,
        config={
            "name": data.name,
            "description": data.description,
            "enabled": data.enabled,
            "priority": data.priority,
            "requires_match": data.requires_match,
            "template_id": data.template_id
        }
    )
    
    if not success:
        raise HTTPException(status_code=400, detail="回复类型已存在")
    
    return ApiResponse(code=200, data={"reply_type": data.reply_type}, message="回复类型添加成功")


@router.put("/reply-types/{reply_type}", response_model=ApiResponse)
async def update_reply_type(reply_type: str, data: ReplyTypeConfigRequest):
    updates = {}
    if data.name is not None:
        updates["name"] = data.name
    if data.description is not None:
        updates["description"] = data.description
    if data.enabled is not None:
        updates["enabled"] = data.enabled
    if data.priority is not None:
        updates["priority"] = data.priority
    if data.requires_match is not None:
        updates["requires_match"] = data.requires_match
    if data.template_id is not None:
        updates["template_id"] = data.template_id
    
    if not updates:
        raise HTTPException(status_code=400, detail="没有提供更新字段")
    
    success = reply_module.update_reply_type(reply_type, updates)
    
    if not success:
        raise HTTPException(status_code=404, detail="回复类型不存在")
    
    return ApiResponse(code=200, data={"reply_type": reply_type, "updated": updates}, message="回复类型更新成功")


@router.post("/reply-types/{reply_type}/enable", response_model=ApiResponse)
async def enable_reply_type(reply_type: str):
    success = reply_module.enable_reply_type(reply_type)
    if not success:
        raise HTTPException(status_code=404, detail="回复类型不存在")
    return ApiResponse(code=200, data={"reply_type": reply_type, "enabled": True}, message="回复类型已启用")


@router.post("/reply-types/{reply_type}/disable", response_model=ApiResponse)
async def disable_reply_type(reply_type: str):
    success = reply_module.disable_reply_type(reply_type)
    if not success:
        raise HTTPException(status_code=400, detail="无法禁用默认回复类型或类型不存在")
    return ApiResponse(code=200, data={"reply_type": reply_type, "enabled": False}, message="回复类型已禁用")


@router.get("/recommend/queue/stats", response_model=ApiResponse)
async def get_recommend_queue_stats():
    stats = recommend_module.get_queue_stats()
    return ApiResponse(code=200, data=stats, message="success")


@router.get("/recommend/result/{qa_id}", response_model=ApiResponse)
async def get_async_recommend_result(qa_id: str):
    result = recommend_module.get_async_result(qa_id)
    if not result:
        return ApiResponse(code=200, data={"ready": False, "result": None}, message="结果未就绪")
    return ApiResponse(code=200, data={"ready": True, "result": result}, message="success")


@router.get("/intents", response_model=ApiResponse)
async def list_intents():
    intents = intent_module.list_intents()
    return ApiResponse(code=200, data=intents, message="success")


@router.post("/intents/create", response_model=ApiResponse)
async def create_intent(data: IntentCreate):
    intent = intent_module.create_intent(data)
    return ApiResponse(code=200, data={"intent_id": intent.intent_id}, message="意图创建成功")


@router.post("/intents/recognize", response_model=ApiResponse)
async def recognize_intent(question: str = Query(..., description="问题文本")):
    intent = intent_module.recognize_intent(question)
    return ApiResponse(code=200, data={"question": question, "intent": intent}, message="success")


@router.get("/config", response_model=ApiResponse)
async def get_config():
    config_info = {
        "app_name": settings.APP_NAME,
        "app_version": settings.APP_VERSION,
        "retrieval": {
            "top_k": settings.RETRIEVAL_TOP_K,
            "keyword_weight": settings.KEYWORD_MATCH_WEIGHT,
            "semantic_weight": settings.SEMANTIC_MATCH_WEIGHT,
            "min_match_score": settings.MIN_MATCH_SCORE
        },
        "recommend": {
            "top_n": settings.RECOMMEND_TOP_N,
            "hot_min_views": settings.HOT_RECOMMEND_MIN_VIEWS
        },
        "index_update": {
            "high_threshold": settings.index_update.HIGH_ACTIVITY_THRESHOLD,
            "medium_threshold": settings.index_update.MEDIUM_ACTIVITY_THRESHOLD,
            "high_interval": settings.index_update.HIGH_ACTIVITY_INTERVAL,
            "medium_interval": settings.index_update.MEDIUM_ACTIVITY_INTERVAL,
            "low_interval": settings.index_update.LOW_ACTIVITY_INTERVAL
        },
        "feedback": {
            "auto_optimization_enabled": settings.feedback.ENABLE_AUTO_OPTIMIZATION,
            "low_satisfaction_threshold": settings.feedback.LOW_SATISFACTION_THRESHOLD
        },
        "redis": {
            "persistence_enabled": settings.redis.ENABLE_PERSISTENCE,
            "host": settings.redis.HOST,
            "port": settings.redis.PORT,
            "max_retries": settings.redis.MAX_RETRIES
        }
    }
    return ApiResponse(code=200, data=config_info, message="success")
