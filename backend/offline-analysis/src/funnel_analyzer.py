import logging
from datetime import datetime, timedelta
from typing import Dict, List, Optional

import pandas as pd
import numpy as np

from .data_loader import DataLoader

logger = logging.getLogger(__name__)


class FunnelAnalyzer:
    """
    付费转化漏斗分析器
    用于分析玩家从注册到付费的转化路径和流失点
    """
    
    FUNNEL_STAGES = [
        {'stage': 'register', 'event_type': 'login', 'stage_name': '首次登录/注册'},
        {'stage': 'tutorial_complete', 'event_type': 'quest_complete', 'stage_name': '完成新手引导'},
        {'stage': 'game_play', 'event_type': 'game_start', 'stage_name': '开始游戏'},
        {'stage': 'item_view', 'event_type': 'item_purchase', 'stage_name': '查看商品'},
        {'stage': 'payment_attempt', 'event_type': 'payment', 'stage_name': '尝试支付'},
        {'stage': 'payment_success', 'event_type': 'payment', 'stage_name': '支付成功'},
    ]
    
    def __init__(self, data_loader: DataLoader):
        self.data_loader = data_loader

    def analyze_payment_funnel(
        self,
        start_date: datetime,
        end_date: datetime,
        game_id: Optional[str] = None
    ) -> Dict:
        """
        分析付费转化漏斗
        
        Args:
            start_date: 分析开始日期
            end_date: 分析结束日期
            game_id: 游戏ID
            
        Returns:
            漏斗分析结果
        """
        logger.info(f"Analyzing payment funnel from {start_date} to {end_date}")
        
        all_events = self.data_loader.get_player_all_events(
            start_date=start_date,
            end_date=end_date
        )
        
        if len(all_events) == 0:
            return self._empty_funnel_result(start_date, end_date, game_id)
        
        player_first_events = self._get_player_first_events(all_events)
        
        funnel_data = self._calculate_funnel_stages(player_first_events, all_events)
        
        conversion_rates = self._calculate_conversion_rates(funnel_data)
        
        result = {
            'start_date': start_date.isoformat(),
            'end_date': end_date.isoformat(),
            'game_id': game_id,
            'funnel_stages': funnel_data,
            'conversion_rates': conversion_rates,
            'calculated_at': datetime.now().isoformat()
        }
        
        logger.info(f"Payment funnel analysis complete: {len(funnel_data)} stages")
        return result

    def _get_player_first_events(self, events: pd.DataFrame) -> pd.DataFrame:
        """
        获取每个玩家的首次事件
        """
        player_events = events.groupby(['player_id', 'event_type']).agg({
            'event_time': 'min'
        }).reset_index()
        
        player_events = player_events.pivot(
            index='player_id',
            columns='event_type',
            values='event_time'
        ).reset_index()
        
        return player_events

    def _calculate_funnel_stages(
        self, 
        player_first_events: pd.DataFrame, 
        all_events: pd.DataFrame
    ) -> List[Dict]:
        """
        计算漏斗各阶段数据
        """
        total_players = len(player_first_events['player_id'].nunique())
        
        funnel_stages = []
        
        stage_counts = {
            'first_login': 0,
            'tutorial_complete': 0,
            'game_play': 0,
            'payment_attempt': 0,
            'payment_success': 0
        }
        
        if 'login' in player_first_events.columns:
            stage_counts['first_login'] = player_first_events['login'].notna().sum()
        
        if 'quest_complete' in player_first_events.columns:
            stage_counts['tutorial_complete'] = player_first_events['quest_complete'].notna().sum()
        
        if 'game_start' in player_first_events.columns:
            stage_counts['game_play'] = player_first_events['game_start'].notna().sum()
        
        payment_events = all_events[all_events['event_type'] == 'payment']
        if len(payment_events) > 0:
            stage_counts['payment_attempt'] = payment_events['player_id'].nunique()
            
            success_payments = payment_events[
                payment_events['event_data'].apply(
                    lambda x: x.get('status') == 'success' if isinstance(x, dict) else True
                )
            ]
            stage_counts['payment_success'] = success_payments['player_id'].nunique()
        
        funnel_stages = [
            {
                'stage': 'first_login',
                'stage_name': '首次登录',
                'count': int(stage_counts['first_login']),
                'percentage': 100.0
            },
            {
                'stage': 'tutorial_complete',
                'stage_name': '完成新手引导',
                'count': int(stage_counts['tutorial_complete']),
                'percentage': self._safe_percentage(
                    stage_counts['tutorial_complete'], 
                    stage_counts['first_login']
                )
            },
            {
                'stage': 'game_play',
                'stage_name': '开始游戏',
                'count': int(stage_counts['game_play']),
                'percentage': self._safe_percentage(
                    stage_counts['game_play'], 
                    stage_counts['first_login']
                )
            },
            {
                'stage': 'payment_attempt',
                'stage_name': '尝试支付',
                'count': int(stage_counts['payment_attempt']),
                'percentage': self._safe_percentage(
                    stage_counts['payment_attempt'], 
                    stage_counts['first_login']
                )
            },
            {
                'stage': 'payment_success',
                'stage_name': '支付成功',
                'count': int(stage_counts['payment_success']),
                'percentage': self._safe_percentage(
                    stage_counts['payment_success'], 
                    stage_counts['first_login']
                )
            }
        ]
        
        return funnel_stages

    def _calculate_conversion_rates(self, funnel_stages: List[Dict]) -> Dict:
        """
        计算阶段转化率
        """
        conversion_rates = {
            'overall_conversion': 0.0,
            'step_conversions': []
        }
        
        if len(funnel_stages) < 2:
            return conversion_rates
        
        for i in range(1, len(funnel_stages)):
            current = funnel_stages[i]
            previous = funnel_stages[i-1]
            
            step_conversion = {
                'from_stage': previous['stage'],
                'to_stage': current['stage'],
                'from_count': previous['count'],
                'to_count': current['count'],
                'conversion_rate': self._safe_percentage(
                    current['count'], 
                    previous['count']
                ),
                'drop_off_rate': self._safe_percentage(
                    previous['count'] - current['count'], 
                    previous['count']
                )
            }
            
            conversion_rates['step_conversions'].append(step_conversion)
        
        if len(funnel_stages) >= 1:
            first_stage = funnel_stages[0]
            last_stage = funnel_stages[-1]
            conversion_rates['overall_conversion'] = self._safe_percentage(
                last_stage['count'], 
                first_stage['count']
            )
        
        return conversion_rates

    def analyze_player_journey(
        self,
        start_date: datetime,
        end_date: datetime,
        player_id: str
    ) -> Dict:
        """
        分析单个玩家的转化路径
        """
        logger.info(f"Analyzing player journey for {player_id}")
        
        player_events = self.data_loader.get_player_all_events(
            start_date=start_date,
            end_date=end_date,
            player_id=player_id
        )
        
        if len(player_events) == 0:
            return {
                'player_id': player_id,
                'events': [],
                'journey_summary': 'No events found'
            }
        
        player_events = player_events.sort_values('event_time')
        
        events_list = []
        for _, event in player_events.iterrows():
            events_list.append({
                'event_type': event['event_type'],
                'event_time': event['event_time'].isoformat() if pd.notna(event['event_time']) else None,
                'event_data': event['event_data']
            })
        
        journey_summary = self._generate_journey_summary(player_events)
        
        return {
            'player_id': player_id,
            'total_events': len(player_events),
            'events': events_list,
            'journey_summary': journey_summary,
            'calculated_at': datetime.now().isoformat()
        }

    def _generate_journey_summary(self, events: pd.DataFrame) -> Dict:
        """
        生成玩家旅程摘要
        """
        summary = {
            'first_event': None,
            'last_event': None,
            'event_types': {},
            'completed_stages': [],
            'time_to_first_payment': None
        }
        
        if len(events) == 0:
            return summary
        
        summary['first_event'] = {
            'type': events.iloc[0]['event_type'],
            'time': events.iloc[0]['event_time'].isoformat()
        }
        
        summary['last_event'] = {
            'type': events.iloc[-1]['event_type'],
            'time': events.iloc[-1]['event_time'].isoformat()
        }
        
        event_counts = events['event_type'].value_counts()
        summary['event_types'] = event_counts.to_dict()
        
        completed_stages = []
        if 'login' in event_counts.index:
            completed_stages.append('first_login')
        if 'quest_complete' in event_counts.index:
            completed_stages.append('tutorial_complete')
        if 'game_start' in event_counts.index:
            completed_stages.append('game_play')
        if 'payment' in event_counts.index:
            completed_stages.append('payment')
        
        summary['completed_stages'] = completed_stages
        
        payment_events = events[events['event_type'] == 'payment']
        if len(payment_events) > 0:
            first_payment = payment_events.iloc[0]
            first_login = events[events['event_type'] == 'login']
            if len(first_login) > 0:
                time_diff = first_payment['event_time'] - first_login.iloc[0]['event_time']
                summary['time_to_first_payment_minutes'] = int(time_diff.total_seconds() / 60)
        
        return summary

    def analyze_payment_distribution(
        self,
        start_date: datetime,
        end_date: datetime,
        game_id: Optional[str] = None
    ) -> Dict:
        """
        分析付费分布
        """
        logger.info("Analyzing payment distribution")
        
        payment_events = self.data_loader.get_player_payment_events(
            start_date=start_date,
            end_date=end_date,
            game_id=game_id
        )
        
        if len(payment_events) == 0:
            return {
                'start_date': start_date.isoformat(),
                'end_date': end_date.isoformat(),
                'total_payments': 0,
                'total_revenue': 0.0,
                'paying_users': 0,
                'distribution': {}
            }
        
        total_payments = len(payment_events)
        total_revenue = payment_events['amount'].sum()
        paying_users = payment_events['player_id'].nunique()
        
        arpu = total_revenue / paying_users if paying_users > 0 else 0
        arppu = total_revenue / total_payments if total_payments > 0 else 0
        
        amount_bins = [0, 10, 50, 100, 500, 1000, float('inf')]
        bin_labels = ['0-10', '10-50', '50-100', '100-500', '500-1000', '1000+']
        
        payment_events['amount_bin'] = pd.cut(
            payment_events['amount'], bins=amount_bins, labels=bin_labels)
        amount_distribution = payment_events['amount_bin'].value_counts().to_dict()
        
        daily_payments = payment_events.groupby('date').agg({
            'amount': 'sum',
            'player_id': 'nunique'
        }).reset_index()
        daily_payments.columns = ['date', 'daily_revenue', 'daily_payers']
        
        daily_data = []
        for _, row in daily_payments.iterrows():
            daily_data.append({
                'date': str(row['date']),
                'revenue': float(row['daily_revenue']),
                'payers': int(row['daily_payers'])
            })
        
        return {
            'start_date': start_date.isoformat(),
            'end_date': end_date.isoformat(),
            'total_payments': int(total_payments),
            'total_revenue': float(total_revenue),
            'paying_users': int(paying_users),
            'arpu': round(arpu, 2),
            'arppu': round(arppu, 2),
            'amount_distribution': amount_distribution,
            'daily_stats': daily_data,
            'calculated_at': datetime.now().isoformat()
        }

    def _safe_percentage(self, numerator: int, denominator: int) -> float:
        """
        安全计算百分比
        """
        if denominator == 0:
            return 0.0
        return round((numerator / denominator) * 100, 2)

    def _empty_funnel_result(
        self, 
        start_date: datetime, 
        end_date: datetime, 
        game_id: Optional[str]
    ) -> Dict:
        return {
            'start_date': start_date.isoformat(),
            'end_date': end_date.isoformat(),
            'game_id': game_id,
            'funnel_stages': [],
            'conversion_rates': {
                'overall_conversion': 0.0,
                'step_conversions': []
            },
            'calculated_at': datetime.now().isoformat()
        }
