from setuptools import setup, find_packages

setup(
    name='pycfd',
    version='0.1.0',
    description='Open-source Computational Fluid Dynamics framework',
    author='CFD Lab',
    packages=find_packages(),
    install_requires=[
        'numpy>=1.21.0',
        'scipy>=1.7.0',
        'numba>=0.55.0',
        'h5py>=3.2.0',
        'matplotlib>=3.4.0',
    ],
    extras_require={
        'mesh': ['pygmsh>=7.1.0', 'meshio>=4.4.0'],
        'optimization': ['scikit-learn>=0.24.0', 'deap>=1.3.1'],
        'visualization': ['pyvista>=0.32.0', 'imageio>=2.9.0'],
    },
    python_requires='>=3.8',
)
