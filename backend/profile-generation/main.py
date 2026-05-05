#!/usr/bin/env python3
"""
GameStats 玩家画像生成服务
提供玩家画像生成、查询、更新等API接口
"""

import logging
from datetime import datetime
from typing import List, Optional

from fastapi import FastAPI, HTTPException, Query, Depends
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from src.config import config
from src.models import (
    PlayerProfile, ProfileGenerationRequest, ProfileGenerationResponse,
    EngineStatusResponse, ReloadRulesResponse
)
from src.profile_generator import ProfileGenerator

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="GameStats Player Profile Service",
    description="玩家画像生成与查询服务 - 支持规则引擎动态配置标签",
    version="2.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


_profile_generator_instance: Optional[ProfileGenerator] = None


def get_profile_generator():
    global _profile_generator_instance
    if _profile_generator_instance is None:
        _profile_generator_instance = ProfileGenerator()
    return _profile_generator_instance


@app.get("/health")
async def health_check():
    """健康检查接口"""
    return {
        "status": "ok",
        "timestamp": datetime.now().isoformat(),
        "service": "player-profile-service",
        "version": "2.0.0"
    }


@app.get("/api/v1/profiles/{player_id}", response_model=PlayerProfile)
async def get_player_profile(
    player_id: str,
    generator: ProfileGenerator = Depends(get_profile_generator)
):
    """
    获取指定玩家的画像
    """
    logger.info(f"Getting profile for player: {player_id}")
    
    try:
        profile = generator.generate_profile(player_id)
        return profile
    except Exception as e:
        logger.error(f"Failed to get profile for {player_id}: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Failed to get player profile: {str(e)}"
        )


@app.post("/api/v1/profiles/generate", response_model=ProfileGenerationResponse)
async def generate_profiles(
    request: ProfileGenerationRequest,
    generator: ProfileGenerator = Depends(get_profile_generator)
):
    """
    批量生成玩家画像
    """
    logger.info("Starting batch profile generation")
    
    try:
        profiles = generator.generate_all_profiles(
            game_id=request.game_id,
            player_ids=request.player_ids
        )
        
        for profile in profiles:
            generator.save_profile(profile)
        
        return ProfileGenerationResponse(
            success=True,
            processed_count=len(profiles),
            message=f"Successfully generated {len(profiles)} profiles",
            profiles=profiles[:100] if len(profiles) > 100 else profiles
        )
    except Exception as e:
        logger.error(f"Failed to generate profiles: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Failed to generate profiles: {str(e)}"
        )


@app.post("/api/v1/profiles/{player_id}/refresh", response_model=PlayerProfile)
async def refresh_player_profile(
    player_id: str,
    generator: ProfileGenerator = Depends(get_profile_generator)
):
    """
    刷新指定玩家的画像
    """
    logger.info(f"Refreshing profile for player: {player_id}")
    
    try:
        profile = generator.generate_profile(player_id)
        generator.save_profile(profile)
        return profile
    except Exception as e:
        logger.error(f"Failed to refresh profile for {player_id}: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Failed to refresh player profile: {str(e)}"
        )


@app.get("/api/v1/profiles/stats/summary")
async def get_profile_stats_summary(
    game_id: Optional[str] = Query(None),
    generator: ProfileGenerator = Depends(get_profile_generator)
):
    """
    获取画像统计摘要
    """
    logger.info("Getting profile stats summary")
    
    try:
        profiles = generator.generate_all_profiles(game_id=game_id)
        
        if not profiles:
            return {
                "total_profiles": 0,
                "by_churn_risk": {},
                "by_activity_level": {},
                "by_payment_level": {}
            }
        
        churn_distribution = {"low": 0, "medium": 0, "high": 0}
        activity_distribution = {"高活跃": 0, "中活跃": 0, "低活跃": 0}
        payment_distribution = {"高付费": 0, "中付费": 0, "低付费": 0, "非付费": 0}
        
        for profile in profiles:
            churn_distribution[profile.churn_risk] += 1
            
            if "高活跃" in profile.profile_tags:
                activity_distribution["高活跃"] += 1
            elif "中活跃" in profile.profile_tags:
                activity_distribution["中活跃"] += 1
            elif "低活跃" in profile.profile_tags:
                activity_distribution["低活跃"] += 1
            
            if "高付费" in profile.profile_tags:
                payment_distribution["高付费"] += 1
            elif "中付费" in profile.profile_tags:
                payment_distribution["中付费"] += 1
            elif "低付费" in profile.profile_tags:
                payment_distribution["低付费"] += 1
            else:
                payment_distribution["非付费"] += 1
        
        return {
            "total_profiles": len(profiles),
            "by_churn_risk": churn_distribution,
            "by_activity_level": activity_distribution,
            "by_payment_level": payment_distribution,
            "game_id": game_id
        }
    except Exception as e:
        logger.error(f"Failed to get profile stats: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Failed to get profile stats: {str(e)}"
        )


@app.get("/api/v1/profiles/churn/high-risk")
async def get_high_risk_players(
    game_id: Optional[str] = Query(None),
    limit: int = Query(100, ge=1, le=1000),
    generator: ProfileGenerator = Depends(get_profile_generator)
):
    """
    获取高流失风险玩家列表
    """
    logger.info(f"Getting high risk players, limit={limit}")
    
    try:
        profiles = generator.generate_all_profiles(game_id=game_id)
        
        high_risk = [
            p for p in profiles 
            if p.churn_risk == "high"
        ]
        
        high_risk = high_risk[:limit]
        
        return {
            "total_high_risk": len([p for p in profiles if p.churn_risk == "high"]),
            "returned_count": len(high_risk),
            "players": [
                {
                    "player_id": p.player_id,
                    "churn_risk": p.churn_risk,
                    "last_active": p.last_active.isoformat() if p.last_active else None,
                    "profile_tags": p.profile_tags,
                    "activity_score": p.activity_score
                }
                for p in high_risk
            ]
        }
    except Exception as e:
        logger.error(f"Failed to get high risk players: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Failed to get high risk players: {str(e)}"
        )


@app.get("/api/v1/profiles/top-payers")
async def get_top_payers(
    game_id: Optional[str] = Query(None),
    limit: int = Query(50, ge=1, le=200),
    generator: ProfileGenerator = Depends(get_profile_generator)
):
    """
    获取高付费玩家列表
    """
    logger.info(f"Getting top payers, limit={limit}")
    
    try:
        profiles = generator.generate_all_profiles(game_id=game_id)
        
        paying_players = [p for p in profiles if p.pay_amount > 0]
        paying_players.sort(key=lambda x: x.pay_amount, reverse=True)
        top_payers = paying_players[:limit]
        
        return {
            "total_paying_players": len(paying_players),
            "returned_count": len(top_payers),
            "players": [
                {
                    "player_id": p.player_id,
                    "pay_amount": p.pay_amount,
                    "profile_tags": p.profile_tags,
                    "payment_score": p.payment_score
                }
                for p in top_payers
            ]
        }
    except Exception as e:
        logger.error(f"Failed to get top payers: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Failed to get top payers: {str(e)}"
        )


@app.get("/api/v1/rules/engine/status", response_model=EngineStatusResponse)
async def get_rules_engine_status(
    generator: ProfileGenerator = Depends(get_profile_generator)
):
    """
    获取规则引擎状态
    """
    logger.info("Getting rules engine status")
    
    try:
        status = generator.get_rules_engine_status()
        
        if not status.get("enabled", False):
            return EngineStatusResponse(
                version="N/A",
                loaded_at=None,
                total_rules=0,
                categories=[],
                exclusive_groups=[],
                auto_reload_enabled=False,
                config_path="N/A"
            )
        
        config_info = status.get("config", {})
        
        return EngineStatusResponse(
            version=config_info.get("version", "unknown"),
            loaded_at=config_info.get("loaded_at"),
            total_rules=config_info.get("total_rules", 0),
            categories=config_info.get("categories", []),
            exclusive_groups=config_info.get("exclusive_groups", []),
            auto_reload_enabled=config.rule_engine.auto_reload,
            config_path=config.rule_engine.config_path
        )
    except Exception as e:
        logger.error(f"Failed to get rules engine status: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Failed to get rules engine status: {str(e)}"
        )


@app.post("/api/v1/rules/reload", response_model=ReloadRulesResponse)
async def reload_rules(
    generator: ProfileGenerator = Depends(get_profile_generator)
):
    """
    重新加载标签规则配置
    运营团队可以通过此接口在更新配置文件后立即生效，无需重启服务
    """
    logger.info("Reloading rules configuration")
    
    try:
        success = generator.reload_rules()
        
        if success:
            status = generator.get_rules_engine_status()
            config_info = status.get("config", {})
            
            return ReloadRulesResponse(
                success=True,
                message="Rules reloaded successfully",
                rules_count=config_info.get("total_rules", 0),
                config_version=config_info.get("version", "unknown")
            )
        else:
            return ReloadRulesResponse(
                success=False,
                message="Failed to reload rules",
                rules_count=0,
                config_version="N/A"
            )
    except Exception as e:
        logger.error(f"Failed to reload rules: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Failed to reload rules: {str(e)}"
        )


@app.get("/api/v1/rules/config")
async def get_rules_config_info():
    """
    获取规则配置信息（当前生效的配置）
    """
    return {
        "config_path": config.rule_engine.config_path,
        "auto_reload": config.rule_engine.auto_reload,
        "reload_interval_seconds": config.rule_engine.reload_interval_seconds,
        "enabled_categories": config.rule_engine.enabled_categories,
        "use_default_rules_if_missing": config.rule_engine.use_default_rules_if_missing
    }


@app.get("/api/v1/config/info")
async def get_full_config_info():
    """
    获取完整的服务配置信息（用于调试和监控）
    """
    return {
        "service": "player-profile-service",
        "version": "2.0.0",
        "config": config.to_dict()
    }


if __name__ == "__main__":
    import uvicorn
    
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8002,
        reload=True
    )
