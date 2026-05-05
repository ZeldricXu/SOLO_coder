import logging
from datetime import datetime, timedelta, date
from typing import Dict, List, Optional, Tuple

import numpy as np
import pandas as pd

from .data_loader import DataLoader

logger = logging.getLogger(__name__)


class RetentionAnalyzer:
    """
    玩家留存率分析器
    支持计算次日留存、3日留存、7日留存、14日留存、30日留存
    """
    
    def __init__(self, data_loader: DataLoader):
        self.data_loader = data_loader

    def calculate_retention(
        self,
        cohort_start: datetime,
        cohort_end: datetime,
        game_id: Optional[str] = None,
        retention_days: List[int] = None
    ) -> Dict:
        """
        计算指定时间段的玩家留存率
        
        Args:
            cohort_start: 新用户观察期开始
            cohort_end: 新用户观察期结束
            game_id: 游戏ID（可选）
            retention_days: 留存天数列表，默认为[1, 3, 7, 14, 30]
            
        Returns:
            留存率分析结果字典
        """
        if retention_days is None:
            retention_days = [1, 3, 7, 14, 30]
        
        logger.info(f"Calculating retention for cohort {cohort_start} to {cohort_end}")
        
        login_events = self.data_loader.get_player_login_events(
            start_date=cohort_start,
            end_date=cohort_end + timedelta(days=max(retention_days) + 1),
            game_id=game_id
        )
        
        if len(login_events) == 0:
            logger.warning("No login events found")
            return self._empty_retention_result(cohort_start, cohort_end, game_id)
        
        first_login = login_events.groupby('player_id').agg({
            'date': 'min',
            'game_id': 'first'
        }).reset_index()
        
        first_login.columns = ['player_id', 'cohort_date', 'game_id']
        
        cohort_users = first_login[
            (first_login['cohort_date'] >= cohort_start.date()) &
            (first_login['cohort_date'] <= cohort_end.date())
        ]
        
        cohort_size = len(cohort_users['player_id'].unique())
        
        if cohort_size == 0:
            logger.warning("No users in cohort")
            return self._empty_retention_result(cohort_start, cohort_end, game_id)
        
        logger.info(f"Cohort size: {cohort_size} users")
        
        user_login_dates = login_events.groupby('player_id').agg({
            'date': lambda x: sorted(set(x))
        }).reset_index()
        
        user_login_dates.columns = ['player_id', 'login_dates']
        
        cohort_users = cohort_users.merge(
            user_login_dates, 
            on='player_id', 
            how='left'
        )
        
        retention_results = {}
        cohort_date = cohort_start.date()
        
        for day in retention_days:
            target_date = cohort_date + timedelta(days=day)
            
            retained_count = 0
            for _, row in cohort_users.iterrows():
                login_dates = row['login_dates'] if pd.notna(row['login_dates']) else []
                if target_date in login_dates:
                    retained_count += 1
            
            retention_rate = retained_count / cohort_size if cohort_size > 0 else 0
            
            retention_results[f'day_{day}'] = {
                'retained_users': retained_count,
                'total_users': cohort_size,
                'retention_rate': round(retention_rate * 100, 2)
            }
        
        result = {
            'cohort_start': cohort_start.isoformat(),
            'cohort_end': cohort_end.isoformat(),
            'game_id': game_id,
            'cohort_size': cohort_size,
            'retention_rates': retention_results,
            'calculated_at': datetime.now().isoformat()
        }
        
        logger.info(f"Retention calculation complete: {retention_results}")
        return result

    def calculate_retention_matrix(
        self,
        start_date: datetime,
        end_date: datetime,
        game_id: Optional[str] = None,
        max_days: int = 30
    ) -> Dict:
        """
        计算留存矩阵（按日分组）
        
        Args:
            start_date: 分析开始日期
            end_date: 分析结束日期
            game_id: 游戏ID
            max_days: 最大计算天数
            
        Returns:
            留存矩阵数据
        """
        logger.info(f"Calculating retention matrix from {start_date} to {end_date}")
        
        login_events = self.data_loader.get_player_login_events(
            start_date=start_date,
            end_date=end_date + timedelta(days=max_days),
            game_id=game_id
        )
        
        if len(login_events) == 0:
            return {
                'start_date': start_date.isoformat(),
                'end_date': end_date.isoformat(),
                'matrix': [],
                'cohort_sizes': {}
            }
        
        first_login = login_events.groupby('player_id').agg({
            'date': 'min',
            'game_id': 'first'
        }).reset_index()
        
        first_login.columns = ['player_id', 'cohort_date', 'game_id']
        
        user_login_dates = login_events.groupby('player_id').agg({
            'date': lambda x: set(x)
        }).reset_index()
        
        user_login_dates.columns = ['player_id', 'login_dates']
        
        merged = first_login.merge(user_login_dates, on='player_id', how='left')
        
        cohorts = merged.groupby('cohort_date')
        
        matrix_data = []
        cohort_sizes = {}
        
        for cohort_date, cohort_data in cohorts:
            cohort_size = len(cohort_data)
            cohort_sizes[str(cohort_date)] = cohort_size
            
            row = {'cohort_date': str(cohort_date), 'cohort_size': cohort_size}
            
            for day in range(max_days + 1):
                target_date = cohort_date + timedelta(days=day)
                
                retained = 0
                for _, user_data in cohort_data.iterrows():
                    login_dates = user_data['login_dates'] if pd.notna(user_data['login_dates']) else set()
                    if target_date in login_dates:
                        retained += 1
                
                rate = retained / cohort_size if cohort_size > 0 else 0
                row[f'day_{day}'] = round(rate * 100, 2)
                row[f'day_{day}_count'] = retained
            
            matrix_data.append(row)
        
        return {
            'start_date': start_date.isoformat(),
            'end_date': end_date.isoformat(),
            'matrix': matrix_data,
            'cohort_sizes': cohort_sizes,
            'max_days': max_days,
            'calculated_at': datetime.now().isoformat()
        }

    def calculate_weekly_retention(
        self,
        start_date: datetime,
        end_date: datetime,
        game_id: Optional[str] = None
    ) -> Dict:
        """
        计算周留存
        """
        logger.info("Calculating weekly retention")
        
        login_events = self.data_loader.get_player_login_events(
            start_date=start_date,
            end_date=end_date + timedelta(weeks=8),
            game_id=game_id
        )
        
        if len(login_events) == 0:
            return {'weekly_retention': []}
        
        login_events['week'] = login_events['event_time'].dt.isocalendar().week
        
        first_login = login_events.groupby('player_id').agg({
            'week': 'min',
            'date': 'min'
        }).reset_index()
        
        first_login.columns = ['player_id', 'cohort_week', 'first_login_date']
        
        user_weeks = login_events.groupby('player_id').agg({
            'week': lambda x: set(x)
        }).reset_index()
        
        user_weeks.columns = ['player_id', 'active_weeks']
        
        merged = first_login.merge(user_weeks, on='player_id', how='left')
        
        cohorts = merged.groupby('cohort_week')
        
        weekly_retention = []
        
        for cohort_week, cohort_data in cohorts:
            cohort_size = len(cohort_data)
            
            row = {
                'cohort_week': int(cohort_week),
                'cohort_size': cohort_size,
                'weeks': {}
            }
            
            for week_offset in range(8):
                target_week = cohort_week + week_offset
                
                retained = 0
                for _, user_data in cohort_data.iterrows():
                    active_weeks = user_data['active_weeks'] if pd.notna(user_data['active_weeks']) else set()
                    if target_week in active_weeks:
                        retained += 1
                
                rate = retained / cohort_size if cohort_size > 0 else 0
                row['weeks'][f'week_{week_offset}'] = {
                    'retained': retained,
                    'rate': round(rate * 100, 2)
                }
            
            weekly_retention.append(row)
        
        return {
            'start_date': start_date.isoformat(),
            'end_date': end_date.isoformat(),
            'weekly_retention': weekly_retention,
            'calculated_at': datetime.now().isoformat()
        }

    def _empty_retention_result(
        self,
        cohort_start: datetime,
        cohort_end: datetime,
        game_id: Optional[str]
    ) -> Dict:
        return {
            'cohort_start': cohort_start.isoformat(),
            'cohort_end': cohort_end.isoformat(),
            'game_id': game_id,
            'cohort_size': 0,
            'retention_rates': {},
            'calculated_at': datetime.now().isoformat()
        }
