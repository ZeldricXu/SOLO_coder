import sys
import os
import random
import pytest
from collections import deque

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from server.map_generator import MapGenerator, BSPNode, Room, TileType


@pytest.fixture
def generated_map():
    random.seed(42)
    gen = MapGenerator()
    gen.generate(60, 40, floor_depth=1)
    random.seed()
    return gen


@pytest.fixture
def generated_map_large():
    random.seed(99)
    gen = MapGenerator()
    gen.generate(80, 50, floor_depth=3)
    random.seed()
    return gen


class TestBSPLeafNodesValidRectangles:
    def test_bsp_leaf_nodes_valid_rectangles(self, generated_map):
        gen = generated_map
        for room in gen.rooms:
            assert room.width >= MapGenerator.MIN_ROOM_SIZE
            assert room.height >= MapGenerator.MIN_ROOM_SIZE
            assert room.x >= 0
            assert room.y >= 0
            assert room.x + room.width <= gen.width
            assert room.y + room.height <= gen.height


class TestAStarCorridorConnectsEndpoints:
    def test_a_star_corridor_connects_endpoints(self, generated_map):
        gen = generated_map
        if len(gen.rooms) < 2:
            pytest.skip("Need at least 2 rooms for A* test")
        start = gen.rooms[0].center()
        end = gen.rooms[-1].center()
        path = gen._a_star(start, end)
        assert len(path) > 0
        assert path[0] == start
        assert path[-1] == end


class TestAllRoomsReachableFloodFill:
    def test_all_rooms_reachable_flood_fill(self, generated_map_large):
        gen = generated_map_large
        if len(gen.rooms) < 2:
            pytest.skip("Need at least 2 rooms for flood fill test")
        room_centers = [room.center() for room in gen.rooms]
        start = room_centers[0]
        visited = set()
        queue = deque([start])
        visited.add(start)
        while queue:
            cx, cy = queue.popleft()
            for dx, dy in [(0, 1), (0, -1), (1, 0), (-1, 0)]:
                nx, ny = cx + dx, cy + dy
                if (nx, ny) in visited:
                    continue
                if 0 <= nx < gen.width and 0 <= ny < gen.height:
                    if gen.tiles[ny][nx] != TileType.WALL:
                        visited.add((nx, ny))
                        queue.append((nx, ny))
        for center in room_centers[1:]:
            assert center in visited, f"Room center {center} not reachable from {start}"


class TestMapsDifferWithDifferentSeeds:
    def test_maps_differ_with_different_seeds(self):
        room_center_sets = []
        for seed in [10, 20, 30]:
            random.seed(seed)
            gen = MapGenerator()
            gen.generate(60, 40, floor_depth=1)
            random.seed()
            centers = frozenset(room.center() for room in gen.rooms)
            room_center_sets.append(centers)
        assert not (room_center_sets[0] == room_center_sets[1] and room_center_sets[1] == room_center_sets[2])


class TestStairsPlaced:
    def test_stairs_placed(self, generated_map):
        gen = generated_map
        assert len(gen.rooms) >= 2
        first_room = gen.rooms[0]
        last_room = gen.rooms[-1]
        fx, fy = first_room.center()
        lx, ly = last_room.center()
        assert gen.tiles[fy][fx] == TileType.STAIRS_UP
        assert gen.tiles[ly][lx] == TileType.STAIRS_DOWN


class TestMultipleFloorsGenerate:
    @pytest.mark.parametrize("floor_depth", [1, 5, 10])
    def test_multiple_floors_generate(self, floor_depth):
        random.seed(7)
        gen = MapGenerator()
        gen.generate(60, 40, floor_depth=floor_depth)
        random.seed()
        assert len(gen.rooms) > 0
        assert gen.floor_depth == floor_depth
        assert len(gen.tiles) == 40
        assert len(gen.tiles[0]) == 60
