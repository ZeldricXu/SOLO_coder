import logging
from typing import Any, Dict, List, Optional
from datetime import datetime, timedelta

import pymongo
from pymongo import MongoClient
from pymongo.collection import Collection
from pymongo.database import Database

from ..config import settings
from ..models import (
    BehaviorEvent,
    BehaviorStat,
    UserTrajectory,
    UserProfile,
    EventRelation,
    AbnormalBehavior
)


logger = logging.getLogger(__name__)


class MongoStorage:
    _instance: Optional["MongoStorage"] = None
    
    def __new__(cls) -> "MongoStorage":
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance
    
    def __init__(self) -> None:
        if self._initialized:
            return
        self._client: Optional[MongoClient] = None
        self._db: Optional[Database] = None
        self._initialized = True
    
    @property
    def db(self) -> Database:
        if self._db is None:
            self._connect()
        return self._db
    
    @property
    def events_collection(self) -> Collection:
        return self.db[settings.EVENTS_COLLECTION]
    
    @property
    def stats_collection(self) -> Collection:
        return self.db[settings.STATS_COLLECTION]
    
    @property
    def trajectories_collection(self) -> Collection:
        return self.db[settings.TRAJECTORIES_COLLECTION]
    
    @property
    def profiles_collection(self) -> Collection:
        return self.db[settings.PROFILES_COLLECTION]
    
    @property
    def relations_collection(self) -> Collection:
        return self.db[settings.RELATIONS_COLLECTION]
    
    @property
    def abnormal_collection(self) -> Collection:
        return self.db[settings.ABNORMAL_COLLECTION]
    
    def _connect(self) -> None:
        logger.info(f"Connecting to MongoDB: {settings.MONGODB_URI}")
        self._client = MongoClient(settings.MONGODB_URI)
        self._db = self._client[settings.MONGODB_DB_NAME]
        self._create_indexes()
        logger.info("Connected to MongoDB successfully")
    
    def _create_indexes(self) -> None:
        self.events_collection.create_index([("user_id", pymongo.ASCENDING)])
        self.events_collection.create_index([("event_type", pymongo.ASCENDING)])
        self.events_collection.create_index([("timestamp", pymongo.DESCENDING)])
        self.events_collection.create_index([("session_id", pymongo.ASCENDING)])
        
        self.stats_collection.create_index([("stat_date", pymongo.DESCENDING)])
        self.stats_collection.create_index([("event_type", pymongo.ASCENDING)])
        
        self.trajectories_collection.create_index([("user_id", pymongo.ASCENDING)])
        self.trajectories_collection.create_index([("session_id", pymongo.ASCENDING)])
        
        self.profiles_collection.create_index([("user_id", pymongo.ASCENDING)], unique=True)
        
        self.relations_collection.create_index(
            [("source_event", pymongo.ASCENDING), ("target_event", pymongo.ASCENDING)]
        )
        
        self.abnormal_collection.create_index([("user_id", pymongo.ASCENDING)])
        self.abnormal_collection.create_index([("detected_at", pymongo.DESCENDING)])
    
    def close(self) -> None:
        if self._client:
            self._client.close()
            self._client = None
            self._db = None
            logger.info("MongoDB connection closed")
    
    def insert_event(self, event: BehaviorEvent) -> str:
        data = event.to_dict()
        self.events_collection.insert_one(data)
        logger.info(f"Inserted event: {event.event_id}")
        return event.event_id
    
    def insert_events(self, events: List[BehaviorEvent]) -> List[str]:
        if not events:
            return []
        
        data_list = [e.to_dict() for e in events]
        self.events_collection.insert_many(data_list)
        event_ids = [e.event_id for e in events]
        logger.info(f"Inserted {len(events)} events")
        return event_ids
    
    def find_events(
        self,
        query: Dict[str, Any],
        limit: int = 100,
        sort_by: str = "timestamp",
        sort_order: int = pymongo.DESCENDING
    ) -> List[BehaviorEvent]:
        cursor = self.events_collection.find(query).sort(sort_by, sort_order).limit(limit)
        return [BehaviorEvent.from_dict(doc) for doc in cursor]
    
    def find_event_by_id(self, event_id: str) -> Optional[BehaviorEvent]:
        doc = self.events_collection.find_one({"event_id": event_id})
        if doc:
            return BehaviorEvent.from_dict(doc)
        return None
    
    def count_events(self, query: Dict[str, Any]) -> int:
        return self.events_collection.count_documents(query)
    
    def aggregate_events(self, pipeline: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        return list(self.events_collection.aggregate(pipeline))
    
    def upsert_stat(self, stat: BehaviorStat) -> str:
        data = stat.to_dict()
        self.stats_collection.update_one(
            {
                "event_type": stat.event_type,
                "stat_date": stat.stat_date
            },
            {"$set": data},
            upsert=True
        )
        logger.info(f"Upserted stat: {stat.stat_id}")
        return stat.stat_id
    
    def find_stats(
        self,
        query: Dict[str, Any],
        limit: int = 100
    ) -> List[BehaviorStat]:
        cursor = self.stats_collection.find(query).sort("stat_date", pymongo.DESCENDING).limit(limit)
        return [BehaviorStat.from_dict(doc) for doc in cursor]
    
    def find_stat_by_id(self, stat_id: str) -> Optional[BehaviorStat]:
        doc = self.stats_collection.find_one({"stat_id": stat_id})
        if doc:
            return BehaviorStat.from_dict(doc)
        return None
    
    def upsert_trajectory(self, trajectory: UserTrajectory) -> str:
        data = trajectory.to_dict()
        self.trajectories_collection.update_one(
            {
                "user_id": trajectory.user_id,
                "session_id": trajectory.session_id
            },
            {"$set": data},
            upsert=True
        )
        logger.info(f"Upserted trajectory: {trajectory.trajectory_id}")
        return trajectory.trajectory_id
    
    def find_trajectories(
        self,
        query: Dict[str, Any],
        limit: int = 100
    ) -> List[UserTrajectory]:
        cursor = self.trajectories_collection.find(query).sort("created_at", pymongo.DESCENDING).limit(limit)
        return [UserTrajectory.from_dict(doc) for doc in cursor]
    
    def find_trajectory_by_session(self, user_id: str, session_id: str) -> Optional[UserTrajectory]:
        doc = self.trajectories_collection.find_one({
            "user_id": user_id,
            "session_id": session_id
        })
        if doc:
            return UserTrajectory.from_dict(doc)
        return None
    
    def upsert_profile(self, profile: UserProfile) -> str:
        data = profile.to_dict()
        self.profiles_collection.update_one(
            {"user_id": profile.user_id},
            {"$set": data},
            upsert=True
        )
        logger.info(f"Upserted profile: {profile.profile_id}")
        return profile.profile_id
    
    def find_profile_by_user_id(self, user_id: str) -> Optional[UserProfile]:
        doc = self.profiles_collection.find_one({"user_id": user_id})
        if doc:
            return UserProfile.from_dict(doc)
        return None
    
    def find_profiles(
        self,
        query: Dict[str, Any],
        limit: int = 100
    ) -> List[UserProfile]:
        cursor = self.profiles_collection.find(query).limit(limit)
        return [UserProfile.from_dict(doc) for doc in cursor]
    
    def upsert_relation(self, relation: EventRelation) -> str:
        data = relation.to_dict()
        self.relations_collection.update_one(
            {
                "source_event": relation.source_event,
                "target_event": relation.target_event,
                "analysis_date": relation.analysis_date
            },
            {"$set": data},
            upsert=True
        )
        logger.info(f"Upserted relation: {relation.relation_id}")
        return relation.relation_id
    
    def find_relations(
        self,
        query: Dict[str, Any],
        limit: int = 100
    ) -> List[EventRelation]:
        cursor = self.relations_collection.find(query).limit(limit)
        return [EventRelation.from_dict(doc) for doc in cursor]
    
    def insert_abnormal(self, abnormal: AbnormalBehavior) -> str:
        data = abnormal.to_dict()
        self.abnormal_collection.insert_one(data)
        logger.info(f"Inserted abnormal behavior: {abnormal.abnormal_id}")
        return abnormal.abnormal_id
    
    def find_abnormal(
        self,
        query: Dict[str, Any],
        limit: int = 100
    ) -> List[AbnormalBehavior]:
        cursor = self.abnormal_collection.find(query).sort("detected_at", pymongo.DESCENDING).limit(limit)
        return [AbnormalBehavior.from_dict(doc) for doc in cursor]
