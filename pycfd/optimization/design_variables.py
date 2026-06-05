import numpy as np
from typing import Union, List, Tuple, Any

class DesignVariable:
    def __init__(self, name: str, bounds: Tuple[Any, Any]):
        self.name = name
        self.bounds = bounds
        self.type = 'continuous'

    def sample(self, n_samples: int = 1) -> np.ndarray:
        raise NotImplementedError

    def rescale(self, x: np.ndarray) -> np.ndarray:
        raise NotImplementedError

    def check_bounds(self, x: np.ndarray) -> bool:
        raise NotImplementedError

class ContinuousVariable(DesignVariable):
    def __init__(self, name: str, lower: float, upper: float, log_scale: bool = False):
        super().__init__(name, (lower, upper))
        self.type = 'continuous'
        self.lower = float(lower)
        self.upper = float(upper)
        self.log_scale = log_scale

    def sample(self, n_samples: int = 1) -> np.ndarray:
        if self.log_scale:
            log_lower = np.log10(self.lower)
            log_upper = np.log10(self.upper)
            return 10 ** (np.random.uniform(log_lower, log_upper, n_samples))
        return np.random.uniform(self.lower, self.upper, n_samples)

    def rescale(self, x: np.ndarray) -> np.ndarray:
        x = np.asarray(x, dtype=np.float64)
        if self.log_scale:
            log_x = np.log10(x)
            log_lower = np.log10(self.lower)
            log_upper = np.log10(self.upper)
            return (log_x - log_lower) / (log_upper - log_lower)
        return (x - self.lower) / (self.upper - self.lower)

    def unscale(self, x_scaled: np.ndarray) -> np.ndarray:
        x_scaled = np.clip(x_scaled, 0.0, 1.0)
        if self.log_scale:
            log_lower = np.log10(self.lower)
            log_upper = np.log10(self.upper)
            return 10 ** (log_lower + x_scaled * (log_upper - log_lower))
        return self.lower + x_scaled * (self.upper - self.lower)

    def check_bounds(self, x: np.ndarray) -> bool:
        return np.all(x >= self.lower) and np.all(x <= self.upper)

class DiscreteVariable(DesignVariable):
    def __init__(self, name: str, values: List[int]):
        super().__init__(name, (min(values), max(values)))
        self.type = 'discrete'
        self.values = np.asarray(values, dtype=int)
        self.n_values = len(values)

    def sample(self, n_samples: int = 1) -> np.ndarray:
        idx = np.random.randint(0, self.n_values, n_samples)
        return self.values[idx]

    def rescale(self, x: np.ndarray) -> np.ndarray:
        x = np.asarray(x, dtype=int)
        idx = np.searchsorted(self.values, x)
        return idx / (self.n_values - 1)

    def unscale(self, x_scaled: np.ndarray) -> np.ndarray:
        x_scaled = np.clip(x_scaled, 0.0, 1.0)
        idx = np.round(x_scaled * (self.n_values - 1)).astype(int)
        return self.values[idx]

    def check_bounds(self, x: np.ndarray) -> bool:
        return np.all(np.isin(x, self.values))

class CategoricalVariable(DesignVariable):
    def __init__(self, name: str, categories: List[str]):
        super().__init__(name, (0, len(categories) - 1))
        self.type = 'categorical'
        self.categories = categories
        self.n_categories = len(categories)

    def sample(self, n_samples: int = 1) -> np.ndarray:
        idx = np.random.randint(0, self.n_categories, n_samples)
        return np.array([self.categories[i] for i in idx], dtype=object)

    def rescale(self, x: np.ndarray) -> np.ndarray:
        x = np.asarray(x, dtype=object)
        idx = np.array([self.categories.index(val) for val in x])
        return idx / (self.n_categories - 1)

    def unscale(self, x_scaled: np.ndarray) -> np.ndarray:
        x_scaled = np.clip(x_scaled, 0.0, 1.0)
        idx = np.round(x_scaled * (self.n_categories - 1)).astype(int)
        return np.array([self.categories[i] for i in idx], dtype=object)

    def check_bounds(self, x: np.ndarray) -> bool:
        return np.all(np.isin(x, self.categories))

class ParameterSpace:
    def __init__(self, variables: List[DesignVariable]):
        self.variables = variables
        self.n_dim = len(variables)
        self.names = [v.name for v in variables]
        self._continuous_mask = np.array([v.type == 'continuous' for v in variables])
        self._discrete_mask = np.array([v.type == 'discrete' for v in variables])
        self._categorical_mask = np.array([v.type == 'categorical' for v in variables])

    def sample(self, n_samples: int = 1) -> dict:
        samples = {}
        for var in self.variables:
            samples[var.name] = var.sample(n_samples)
        return samples

    def to_array(self, sample_dict: dict) -> np.ndarray:
        result = []
        for var in self.variables:
            result.append(var.rescale(sample_dict[var.name]))
        return np.asarray(result).T

    def from_array(self, x: np.ndarray) -> dict:
        result = {}
        for i, var in enumerate(self.variables):
            result[var.name] = var.unscale(x[:, i])
        return result

    def bounds_array(self) -> np.ndarray:
        return np.array([[0.0, 1.0] for _ in range(self.n_dim)], dtype=np.float64)

    def check_bounds(self, sample_dict: dict) -> bool:
        return all(var.check_bounds(sample_dict[var.name]) for var in self.variables)
