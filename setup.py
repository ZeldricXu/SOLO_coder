from setuptools import setup, find_packages

setup(
    name="genome-variant-pipeline",
    version="1.0.0",
    description="Genome Variant Detection and Annotation Pipeline",
    author="Bioinformatics Team",
    packages=find_packages(),
    python_requires=">=3.9",
    install_requires=[
        "SQLAlchemy>=2.0.0",
        "psycopg2-binary>=2.9.0",
        "celery>=5.3.0",
        "redis>=5.0.0",
        "minio>=7.2.0",
        "pydantic>=2.5.0",
        "pydantic-settings>=2.1.0",
        "reportlab>=4.0.0",
        "jinja2>=3.1.0",
        "tenacity>=8.2.0",
        "networkx>=3.2.0",
        "pandas>=2.1.0",
        "PyYAML>=6.0.0",
    ],
    entry_points={
        "console_scripts": [
            "gvp-pipeline=pipeline.cli:main",
            "gvp-admin=data_management.cli:main",
        ],
    },
)
