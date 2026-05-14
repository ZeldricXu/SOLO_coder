import pytest
import json
import tempfile
import os
from unittest.mock import Mock, patch, MagicMock

from behaviortrack.modules import AbnormalDetector, DetectionRule
from .test_data_builder import TestDataBuilder


class TestDetectionRule:
    def test_detection_rule_creation(self):
        rule = DetectionRule(
            rule_id="test_rule",
            rule_name="测试规则",
            rule_type="frequency",
            enabled=True,
            threshold={"max_events": 100},
            description="测试描述",
            severity="high",
            action="block"
        )
        
        assert rule.rule_id == "test_rule"
        assert rule.rule_name == "测试规则"
        assert rule.rule_type == "frequency"
        assert rule.enabled is True
        assert rule.threshold == {"max_events": 100}
        assert rule.description == "测试描述"
        assert rule.severity == "high"
        assert rule.action == "block"
    
    def test_detection_rule_to_dict(self):
        rule = DetectionRule(
            rule_id="dict_test",
            rule_name="字典测试",
            rule_type="temporal",
            threshold={"normal_hours_start": 6, "normal_hours_end": 22}
        )
        
        rule_dict = rule.to_dict()
        
        assert isinstance(rule_dict, dict)
        assert rule_dict["rule_id"] == "dict_test"
        assert rule_dict["rule_name"] == "字典测试"
        assert rule_dict["rule_type"] == "temporal"
        assert "threshold" in rule_dict
    
    def test_detection_rule_from_dict(self):
        data = {
            "rule_id": "from_dict_test",
            "rule_name": "从字典创建",
            "rule_type": "geographic",
            "enabled": True,
            "threshold": {"max_distance_km": 500},
            "description": "地理位置检测",
            "severity": "high",
            "action": "flag"
        }
        
        rule = DetectionRule.from_dict(data)
        
        assert rule.rule_id == "from_dict_test"
        assert rule.rule_type == "geographic"
        assert rule.threshold["max_distance_km"] == 500
    
    def test_detection_rule_defaults(self):
        rule = DetectionRule(
            rule_id="defaults_test",
            rule_name="默认值测试",
            rule_type="device"
        )
        
        assert rule.enabled is True
        assert rule.severity == "medium"
        assert rule.action == "flag"
        assert rule.threshold is None


class TestAbnormalDetectorInit:
    def test_detector_initialization(self):
        detector = AbnormalDetector()
        
        rules = detector.get_all_rules()
        assert len(rules) >= 10
        
        stats = detector.get_detection_stats()
        assert stats["total_detections"] == 0
        assert stats["active_rules"] >= 10
        assert stats["total_rules"] >= 10
    
    def test_default_rules_coverage(self):
        detector = AbnormalDetector()
        
        rules = detector.get_all_rules()
        rule_ids = [r["rule_id"] for r in rules]
        
        expected_rule_ids = [
            "frequent_events",
            "rapid_session_creation",
            "unusual_hours",
            "geographic_anomaly",
            "device_switching",
            "sequence_anomaly",
            "suspicious_pattern",
            "rapid_purchases",
            "credential_stuffing",
            "data_exfiltration"
        ]
        
        for rule_id in expected_rule_ids:
            assert rule_id in rule_ids
    
    def test_rule_types(self):
        detector = AbnormalDetector()
        
        rules = detector.get_all_rules()
        rule_types = set(r["rule_type"] for r in rules)
        
        expected_types = [
            "frequency",
            "temporal",
            "geographic",
            "device",
            "sequence",
            "pattern",
            "transaction",
            "auth",
            "data"
        ]
        
        for rule_type in expected_types:
            assert rule_type in rule_types


class TestRuleManagement:
    def test_get_all_rules(self):
        detector = AbnormalDetector()
        
        rules = detector.get_all_rules()
        
        assert isinstance(rules, list)
        assert len(rules) >= 10
        
        for rule in rules:
            assert "rule_id" in rule
            assert "rule_name" in rule
            assert "rule_type" in rule
            assert "enabled" in rule
            assert "severity" in rule
    
    def test_get_specific_rule(self):
        detector = AbnormalDetector()
        
        rule = detector.get_rule("frequent_events")
        
        assert rule is not None
        assert rule["rule_id"] == "frequent_events"
        assert rule["rule_type"] == "frequency"
    
    def test_get_nonexistent_rule(self):
        detector = AbnormalDetector()
        
        rule = detector.get_rule("nonexistent_rule")
        
        assert rule is None
    
    def test_enable_rule(self):
        detector = AbnormalDetector()
        
        result = detector.enable_rule("frequent_events")
        
        assert result["success"] is True
        assert result["enabled"] is True
        
        rule = detector.get_rule("frequent_events")
        assert rule["enabled"] is True
    
    def test_disable_rule(self):
        detector = AbnormalDetector()
        
        result = detector.disable_rule("frequent_events")
        
        assert result["success"] is True
        assert result["enabled"] is False
        
        rule = detector.get_rule("frequent_events")
        assert rule["enabled"] is False
    
    def test_enable_nonexistent_rule(self):
        detector = AbnormalDetector()
        
        result = detector.enable_rule("nonexistent")
        
        assert result["success"] is False
        assert "error" in result
    
    def test_update_rule(self):
        detector = AbnormalDetector()
        
        result = detector.update_rule(
            "frequent_events",
            {
                "description": "更新后的描述",
                "severity": "critical"
            }
        )
        
        assert result["success"] is True
        
        rule = detector.get_rule("frequent_events")
        assert rule["description"] == "更新后的描述"
        assert rule["severity"] == "critical"


class TestRulesConfigLoading:
    def test_load_rules_from_file(self):
        detector = AbnormalDetector()
        
        config_data = TestDataBuilder.build_rules_config()
        
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False) as f:
            json.dump(config_data, f, ensure_ascii=False)
            temp_path = f.name
        
        try:
            result = detector.load_rules_from_file(temp_path)
            
            assert result["success"] is True
            assert result["loaded_count"] == 2
            
            custom_rule = detector.get_rule("custom_frequency")
            assert custom_rule is not None
            assert custom_rule["rule_name"] == "自定义频率检测"
        finally:
            os.unlink(temp_path)
    
    def test_load_rules_invalid_file(self):
        detector = AbnormalDetector()
        
        result = detector.load_rules_from_file("/nonexistent/path/rules.json")
        
        assert result["success"] is False
        assert "error" in result
    
    def test_load_rules_empty_config(self):
        detector = AbnormalDetector()
        
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False) as f:
            json.dump({"rules": []}, f)
            temp_path = f.name
        
        try:
            result = detector.load_rules_from_file(temp_path)
            
            assert result["success"] is True
            assert result["loaded_count"] == 0
        finally:
            os.unlink(temp_path)


class TestFrequencyDetection:
    def test_high_frequency_detection(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_high_frequency_events(
            user_id="freq_user",
            events_per_session=150
        )
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event))
        
        freq_detections = [
            d for d in detections 
            if d["rule_id"] == "frequent_events"
        ]
        
        assert len(freq_detections) >= 1
        
        for detection in freq_detections:
            assert detection["is_abnormal"] is True
            assert detection["detection_type"] == "frequency"
            assert "actual_count" in detection
            assert detection["actual_count"] > 100
    
    def test_normal_frequency_no_detection(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_normal_user_events(
            user_id="normal_freq",
            count=30
        )
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event))
        
        freq_detections = [
            d for d in detections 
            if d["rule_id"] == "frequent_events"
        ]
        
        assert len(freq_detections) == 0
    
    def test_frequency_detection_confidence(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_high_frequency_events(
            user_id="confidence_user",
            events_per_session=200
        )
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event))
        
        freq_detections = [
            d for d in detections 
            if d["rule_id"] == "frequent_events"
        ]
        
        if freq_detections:
            detection = freq_detections[0]
            assert "confidence" in detection
            assert 0.0 <= detection["confidence"] <= 1.0
            assert detection["confidence"] >= 0.7


class TestTemporalDetection:
    def test_unusual_hours_detection(self):
        detector = AbnormalDetector()
        
        night_events = TestDataBuilder.build_abnormal_time_events(
            user_id="night_owl",
            hour=3,
            count=20
        )
        
        normal_events = TestDataBuilder.build_normal_user_events(
            user_id="night_owl",
            count=10
        )
        
        all_events = normal_events + night_events
        
        detections = []
        for event in all_events:
            detections.extend(detector.detect(event))
        
        temporal_detections = [
            d for d in detections 
            if d["rule_id"] == "unusual_hours"
        ]
        
        assert len(temporal_detections) >= 1
        
        if temporal_detections:
            detection = temporal_detections[0]
            assert detection["detection_type"] == "temporal"
            assert "hour" in detection
            assert 0 <= detection["hour"] < 6 or 22 <= detection["hour"] < 24
    
    def test_normal_hours_no_detection(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_normal_user_events(
            user_id="normal_hours",
            count=50
        )
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event))
        
        temporal_detections = [
            d for d in detections 
            if d["rule_id"] == "unusual_hours"
        ]
        
        assert len(temporal_detections) == 0


class TestPatternDetection:
    def test_suspicious_pattern_detection(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_suspicious_pattern_events(
            user_id="pattern_user",
            repeat_count=5
        )
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event))
        
        pattern_detections = [
            d for d in detections 
            if d["rule_id"] == "suspicious_pattern"
        ]
        
        assert len(pattern_detections) >= 1
        
        if pattern_detections:
            detection = pattern_detections[0]
            assert detection["detection_type"] == "pattern"
            assert "pattern" in detection
            assert "repeat_count" in detection
            assert detection["repeat_count"] >= 3


class TestTransactionDetection:
    def test_rapid_purchases_detection(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_rapid_purchase_events(
            user_id="rapid_buyer",
            purchase_count=8
        )
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event))
        
        transaction_detections = [
            d for d in detections 
            if d["rule_id"] == "rapid_purchases"
        ]
        
        assert len(transaction_detections) >= 1
        
        if transaction_detections:
            detection = transaction_detections[0]
            assert detection["detection_type"] == "transaction"
            assert "purchase_count" in detection
            assert detection["purchase_count"] > 5
            assert detection["severity"] == "high"
            assert detection["action"] == "block"
    
    def test_normal_purchases_no_detection(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_rapid_purchase_events(
            user_id="normal_buyer",
            purchase_count=3
        )
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event))
        
        transaction_detections = [
            d for d in detections 
            if d["rule_id"] == "rapid_purchases"
        ]
        
        assert len(transaction_detections) == 0


class TestAuthDetection:
    def test_credential_stuffing_detection(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_failed_login_events(
            user_id="attack_target",
            failed_count=12
        )
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event))
        
        auth_detections = [
            d for d in detections 
            if d["rule_id"] == "credential_stuffing"
        ]
        
        assert len(auth_detections) >= 1
        
        if auth_detections:
            detection = auth_detections[0]
            assert detection["detection_type"] == "auth"
            assert "failed_count" in detection
            assert detection["failed_count"] >= 10
            assert detection["severity"] == "critical"
            assert detection["action"] == "block"
    
    def test_normal_login_attempts(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_failed_login_events(
            user_id="normal_user",
            failed_count=5
        )
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event))
        
        auth_detections = [
            d for d in detections 
            if d["rule_id"] == "credential_stuffing"
        ]
        
        assert len(auth_detections) == 0


class TestSequenceDetection:
    def test_abnormal_sequence_detection(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_abnormal_sequence_events(
            user_id="sequence_user"
        )
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event))
        
        sequence_detections = [
            d for d in detections 
            if d["rule_id"] == "sequence_anomaly"
        ]
        
        assert len(sequence_detections) >= 1
        
        if sequence_detections:
            detection = sequence_detections[0]
            assert detection["detection_type"] == "sequence"
            assert "sequence" in detection
            assert len(detection["sequence"]) == 3


class TestDataDetection:
    def test_data_export_detection(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_data_export_events(
            user_id="data_leaker",
            export_count=8
        )
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event))
        
        data_detections = [
            d for d in detections 
            if d["rule_id"] == "data_exfiltration"
        ]
        
        assert len(data_detections) >= 1
        
        if data_detections:
            detection = data_detections[0]
            assert detection["detection_type"] == "data"
            assert "export_count" in detection
            assert detection["export_count"] > 5


class TestGeographicDetection:
    def test_geographic_anomaly_with_profile(self):
        detector = AbnormalDetector()
        
        user_profile = TestDataBuilder.build_user_profile_with_behavior_context(
            user_id="geo_user"
        )
        
        events = TestDataBuilder.build_geographic_anomaly_events(
            user_id="geo_user"
        )
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event, user_profile))
        
        geo_detections = [
            d for d in detections 
            if d["rule_id"] == "geographic_anomaly"
        ]
        
        if geo_detections:
            detection = geo_detections[0]
            assert detection["detection_type"] == "geographic"
            assert "distance_km" in detection
            assert detection["distance_km"] > 500
    
    def test_geographic_normal_location(self):
        detector = AbnormalDetector()
        
        user_profile = TestDataBuilder.build_user_profile_with_behavior_context(
            user_id="normal_geo"
        )
        
        events = [
            TestDataBuilder.build_behavior_event_dict(
                user_id="normal_geo",
                event_type="login",
                location={"country": "中国", "city": "北京", "latitude": 39.9, "longitude": 116.4}
            )
        ]
        
        detections = []
        for event in events:
            detections.extend(detector.detect(event, user_profile))
        
        geo_detections = [
            d for d in detections 
            if d["rule_id"] == "geographic_anomaly"
        ]
        
        assert len(geo_detections) == 0


class TestDeviceDetection:
    def test_device_switching_with_profile(self):
        detector = AbnormalDetector()
        
        user_profile = {
            "user_id": "multi_device",
            "behavior": {
                "used_devices": [
                    {"type": "mobile", "os": "ios"},
                    {"type": "desktop", "os": "windows"},
                    {"type": "tablet", "os": "android"},
                    {"type": "mobile", "os": "android"}
                ]
            }
        }
        
        event = TestDataBuilder.build_behavior_event_dict(
            user_id="multi_device",
            event_type="login",
            device={"type": "mobile", "os": "android"}
        )
        
        detections = detector.detect(event, user_profile)
        
        device_detections = [
            d for d in detections 
            if d["rule_id"] == "device_switching"
        ]
        
        if device_detections:
            detection = device_detections[0]
            assert detection["detection_type"] == "device"
            assert "device_count" in detection
            assert detection["device_count"] > 3


class TestDetectionHistory:
    def test_detection_history_tracking(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_high_frequency_events(
            user_id="history_user",
            events_per_session=150
        )
        
        for event in events:
            detector.detect(event)
        
        history = detector.get_detection_history()
        
        assert len(history) >= 1
        
        for record in history:
            assert "user_id" in record
            assert "rule_id" in record
            assert "rule_name" in record
            assert "severity" in record
            assert "detected_at" in record
    
    def test_detection_history_by_user(self):
        detector = AbnormalDetector()
        
        user1_events = TestDataBuilder.build_high_frequency_events(
            user_id="user1",
            events_per_session=120
        )
        user2_events = TestDataBuilder.build_high_frequency_events(
            user_id="user2",
            events_per_session=130
        )
        
        for event in user1_events + user2_events:
            detector.detect(event)
        
        user1_history = detector.get_detection_history(user_id="user1")
        user2_history = detector.get_detection_history(user_id="user2")
        
        assert all(h["user_id"] == "user1" for h in user1_history)
        assert all(h["user_id"] == "user2" for h in user2_history)
    
    def test_detection_history_limit(self):
        detector = AbnormalDetector()
        
        for i in range(5):
            events = TestDataBuilder.build_high_frequency_events(
                user_id=f"user_{i}",
                events_per_session=150
            )
            for event in events:
                detector.detect(event)
        
        history = detector.get_detection_history(limit=5)
        
        assert len(history) <= 5


class TestDetectionStatistics:
    def test_detection_stats_basic(self):
        detector = AbnormalDetector()
        
        stats = detector.get_detection_stats()
        
        assert stats["total_detections"] == 0
        assert "severity_distribution" in stats
        assert "top_rules" in stats
        assert "active_rules" in stats
        assert "total_rules" in stats
    
    def test_detection_stats_after_detections(self):
        detector = AbnormalDetector()
        
        events = TestDataBuilder.build_high_frequency_events(
            user_id="stats_user",
            events_per_session=150
        )
        
        for event in events:
            detector.detect(event)
        
        stats = detector.get_detection_stats()
        
        assert stats["total_detections"] >= 1
        assert len(stats["severity_distribution"]) >= 1
        assert len(stats["top_rules"]) >= 1


class TestRulesEvaluation:
    def test_evaluate_rules_metrics(self):
        detector = AbnormalDetector()
        
        normal_events = TestDataBuilder.build_normal_user_events(
            user_id="eval_normal",
            count=20
        )
        
        abnormal_events = TestDataBuilder.build_high_frequency_events(
            user_id="eval_abnormal",
            events_per_session=150
        )
        
        evaluation = detector.evaluate_rules(
            normal_events=normal_events,
            abnormal_events=abnormal_events
        )
        
        assert "true_positives" in evaluation
        assert "false_positives" in evaluation
        assert "true_negatives" in evaluation
        assert "false_negatives" in evaluation
        assert "accuracy" in evaluation
        assert "precision" in evaluation
        assert "recall" in evaluation
        assert "f1_score" in evaluation
        assert "false_positive_rate" in evaluation
        assert "misclassification_rate" in evaluation
    
    def test_evaluate_rules_values_range(self):
        detector = AbnormalDetector()
        
        normal_events = TestDataBuilder.build_normal_user_events(
            user_id="range_normal",
            count=10
        )
        
        abnormal_events = TestDataBuilder.build_high_frequency_events(
            user_id="range_abnormal",
            events_per_session=120
        )
        
        evaluation = detector.evaluate_rules(
            normal_events=normal_events,
            abnormal_events=abnormal_events
        )
        
        assert 0.0 <= evaluation["accuracy"] <= 1.0
        assert 0.0 <= evaluation["precision"] <= 1.0
        assert 0.0 <= evaluation["recall"] <= 1.0
        assert 0.0 <= evaluation["f1_score"] <= 1.0
        assert 0.0 <= evaluation["false_positive_rate"] <= 1.0
        assert 0.0 <= evaluation["misclassification_rate"] <= 1.0
    
    def test_false_positive_rate_control(self):
        detector = AbnormalDetector()
        
        detector.disable_rule("unusual_hours")
        detector.disable_rule("sequence_anomaly")
        
        normal_events = TestDataBuilder.build_normal_user_events(
            user_id="fpr_user",
            count=50
        )
        
        abnormal_events = TestDataBuilder.build_high_frequency_events(
            user_id="fpr_abnormal",
            events_per_session=150
        )
        
        evaluation = detector.evaluate_rules(
            normal_events=normal_events,
            abnormal_events=abnormal_events
        )
        
        assert evaluation["false_positive_rate"] <= 0.3
    
    def test_evaluate_empty_datasets(self):
        detector = AbnormalDetector()
        
        evaluation = detector.evaluate_rules(
            normal_events=[],
            abnormal_events=[]
        )
        
        assert evaluation["accuracy"] == 0.0
        assert evaluation["precision"] == 0.0
        assert evaluation["recall"] == 0.0
