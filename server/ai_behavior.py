"""
行为树（Behavior Tree）系统 - 怪物AI决策引擎

节点类型：
    ┌─────────────────────────────────────────────────────────────┐
    │  类型        符号     执行逻辑                              │
    ├─────────────────────────────────────────────────────────────┤
    │  Sequence    →→→     顺序执行：全部成功才成功，失败则短路    │
    │  Selector    ???     选择执行：一个成功就成功，全部失败才失败│
    │  Condition   [?]     条件判断：返回SUCCESS/FAILURE          │
    │  Action      [!]     动作执行：返回SUCCESS + 动作类型       │
    └─────────────────────────────────────────────────────────────┘

Tick执行流程（以群体AI为例）：

                  ┌───────────┐
                  │ Selector  │
                  └─────┬─────┘
          ┌─────────────┼─────────────┐
    ┌─────▼─────┐  ┌────▼────┐  ┌────▼────┐
    │ hp_low?   │  │ allies? │  │ patrol  │
    │ [COND]    │  │ [COND]  │  │ [ACTION]│
    └─────┬─────┘  └────┬────┘  └─────────┘
          │              │
     ┌────▼────┐   ┌─────▼──────┐
     │ flee    │   │ swarm_attack│
     │ [ACTION]│   │  [ACTION]  │
     └─────────┘   └────────────┘

执行顺序：
    1. 检查血量是否过低 → 是则逃跑
    2. 检查周围友军数量 ≥3 → 是则围攻
    3. 否则巡逻

XML格式示例：
    <BehaviorTree id="group_ai">
      <Selector>
        <Sequence>
          <Condition type="hp_below" threshold="15"/>
          <Action type="retreat" distance="3"/>
        </Sequence>
        <Sequence>
          <Condition type="allies_nearby_count" count="3" range="5"/>
          <Action type="swarm"/>
        </Sequence>
        <Action type="wander"/>
      </Selector>
    </BehaviorTree>
"""

import enum
import math
import os
import xml.etree.ElementTree as ET
from collections import deque


class NodeType(enum.Enum):
    SEQUENCE = "sequence"
    SELECTOR = "selector"
    CONDITION = "condition"
    ACTION = "action"


class BTNode:
    def __init__(self, node_type, children=None, condition_type=None,
                 condition_params=None, action_type=None, action_params=None):
        self.type = node_type
        self.children = children if children is not None else []
        self.condition_type = condition_type
        self.condition_params = condition_params if condition_params is not None else {}
        self.action_type = action_type
        self.action_params = action_params if action_params is not None else {}


_SUCCESS = "SUCCESS"
_FAILURE = "FAILURE"


class BehaviorTree:
    def __init__(self):
        self.root = None
        self.name = ""

    def load(self, xml_path):
        tree = ET.parse(xml_path)
        root = tree.getroot()
        self.name = root.get("id", root.get("name", ""))
        self.root = self._parse_node(root)

    def _parse_node(self, element):
        tag = element.tag.lower()

        if tag == "behaviortree":
            children = []
            for child_elem in element:
                children.append(self._parse_node(child_elem))
            if len(children) == 1:
                return children[0]
            return BTNode(NodeType.SELECTOR, children=children)

        try:
            node_type = NodeType(tag)
        except ValueError:
            return BTNode(NodeType.SELECTOR, children=[])

        condition_type = None
        action_type = None
        condition_params = {}
        action_params = {}

        if node_type == NodeType.CONDITION:
            condition_type = element.get("type")
        elif node_type == NodeType.ACTION:
            action_type = element.get("type")

        _skip = {"type", "condition_type", "action_type"}
        for attr, val_str in element.attrib.items():
            if attr in _skip:
                continue
            val = val_str
            try:
                val = int(val_str)
            except (ValueError, TypeError):
                try:
                    val = float(val_str)
                except (ValueError, TypeError):
                    pass
            if node_type == NodeType.CONDITION:
                condition_params[attr] = val
            elif node_type == NodeType.ACTION:
                action_params[attr] = val

        children = []
        for child_elem in element:
            child_tag = child_elem.tag.lower()
            if child_tag == "params":
                for param in child_elem:
                    val = param.text
                    try:
                        val = int(val)
                    except (ValueError, TypeError):
                        try:
                            val = float(val)
                        except (ValueError, TypeError):
                            pass
                    if node_type == NodeType.CONDITION:
                        condition_params[param.tag] = val
                    elif node_type == NodeType.ACTION:
                        action_params[param.tag] = val
                continue
            children.append(self._parse_node(child_elem))

        return BTNode(
            node_type=node_type,
            children=children,
            condition_type=condition_type,
            condition_params=condition_params,
            action_type=action_type,
            action_params=action_params,
        )

    def execute(self, monster, game_context):
        """
        行为树tick入口 - 每回合调用一次

        执行流程：
            1. 从根节点开始递归遍历
            2. Condition节点：条件成立返回SUCCESS，否则FAILURE
            3. Sequence节点：顺序执行子节点，一个失败则整个序列失败
            4. Selector节点：顺序执行子节点，一个成功则整个选择成功
            5. Action节点：执行具体动作，返回SUCCESS + 动作类型

        Args:
            monster: 怪物数据字典，包含位置、血量、状态等
            game_context: 游戏上下文，包含玩家位置、其他怪物等

        Returns:
            dict: {"type": 动作类型, "params": 动作参数}
                  默认为wander（巡逻）
        """
        if self.root is None:
            return {"type": "wander", "params": {}}
        result = self._execute_node(self.root, monster, game_context)
        if isinstance(result, dict) and result.get("status") == _SUCCESS and "type" in result:
            return {"type": result["type"], "params": result.get("params", {})}
        return {"type": "wander", "params": {}}

    def _execute_node(self, node, monster, game_context):
        if node.type == NodeType.SEQUENCE:
            last_action = None
            last_gated_action = None
            prev_was_condition = False
            for child in node.children:
                result = self._execute_node(child, monster, game_context)
                if result.get("status") == _FAILURE:
                    if last_gated_action is not None:
                        return last_gated_action
                    if last_action is not None:
                        return last_action
                    return {"status": _FAILURE}
                if "type" in result:
                    last_action = result
                    if prev_was_condition:
                        last_gated_action = result
                prev_was_condition = result.get("status") == _SUCCESS and "type" not in result
            if last_gated_action is not None:
                return last_gated_action
            if last_action is not None:
                return last_action
            return {"status": _SUCCESS}

        if node.type == NodeType.SELECTOR:
            for child in node.children:
                result = self._execute_node(child, monster, game_context)
                if result.get("status") == _SUCCESS:
                    return result
            return {"status": _FAILURE}

        if node.type == NodeType.CONDITION:
            if self._evaluate_condition(node, monster, game_context):
                return {"status": _SUCCESS}
            return {"status": _FAILURE}

        if node.type == NodeType.ACTION:
            return self._perform_action(node, monster, game_context)

        return {"status": _FAILURE}

    def _evaluate_condition(self, node, monster, game_context):
        cond = node.condition_type
        params = node.condition_params

        if cond == "hp_low":
            threshold = float(params.get("threshold", 0.3))
            max_hp = monster.get("max_hp", 1)
            hp = monster.get("hp", 0)
            return (hp / max_hp) < threshold

        if cond == "hp_below":
            threshold = float(params.get("threshold", 10))
            hp = monster.get("hp", 0)
            max_hp = monster.get("max_hp", 1)
            if threshold >= 1:
                return hp < threshold
            return (hp / max_hp) < threshold

        if cond == "can_see_player":
            visible_tiles = game_context.get("visible_tiles", set())
            for pos in game_context.get("player_positions", []):
                tpos = (pos[0], pos[1]) if not isinstance(pos, tuple) else pos
                if tpos in visible_tiles:
                    return True
            return False

        if cond == "in_attack_range":
            mpos = monster.get("position", (0, 0))
            atk_range = monster.get("attack_range", 1)
            for pos in game_context.get("player_positions", []):
                dist = abs(pos[0] - mpos[0]) + abs(pos[1] - mpos[1])
                if dist <= atk_range:
                    return True
            return False

        if cond == "in_range":
            r = float(params.get("range", 5))
            mpos = monster.get("position", (0, 0))
            for pos in game_context.get("player_positions", []):
                dist = math.sqrt((pos[0] - mpos[0]) ** 2 + (pos[1] - mpos[1]) ** 2)
                if dist <= r:
                    return True
            return False

        if cond == "ally_nearby":
            mpos = monster.get("position", (0, 0))
            ally_range = float(params.get("range", 3))
            for other in game_context.get("monsters", []):
                if other.get("id") == monster.get("id"):
                    continue
                opos = other.get("position", (0, 0))
                dist = abs(opos[0] - mpos[0]) + abs(opos[1] - mpos[1])
                if dist <= ally_range:
                    return True
            return False

        if cond == "heard_sound":
            return monster.get("heard_sound", False)

        if cond == "not_in_attack_range":
            mpos = monster.get("position", (0, 0))
            atk_range = monster.get("attack_range", 1)
            for pos in game_context.get("player_positions", []):
                dist = abs(pos[0] - mpos[0]) + abs(pos[1] - mpos[1])
                if dist <= atk_range:
                    return False
            return True

        if cond == "cooldown_ready":
            spell = params.get("spell", "")
            cooldowns = monster.get("cooldowns", {})
            return cooldowns.get(spell, 0) <= 0

        if cond == "can_summon":
            max_summons = float(params.get("max_summons", 3))
            current_summons = monster.get("summon_count", 0)
            return current_summons < max_summons

        if cond == "player_too_close":
            min_dist = float(params.get("min_distance", 2))
            mpos = monster.get("position", (0, 0))
            for pos in game_context.get("player_positions", []):
                dist = abs(pos[0] - mpos[0]) + abs(pos[1] - mpos[1])
                if dist <= min_dist:
                    return True
            return False

        if cond == "player_far":
            min_dist = float(params.get("min_distance", 3))
            mpos = monster.get("position", (0, 0))
            for pos in game_context.get("player_positions", []):
                dist = abs(pos[0] - mpos[0]) + abs(pos[1] - mpos[1])
                if dist >= min_dist:
                    return True
            return False

        if cond == "allies_nearby_count":
            min_count = int(params.get("count", 3))
            mpos = monster.get("position", (0, 0))
            ally_range = float(params.get("range", 5))
            count = 0
            for other in game_context.get("monsters", []):
                if other.get("id") == monster.get("id"):
                    continue
                if not other.get("alive", True):
                    continue
                opos = other.get("position", (0, 0))
                dist = abs(opos[0] - mpos[0]) + abs(opos[1] - mpos[1])
                if dist <= ally_range:
                    count += 1
                    if count >= min_count:
                        return True
            return False

        return False

    def _perform_action(self, node, monster, game_context):
        action = node.action_type
        params = dict(node.action_params)

        if action == "cast_spell":
            spell = params.get("spell", "")
            return {"status": _SUCCESS, "type": "cast_spell", "params": {"spell": spell}}

        if action == "summon":
            template = params.get("template", "skeleton")
            count = int(params.get("count", 1))
            return {"status": _SUCCESS, "type": "summon", "params": {"template": template, "count": count}}

        if action == "retreat":
            distance = int(params.get("distance", 2))
            return {"status": _SUCCESS, "type": "retreat", "params": {"distance": distance}}

        if action == "call_help":
            radius = int(params.get("radius", 8))
            return {"status": _SUCCESS, "type": "call_for_help", "params": {"radius": radius}}

        if action == "swarm":
            return {"status": _SUCCESS, "type": "swarm", "params": params}

        known = {"attack", "chase", "flee", "patrol", "wander",
                 "investigate", "call_for_help", "stealth", "vanish"}
        if action in known:
            return {"status": _SUCCESS, "type": action, "params": params}

        return {"status": _FAILURE}


class VisionSystem:
    @staticmethod
    def compute_visible_tiles(monster_pos, sight_range, map_tiles):
        visible = set()
        mx, my = int(monster_pos[0]), int(monster_pos[1])

        if isinstance(map_tiles, dict):
            def _get(x, y):
                return map_tiles.get((x, y))
            keys = list(map_tiles.keys())
            if keys:
                width = max(k[0] for k in keys) + 1
                height = max(k[1] for k in keys) + 1
            else:
                width = 0
                height = 0
        else:
            height = len(map_tiles)
            width = len(map_tiles[0]) if height > 0 else 0

            def _get(x, y):
                if 0 <= y < height and 0 <= x < width:
                    return map_tiles[y][x]
                return None

        visible.add((mx, my))

        num_rays = 360
        step = 0.5
        max_steps = int(sight_range / step) + 1

        for i in range(num_rays):
            angle = 2 * math.pi * i / num_rays
            dx = math.cos(angle) * step
            dy = math.sin(angle) * step

            rx = mx + 0.5
            ry = my + 0.5

            for _ in range(max_steps):
                rx += dx
                ry += dy
                tx = int(rx)
                ty = int(ry)

                if tx < 0 or ty < 0 or tx >= width or ty >= height:
                    break

                tile = _get(tx, ty)
                if tile is None:
                    break

                visible.add((tx, ty))

                if "wall" in str(tile).lower():
                    break

        return visible


class SoundPropagation:
    @staticmethod
    def propagate_sound(source_pos, intensity, map_tiles):
        if isinstance(map_tiles, dict):
            def _get(x, y):
                return map_tiles.get((x, y))
            valid = set(map_tiles.keys())
        else:
            h = len(map_tiles)
            w = len(map_tiles[0]) if h > 0 else 0
            valid = set()
            for row in range(h):
                for col in range(w):
                    valid.add((col, row))

            def _get(x, y):
                if 0 <= y < h and 0 <= x < w:
                    return map_tiles[y][x]
                return None

        sx, sy = int(source_pos[0]), int(source_pos[1])
        if (sx, sy) not in valid:
            return []

        heard = [(sx, sy)]
        visited = {(sx, sy): intensity}
        queue = deque([(sx, sy, intensity)])

        while queue:
            cx, cy, current = queue.popleft()
            if current <= 0:
                continue

            for ddx, ddy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, ny = cx + ddx, cy + ddy
                if (nx, ny) not in valid:
                    continue

                tile = _get(nx, ny)
                if tile is None:
                    continue

                ts = str(tile).lower()
                if "wall" in ts:
                    continue

                new_int = current - 1
                if "door" in ts:
                    new_int = current - 3

                if new_int <= 0:
                    continue

                if (nx, ny) in visited and visited[(nx, ny)] >= new_int:
                    continue

                visited[(nx, ny)] = new_int
                queue.append((nx, ny, new_int))
                if (nx, ny) not in heard:
                    heard.append((nx, ny))

        return heard


class MonsterAI:
    def __init__(self):
        self.behavior_trees = {}
        self.sound_sources = []

    def load_behavior_trees(self):
        base_path = os.path.join("data", "monsters", "behaviors")
        if not os.path.exists(base_path):
            return
        for filename in sorted(os.listdir(base_path)):
            if not filename.endswith(".xml"):
                continue
            xml_path = os.path.join(base_path, filename)
            bt = BehaviorTree()
            bt.load(xml_path)
            tree_id = bt.name or os.path.splitext(filename)[0]
            self.behavior_trees[tree_id] = bt
            self.behavior_trees[filename] = bt

    def decide_action(self, monster_id, game_context):
        monsters = game_context.get("monsters", [])
        monster = None
        for m in monsters:
            if m.get("id") == monster_id:
                monster = m
                break

        if monster is None:
            return {"type": "wander", "params": {}}

        map_tiles = game_context.get("map_tiles", {})

        for sound_src in self.sound_sources:
            src_pos = sound_src["position"]
            src_int = sound_src["intensity"]
            heard_tiles = SoundPropagation.propagate_sound(src_pos, src_int, map_tiles)
            mpos = monster.get("position", (0, 0))
            mpos_t = (mpos[0], mpos[1]) if not isinstance(mpos, tuple) else mpos
            if mpos_t in heard_tiles:
                monster["heard_sound"] = True
                monster["investigate_target"] = src_pos
                break

        sight_range = monster.get("sight_range", 5)
        mpos = monster.get("position", (0, 0))
        visible = VisionSystem.compute_visible_tiles(mpos, sight_range, map_tiles)
        game_context["visible_tiles"] = visible

        bt_key = monster.get("behavior_tree", "")
        bt = self.behavior_trees.get(bt_key)
        if bt is None:
            bt = self.behavior_trees.get(os.path.splitext(bt_key)[0])
        if bt is None:
            return {"type": "wander", "params": {}}

        action = bt.execute(monster, game_context)
        monster["heard_sound"] = False
        return action

    def notify_sound(self, source_pos, intensity):
        self.sound_sources.append({
            "position": source_pos,
            "intensity": intensity,
        })
