import logging
from datetime import datetime, timedelta
from typing import Dict, List, Optional, Tuple, Any

import pandas as pd
import numpy as np
from sqlalchemy import create_engine, text

from .config import config
from .models import PlayerProfile, PlayerStats, ChurnPrediction, ProfileTag
from .rules_engine import TagRulesEngine, RuleEvaluationResult, TagRule

logger = logging.getLogger(__name__)


class ProfileGenerator:
    """
    玩家画像生成器
    负责根据玩家行为数据生成画像标签和分群
    
    重构说明：
    - 使用规则引擎进行标签判定，标签规则可通过配置文件动态调整
    - 保持原有 API 接口不变，确保向后兼容
    - 支持规则引擎与硬编码逻辑双轨运行（可配置）
    """
    
    def __init__(self, use_rule_engine: bool = True):
        self.engine = create_engine(config.mysql.connection_string, echo=False)
        self.use_rule_engine = use_rule_engine
        
        self._rules_engine: Optional[TagRulesEngine] = None
        self._last_rules_reload: Optional[datetime] = None
        
        if self.use_rule_engine:
            self._init_rules_engine()
        
        logger.info(f"ProfileGenerator initialized, use_rule_engine={use_rule_engine}")
    
    def _init_rules_engine(self):
        logger.info("Initializing tag rules engine...")
        
        self._rules_engine = TagRulesEngine()
        
        config_path = config.rule_engine.get_absolute_path()
        
        import os
        if os.path.exists(config_path):
            logger.info(f"Loading rules from config file: {config_path}")
            self._rules_engine.load_rules_from_yaml(config_path)
        else:
            logger.warning(f"Rules config file not found: {config_path}, using default rules")
        
        self._last_rules_reload = datetime.now()
        
        engine_info = self._rules_engine.get_config_info()
        logger.info(f"Rules engine initialized: {engine_info}")
    
    def _reload_rules_if_needed(self):
        if not config.rule_engine.auto_reload:
            return
        
        if self._last_rules_reload is None:
            return
        
        reload_interval = timedelta(seconds=config.rule_engine.reload_interval_seconds)
        if datetime.now() - self._last_rules_reload > reload_interval:
            logger.info("Auto-reloading rules...")
            self._init_rules_engine()
    
    def get_player_events(
        self,
        player_id: str,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None
    ) -> pd.DataFrame:
        """
        获取玩家的事件数据
        """
        if end_date is None:
            end_date = datetime.now()
        if start_date is None:
            start_date = end_date - timedelta(days=90)
        
        query = """
            SELECT 
                event_id,
                player_id,
                game_id,
                server_id,
                event_type,
                event_time,
                event_data
            FROM events
            WHERE 
                player_id = :player_id
                AND event_time >= :start_date
                AND event_time < :end_date
            ORDER BY event_time ASC
        """
        
        with self.engine.connect() as conn:
            df = pd.read_sql(text(query), conn, params={
                'player_id': player_id,
                'start_date': start_date,
                'end_date': end_date
            })
        
        if len(df) > 0:
            df['event_time'] = pd.to_datetime(df['event_time'])
        
        return df
    
    def get_all_players_events(
        self,
        game_id: Optional[str] = None,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None
    ) -> pd.DataFrame:
        """
        获取所有玩家的事件数据
        """
        if end_date is None:
            end_date = datetime.now()
        if start_date is None:
            start_date = end_date - timedelta(days=90)
        
        query = """
            SELECT 
                event_id,
                player_id,
                game_id,
                server_id,
                event_type,
                event_time,
                event_data
            FROM events
            WHERE 
                event_time >= :start_date
                AND event_time < :end_date
        """
        
        params = {
            'start_date': start_date,
            'end_date': end_date
        }
        
        if game_id:
            query += " AND game_id = :game_id"
            params['game_id'] = game_id
        
        query += " ORDER BY player_id, event_time ASC"
        
        with self.engine.connect() as conn:
            df = pd.read_sql(text(query), conn, params=params)
        
        if len(df) > 0:
            df['event_time'] = pd.to_datetime(df['event_time'])
        
        return df
    
    def calculate_player_stats(
        self,
        events: pd.DataFrame,
        player_id: str
    ) -> PlayerStats:
        """
        计算玩家统计数据
        """
        if len(events) == 0:
            return PlayerStats(
                player_id=player_id,
                total_events=0,
                login_count=0,
                payment_count=0,
                social_interaction_count=0,
                quest_complete_count=0,
                total_payment_amount=0.0,
                days_since_last_active=999,
                unique_active_days=0,
                avg_events_per_day=0.0
            )
        
        total_events = len(events)
        login_count = len(events[events['event_type'] == 'login'])
        payment_count = len(events[events['event_type'] == 'payment'])
        social_count = len(events[events['event_type'] == 'social_interaction'])
        quest_count = len(events[events['event_type'] == 'quest_complete'])
        
        payment_events = events[events['event_type'] == 'payment']
        total_payment = 0.0
        for _, event in payment_events.iterrows():
            if isinstance(event['event_data'], dict):
                total_payment += event['event_data'].get('amount', 0)
        
        last_active = events['event_time'].max()
        days_since_last = (datetime.now() - last_active.to_pydatetime()).days
        
        unique_days = events['event_time'].dt.date.nunique()
        avg_events = total_events / unique_days if unique_days > 0 else 0
        
        return PlayerStats(
            player_id=player_id,
            total_events=total_events,
            login_count=login_count,
            payment_count=payment_count,
            social_interaction_count=social_count,
            quest_complete_count=quest_count,
            total_payment_amount=total_payment,
            days_since_last_active=days_since_last,
            unique_active_days=unique_days,
            avg_events_per_day=avg_events
        )
    
    def generate_tags_using_rules_engine(
        self,
        stats: PlayerStats
    ) -> Tuple[List[ProfileTag], List[RuleEvaluationResult]]:
        """
        使用规则引擎生成标签
        
        返回：
        - 标签列表（ProfileTag）
        - 规则评估结果列表（用于调试和审计）
        """
        if self._rules_engine is None:
            logger.warning("Rules engine not initialized, returning empty tags")
            return [], []
        
        self._reload_rules_if_needed()
        
        context = stats.to_context()
        
        categories = config.rule_engine.enabled_categories
        
        evaluation_results = self._rules_engine.evaluate(context, categories)
        
        matched_results = [r for r in evaluation_results if r.matched]
        
        tags = []
        for result in matched_results:
            tag = ProfileTag(
                tag=result.tag_name,
                category=result.category,
                confidence=result.confidence,
                reasoning=result.reasoning
            )
            tags.append(tag)
        
        logger.debug(f"Generated {len(tags)} tags using rules engine for player {stats.player_id}")
        
        return tags, evaluation_results
    
    def generate_activity_tags_legacy(
        self,
        stats: PlayerStats
    ) -> List[ProfileTag]:
        """
        遗留的活跃度标签生成逻辑（硬编码）
        用于向后兼容和规则引擎的备用方案
        """
        tags = []
        
        if stats.unique_active_days >= config.profile.active_days_threshold:
            if stats.avg_events_per_day >= 20:
                tags.append(ProfileTag(
                    tag="高活跃",
                    category="activity",
                    confidence=0.85,
                    reasoning=f"近90天活跃{stats.unique_active_days}天，日均{stats.avg_events_per_day:.1f}次行为"
                ))
            elif stats.avg_events_per_day >= 10:
                tags.append(ProfileTag(
                    tag="中活跃",
                    category="activity",
                    confidence=0.8,
                    reasoning=f"近90天活跃{stats.unique_active_days}天，日均{stats.avg_events_per_day:.1f}次行为"
                ))
            else:
                tags.append(ProfileTag(
                    tag="低活跃",
                    category="activity",
                    confidence=0.7,
                    reasoning=f"近90天活跃{stats.unique_active_days}天，日均{stats.avg_events_per_day:.1f}次行为"
                ))
        else:
            tags.append(ProfileTag(
                tag="流失风险",
                category="activity",
                confidence=0.75,
                reasoning=f"近90天仅活跃{stats.unique_active_days}天"
            ))
        
        if stats.login_count > 0:
            tags.append(ProfileTag(
                tag="登录用户",
                category="activity",
                confidence=1.0,
                reasoning=f"累计登录{stats.login_count}次"
            ))
        
        return tags
    
    def generate_payment_tags_legacy(
        self,
        stats: PlayerStats
    ) -> List[ProfileTag]:
        """
        遗留的付费标签生成逻辑（硬编码）
        """
        tags = []
        
        if stats.total_payment_amount > 0:
            tags.append(ProfileTag(
                tag="付费玩家",
                category="payment",
                confidence=1.0,
                reasoning=f"累计付费{stats.total_payment_amount:.2f}元，共{stats.payment_count}次"
            ))
            
            if stats.total_payment_amount >= config.profile.high_pay_threshold:
                tags.append(ProfileTag(
                    tag="高付费",
                    category="payment",
                    confidence=0.9,
                    reasoning=f"累计付费超过{config.profile.high_pay_threshold}元"
                ))
            elif stats.total_payment_amount >= config.profile.medium_pay_threshold:
                tags.append(ProfileTag(
                    tag="中付费",
                    category="payment",
                    confidence=0.85,
                    reasoning=f"累计付费在{config.profile.medium_pay_threshold}-{config.profile.high_pay_threshold}元之间"
                ))
            else:
                tags.append(ProfileTag(
                    tag="低付费",
                    category="payment",
                    confidence=0.7,
                    reasoning=f"累计付费低于{config.profile.medium_pay_threshold}元"
                ))
        else:
            tags.append(ProfileTag(
                tag="非付费",
                category="payment",
                confidence=1.0,
                reasoning="无付费记录"
            ))
        
        return tags
    
    def generate_social_tags_legacy(
        self,
        stats: PlayerStats
    ) -> List[ProfileTag]:
        """
        遗留的社交标签生成逻辑（硬编码）
        """
        tags = []
        
        if stats.social_interaction_count > 20:
            tags.append(ProfileTag(
                tag="社交型",
                category="social",
                confidence=0.85,
                reasoning=f"累计社交互动{stats.social_interaction_count}次"
            ))
        elif stats.social_interaction_count > 5:
            tags.append(ProfileTag(
                tag="轻度社交",
                category="social",
                confidence=0.7,
                reasoning=f"累计社交互动{stats.social_interaction_count}次"
            ))
        else:
            tags.append(ProfileTag(
                tag="独狼型",
                category="social",
                confidence=0.7,
                reasoning=f"社交互动较少，仅{stats.social_interaction_count}次"
            ))
        
        if stats.quest_complete_count > 50:
            tags.append(ProfileTag(
                tag="任务达人",
                category="gameplay",
                confidence=0.8,
                reasoning=f"完成任务{stats.quest_complete_count}次"
            ))
        
        return tags
    
    def generate_activity_tags(
        self,
        stats: PlayerStats
    ) -> List[ProfileTag]:
        """
        生成活跃度标签（统一入口）
        如果规则引擎可用，优先使用规则引擎；否则使用硬编码逻辑
        """
        if self.use_rule_engine and self._rules_engine is not None:
            try:
                tags, _ = self.generate_tags_using_rules_engine(stats)
                
                activity_tags = [t for t in tags if t.category == "activity"]
                
                if activity_tags:
                    return activity_tags
            except Exception as e:
                logger.warning(f"Rules engine failed for activity tags, falling back to legacy: {e}")
        
        return self.generate_activity_tags_legacy(stats)
    
    def generate_payment_tags(
        self,
        stats: PlayerStats
    ) -> List[ProfileTag]:
        """
        生成付费标签（统一入口）
        """
        if self.use_rule_engine and self._rules_engine is not None:
            try:
                tags, _ = self.generate_tags_using_rules_engine(stats)
                
                payment_tags = [t for t in tags if t.category == "payment"]
                
                if payment_tags:
                    return payment_tags
            except Exception as e:
                logger.warning(f"Rules engine failed for payment tags, falling back to legacy: {e}")
        
        return self.generate_payment_tags_legacy(stats)
    
    def generate_social_tags(
        self,
        stats: PlayerStats
    ) -> List[ProfileTag]:
        """
        生成社交标签（统一入口）
        """
        if self.use_rule_engine and self._rules_engine is not None:
            try:
                tags, _ = self.generate_tags_using_rules_engine(stats)
                
                social_tags = [t for t in tags if t.category in ["social", "gameplay"]]
                
                if social_tags:
                    return social_tags
            except Exception as e:
                logger.warning(f"Rules engine failed for social tags, falling back to legacy: {e}")
        
        return self.generate_social_tags_legacy(stats)
    
    def generate_all_tags_using_engine(
        self,
        stats: PlayerStats
    ) -> List[ProfileTag]:
        """
        使用规则引擎生成所有标签
        """
        if self._rules_engine is None:
            return []
        
        tags, _ = self.generate_tags_using_rules_engine(stats)
        return tags
    
    def predict_churn_risk(
        self,
        stats: PlayerStats
    ) -> ChurnPrediction:
        """
        预测流失风险
        """
        risk_factors = []
        risk_score = 0.0
        
        if stats.days_since_last_active > config.churn_prediction.high_risk_days:
            risk_score += config.churn_prediction.risk_weights["high_inactivity_days"]
            risk_factors.append(f"超过{config.churn_prediction.high_risk_days}天未活跃")
        elif stats.days_since_last_active > config.churn_prediction.medium_risk_days:
            risk_score += config.churn_prediction.risk_weights["medium_inactivity_days"]
            risk_factors.append(f"超过{config.churn_prediction.medium_risk_days}天未活跃")
        
        if stats.unique_active_days < config.churn_prediction.low_activity_days:
            risk_score += config.churn_prediction.risk_weights["low_activity_days"]
            risk_factors.append("活跃天数较少")
        
        if stats.total_events < config.churn_prediction.low_activity_events:
            risk_score += config.churn_prediction.risk_weights["low_events"]
            risk_factors.append("行为事件较少")
        
        if stats.total_payment_amount == 0:
            risk_score += config.churn_prediction.risk_weights["non_payer"]
            risk_factors.append("非付费用户")
        
        risk_score = min(risk_score, 1.0)
        
        if risk_score >= config.churn_prediction.risk_thresholds["high"]:
            risk_level = "high"
        elif risk_score >= config.churn_prediction.risk_thresholds["medium"]:
            risk_level = "medium"
        else:
            risk_level = "low"
        
        return ChurnPrediction(
            player_id=stats.player_id,
            risk_level=risk_level,
            risk_score=risk_score,
            risk_factors=risk_factors,
            predicted_churn_probability=risk_score
        )
    
    def calculate_scores(
        self,
        stats: PlayerStats
    ) -> Tuple[float, float, float]:
        """
        计算各项评分
        """
        max_events = config.scoring.activity_max_events
        activity_score = min(stats.total_events / max_events, 1.0) * 100
        
        if stats.unique_active_days > 0:
            activity_score *= (1 + stats.unique_active_days * config.scoring.activity_days_weight)
        
        activity_score = min(activity_score, 100)
        
        payment_score = 0.0
        if stats.total_payment_amount > 0:
            payment_score = min(stats.total_payment_amount / config.scoring.payment_max_amount, 1.0) * 100
        
        social_score = min(stats.social_interaction_count / config.scoring.social_max_interactions, 1.0) * 100
        
        return (
            round(activity_score, 2),
            round(payment_score, 2),
            round(social_score, 2)
        )
    
    def generate_profile(
        self,
        player_id: str,
        events: Optional[pd.DataFrame] = None
    ) -> PlayerProfile:
        """
        生成玩家完整画像
        
        重构说明：
        - 优先使用规则引擎生成标签
        - 如果规则引擎不可用或失败，自动回退到硬编码逻辑
        - 保持原有的 PlayerProfile 结构不变，确保向后兼容
        """
        logger.info(f"Generating profile for player: {player_id}")
        
        if events is None:
            events = self.get_player_events(player_id)
        
        stats = self.calculate_player_stats(events, player_id)
        
        tags = []
        
        if self.use_rule_engine and self._rules_engine is not None:
            try:
                engine_tags = self.generate_all_tags_using_engine(stats)
                if engine_tags:
                    tags = engine_tags
                    logger.debug(f"Using rules engine for player {player_id}: {len(tags)} tags")
            except Exception as e:
                logger.error(f"Rules engine failed, falling back to legacy logic: {e}")
        
        if not tags:
            logger.debug(f"Using legacy logic for player {player_id}")
            tags.extend(self.generate_activity_tags_legacy(stats))
            tags.extend(self.generate_payment_tags_legacy(stats))
            tags.extend(self.generate_social_tags_legacy(stats))
        
        unique_tags = list(set([t.tag for t in tags]))
        
        churn_prediction = self.predict_churn_risk(stats)
        
        activity_score, payment_score, social_score = self.calculate_scores(stats)
        
        last_active = datetime.now() - timedelta(days=stats.days_since_last_active)
        if len(events) > 0:
            last_active = events['event_time'].max().to_pydatetime()
        
        profile = PlayerProfile(
            player_id=player_id,
            profile_tags=unique_tags,
            level=1,
            vip_level=1 if stats.total_payment_amount >= 100 else 0,
            total_play_time=stats.login_count * 30,
            pay_amount=stats.total_payment_amount,
            last_active=last_active,
            churn_risk=churn_prediction.risk_level,
            activity_score=activity_score,
            payment_score=payment_score,
            social_score=social_score
        )
        
        logger.info(f"Generated profile for {player_id}: tags={unique_tags}, churn_risk={churn_prediction.risk_level}")
        return profile
    
    def generate_all_profiles(
        self,
        game_id: Optional[str] = None,
        player_ids: Optional[List[str]] = None
    ) -> List[PlayerProfile]:
        """
        批量生成玩家画像
        """
        logger.info("Starting batch profile generation")
        
        events = self.get_all_players_events(game_id=game_id)
        
        if len(events) == 0:
            logger.warning("No events found for profile generation")
            return []
        
        all_players = events['player_id'].unique()
        if player_ids:
            all_players = [p for p in all_players if p in player_ids]
        
        profiles = []
        for player_id in all_players:
            player_events = events[events['player_id'] == player_id]
            profile = self.generate_profile(player_id, player_events)
            profiles.append(profile)
        
        logger.info(f"Generated {len(profiles)} player profiles")
        return profiles
    
    def save_profile(self, profile: PlayerProfile):
        """
        保存画想到数据库
        """
        import json
        
        query = """
            INSERT INTO player_profiles (
                player_id, profile_tags, level, vip_level, total_play_time,
                pay_amount, last_active, churn_risk, activity_score,
                payment_score, social_score, created_at, updated_at
            ) VALUES (
                :player_id, :profile_tags, :level, :vip_level, :total_play_time,
                :pay_amount, :last_active, :churn_risk, :activity_score,
                :payment_score, :social_score, NOW(), NOW()
            ) ON DUPLICATE KEY UPDATE
                profile_tags = VALUES(profile_tags),
                level = VALUES(level),
                vip_level = VALUES(vip_level),
                total_play_time = VALUES(total_play_time),
                pay_amount = VALUES(pay_amount),
                last_active = VALUES(last_active),
                churn_risk = VALUES(churn_risk),
                activity_score = VALUES(activity_score),
                payment_score = VALUES(payment_score),
                social_score = VALUES(social_score),
                updated_at = NOW()
        """
        
        with self.engine.connect() as conn:
            conn.execute(text(query), {
                'player_id': profile.player_id,
                'profile_tags': json.dumps(profile.profile_tags),
                'level': profile.level,
                'vip_level': profile.vip_level,
                'total_play_time': profile.total_play_time,
                'pay_amount': profile.pay_amount,
                'last_active': profile.last_active,
                'churn_risk': profile.churn_risk,
                'activity_score': profile.activity_score,
                'payment_score': profile.payment_score,
                'social_score': profile.social_score
            })
            conn.commit()
        
        logger.info(f"Saved profile for player: {profile.player_id}")
    
    def get_rules_engine_status(self) -> Dict[str, Any]:
        """
        获取规则引擎状态
        """
        if self._rules_engine is None:
            return {
                "enabled": False,
                "message": "Rules engine not initialized"
            }
        
        return {
            "enabled": True,
            "use_rule_engine": self.use_rule_engine,
            "last_reload": self._last_rules_reload.isoformat() if self._last_rules_reload else None,
            "config": self._rules_engine.get_config_info()
        }
    
    def reload_rules(self) -> bool:
        """
        重新加载规则
        """
        try:
            self._init_rules_engine()
            return True
        except Exception as e:
            logger.error(f"Failed to reload rules: {e}")
            return False
