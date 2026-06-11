#!/usr/bin/env python3
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

import random
import json
from datetime import datetime, timedelta
from pathlib import Path
import numpy as np

from app.config import settings
from app.database import SessionLocal, init_db
from app.models import (
    Building, RoadNetwork, POI, TrafficSensor,
    TrafficFlowRecord, TrafficZone, ODRecord,
    SignalTimingPlan, User
)
from app.utils.auth import get_password_hash
from geoalchemy2.shape import from_shape
from shapely.geometry import Point, LineString, Polygon

CENTER_LAT = settings.MAP_CENTER_LAT
CENTER_LON = settings.MAP_CENTER_LON


def generate_sample_data():
    db = SessionLocal()

    try:
        print("初始化数据库...")
        init_db()

        print("生成道路网络数据...")
        generate_roads(db)

        print("生成建筑物数据...")
        generate_buildings(db)

        print("生成POI数据...")
        generate_pois(db)

        print("生成交通传感器数据...")
        generate_sensors(db)

        print("生成交通流量历史数据...")
        generate_traffic_flows(db)

        print("生成交通分区数据...")
        generate_zones(db)

        print("生成OD数据...")
        generate_od_records(db)

        print("生成信号灯配时方案...")
        generate_signal_plans(db)

        print("生成示例用户...")
        generate_users(db)

        print("示例数据生成完成！")

    except Exception as e:
        print(f"生成数据时出错: {e}")
        import traceback
        traceback.print_exc()
        db.rollback()
    finally:
        db.close()


def generate_roads(db, count: int = 50):
    roads = []

    for i in range(count):
        angle = (i / count) * 2 * np.pi
        length = 0.01 + random.random() * 0.03

        start_lon = CENTER_LON + np.cos(angle) * 0.005
        start_lat = CENTER_LAT + np.sin(angle) * 0.005
        end_lon = CENTER_LON + np.cos(angle) * length
        end_lat = CENTER_LAT + np.sin(angle) * length

        road_type = random.choice(["highway", "main_road", "secondary", "branch"])
        lanes = {"highway": 8, "main_road": 6, "secondary": 4, "branch": 2}[road_type]
        speed_limit = {"highway": 120, "main_road": 80, "secondary": 60, "branch": 40}[road_type]

        line = LineString([(start_lon, start_lat), (end_lon, end_lat)])

        road = RoadNetwork(
            name=f"{road_type}_{i:03d}",
            road_type=road_type,
            lanes=lanes,
            speed_limit=speed_limit,
            geom=from_shape(line, srid=4326),
        )
        roads.append(road)

    for i in range(count // 2):
        angle = (i / (count // 2)) * 2 * np.pi + np.pi / count
        length = 0.005 + random.random() * 0.02

        start_lon = CENTER_LON - np.sin(angle) * 0.01
        start_lat = CENTER_LAT + np.cos(angle) * 0.01
        end_lon = CENTER_LON + np.sin(angle) * length
        end_lat = CENTER_LAT - np.cos(angle) * length

        line = LineString([(start_lon, start_lat), (end_lon, end_lat)])

        road = RoadNetwork(
            name=f"cross_road_{i:03d}",
            road_type="secondary",
            lanes=4,
            speed_limit=60,
            geom=from_shape(line, srid=4326),
        )
        roads.append(road)

    db.bulk_save_objects(roads)
    db.commit()
    print(f"  生成了 {len(roads)} 条道路")


def generate_buildings(db, count: int = 200):
    buildings = []

    for i in range(count):
        lon = CENTER_LON + (random.random() - 0.5) * 0.04
        lat = CENTER_LAT + (random.random() - 0.5) * 0.04

        width = 0.0005 + random.random() * 0.002
        height = 0.0005 + random.random() * 0.002

        corners = [
            (lon - width / 2, lat - height / 2),
            (lon + width / 2, lat - height / 2),
            (lon + width / 2, lat + height / 2),
            (lon - width / 2, lat + height / 2),
            (lon - width / 2, lat - height / 2),
        ]

        building_height = random.randint(10, 100)
        floors = building_height // 3

        building_type = random.choice(["residential", "commercial", "office", "public", "industrial"])

        poly = Polygon(corners)

        building = Building(
            name=f"建筑_{i:04d}",
            height=building_height,
            floors=floors,
            building_type=building_type,
            geom=from_shape(poly, srid=4326),
            footprint_area=width * height * 111000 * 111000,
        )
        buildings.append(building)

    db.bulk_save_objects(buildings)
    db.commit()
    print(f"  生成了 {len(buildings)} 栋建筑物")


def generate_pois(db, count: int = 100):
    pois = []
    categories = ["restaurant", "shopping", "hospital", "school", "park", "station", "office", "hotel"]

    for i in range(count):
        lon = CENTER_LON + (random.random() - 0.5) * 0.04
        lat = CENTER_LAT + (random.random() - 0.5) * 0.04
        category = random.choice(categories)

        point = Point(lon, lat)

        poi = POI(
            name=f"{category}_{i:03d}",
            category=category,
            address=f"示例街道 {random.randint(1, 999)} 号",
            geom=from_shape(point, srid=4326),
            properties={"rating": round(random.uniform(1, 5), 1)},
        )
        pois.append(poi)

    db.bulk_save_objects(pois)
    db.commit()
    print(f"  生成了 {len(pois)} 个POI")


def generate_sensors(db, count: int = 30):
    sensors = []
    sensor_types = ["camera", "inductive_loop", "radar", "gps"]

    for i in range(count):
        lon = CENTER_LON + (random.random() - 0.5) * 0.03
        lat = CENTER_LAT + (random.random() - 0.5) * 0.03
        sensor_type = random.choice(sensor_types)

        point = Point(lon, lat)

        sensor = TrafficSensor(
            sensor_id=f"SENSOR_{i:04d}",
            sensor_type=sensor_type,
            name=f"{sensor_type}_传感器_{i:03d}",
            location=from_shape(point, srid=4326),
            direction=random.uniform(0, 360),
            status="active",
            installed_at=datetime.utcnow() - timedelta(days=random.randint(1, 365)),
        )
        sensors.append(sensor)

    db.bulk_save_objects(sensors)
    db.commit()
    print(f"  生成了 {len(sensors)} 个传感器")


def generate_traffic_flows(db, days: int = 7):
    sensors = db.query(TrafficSensor).all()
    if not sensors:
        print("  没有传感器数据，跳过流量数据生成")
        return

    flow_records = []
    vehicle_types = ["all", "car", "bus", "truck", "motorcycle"]
    directions = ["both", "east", "west", "north", "south"]
    time_windows = ["5m", "15m", "1h", "1d"]

    end_time = datetime.utcnow()
    start_time = end_time - timedelta(days=days)

    current = start_time
    total_records = 0

    while current <= end_time:
        for sensor in sensors:
            point_shape = None
            try:
                from geoalchemy2.shape import to_shape
                point_shape = to_shape(sensor.location)
            except:
                pass

            base_flow = 100 + abs(np.sin(current.hour / 24 * 2 * np.pi + sensor.id)) * 500

            if 7 <= current.hour <= 9 or 17 <= current.hour <= 19:
                base_flow *= 1.8

            vehicle_count = int(base_flow * random.uniform(0.8, 1.2))
            pedestrian_count = int(vehicle_count * random.uniform(0.1, 0.5))
            avg_speed = max(10, 60 - vehicle_count / 20 * random.uniform(0.5, 1.5))
            congestion_index = min(1.0, vehicle_count / 800)

            record = TrafficFlowRecord(
                sensor_id=sensor.sensor_id,
                timestamp=current,
                vehicle_count=vehicle_count,
                pedestrian_count=pedestrian_count,
                avg_speed=avg_speed,
                congestion_index=congestion_index,
                vehicle_type=random.choice(vehicle_types),
                direction=random.choice(directions),
                time_window="5m",
                geom=sensor.location if point_shape else None,
            )
            flow_records.append(record)
            total_records += 1

        current += timedelta(minutes=15)

        if len(flow_records) >= 1000:
            db.bulk_save_objects(flow_records)
            db.commit()
            flow_records = []
            print(f"  已生成 {total_records} 条流量记录...")

    if flow_records:
        db.bulk_save_objects(flow_records)
        db.commit()

    print(f"  生成了 {total_records} 条交通流量记录")


def generate_zones(db, count: int = 20):
    zones = []
    zone_types = ["residential", "commercial", "industrial", "park", "transportation"]

    for i in range(count):
        center_lon = CENTER_LON + (random.random() - 0.5) * 0.06
        center_lat = CENTER_LAT + (random.random() - 0.5) * 0.06
        width = 0.005 + random.random() * 0.01
        height = 0.005 + random.random() * 0.01

        corners = [
            (center_lon - width / 2, center_lat - height / 2),
            (center_lon + width / 2, center_lat - height / 2),
            (center_lon + width / 2, center_lat + height / 2),
            (center_lon - width / 2, center_lat + height / 2),
            (center_lon - width / 2, center_lat - height / 2),
        ]

        poly = Polygon(corners)

        zone = TrafficZone(
            zone_code=f"ZONE_{i:03d}",
            name=f"交通分区_{i:03d}",
            zone_type=random.choice(zone_types),
            geom=from_shape(poly, srid=4326),
            properties={"population": random.randint(1000, 50000)},
        )
        zones.append(zone)

    db.bulk_save_objects(zones)
    db.commit()
    print(f"  生成了 {len(zones)} 个交通分区")


def generate_od_records(db, count: int = 500):
    zones = db.query(TrafficZone).all()
    if len(zones) < 2:
        print("  交通分区不足，跳过OD数据生成")
        return

    records = []
    time_periods = ["morning_peak", "morning", "lunch", "afternoon", "evening_peak", "evening", "night"]
    travel_modes = ["car", "bus", "subway", "walk", "bike"]

    from geoalchemy2.shape import to_shape

    for i in range(count):
        origin_zone = random.choice(zones)
        dest_zone = random.choice(zones)
        while dest_zone.id == origin_zone.id:
            dest_zone = random.choice(zones)

        origin_geom = to_shape(origin_zone.geom).centroid
        dest_geom = to_shape(dest_zone.geom).centroid

        record = ODRecord(
            origin_lng=origin_geom.x,
            origin_lat=origin_geom.y,
            dest_lng=dest_geom.x,
            dest_lat=dest_geom.y,
            origin_zone_id=origin_zone.id,
            dest_zone_id=dest_zone.id,
            trip_count=random.randint(10, 1000),
            travel_mode=random.choice(travel_modes),
            time_period=random.choice(time_periods),
            avg_travel_time=random.uniform(5, 60),
        )
        records.append(record)

    db.bulk_save_objects(records)
    db.commit()
    print(f"  生成了 {len(records)} 条OD记录")


def generate_signal_plans(db, count: int = 5):
    plans = []

    for i in range(count):
        phases = []
        phase_count = random.randint(2, 4)
        cycle_length = random.choice([60, 90, 120, 150, 180])
        green_per_phase = (cycle_length - phase_count * 3) // phase_count

        for p in range(phase_count):
            phases.append({
                "phase_id": p + 1,
                "name": f"相位{p + 1}",
                "green_time": green_per_phase,
                "yellow_time": 3,
                "all_red_time": 0,
                "directions": ["north_south", "east_west", "left_turn", "pedestrian"][:phase_count][p],
            })

        plan = SignalTimingPlan(
            name=f"配时方案_{i:02d}",
            intersection_id=i + 1,
            intersection_name=f"交叉口_{i:02d}",
            phases=phases,
            cycle_length=cycle_length,
            status="draft" if i > 0 else "active",
        )
        plans.append(plan)

    db.bulk_save_objects(plans)
    db.commit()
    print(f"  生成了 {len(plans)} 个信号灯配时方案")


def generate_users(db):
    users = [
        {
            "username": "admin",
            "password": "admin123",
            "email": "admin@traffic.com",
            "full_name": "系统管理员",
            "role": "admin",
        },
        {
            "username": "operator",
            "password": "operator123",
            "email": "operator@traffic.com",
            "full_name": "运维人员",
            "role": "editor",
        },
        {
            "username": "viewer",
            "password": "viewer123",
            "email": "viewer@traffic.com",
            "full_name": "访客用户",
            "role": "viewer",
        },
    ]

    for user_data in users:
        existing = db.query(User).filter(User.username == user_data["username"]).first()
        if existing:
            continue

        hashed = get_password_hash(user_data["password"])
        user = User(
            username=user_data["username"],
            email=user_data["email"],
            hashed_password=hashed,
            full_name=user_data["full_name"],
            role=user_data["role"],
            is_active=True,
        )
        db.add(user)

    db.commit()
    print(f"  生成了 {len(users)} 个示例用户 (admin/admin123, operator/operator123, viewer/viewer123)")


if __name__ == "__main__":
    generate_sample_data()
