import logging
from datetime import datetime, timedelta
from typing import Optional, List

import pandas as pd
from sqlalchemy import create_engine, text

from .config import config

logger = logging.getLogger(__name__)


class DataLoader:
    def __init__(self):
        self.engine = create_engine(config.mysql.connection_string, echo=False)
        logger.info("DataLoader initialized")

    def get_player_login_events(
        self,
        start_date: datetime,
        end_date: datetime,
        game_id: Optional[str] = None
    ) -> pd.DataFrame:
        """
        获取玩家登录事件数据
        """
        query = """
            SELECT 
                player_id,
                game_id,
                server_id,
                event_time,
                event_data
            FROM events
            WHERE 
                event_type = 'login'
                AND event_time >= :start_date
                AND event_time < :end_date
        """
        
        params = {
            'start_date': start_date,
            'end_date': end_date
        }
        
        if game_id:
            query += " AND game_id = :game_id"
            params['game_id'] = game_id
        
        query += " ORDER BY event_time ASC"
        
        logger.info(f"Loading login events from {start_date} to {end_date}")
        
        with self.engine.connect() as conn:
            df = pd.read_sql(text(query), conn, params=params)
        
        df['event_time'] = pd.to_datetime(df['event_time'])
        df['date'] = df['event_time'].dt.date
        
        logger.info(f"Loaded {len(df)} login events")
        return df

    def get_player_payment_events(
        self,
        start_date: datetime,
        end_date: datetime,
        game_id: Optional[str] = None
    ) -> pd.DataFrame:
        """
        获取玩家支付事件数据
        """
        query = """
            SELECT 
                player_id,
                game_id,
                server_id,
                event_time,
                event_data
            FROM events
            WHERE 
                event_type = 'payment'
                AND event_time >= :start_date
                AND event_time < :end_date
        """
        
        params = {
            'start_date': start_date,
            'end_date': end_date
        }
        
        if game_id:
            query += " AND game_id = :game_id"
            params['game_id'] = game_id
        
        query += " ORDER BY event_time ASC"
        
        logger.info(f"Loading payment events from {start_date} to {end_date}")
        
        with self.engine.connect() as conn:
            df = pd.read_sql(text(query), conn, params=params)
        
        if len(df) > 0:
            df['event_time'] = pd.to_datetime(df['event_time'])
            df['amount'] = df['event_data'].apply(
                lambda x: x.get('amount', 0) if isinstance(x, dict) else 0
            )
            df['date'] = df['event_time'].dt.date
        
        logger.info(f"Loaded {len(df)} payment events")
        return df

    def get_player_all_events(
        self,
        start_date: datetime,
        end_date: datetime,
        player_id: Optional[str] = None
    ) -> pd.DataFrame:
        """
        获取指定玩家的所有事件数据
        """
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
        
        if player_id:
            query += " AND player_id = :player_id"
            params['player_id'] = player_id
        
        query += " ORDER BY player_id, event_time ASC"
        
        logger.info(f"Loading all events from {start_date} to {end_date}")
        
        with self.engine.connect() as conn:
            df = pd.read_sql(text(query), conn, params=params)
        
        if len(df) > 0:
            df['event_time'] = pd.to_datetime(df['event_time'])
        
        logger.info(f"Loaded {len(df)} events")
        return df

    def get_dau_by_date(
        self,
        start_date: datetime,
        end_date: datetime,
        game_id: Optional[str] = None
    ) -> pd.DataFrame:
        """
        获取每日活跃用户数
        """
        query = """
            SELECT 
                DATE(event_time) as date,
                COUNT(DISTINCT player_id) as dau
            FROM events
            WHERE 
                event_type = 'login'
                AND event_time >= :start_date
                AND event_time < :end_date
        """
        
        params = {
            'start_date': start_date,
            'end_date': end_date
        }
        
        if game_id:
            query += " AND game_id = :game_id"
            params['game_id'] = game_id
        
        query += " GROUP BY DATE(event_time) ORDER BY date"
        
        with self.engine.connect() as conn:
            df = pd.read_sql(text(query), conn, params=params)
        
        return df

    def save_analysis_result(
        self,
        analysis_type: str,
        result_data: dict,
        game_id: str,
        period_start: datetime,
        period_end: datetime
    ):
        """
        保存分析结果到数据库
        """
        import json
        
        query = """
            INSERT INTO analysis_results 
            (analysis_type, game_id, result_data, period_start, period_end, created_at)
            VALUES 
            (:analysis_type, :game_id, :result_data, :period_start, :period_end, NOW())
        """
        
        with self.engine.connect() as conn:
            conn.execute(
                text(query),
                {
                    'analysis_type': analysis_type,
                    'game_id': game_id,
                    'result_data': json.dumps(result_data),
                    'period_start': period_start,
                    'period_end': period_end
                }
            )
            conn.commit()
        
        logger.info(f"Saved {analysis_type} analysis result for game {game_id}")
