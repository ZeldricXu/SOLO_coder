from setuptools import setup, find_packages

setup(
    name='devkit',
    version='1.0.0',
    description='Developer Command Line Toolkit',
    author='DevKit Team',
    packages=find_packages(),
    include_package_data=True,
    package_data={
        'devkit': ['data/*.txt'],
    },
    install_requires=[
        'click>=8.0.0',
        'PyYAML>=6.0',
        'toml>=0.10.2',
        'colorama>=0.4.6',
        'Pygments>=2.15.0',
        'requests>=2.31.0',
        'chardet>=5.2.0',
        'python-dateutil>=2.8.2',
        'croniter>=1.4.1',
        'bcrypt>=4.0.1',
        'cryptography>=41.0.0',
        'PyJWT>=2.8.0',
        'dnspython>=2.4.0',
        'websockets>=12.0.0',
        'gitpython>=3.1.40',
        'jsondiff>=2.0.0',
        'pytz>=2023.3',
        'Jinja2>=3.1.0',
        'pymysql>=1.1.0',
        'psycopg2-binary>=2.9.0',
        'rich>=13.0.0',
        'psutil>=5.9.0',
    ],
    entry_points='''
        [console_scripts]
        devkit=devkit.cli:cli
    ''',
    classifiers=[
        'Environment :: Console',
        'Intended Audience :: Developers',
        'License :: OSI Approved :: MIT License',
        'Operating System :: OS Independent',
        'Programming Language :: Python :: 3',
        'Programming Language :: Python :: 3.8',
        'Programming Language :: Python :: 3.9',
        'Programming Language :: Python :: 3.10',
        'Programming Language :: Python :: 3.11',
    ],
    python_requires='>=3.8',
)
