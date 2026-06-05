import numpy as np
from scipy.linalg import cholesky, cho_solve
from scipy.optimize import minimize
from scipy.stats import norm
from typing import Callable, List, Tuple, Optional, Dict
from .design_variables import ParameterSpace, DesignVariable

class GaussianProcess:
    def __init__(self, kernel: str = 'rbf', noise: float = 1e-5, length_scale: float = 1.0):
        self.kernel_type = kernel
        self.noise = noise
        self.length_scale = length_scale
        self.signal_variance = 1.0
        self.X_train = None
        self.y_train = None
        self.L = None
        self.alpha = None

    def kernel(self, X1: np.ndarray, X2: np.ndarray) -> np.ndarray:
        n1 = X1.shape[0]
        n2 = X2.shape[0]
        K = np.zeros((n1, n2), dtype=np.float64)
        for i in range(n1):
            for j in range(n2):
                diff = X1[i] - X2[j]
                if self.kernel_type == 'rbf':
                    K[i, j] = self.signal_variance * np.exp(-0.5 * np.sum(diff ** 2) / self.length_scale ** 2)
                elif self.kernel_type == 'matern':
                    d = np.sqrt(np.sum(diff ** 2) / self.length_scale ** 2)
                    if d < 1e-15:
                        K[i, j] = self.signal_variance
                    else:
                        K[i, j] = self.signal_variance * (1 + np.sqrt(3) * d) * np.exp(-np.sqrt(3) * d)
                elif self.kernel_type == 'rational_quadratic':
                    d = np.sum(diff ** 2) / (2 * self.length_scale ** 2)
                    K[i, j] = self.signal_variance * (1 + d) ** (-1)
        return K

    def fit(self, X: np.ndarray, y: np.ndarray) -> None:
        self.X_train = np.asarray(X, dtype=np.float64)
        self.y_train = np.asarray(y, dtype=np.float64)
        n = self.X_train.shape[0]
        K = self.kernel(self.X_train, self.X_train) + self.noise * np.eye(n)
        try:
            self.L = cholesky(K, lower=True)
        except np.linalg.LinAlgError:
            K += 1e-8 * np.eye(n)
            self.L = cholesky(K, lower=True)
        self.alpha = cho_solve((self.L, True), self.y_train)

    def predict(self, X: np.ndarray, return_std: bool = False) -> Tuple[np.ndarray, Optional[np.ndarray]]:
        if self.X_train is None:
            mu = np.zeros(X.shape[0], dtype=np.float64)
            if return_std:
                std = self.signal_variance * np.ones(X.shape[0], dtype=np.float64)
                return mu, std
            return mu
        K_s = self.kernel(self.X_train, X)
        mu = K_s.T @ self.alpha
        if return_std:
            K_ss = np.diag(self.kernel(X, X))
            v = cho_solve((self.L, True), K_s)
            std = np.sqrt(np.maximum(0, K_ss - np.sum(K_s * v, axis=0)))
            return mu, std
        return mu

    def optimize_hyperparameters(self, bounds: Tuple[float, float] = (1e-2, 1e2)) -> None:
        def log_marginal_likelihood(params):
            self.length_scale = np.exp(params[0])
            self.signal_variance = np.exp(params[1])
            try:
                self.fit(self.X_train, self.y_train)
                n = self.X_train.shape[0]
                K = self.kernel(self.X_train, self.X_train) + self.noise * np.eye(n)
                L = cholesky(K, lower=True)
                alpha = cho_solve((L, True), self.y_train)
                lml = -0.5 * self.y_train @ alpha - np.sum(np.log(np.diag(L))) - n/2 * np.log(2 * np.pi)
                return -lml
            except:
                return 1e10
        result = minimize(
            log_marginal_likelihood,
            np.array([np.log(self.length_scale), np.log(self.signal_variance)]),
            bounds=[(np.log(bounds[0]), np.log(bounds[1]))] * 2,
            method='L-BFGS-B'
        )
        self.length_scale = np.exp(result.x[0])
        self.signal_variance = np.exp(result.x[1])
        self.fit(self.X_train, self.y_train)

class AcquisitionFunction:
    def __init__(self, kind: str = 'ei', xi: float = 0.01):
        self.kind = kind
        self.xi = xi
        self.y_best = None

    def update(self, y_best: float) -> None:
        self.y_best = y_best

    def evaluate(self, mu: np.ndarray, sigma: np.ndarray) -> np.ndarray:
        if self.kind == 'ei':
            return ExpectedImprovement(mu, sigma, self.y_best, self.xi)
        elif self.kind == 'pi':
            return ProbabilityOfImprovement(mu, sigma, self.y_best, self.xi)
        elif self.kind == 'ucb':
            return UpperConfidenceBound(mu, sigma, self.xi)
        else:
            return -mu

def ExpectedImprovement(mu: np.ndarray, sigma: np.ndarray, y_best: float, xi: float = 0.01) -> np.ndarray:
    sigma = np.maximum(sigma, 1e-9)
    z = (mu - y_best - xi) / sigma
    ei = (mu - y_best - xi) * norm.cdf(z) + sigma * norm.pdf(z)
    return ei

def ProbabilityOfImprovement(mu: np.ndarray, sigma: np.ndarray, y_best: float, xi: float = 0.01) -> np.ndarray:
    sigma = np.maximum(sigma, 1e-9)
    z = (mu - y_best - xi) / sigma
    return norm.cdf(z)

def UpperConfidenceBound(mu: np.ndarray, sigma: np.ndarray, kappa: float = 2.0) -> np.ndarray:
    return mu + kappa * sigma

class BayesianOptimizer:
    def __init__(self, objective_func: Callable, variables: List[DesignVariable],
                 n_initial: int = 10, acq_func: str = 'ei', maximize: bool = False):
        self.objective_func = objective_func
        self.param_space = ParameterSpace(variables)
        self.n_initial = n_initial
        self.maximize = maximize
        self.gp = GaussianProcess(kernel='rbf')
        self.acquisition = AcquisitionFunction(kind=acq_func)
        self.X_samples = None
        self.y_samples = None
        self.sample_dicts = []
        self.results = []
        self.best_x = None
        self.best_y = None if maximize else float('inf')

    def _objective_wrapper(self, sample_dict: dict) -> float:
        y = self.objective_func(sample_dict)
        if self.maximize:
            return y
        return -y

    def _sample_initial(self) -> None:
        initial_samples = self.param_space.sample(self.n_initial)
        for i in range(self.n_initial):
            sample_dict = {name: val[i] if hasattr(val, '__len__') and not isinstance(val, str) else val 
                          for name, val in initial_samples.items()}
            self.sample_dicts.append(sample_dict)
            y = self._objective_wrapper(sample_dict)
            self.results.append((sample_dict, y))
            if self.X_samples is None:
                self.X_samples = self.param_space.to_array({k: np.array([v]) for k, v in sample_dict.items()})
                self.y_samples = np.array([y])
            else:
                x_new = self.param_space.to_array({k: np.array([v]) for k, v in sample_dict.items()})
                self.X_samples = np.vstack([self.X_samples, x_new])
                self.y_samples = np.append(self.y_samples, y)
            self._update_best(sample_dict, y)

    def _update_best(self, sample_dict: dict, y: float) -> None:
        if self.maximize:
            if y > self.best_y:
                self.best_y = y
                self.best_x = sample_dict
        else:
            if y < self.best_y:
                self.best_y = y
                self.best_x = sample_dict

    def optimize(self, n_iterations: int = 50, n_restarts: int = 5) -> Dict:
        if self.X_samples is None:
            self._sample_initial()
        bounds = self.param_space.bounds_array()
        for it in range(n_iterations):
            self.gp.fit(self.X_samples, self.y_samples)
            self.acquisition.update(np.max(self.y_samples))
            def neg_acq(x):
                mu, sigma = self.gp.predict(x.reshape(1, -1), return_std=True)
                return -self.acquisition.evaluate(mu, sigma)[0]
            best_val = float('inf')
            best_x = None
            for _ in range(n_restarts):
                x0 = np.random.uniform(0, 1, self.param_space.n_dim)
                result = minimize(neg_acq, x0, bounds=bounds, method='L-BFGS-B')
                if result.fun < best_val:
                    best_val = result.fun
                    best_x = result.x
            new_x = best_x.reshape(1, -1)
            new_sample = self.param_space.from_array(new_x)
            sample_dict = {k: v[0] if hasattr(v, '__len__') and not isinstance(v, str) else v 
                          for k, v in new_sample.items()}
            self.sample_dicts.append(sample_dict)
            new_y = self._objective_wrapper(sample_dict)
            self.results.append((sample_dict, new_y))
            self.X_samples = np.vstack([self.X_samples, new_x])
            self.y_samples = np.append(self.y_samples, new_y)
            self._update_best(sample_dict, new_y)
            print(f"Iteration {it+1}/{n_iterations}: Best y = {self.best_y:.4f}")
        return {
            'best_x': self.best_x,
            'best_y': self.best_y,
            'X': self.X_samples,
            'y': self.y_samples,
            'results': self.results
        }

    def get_optimization_history(self) -> Tuple[np.ndarray, np.ndarray]:
        return self.X_samples, self.y_samples

    def plot_convergence(self, ax=None):
        import matplotlib.pyplot as plt
        if ax is None:
            fig, ax = plt.subplots()
        y_history = np.array([res[1] for res in self.results])
        if self.maximize:
            y_cumulative = np.maximum.accumulate(y_history)
        else:
            y_cumulative = np.minimum.accumulate(-y_history)
            y_history = -y_history
        ax.plot(y_history, 'o', label='Samples', alpha=0.6)
        ax.plot(y_cumulative, '-', label='Best found', linewidth=2)
        ax.set_xlabel('Iteration')
        ax.set_ylabel('Objective value')
        ax.legend()
        ax.grid(True, alpha=0.3)
        return ax
