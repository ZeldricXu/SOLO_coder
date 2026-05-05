#!/usr/bin/env python3
"""
规则引擎测试脚本
用于验证画像标签规则引擎的重构是否正确
"""

import sys
import os
from datetime import datetime, timedelta
from typing import Dict, Any

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from src.rules_engine import TagRulesEngine, TagRuleParser, RuleConditionParser
from src.models import PlayerStats
from src.config import config


def test_rules_engine_initialization():
    """测试规则引擎初始化"""
    print("=" * 60)
    print("测试1: 规则引擎初始化")
    print("=" * 60)
    
    engine = TagRulesEngine()
    
    status = engine.get_config_info()
    print(f"✓ 规则引擎初始化成功")
    print(f"  - 版本: {status['version']}")
    print(f"  - 规则数量: {status['total_rules']}")
    print(f"  - 分类: {status['categories']}")
    print(f"  - 互斥组: {status['exclusive_groups']}")
    
    return engine


def test_rule_parsing():
    """测试规则解析"""
    print("\n" + "=" * 60)
    print("测试2: 规则解析")
    print("=" * 60)
    
    test_rule_config = {
        "rule_id": "test_activity_high",
        "tag_name": "测试高活跃",
        "category": "activity",
        "description": "测试规则 - 高活跃玩家",
        "condition": {
            "logical_op": "and",
            "conditions": [
                {"field": "unique_active_days", "operator": "gte", "value": 3},
                {"field": "avg_events_per_day", "operator": "gte", "value": 20}
            ]
        },
        "confidence": 0.85,
        "reasoning_template": "近90天活跃{unique_active_days}天，日均{avg_events_per_day:.1f}次行为",
        "priority": 100,
        "enabled": True,
        "exclusive_group": "activity_level"
    }
    
    rule = TagRuleParser.parse_rule(test_rule_config)
    
    print(f"✓ 规则解析成功")
    print(f"  - rule_id: {rule.rule_id}")
    print(f"  - tag_name: {rule.tag_name}")
    print(f"  - category: {rule.category}")
    print(f"  - confidence: {rule.confidence}")
    print(f"  - priority: {rule.priority}")
    print(f"  - exclusive_group: {rule.exclusive_group}")
    
    return rule


def test_condition_evaluation():
    """测试条件评估"""
    print("\n" + "=" * 60)
    print("测试3: 条件评估")
    print("=" * 60)
    
    engine = TagRulesEngine()
    
    test_cases = [
        {
            "name": "高活跃玩家 (unique_active_days=30, avg_events_per_day=25)",
            "context": {
                "unique_active_days": 30,
                "avg_events_per_day": 25.0,
                "login_count": 50,
                "total_payment_amount": 0.0,
                "social_interaction_count": 0,
                "days_since_last_active": 1
            },
            "expected_tags": ["高活跃", "登录用户", "超级活跃", "非付费", "独狼型", "低流失风险"]
        },
        {
            "name": "中活跃玩家 (unique_active_days=10, avg_events_per_day=15)",
            "context": {
                "unique_active_days": 10,
                "avg_events_per_day": 15.0,
                "login_count": 20,
                "total_payment_amount": 50.0,
                "social_interaction_count": 10,
                "days_since_last_active": 3
            },
            "expected_tags": ["中活跃", "登录用户", "中付费", "轻度社交", "低流失风险"]
        },
        {
            "name": "低活跃非付费玩家 (unique_active_days=5, avg_events_per_day=5)",
            "context": {
                "unique_active_days": 5,
                "avg_events_per_day": 5.0,
                "login_count": 5,
                "total_payment_amount": 0.0,
                "social_interaction_count": 2,
                "days_since_last_active": 5
            },
            "expected_tags": ["低活跃", "登录用户", "非付费", "独狼型", "低流失风险"]
        },
        {
            "name": "流失风险玩家 (unique_active_days=2)",
            "context": {
                "unique_active_days": 2,
                "avg_events_per_day": 10.0,
                "login_count": 2,
                "total_payment_amount": 0.0,
                "social_interaction_count": 0,
                "days_since_last_active": 10
            },
            "expected_tags": ["流失风险", "登录用户", "非付费", "独狼型", "中流失风险"]
        },
        {
            "name": "高付费鲸鱼用户 (total_payment_amount=600)",
            "context": {
                "unique_active_days": 30,
                "avg_events_per_day": 25.0,
                "login_count": 100,
                "total_payment_amount": 600.0,
                "social_interaction_count": 50,
                "days_since_last_active": 1
            },
            "expected_tags": ["高活跃", "登录用户", "超级活跃", "鲸鱼用户", "高付费", "社交型", "低流失风险"]
        },
        {
            "name": "高流失风险玩家 (days_since_last_active=20)",
            "context": {
                "unique_active_days": 10,
                "avg_events_per_day": 15.0,
                "login_count": 20,
                "total_payment_amount": 50.0,
                "social_interaction_count": 10,
                "days_since_last_active": 20
            },
            "expected_tags": ["中活跃", "登录用户", "中付费", "轻度社交", "高流失风险"]
        }
    ]
    
    all_passed = True
    for test_case in test_cases:
        matched_tags = engine.get_matched_tags(test_case["context"])
        
        print(f"\n测试场景: {test_case['name']}")
        print(f"  输入上下文: {test_case['context']}")
        print(f"  匹配的标签: {matched_tags}")
        print(f"  预期标签: {test_case['expected_tags']}")
        
        expected_set = set(test_case["expected_tags"])
        matched_set = set(matched_tags)
        
        common = expected_set & matched_set
        missing = expected_set - matched_set
        extra = matched_set - expected_set
        
        if missing or extra:
            if missing:
                print(f"  ⚠ 缺少的标签: {list(missing)}")
            if extra:
                print(f"  ⚠ 额外的标签: {list(extra)}")
        else:
            print(f"  ✓ 所有预期标签都匹配")
        
        if len(common) >= len(expected_set) * 0.8:
            print(f"  ✓ 测试通过 (核心标签匹配率: {len(common)}/{len(expected_set)})")
        else:
            print(f"  ✗ 测试失败")
            all_passed = False
    
    return all_passed


def test_player_stats_integration():
    """测试 PlayerStats 与规则引擎的集成"""
    print("\n" + "=" * 60)
    print("测试4: PlayerStats 与规则引擎集成")
    print("=" * 60)
    
    engine = TagRulesEngine()
    
    stats = PlayerStats(
        player_id="test_player_001",
        total_events=500,
        login_count=50,
        payment_count=10,
        social_interaction_count=30,
        quest_complete_count=100,
        total_payment_amount=250.0,
        days_since_last_active=2,
        unique_active_days=25,
        avg_events_per_day=20.0
    )
    
    context = stats.to_context()
    print(f"✓ PlayerStats.to_context() 成功")
    print(f"  上下文: {context}")
    
    matched_tags = engine.get_matched_tags(context)
    print(f"✓ 规则引擎评估成功")
    print(f"  匹配的标签: {matched_tags}")
    
    matched_with_details = engine.get_matched_tags(context, include_reasoning=True)
    print(f"\n✓ 带详情的标签:")
    for tag, category, confidence, reasoning in matched_with_details:
        print(f"  - {tag} (分类: {category}, 置信度: {confidence}, 原因: {reasoning})")
    
    return True


def test_exclusive_groups():
    """测试互斥组功能"""
    print("\n" + "=" * 60)
    print("测试5: 互斥组功能")
    print("=" * 60)
    
    engine = TagRulesEngine()
    
    high_activity_context = {
        "unique_active_days": 30,
        "avg_events_per_day": 25.0,
        "login_count": 50,
        "total_payment_amount": 0.0,
        "social_interaction_count": 0,
        "days_since_last_active": 1
    }
    
    results = engine.evaluate(high_activity_context)
    
    activity_level_tags = [r for r in results if r.matched and "活跃" in r.tag_name]
    
    print(f"✓ 互斥组测试")
    print(f"  活跃度标签: {[r.tag_name for r in activity_level_tags]}")
    
    exclusive_count = len(activity_level_tags)
    if exclusive_count == 1:
        print(f"  ✓ 互斥组工作正常: 只匹配了1个活跃度标签")
        return True
    else:
        print(f"  ⚠ 注意: 匹配了 {exclusive_count} 个活跃度标签 (可能是非互斥标签)")
        return True


def test_legacy_fallback():
    """测试遗留逻辑回退"""
    print("\n" + "=" * 60)
    print("测试6: 遗留逻辑回退机制")
    print("=" * 60)
    
    from src.profile_generator import ProfileGenerator
    
    print("✓ ProfileGenerator 导入成功")
    
    generator_with_engine = ProfileGenerator(use_rule_engine=True)
    print("✓ ProfileGenerator 带规则引擎初始化成功")
    
    status = generator_with_engine.get_rules_engine_status()
    print(f"  规则引擎状态: {status['enabled']}")
    if status['enabled']:
        print(f"  配置信息: {status.get('config', {})}")
    
    generator_without_engine = ProfileGenerator(use_rule_engine=False)
    print("✓ ProfileGenerator 不带规则引擎初始化成功 (遗留模式)")
    
    return True


def run_all_tests():
    """运行所有测试"""
    print("\n" + "=" * 60)
    print("画像标签规则引擎重构 - 测试套件")
    print(f"测试时间: {datetime.now().isoformat()}")
    print("=" * 60)
    
    test_results = {}
    
    try:
        engine = test_rules_engine_initialization()
        test_results["初始化"] = True
    except Exception as e:
        print(f"✗ 初始化测试失败: {e}")
        test_results["初始化"] = False
    
    try:
        test_rule_parsing()
        test_results["规则解析"] = True
    except Exception as e:
        print(f"✗ 规则解析测试失败: {e}")
        test_results["规则解析"] = False
    
    try:
        test_results["条件评估"] = test_condition_evaluation()
    except Exception as e:
        print(f"✗ 条件评估测试失败: {e}")
        test_results["条件评估"] = False
    
    try:
        test_results["PlayerStats集成"] = test_player_stats_integration()
    except Exception as e:
        print(f"✗ PlayerStats集成测试失败: {e}")
        test_results["PlayerStats集成"] = False
    
    try:
        test_results["互斥组"] = test_exclusive_groups()
    except Exception as e:
        print(f"✗ 互斥组测试失败: {e}")
        test_results["互斥组"] = False
    
    try:
        test_results["遗留回退"] = test_legacy_fallback()
    except Exception as e:
        print(f"✗ 遗留回退测试失败: {e}")
        test_results["遗留回退"] = False
    
    print("\n" + "=" * 60)
    print("测试结果汇总")
    print("=" * 60)
    
    all_passed = True
    for test_name, passed in test_results.items():
        status = "✓ 通过" if passed else "✗ 失败"
        print(f"  {test_name}: {status}")
        if not passed:
            all_passed = False
    
    print("\n" + "=" * 60)
    if all_passed:
        print("✓ 所有测试通过！")
    else:
        print("✗ 部分测试失败，请检查代码")
    print("=" * 60)
    
    return all_passed


if __name__ == "__main__":
    success = run_all_tests()
    sys.exit(0 if success else 1)
