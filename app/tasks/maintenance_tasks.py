import os
import logging
from datetime import datetime, timedelta
from app import db, celery
from app.models import ShareLink, Report
from app import redis_client

logger = logging.getLogger(__name__)


@celery.task
def cleanup_expired_share_links():
    try:
        now = datetime.utcnow()
        expired = ShareLink.query.filter(
            (ShareLink.expires_at <= now) |
            (ShareLink.is_active == False)
        ).all()

        count = len(expired)
        for share in expired:
            db.session.delete(share)

        db.session.commit()
        logger.info(f"Cleaned up {count} expired share links")
        return {'deleted': count}
    except Exception as e:
        logger.error(f"Cleanup expired share links failed: {str(e)}")
        return {'error': str(e)}


@celery.task
def cleanup_old_reports(days=30):
    try:
        cutoff = datetime.utcnow() - timedelta(days=days)
        old_reports = Report.query.filter(Report.created_at <= cutoff).all()

        count = len(old_reports)
        for report in old_reports:
            if report.file_path and os.path.exists(report.file_path):
                try:
                    os.remove(report.file_path)
                except Exception:
                    pass
            db.session.delete(report)

        db.session.commit()
        logger.info(f"Cleaned up {count} old reports (older than {days} days)")
        return {'deleted': count}
    except Exception as e:
        logger.error(f"Cleanup old reports failed: {str(e)}")
        return {'error': str(e)}


@celery.task
def clear_cache(pattern=None):
    try:
        if not redis_client:
            return {'error': 'Redis not connected'}

        if pattern:
            keys = redis_client.keys(pattern)
            if keys:
                redis_client.delete(*keys)
            count = len(keys)
        else:
            count = redis_client.flushdb()

        logger.info(f"Cleared {count} cache keys")
        return {'cleared': count}
    except Exception as e:
        logger.error(f"Clear cache failed: {str(e)}")
        return {'error': str(e)}


@celery.task
def update_cache_health():
    try:
        if not redis_client:
            return {'status': 'disconnected'}

        info = redis_client.info()
        stats = {
            'connected_clients': info.get('connected_clients', 0),
            'used_memory_human': info.get('used_memory_human', '0'),
            'total_commands_processed': info.get('total_commands_processed', 0),
            'keyspace_hits': info.get('keyspace_hits', 0),
            'keyspace_misses': info.get('keyspace_misses', 0),
            'hit_rate': 0
        }

        hits = stats['keyspace_hits']
        misses = stats['keyspace_misses']
        if hits + misses > 0:
            stats['hit_rate'] = round(hits / (hits + misses) * 100, 2)

        logger.info(f"Cache health check: {stats['hit_rate']}% hit rate")
        return stats
    except Exception as e:
        logger.error(f"Cache health check failed: {str(e)}")
        return {'error': str(e)}
