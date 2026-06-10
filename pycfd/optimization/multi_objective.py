import numpy as np
from typing import Callable, List, Tuple, Optional, Dict
import random
from .design_variables import ParameterSpace, DesignVariable

def dominates(f1: np.ndarray, f2: np.ndarray, minimize: bool = True) -> bool:
    f1 = np.asarray(f1, dtype=np.float64)
    f2 = np.asarray(f2, dtype=np.float64)
    if minimize:
        return np.all(f1 <= f2) and np.any(f1 < f2)
    else:
        return np.all(f1 >= f2) and np.any(f1 > f2)

def select_nondominated(population: List[Dict], minimize: bool = True) -> List[Dict]:
    nondominated = []
    for i, ind1 in enumerate(population):
        is_dominated = False
        for j, ind2 in enumerate(population):
            if i != j and dominates(ind2['fitness'], ind1['fitness'], minimize):
                is_dominated = True
                break
        if not is_dominated:
            nondominated.append(ind1)
    return nondominated

def fast_nondominated_sort(population: List[Dict], minimize: bool = True) -> List[List[Dict]]:
    n = len(population)
    domination_count = np.zeros(n, dtype=int)
    dominated_solutions = [[] for _ in range(n)]
    for i in range(n):
        for j in range(n):
            if i != j:
                if dominates(population[i]['fitness'], population[j]['fitness'], minimize):
                    dominated_solutions[i].append(j)
                elif dominates(population[j]['fitness'], population[i]['fitness'], minimize):
                    domination_count[i] += 1
    fronts = []
    current_front = [i for i in range(n) if domination_count[i] == 0]
    while current_front:
        fronts.append([population[i] for i in current_front])
        next_front = []
        for i in current_front:
            for j in dominated_solutions[i]:
                domination_count[j] -= 1
                if domination_count[j] == 0:
                    next_front.append(j)
        current_front = next_front
    return fronts

def compute_crowding_distance(front: List[Dict]) -> np.ndarray:
    n = len(front)
    if n == 0:
        return np.array([], dtype=np.float64)
    if n == 1:
        return np.array([float('inf')], dtype=np.float64)
    if n == 2:
        return np.array([float('inf'), float('inf')], dtype=np.float64)
    n_obj = len(front[0]['fitness'])
    distances = np.zeros(n, dtype=np.float64)
    for obj in range(n_obj):
        sorted_indices = sorted(range(n), key=lambda i: front[i]['fitness'][obj])
        distances[sorted_indices[0]] = float('inf')
        distances[sorted_indices[-1]] = float('inf')
        f_min = front[sorted_indices[0]]['fitness'][obj]
        f_max = front[sorted_indices[-1]]['fitness'][obj]
        if f_max - f_min < 1e-15:
            continue
        for i in range(1, n - 1):
            idx = sorted_indices[i]
            next_val = front[sorted_indices[i + 1]]['fitness'][obj]
            prev_val = front[sorted_indices[i - 1]]['fitness'][obj]
            distances[idx] += (next_val - prev_val) / (f_max - f_min)
    return distances

def compute_pareto_front(fitness: np.ndarray, minimize: bool = True) -> np.ndarray:
    n = fitness.shape[0]
    pareto_indices = []
    for i in range(n):
        is_pareto = True
        for j in range(n):
            if i != j and dominates(fitness[j], fitness[i], minimize):
                is_pareto = False
                break
        if is_pareto:
            pareto_indices.append(i)
    return fitness[pareto_indices]

class ParetoFront:
    def __init__(self, minimize: bool = True):
        self.minimize = minimize
        self.points = []
        self.fitness = []

    def add(self, x: np.ndarray, fitness: np.ndarray) -> None:
        fitness = np.asarray(fitness, dtype=np.float64)
        dominated_by_existing = False
        to_remove = []
        for i, f in enumerate(self.fitness):
            if dominates(f, fitness, self.minimize):
                dominated_by_existing = True
                break
            if dominates(fitness, f, self.minimize):
                to_remove.append(i)
        if not dominated_by_existing:
            for i in reversed(to_remove):
                del self.points[i]
                del self.fitness[i]
            self.points.append(x.copy())
            self.fitness.append(fitness.copy())

    def get_pareto_set(self) -> np.ndarray:
        return np.array(self.points)

    def get_pareto_front(self) -> np.ndarray:
        return np.array(self.fitness)

    def get_spread(self) -> float:
        if len(self.fitness) < 2:
            return 0.0
        fitness = np.array(self.fitness)
        min_f = np.min(fitness, axis=0)
        max_f = np.max(fitness, axis=0)
        range_f = max_f - min_f
        normalized = (fitness - min_f) / (range_f + 1e-15)
        distances = np.sqrt(np.sum((normalized[1:] - normalized[:-1]) ** 2, axis=1))
        return np.sum(distances)

    def get_hypervolume(self, reference_point: Optional[np.ndarray] = None) -> float:
        if len(self.fitness) == 0:
            return 0.0
        fitness = np.array(self.fitness)
        if reference_point is None:
            reference_point = np.max(fitness, axis=0) + 1.0
        if self.minimize:
            points = reference_point - fitness
        else:
            points = fitness - reference_point
        points = np.maximum(points, 0)
        return np.prod(np.mean(points, axis=0))

    def plot(self, ax=None, objective_names=None):
        import matplotlib.pyplot as plt
        fitness = np.array(self.fitness)
        if ax is None:
            fig, ax = plt.subplots()
        if fitness.shape[1] == 2:
            ax.scatter(fitness[:, 0], fitness[:, 1], 'b-', s=50, alpha=0.7)
            if objective_names:
                ax.set_xlabel(objective_names[0])
                ax.set_ylabel(objective_names[1])
            else:
                ax.set_xlabel('Objective 1')
                ax.set_ylabel('Objective 2')
        elif fitness.shape[1] == 3:
            from mpl_toolkits.mplot3d import Axes3D
            if not hasattr(ax, 'get_zlim'):
                fig = plt.gcf()
                ax = fig.add_subplot(111, projection='3d')
            ax.scatter(fitness[:, 0], fitness[:, 1], fitness[:, 2], 'b-', s=50, alpha=0.7)
            if objective_names:
                ax.set_xlabel(objective_names[0])
                ax.set_ylabel(objective_names[1])
                ax.set_zlabel(objective_names[2])
        ax.grid(True, alpha=0.3)
        return ax

class NSGAIISolver:
    def __init__(self, objective_func: Callable, variables: List[DesignVariable],
                 population_size: int = 100, n_objectives: int = 2,
                 crossover_prob: float = 0.9, mutation_prob: float = 0.1,
                 minimize: bool = True):
        self.objective_func = objective_func
        self.param_space = ParameterSpace(variables)
        self.pop_size = population_size
        self.n_obj = n_objectives
        self.crossover_prob = crossover_prob
        self.mutation_prob = mutation_prob
        self.minimize = minimize
        self.population = []
        self.offspring = []
        self.pareto_front = ParetoFront(minimize)
        self.history = []

    def _create_individual(self, x: np.ndarray) -> Dict:
        return {
            'x': x.copy(),
            'x_dict': self.param_space.from_array(x.reshape(1, -1)),
            'fitness': None,
            'rank': None,
            'crowding_distance': None
        }

    def _initialize_population(self) -> None:
        self.population = []
        samples = self.param_space.sample(self.pop_size)
        for i in range(self.pop_size):
            sample_dict = {name: val[i] if hasattr(val, '__len__') and not isinstance(val, str) else val 
                          for name, val in samples.items()}
            x = self.param_space.to_array({k: np.array([v]) for k, v in sample_dict.items()})
            self.population.append(self._create_individual(x.flatten()))

    def _evaluate_population(self, population: List[Dict]) -> None:
        for ind in population:
            if ind['fitness'] is None:
                sample_dict = {k: v[0] if hasattr(v, '__len__') and not isinstance(v, str) else v 
                              for k, v in ind['x_dict'].items()}
                fitness = self.objective_func(sample_dict)
                ind['fitness'] = np.asarray(fitness, dtype=np.float64)
                self.pareto_front.add(ind['x'], ind['fitness'])

    def _tournament_selection(self, population: List[Dict], k: int = 2) -> Dict:
        candidates = random.sample(population, k)
        candidates.sort(key=lambda ind: (ind['rank'], -ind['crowding_distance']))
        return candidates[0]

    def _crossover(self, parent1: Dict, parent2: Dict) -> Dict:
        if random.random() < self.crossover_prob:
            child_x = parent1['x'].copy()
            for i in range(len(child_x)):
                if random.random() < 0.5:
                    child_x[i] = parent2['x'][i]
            child_x = np.clip(child_x, 0.0, 1.0)
            return self._create_individual(child_x)
        else:
            return self._create_individual(parent1['x'].copy())

    def _mutate(self, individual: Dict) -> Dict:
        if random.random() < self.mutation_prob:
            x = individual['x'].copy()
            for i in range(len(x)):
                if random.random() < 0.1:
                    x[i] = np.clip(x[i] + np.random.normal(0, 0.1), 0.0, 1.0)
            return self._create_individual(x)
        else:
            return individual

    def _create_offspring(self) -> List[Dict]:
        offspring = []
        while len(offspring) < self.pop_size:
            parent1 = self._tournament_selection(self.population)
            parent2 = self._tournament_selection(self.population)
            child = self._crossover(parent1, parent2)
            child = self._mutate(child)
            offspring.append(child)
        return offspring

    def _assign_ranks_and_distances(self, population: List[Dict]) -> None:
        fronts = fast_nondominated_sort(population, self.minimize)
        for rank, front in enumerate(fronts):
            distances = compute_crowding_distance(front)
            for i, ind in enumerate(front):
                ind['rank'] = rank
                ind['crowding_distance'] = distances[i]

    def _select_next_generation(self, combined: List[Dict]) -> List[Dict]:
        self._assign_ranks_and_distances(combined)
        combined.sort(key=lambda ind: (ind['rank'], -ind['crowding_distance']))
        return combined[:self.pop_size]

    def optimize(self, n_generations: int = 50, callback: Optional[Callable] = None) -> Dict:
        if not self.population:
            self._initialize_population()
        self._evaluate_population(self.population)
        self._assign_ranks_and_distances(self.population)
        for gen in range(n_generations):
            self.offspring = self._create_offspring()
            self._evaluate_population(self.offspring)
            combined = self.population + self.offspring
            self.population = self._select_next_generation(combined)
            pareto_size = len(select_nondominated(self.population, self.minimize))
            self.history.append({
                'generation': gen,
                'pareto_size': pareto_size,
                'hypervolume': self.pareto_front.get_hypervolume()
            })
            if callback is not None:
                callback(gen, self.population, self.pareto_front)
            print(f"Generation {gen+1}/{n_generations}: Pareto front size = {pareto_size}")
        pareto_set = self.pareto_front.get_pareto_set()
        pareto_front = self.pareto_front.get_pareto_front()
        
        pareto_solutions = []
        for i in range(len(pareto_set)):
            x_array = pareto_set[i].reshape(1, -1)
            x_dict = self.param_space.from_array(x_array)
            solution = {}
            for k, v in x_dict.items():
                solution[k] = v[0] if hasattr(v, '__len__') and not isinstance(v, str) else v
            pareto_solutions.append(solution)
        
        return {
            'pareto_set': pareto_set,
            'pareto_front': pareto_front,
            'pareto_solutions': pareto_solutions,
            'population': self.population,
            'history': self.history,
            'generations': self.history,
            'n_generations': n_generations
        }

    def get_optimization_history(self) -> List[Dict]:
        return self.history

    def plot_pareto_front(self, ax=None, objective_names=None):
        return self.pareto_front.plot(ax, objective_names)

    def plot_convergence(self, ax=None):
        import matplotlib.pyplot as plt
        if ax is None:
            fig, ax = plt.subplots()
        hv = [h['hypervolume'] for h in self.history]
        pf_size = [h['pareto_size'] for h in self.history]
        ax2 = ax.twinx()
        ax.plot(hv, 'b-', label='Hypervolume', linewidth=2)
        ax2.plot(pf_size, 'r--', label='Pareto size', linewidth=2)
        ax.set_xlabel('Generation')
        ax.set_ylabel('Hypervolume', color='b')
        ax2.set_ylabel('Pareto front size', color='r')
        ax.grid(True, alpha=0.3)
        lines1, labels1 = ax.get_legend_handles_labels()
        lines2, labels2 = ax2.get_legend_handles_labels()
        ax.legend(lines1 + lines2, labels1 + labels2, loc='best')
        return ax
