import asyncio
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "src"))

from gateway.db.database import get_engine, Base
from gateway.db.models import Route, APIKey, APIKeyUsage, IdPConfig, TransformRule
from gateway.db.repository import RouteRepository, IdPConfigRepository, TransformRuleRepository
from gateway.config import get_settings
from gateway.logger import setup_logging, get_logger

setup_logging()
logger = get_logger("init_db")


async def create_tables():
    logger.info("Creating database tables...")
    engine = get_engine()
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    logger.info("All tables created successfully")


async def seed_sample_data():
    logger.info("Seeding sample data...")

    from sqlalchemy.ext.asyncio import AsyncSession
    from gateway.db.database import get_session_factory

    session_factory = get_session_factory()
    async with session_factory() as session:
        route_repo = RouteRepository(session)
        idp_repo = IdPConfigRepository(session)
        transform_repo = TransformRuleRepository(session)

        sample_routes = [
            {
                "name": "user-service-public",
                "description": "User service public APIs",
                "match_type": "prefix",
                "match_pattern": "/api/public/users",
                "targets": [{"url": "http://localhost:8081", "weight": 100, "timeout": 30.0}],
                "timeout": 30.0,
                "rate_limit_enabled": True,
                "rate_limit_requests": 100,
                "rate_limit_window": 60,
                "circuit_breaker_enabled": True,
                "is_public": True,
            },
            {
                "name": "order-service-internal",
                "description": "Order service internal APIs",
                "match_type": "prefix",
                "match_pattern": "/api/internal/orders",
                "targets": [{"url": "http://localhost:8082", "weight": 100, "timeout": 30.0}],
                "timeout": 30.0,
                "rate_limit_enabled": True,
                "rate_limit_requests": 1000,
                "rate_limit_window": 60,
                "circuit_breaker_enabled": True,
                "is_public": False,
            },
            {
                "name": "payment-service-regex",
                "description": "Payment service with regex matching",
                "match_type": "regex",
                "match_pattern": r"^/api/v\d+/payments/.*$",
                "targets": [{"url": "http://localhost:8083", "weight": 100, "timeout": 30.0}],
                "timeout": 30.0,
                "rate_limit_enabled": True,
                "rate_limit_requests": 50,
                "rate_limit_window": 60,
                "circuit_breaker_enabled": True,
                "is_public": False,
            },
            {
                "name": "search-service-weighted",
                "description": "Search service with weighted routing",
                "match_type": "weighted",
                "match_pattern": "/api/search",
                "targets": [
                    {"url": "http://localhost:8084", "weight": 70, "timeout": 30.0},
                    {"url": "http://localhost:8085", "weight": 30, "timeout": 30.0},
                ],
                "timeout": 30.0,
                "rate_limit_enabled": True,
                "rate_limit_requests": 200,
                "rate_limit_window": 60,
                "circuit_breaker_enabled": True,
                "is_public": True,
            },
        ]

        for route_data in sample_routes:
            existing = await route_repo.get_by_name(route_data["name"])
            if not existing:
                await route_repo.create(route_data)
                logger.info(f"Created route: {route_data['name']}")
            else:
                logger.info(f"Route already exists: {route_data['name']}")

        sample_idps = [
            {
                "name": "default",
                "type": "jwt",
                "config": {
                    "issuer": "https://idp.example.com",
                    "jwks_url": "https://idp.example.com/.well-known/jwks.json",
                    "client_id": "gateway",
                },
                "is_active": True,
            },
            {
                "name": "keycloak",
                "type": "oauth2",
                "plugin": "keycloak",
                "config": {
                    "issuer": "http://localhost:8080/realms/gateway",
                    "jwks_url": "http://localhost:8080/realms/gateway/protocol/openid-connect/certs",
                    "client_id": "gateway-client",
                    "introspection_url": "http://localhost:8080/realms/gateway/protocol/openid-connect/token/introspect",
                },
                "is_active": True,
            },
        ]

        for idp_data in sample_idps:
            existing = await idp_repo.get_by_name(idp_data["name"])
            if not existing:
                await idp_repo.create(idp_data)
                logger.info(f"Created IdP config: {idp_data['name']}")
            else:
                logger.info(f"IdP config already exists: {idp_data['name']}")

        sample_transforms = [
            {
                "name": "add-trace-id",
                "type": "request_header",
                "rule_type": "request_header",
                "config": {
                    "action": "add",
                    "header": "X-Trace-ID",
                    "value": "{request_id}",
                },
                "priority": 100,
                "is_global": True,
                "is_active": True,
            },
            {
                "name": "cors-headers",
                "type": "response_header",
                "rule_type": "response_header",
                "config": {
                    "action": "add",
                    "headers": {
                        "Access-Control-Allow-Origin": "*",
                        "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
                        "Access-Control-Allow-Headers": "Authorization, Content-Type, X-API-Key",
                    },
                },
                "priority": 50,
                "is_global": True,
                "is_active": True,
            },
        ]

        for transform_data in sample_transforms:
            existing = await transform_repo.get_by_name(transform_data["name"])
            if not existing:
                await transform_repo.create(transform_data)
                logger.info(f"Created transform rule: {transform_data['name']}")
            else:
                logger.info(f"Transform rule already exists: {transform_data['name']}")

        await session.commit()

    logger.info("Sample data seeding completed")


async def main():
    try:
        await create_tables()
        await seed_sample_data()
        logger.info("Database initialization completed successfully")
    except Exception as e:
        logger.error(f"Database initialization failed: {e}", exc_info=True)
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
