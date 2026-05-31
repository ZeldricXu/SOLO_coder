#!/usr/bin/env python
from setuptools import setup, find_packages

with open("README.md", "r", encoding="utf-8") as fh:
    long_description = fh.read()

setup(
    name="blockchain-infra-platform",
    version="1.0.0",
    description="区块链基础设施平台 - 合约事件监听、Gas预估、ZKP验证等",
    long_description=long_description,
    long_description_content_type="text/markdown",
    author="Blockchain Infra Team",
    author_email="team@blockchain-infra.com",
    url="https://github.com/blockchain-infra/platform",
    packages=find_packages(where="src", exclude=["tests*", "docs*", "examples*"]),
    package_dir={"": "src"},
    include_package_data=True,
    package_data={"": ["*.yaml", "*.yml", "*.json"]},
    python_requires=">=3.10",
    install_requires=[
        "fastapi==0.109.0",
        "uvicorn[standard]==0.27.0",
        "pydantic==2.5.3",
        "pydantic-settings==2.1.0",
        "web3==6.15.1",
        "eth-utils==4.0.0",
        "eth-typing==3.5.2",
        "eth-account==0.11.0",
        "sqlalchemy==2.0.25",
        "alembic==1.13.1",
        "redis==5.0.1",
        "celery==5.3.6",
        "httpx==0.26.0",
        "aiohttp==3.9.1",
        "python-dotenv==1.0.0",
        "pyyaml==6.0.1",
        "python-multipart==0.0.6",
        "cryptography==41.0.7",
        "ecdsa==0.18.0",
        "hdwallet==2.2.1",
        "bip-utils==2.8.0",
    ],
    extras_require={
        "dev": [
            "black==23.12.1",
            "isort==5.13.2",
            "ruff==0.1.14",
            "flake8==6.1.0",
            "pylint==3.0.3",
            "mypy==1.8.0",
        ],
        "test": [
            "pytest==7.4.4",
            "pytest-asyncio==0.23.3",
            "pytest-cov==4.1.0",
            "pytest-mock==3.12.0",
        ],
        "prod": [
            "uvloop==0.19.0",
            "httptools==0.6.1",
            "python-json-logger==2.0.7",
            "prometheus-client==0.19.0",
        ],
    },
    entry_points={
        "console_scripts": [
            "bci-api=src.main:main",
        ],
    },
    classifiers=[
        "Development Status :: 5 - Production/Stable",
        "Intended Audience :: Developers",
        "License :: OSI Approved :: MIT License",
        "Operating System :: OS Independent",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Programming Language :: Python :: 3.12",
    ],
    keywords=["blockchain", "ethereum", "web3", "gas-estimation"],
    zip_safe=False,
)
