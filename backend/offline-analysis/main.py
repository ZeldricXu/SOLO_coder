#!/usr/bin/env python3
"""
GameStats 离线分析服务
支持留存率分析、付费漏斗分析等批量计算任务
"""

import logging
import json
from datetime import datetime, timedelta
from typing import Optional

import click

from src.config import config
from src.data_loader import DataLoader
from src.retention_analyzer import RetentionAnalyzer
from src.funnel_analyzer import FunnelAnalyzer

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


@click.group()
def cli():
    """GameStats 离线分析服务"""
    pass


@cli.command()
@click.option('--game-id', default='game_mmorpg_01', help='游戏ID')
@click.option('--days', default=7, help='分析天数')
@click.option('--output', default=None, help='输出文件路径')
def retention(game_id: str, days: int, output: Optional[str]):
    """计算玩家留存率"""
    logger.info(f"开始计算留存率: game_id={game_id}, days={days}")
    
    data_loader = DataLoader()
    analyzer = RetentionAnalyzer(data_loader)
    
    end_date = datetime.now()
    start_date = end_date - timedelta(days=days)
    
    result = analyzer.calculate_retention(
        cohort_start=start_date,
        cohort_end=end_date,
        game_id=game_id
    )
    
    matrix_result = analyzer.calculate_retention_matrix(
        start_date=start_date,
        end_date=end_date,
        game_id=game_id
    )
    
    result['retention_matrix'] = matrix_result
    
    if output:
        with open(output, 'w', encoding='utf-8') as f:
            json.dump(result, f, indent=2, ensure_ascii=False, default=str)
        logger.info(f"结果已保存到: {output}")
    else:
        print(json.dumps(result, indent=2, ensure_ascii=False, default=str))
    
    logger.info("留存率计算完成")


@cli.command()
@click.option('--game-id', default='game_mmorpg_01', help='游戏ID')
@click.option('--days', default=30, help='分析天数')
@click.option('--output', default=None, help='输出文件路径')
def funnel(game_id: str, days: int, output: Optional[str]):
    """分析付费转化漏斗"""
    logger.info(f"开始分析付费漏斗: game_id={game_id}, days={days}")
    
    data_loader = DataLoader()
    analyzer = FunnelAnalyzer(data_loader)
    
    end_date = datetime.now()
    start_date = end_date - timedelta(days=days)
    
    result = analyzer.analyze_payment_funnel(
        start_date=start_date,
        end_date=end_date,
        game_id=game_id
    )
    
    distribution_result = analyzer.analyze_payment_distribution(
        start_date=start_date,
        end_date=end_date,
        game_id=game_id
    )
    
    result['payment_distribution'] = distribution_result
    
    if output:
        with open(output, 'w', encoding='utf-8') as f:
            json.dump(result, f, indent=2, ensure_ascii=False, default=str)
        logger.info(f"结果已保存到: {output}")
    else:
        print(json.dumps(result, indent=2, ensure_ascii=False, default=str))
    
    logger.info("付费漏斗分析完成")


@cli.command()
@click.option('--player-id', required=True, help='玩家ID')
@click.option('--days', default=30, help='分析天数')
@click.option('--output', default=None, help='输出文件路径')
def player(player_id: str, days: int, output: Optional[str]):
    """分析单个玩家的行为路径"""
    logger.info(f"开始分析玩家行为: player_id={player_id}, days={days}")
    
    data_loader = DataLoader()
    analyzer = FunnelAnalyzer(data_loader)
    
    end_date = datetime.now()
    start_date = end_date - timedelta(days=days)
    
    result = analyzer.analyze_player_journey(
        start_date=start_date,
        end_date=end_date,
        player_id=player_id
    )
    
    if output:
        with open(output, 'w', encoding='utf-8') as f:
            json.dump(result, f, indent=2, ensure_ascii=False, default=str)
        logger.info(f"结果已保存到: {output}")
    else:
        print(json.dumps(result, indent=2, ensure_ascii=False, default=str))
    
    logger.info("玩家行为分析完成")


@cli.command()
@click.option('--game-id', default='game_mmorpg_01', help='游戏ID')
@click.option('--days', default=30, help='分析天数')
@click.option('--output', default=None, help='输出文件路径')
def daily_batch(game_id: str, days: int, output: Optional[str]):
    """执行每日批量分析任务"""
    logger.info(f"开始执行每日批量分析: game_id={game_id}")
    
    data_loader = DataLoader()
    retention_analyzer = RetentionAnalyzer(data_loader)
    funnel_analyzer = FunnelAnalyzer(data_loader)
    
    end_date = datetime.now()
    start_date = end_date - timedelta(days=days)
    
    results = {
        'analysis_time': datetime.now().isoformat(),
        'game_id': game_id,
        'period_start': start_date.isoformat(),
        'period_end': end_date.isoformat()
    }
    
    logger.info("计算留存率...")
    results['retention'] = retention_analyzer.calculate_retention(
        cohort_start=start_date - timedelta(days=30),
        cohort_end=end_date,
        game_id=game_id
    )
    
    logger.info("分析付费漏斗...")
    results['funnel'] = funnel_analyzer.analyze_payment_funnel(
        start_date=start_date,
        end_date=end_date,
        game_id=game_id
    )
    
    logger.info("分析付费分布...")
    results['payment_distribution'] = funnel_analyzer.analyze_payment_distribution(
        start_date=start_date,
        end_date=end_date,
        game_id=game_id
    )
    
    if output:
        with open(output, 'w', encoding='utf-8') as f:
            json.dump(results, f, indent=2, ensure_ascii=False, default=str)
        logger.info(f"结果已保存到: {output}")
    else:
        print(json.dumps(results, indent=2, ensure_ascii=False, default=str))
    
    logger.info("每日批量分析完成")


if __name__ == '__main__':
    cli()
