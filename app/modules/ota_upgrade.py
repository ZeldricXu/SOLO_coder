import hashlib
import os
import difflib
from datetime import datetime
from typing import Any, Dict, List, Optional
from sqlalchemy import select, and_, update
from sqlalchemy.ext.asyncio import AsyncSession
from app.models import Firmware, OTACampaign, OTAStatus
from app.config import settings
from app.logger import logger


class OTAError(Exception):
    pass


class OTAManager:
    def __init__(self, db: AsyncSession):
        self.db = db
    
    async def create_firmware(
        self,
        version: str,
        device_model: str,
        file_path: str,
        release_notes: str = "",
        diff_from_version: str = None
    ) -> Firmware:
        if not os.path.exists(file_path):
            raise OTAError(f"Firmware file not found: {file_path}")
        
        file_size = os.path.getsize(file_path)
        checksum = self._calculate_checksum(file_path)
        
        firmware = Firmware(
            version=version,
            device_model=device_model,
            file_path=file_path,
            file_size=file_size,
            checksum=checksum,
            release_notes=release_notes,
            diff_from_version=diff_from_version
        )
        
        if diff_from_version:
            firmware.diff_file_path = await self._generate_diff_package(
                device_model,
                diff_from_version,
                version,
                file_path
            )
        
        self.db.add(firmware)
        await self.db.flush()
        
        logger.info("Created firmware", version=version, device_model=device_model)
        return firmware
    
    async def get_firmware(self, firmware_id: str) -> Optional[Firmware]:
        stmt = select(Firmware).where(Firmware.id == firmware_id)
        result = await self.db.execute(stmt)
        return result.scalar_one_or_none()
    
    async def get_latest_firmware(self, device_model: str) -> Optional[Firmware]:
        stmt = select(Firmware).where(
            and_(
                Firmware.device_model == device_model,
                Firmware.is_enabled == True
            )
        ).order_by(Firmware.created_at.desc()).limit(1)
        result = await self.db.execute(stmt)
        return result.scalar_one_or_none()
    
    async def create_campaign(
        self,
        firmware_id: str,
        name: str,
        device_ids: List[str],
        grayscale_percent: int = 100,
        auto_rollback: bool = True
    ) -> OTACampaign:
        firmware = await self.get_firmware(firmware_id)
        if not firmware:
            raise OTAError(f"Firmware not found: {firmware_id}")
        
        campaign = OTACampaign(
            firmware_id=firmware_id,
            name=name,
            status="pending",
            grayscale_percent=grayscale_percent,
            total_devices=len(device_ids),
            auto_rollback=auto_rollback
        )
        self.db.add(campaign)
        await self.db.flush()
        
        for device_id in device_ids:
            ota_status = OTAStatus(
                campaign_id=campaign.id,
                device_id=device_id,
                status="pending",
                target_version=firmware.version
            )
            self.db.add(ota_status)
        
        await self.db.flush()
        logger.info("Created OTA campaign", campaign_id=campaign.id, name=name, total_devices=len(device_ids))
        return campaign
    
    async def start_campaign(self, campaign_id: str) -> OTACampaign:
        stmt = select(OTACampaign).where(OTACampaign.id == campaign_id)
        result = await self.db.execute(stmt)
        campaign = result.scalar_one_or_none()
        
        if not campaign:
            raise OTAError(f"Campaign not found: {campaign_id}")
        
        if campaign.status != "pending":
            raise OTAError(f"Campaign is not in pending state: {campaign.status}")
        
        campaign.status = "running"
        campaign.started_at = datetime.utcnow()
        await self.db.flush()
        
        await self._process_next_batch(campaign)
        logger.info("Started OTA campaign", campaign_id=campaign_id)
        return campaign
    
    async def update_device_status(
        self,
        campaign_id: str,
        device_id: str,
        status: str,
        error_message: str = None,
        current_version: str = None
    ) -> OTAStatus:
        stmt = select(OTAStatus).where(
            and_(
                OTAStatus.campaign_id == campaign_id,
                OTAStatus.device_id == device_id
            )
        )
        result = await self.db.execute(stmt)
        ota_status = result.scalar_one_or_none()
        
        if not ota_status:
            raise OTAError(f"OTA status not found for device {device_id} in campaign {campaign_id}")
        
        ota_status.status = status
        if current_version:
            ota_status.current_version = current_version
        
        if status == "in_progress" and ota_status.started_at is None:
            ota_status.started_at = datetime.utcnow()
        
        if status in ["success", "failed", "rolled_back"]:
            ota_status.completed_at = datetime.utcnow()
            if status == "success":
                ota_status.current_version = ota_status.target_version
            if error_message:
                ota_status.last_error = error_message
        
        await self.db.flush()
        await self._update_campaign_progress(campaign_id)
        
        logger.info("Updated device OTA status", device_id=device_id, status=status)
        return ota_status
    
    async def get_campaign_status(self, campaign_id: str) -> Dict[str, Any]:
        stmt = select(OTACampaign).where(OTACampaign.id == campaign_id)
        result = await self.db.execute(stmt)
        campaign = result.scalar_one_or_none()
        
        if not campaign:
            raise OTAError(f"Campaign not found: {campaign_id}")
        
        devices_stmt = select(OTAStatus).where(OTAStatus.campaign_id == campaign_id)
        devices_result = await self.db.execute(devices_stmt)
        devices = devices_result.scalars().all()
        
        status_counts = {}
        for d in devices:
            status_counts[d.status] = status_counts.get(d.status, 0) + 1
        
        return {
            "campaign_id": campaign.id,
            "name": campaign.name,
            "status": campaign.status,
            "current_batch": campaign.current_batch,
            "total_devices": campaign.total_devices,
            "success_count": campaign.success_count,
            "failed_count": campaign.failed_count,
            "status_breakdown": status_counts,
            "started_at": campaign.started_at.isoformat() if campaign.started_at else None,
            "completed_at": campaign.completed_at.isoformat() if campaign.completed_at else None
        }
    
    async def rollback_campaign(self, campaign_id: str) -> OTACampaign:
        stmt = select(OTACampaign).where(OTACampaign.id == campaign_id)
        result = await self.db.execute(stmt)
        campaign = result.scalar_one_or_none()
        
        if not campaign:
            raise OTAError(f"Campaign not found: {campaign_id}")
        
        campaign.status = "rolling_back"
        await self.db.flush()
        
        devices_stmt = select(OTAStatus).where(
            and_(
                OTAStatus.campaign_id == campaign_id,
                OTAStatus.status == "success"
            )
        )
        devices_result = await self.db.execute(devices_stmt)
        devices = devices_result.scalars().all()
        
        for device in devices:
            device.status = "rolling_back"
        
        await self.db.flush()
        logger.info("Initiated rollback for campaign", campaign_id=campaign_id)
        return campaign
    
    async def list_firmwares(self, device_model: str = None, limit: int = 100) -> List[Firmware]:
        conditions = []
        if device_model:
            conditions.append(Firmware.device_model == device_model)
        
        stmt = select(Firmware).where(
            and_(*conditions) if conditions else True
        ).order_by(Firmware.created_at.desc()).limit(limit)
        
        result = await self.db.execute(stmt)
        return result.scalars().all()
    
    async def list_campaigns(self, status: str = None, limit: int = 100) -> List[OTACampaign]:
        conditions = []
        if status:
            conditions.append(OTACampaign.status == status)
        
        stmt = select(OTACampaign).where(
            and_(*conditions) if conditions else True
        ).order_by(OTACampaign.created_at.desc()).limit(limit)
        
        result = await self.db.execute(stmt)
        return result.scalars().all()
    
    async def _generate_diff_package(
        self,
        device_model: str,
        from_version: str,
        to_version: str,
        new_file_path: str
    ) -> Optional[str]:
        base_firmware_stmt = select(Firmware).where(
            and_(
                Firmware.device_model == device_model,
                Firmware.version == from_version
            )
        )
        result = await self.db.execute(base_firmware_stmt)
        base_firmware = result.scalar_one_or_none()
        
        if not base_firmware or not os.path.exists(base_firmware.file_path):
            return None
        
        firmware_path = settings.FIRMWARE_PATH
        os.makedirs(firmware_path, exist_ok=True)
        
        diff_filename = f"diff_{device_model}_{from_version}_to_{to_version}.bin"
        diff_path = os.path.join(firmware_path, diff_filename)
        
        with open(base_firmware.file_path, 'rb') as f1:
            with open(new_file_path, 'rb') as f2:
                with open(diff_path, 'wb') as df:
                    base_data = f1.read()
                    new_data = f2.read()
                    df.write(new_data)
        
        return diff_path
    
    async def _process_next_batch(self, campaign: OTACampaign):
        batch_size = settings.OTA_GRAYSCALE_BATCH_SIZE
        if campaign.grayscale_percent < 100:
            batch_size = int(campaign.total_devices * campaign.grayscale_percent / 100)
        
        stmt = select(OTAStatus).where(
            and_(
                OTAStatus.campaign_id == campaign.id,
                OTAStatus.status == "pending"
            )
        ).limit(batch_size)
        result = await self.db.execute(stmt)
        devices = result.scalars().all()
        
        for device in devices:
            device.status = "queued"
        
        campaign.current_batch += 1
        await self.db.flush()
    
    async def _update_campaign_progress(self, campaign_id: str):
        devices_stmt = select(OTAStatus).where(OTAStatus.campaign_id == campaign_id)
        result = await self.db.execute(devices_stmt)
        devices = result.scalars().all()
        
        success_count = sum(1 for d in devices if d.status == "success")
        failed_count = sum(1 for d in devices if d.status == "failed")
        pending_count = sum(1 for d in devices if d.status in ["pending", "queued", "in_progress"])
        
        campaign_stmt = select(OTACampaign).where(OTACampaign.id == campaign_id)
        result2 = await self.db.execute(campaign_stmt)
        campaign = result2.scalar_one_or_none()
        
        if campaign:
            campaign.success_count = success_count
            campaign.failed_count = failed_count
            
            if pending_count == 0 and campaign.status == "running":
                campaign.status = "completed"
                campaign.completed_at = datetime.utcnow()
            
            if campaign.auto_rollback and failed_count > 0:
                if failed_count / campaign.total_devices > 0.1:
                    campaign.status = "failed"
                    await self.rollback_campaign(campaign_id)
            
            await self.db.flush()
    
    def _calculate_checksum(self, file_path: str) -> str:
        sha256 = hashlib.sha256()
        with open(file_path, 'rb') as f:
            for chunk in iter(lambda: f.read(8192), b''):
                sha256.update(chunk)
        return sha256.hexdigest()
