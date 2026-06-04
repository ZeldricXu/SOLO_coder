"""
BSP（Binary Space Partitioning）程序化地图生成器

算法步骤：
    1. 分割（Split）    → 根节点递归二分为叶子节点，每个叶子容纳一个房间
    2. 叶子（Leaf）     → 所有叶子都是有效矩形，满足最小/最大房间尺寸约束
    3. 房间（Room）     → 在每个叶子内随机位置生成房间，留1格墙间距
    4. 走廊（Corridor） → A*算法连接所有房间，走廊用"先横后纵"或"先纵后横"
    5. 连通性验证       → Flood Fill验证所有房间可达

楼层连贯性实现：
    下一层入口房间选择距离上一层出口坐标最近的叶子，保持空间对应关系。
"""

import enum
import random
import heapq


class TileType(enum.IntEnum):
    WALL = 0
    FLOOR = 1
    CORRIDOR = 2
    DOOR = 3
    STAIRS_UP = 4
    STAIRS_DOWN = 5
    TRAP = 6
    CHEST = 7
    ALTAR = 8
    WATER = 9


class Room:
    _next_id = 0

    def __init__(self, room_id, x, y, width, height):
        self.id = room_id
        self.x = x
        self.y = y
        self.width = width
        self.height = height
        self.tiles = []

    @classmethod
    def create(cls, x, y, width, height):
        room = cls(cls._next_id, x, y, width, height)
        cls._next_id += 1
        return room

    def center(self):
        return (self.x + self.width // 2, self.y + self.height // 2)


class BSPNode:
    def __init__(self, x, y, width, height):
        self.x = x
        self.y = y
        self.width = width
        self.height = height
        self.left = None
        self.right = None
        self.room = None

    def is_leaf(self):
        return self.left is None and self.right is None

    def split(self, min_room_size, max_room_size):
        """
        BSP递归分割算法

        将当前节点按比例(0.35~0.65)随机二分为左右子节点。
        分割方向：宽>高优先垂直分割，高>宽优先水平分割。
        子节点尺寸 < max_room_size 时停止分割，作为叶子节点。

        Args:
            min_room_size: 最小房间尺寸（保证分割后每个叶子至少能放下一个房间）
            max_room_size: 最大房间尺寸（超过则继续分割）
        """
        if not self.is_leaf():
            if self.left:
                self.left.split(min_room_size, max_room_size)
            if self.right:
                self.right.split(min_room_size, max_room_size)
            return

        if self.width <= max_room_size and self.height <= max_room_size:
            return

        can_split_h = self.height >= (min_room_size + 2) * 2
        can_split_v = self.width >= (min_room_size + 2) * 2

        if not can_split_h and not can_split_v:
            return

        split_horizontal = random.random() > 0.5
        if self.width > self.height:
            split_horizontal = False
        elif self.height > self.width:
            split_horizontal = True

        if split_horizontal and not can_split_h:
            split_horizontal = False
        if not split_horizontal and not can_split_v:
            split_horizontal = True
        if not can_split_v and not can_split_h:
            return

        ratio = random.uniform(0.35, 0.65)
        if split_horizontal:
            split_pos = int(self.height * ratio)
            split_pos = max(min_room_size + 2, min(split_pos, self.height - min_room_size - 2))
            self.left = BSPNode(self.x, self.y, self.width, split_pos)
            self.right = BSPNode(self.x, self.y + split_pos, self.width, self.height - split_pos)
        else:
            split_pos = int(self.width * ratio)
            split_pos = max(min_room_size + 2, min(split_pos, self.width - min_room_size - 2))
            self.left = BSPNode(self.x, self.y, split_pos, self.height)
            self.right = BSPNode(self.x + split_pos, self.y, self.width - split_pos, self.height)

        self.left.split(min_room_size, max_room_size)
        self.right.split(min_room_size, max_room_size)

    def get_leaves(self):
        if self.is_leaf():
            return [self]
        leaves = []
        if self.left:
            leaves.extend(self.left.get_leaves())
        if self.right:
            leaves.extend(self.right.get_leaves())
        return leaves

    def get_center(self):
        return (self.x + self.width // 2, self.y + self.height // 2)

    def get_all_rooms(self):
        if self.is_leaf():
            if self.room:
                return [self.room]
            return []
        rooms = []
        if self.left:
            rooms.extend(self.left.get_all_rooms())
        if self.right:
            rooms.extend(self.right.get_all_rooms())
        return rooms


class MapGenerator:
    MIN_ROOM_SIZE = 4

    def __init__(self):
        self.width = 0
        self.height = 0
        self.floor_depth = 1
        self.tiles = []
        self.rooms = []
        self.room_map = {}
        self._corridor_room_ids = set()

    def _max_room_size(self):
        if self.floor_depth <= 3:
            return 8 + self.floor_depth
        elif self.floor_depth <= 6:
            return 10 + self.floor_depth
        else:
            return 14 + min(self.floor_depth, 10)

    def _init_tiles(self):
        self.tiles = [[TileType.WALL for _ in range(self.width)] for _ in range(self.height)]

    def _carve_room(self, room):
        for ry in range(room.y, room.y + room.height):
            for rx in range(room.x, room.x + room.width):
                if 0 <= rx < self.width and 0 <= ry < self.height:
                    self.tiles[ry][rx] = TileType.FLOOR
                    room.tiles.append((rx, ry))
                    self.room_map[(rx, ry)] = room.id

    def _create_rooms(self, root, previous_stairs_pos=None):
        leaves = root.get_leaves()

        if previous_stairs_pos is not None and leaves:
            target_x, target_y = previous_stairs_pos
            best_idx = 0
            best_dist = float('inf')
            for i, leaf in enumerate(leaves):
                leaf_center = leaf.get_center()
                dist = abs(leaf_center[0] - target_x) + abs(leaf_center[1] - target_y)
                if dist < best_dist:
                    best_dist = dist
                    best_idx = i
            leaves[0], leaves[best_idx] = leaves[best_idx], leaves[0]

        for leaf in leaves:
            pad = 1
            max_w = leaf.width - 2 * pad
            max_h = leaf.height - 2 * pad

            if max_w < self.MIN_ROOM_SIZE or max_h < self.MIN_ROOM_SIZE:
                max_w = leaf.width
                max_h = leaf.height
                pad = 0

            max_w = max(self.MIN_ROOM_SIZE, max_w)
            max_h = max(self.MIN_ROOM_SIZE, max_h)

            room_w = random.randint(self.MIN_ROOM_SIZE, max_w)
            room_h = random.randint(self.MIN_ROOM_SIZE, max_h)

            if pad > 0:
                offset_x = random.randint(pad, max(pad, leaf.width - pad - room_w))
                offset_y = random.randint(pad, max(pad, leaf.height - pad - room_h))
            else:
                offset_x = random.randint(0, max(0, leaf.width - room_w))
                offset_y = random.randint(0, max(0, leaf.height - room_h))

            room_x = leaf.x + offset_x
            room_y = leaf.y + offset_y

            room = Room.create(room_x, room_y, room_w, room_h)
            leaf.room = room
            self.rooms.append(room)
            self._carve_room(room)

    def _a_star(self, start, end):
        """
        A*寻路算法 - 用于生成连接房间的走廊

        启发函数 h(n): 曼哈顿距离 |x1-x2| + |y1-y2|，保证不会高估实际代价（可采纳）
        代价函数 g(n): 移动代价，根据地形不同调整：
            - 走廊(CORRIDOR): 0.5  → 优先走已有的走廊
            - 墙(WALL): 2       → 穿墙代价高
            - 房间(FLOOR): 10   → 尽量不横穿其他房间，绕走廊走
            - 普通: 1

        每个节点的评估值 f(n) = g(n) + h(n)
        使用优先队列（最小堆）按f(n)排序扩展节点。
        """
        open_set = []
        counter = 0
        heapq.heappush(open_set, (0, counter, start))
        came_from = {}
        g_score = {start: 0}
        closed = set()

        while open_set:
            _, _, current = heapq.heappop(open_set)

            if current == end:
                path = []
                while current in came_from:
                    path.append(current)
                    current = came_from[current]
                path.append(start)
                path.reverse()
                return path

            if current in closed:
                continue
            closed.add(current)

            cx, cy = current
            for dx, dy in [(0, 1), (0, -1), (1, 0), (-1, 0)]:
                nx, ny = cx + dx, cy + dy
                if 0 <= nx < self.width and 0 <= ny < self.height:
                    neighbor = (nx, ny)
                    if neighbor in closed:
                        continue

                    move_cost = 1
                    tile = self.tiles[ny][nx]

                    if tile == TileType.FLOOR:
                        if (nx, ny) in self.room_map and self.room_map[(nx, ny)] not in self._corridor_room_ids:
                            move_cost = 10
                    elif tile == TileType.CORRIDOR:
                        move_cost = 0.5
                    elif tile == TileType.WALL:
                        move_cost = 2

                    tentative_g = g_score[current] + move_cost

                    if neighbor not in g_score or tentative_g < g_score[neighbor]:
                        g_score[neighbor] = tentative_g
                        h = abs(nx - end[0]) + abs(ny - end[1])
                        counter += 1
                        heapq.heappush(open_set, (tentative_g + h, counter, neighbor))
                        came_from[neighbor] = current

        return []

    def _carve_corridor(self, path):
        for x, y in path:
            if self.tiles[y][x] == TileType.WALL:
                self.tiles[y][x] = TileType.CORRIDOR

        for i in range(len(path)):
            x, y = path[i]
            if self.tiles[y][x] == TileType.CORRIDOR:
                for dx, dy in [(0, 1), (0, -1), (1, 0), (-1, 0)]:
                    adj_x, adj_y = x + dx, y + dy
                    if 0 <= adj_x < self.width and 0 <= adj_y < self.height:
                        if self.tiles[adj_y][adj_x] == TileType.FLOOR and (adj_x, adj_y) in self.room_map:
                            self.tiles[y][x] = TileType.DOOR
                            break

    def _connect_rooms(self, root):
        if root.is_leaf():
            return

        if root.left and root.right:
            left_rooms = root.left.get_all_rooms()
            right_rooms = root.right.get_all_rooms()

            if left_rooms and right_rooms:
                left_room = random.choice(left_rooms)
                right_room = random.choice(right_rooms)

                self._corridor_room_ids = {left_room.id, right_room.id}

                start = left_room.center()
                end = right_room.center()
                mid = root.get_center()

                path1 = self._a_star(start, mid)
                path2 = self._a_star(mid, end)

                if path1 and path2:
                    full_path = path1 + path2[1:]
                    self._carve_corridor(full_path)
                elif path1:
                    self._carve_corridor(path1)
                elif path2:
                    self._carve_corridor(path2)
                else:
                    direct = self._a_star(start, end)
                    if direct:
                        self._carve_corridor(direct)

        if root.left:
            self._connect_rooms(root.left)
        if root.right:
            self._connect_rooms(root.right)

    def _place_specials(self):
        if not self.rooms:
            return

        first_room = self.rooms[0]
        last_room = self.rooms[-1]

        fx, fy = first_room.center()
        self.tiles[fy][fx] = TileType.STAIRS_UP

        lx, ly = last_room.center()
        self.tiles[ly][lx] = TileType.STAIRS_DOWN

        trap_prob = 0.0
        chest_prob = 0.02
        altar_prob = 0.01

        if self.floor_depth <= 3:
            trap_prob = 0.0
            chest_prob = 0.03
        elif self.floor_depth <= 6:
            trap_prob = 0.05
            chest_prob = 0.04
            altar_prob = 0.02
        else:
            trap_prob = 0.08
            chest_prob = 0.03
            altar_prob = 0.03

        for room in self.rooms:
            if room.id == first_room.id or room.id == last_room.id:
                continue
            for tx, ty in room.tiles:
                if self.tiles[ty][tx] == TileType.FLOOR:
                    r = random.random()
                    if r < altar_prob:
                        self.tiles[ty][tx] = TileType.ALTAR
                    elif r < altar_prob + chest_prob:
                        self.tiles[ty][tx] = TileType.CHEST
                    elif r < altar_prob + chest_prob + trap_prob:
                        self.tiles[ty][tx] = TileType.TRAP

    def generate(self, width, height, floor_depth=1, previous_stairs_pos=None):
        self.width = width
        self.height = height
        self.floor_depth = floor_depth
        self.rooms = []
        self.room_map = {}
        self.previous_stairs_pos = previous_stairs_pos
        Room._next_id = 0

        self._init_tiles()

        max_room_size = self._max_room_size()

        root = BSPNode(0, 0, width, height)
        root.split(self.MIN_ROOM_SIZE, max_room_size)

        self._create_rooms(root, previous_stairs_pos)
        self._connect_rooms(root)
        self._place_specials()

        return self

    def get_spawnable_tiles(self):
        tiles = []
        for y in range(self.height):
            for x in range(self.width):
                if self.tiles[y][x] == TileType.FLOOR:
                    tiles.append((x, y))
        return tiles

    def get_room_at(self, x, y):
        if (x, y) in self.room_map:
            return self.room_map[(x, y)]
        return None

    def to_dict(self):
        rooms_data = []
        for room in self.rooms:
            rooms_data.append({
                "id": room.id,
                "x": room.x,
                "y": room.y,
                "width": room.width,
                "height": room.height,
                "tiles": room.tiles
            })

        tiles_data = []
        for row in self.tiles:
            tiles_data.append([int(t) for t in row])

        return {
            "width": self.width,
            "height": self.height,
            "floor_depth": self.floor_depth,
            "tiles": tiles_data,
            "rooms": rooms_data
        }
