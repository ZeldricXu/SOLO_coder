from setuptools import setup, find_packages

setup(
    name="platform-engineer",
    version="1.0.0",
    package_dir={"": "src"},
    packages=find_packages(where="src"),
    include_package_data=True,
    zip_safe=False,
)
