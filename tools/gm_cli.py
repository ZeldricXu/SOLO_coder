from __future__ import annotations

import argparse
import asyncio
import json
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from server.database import get_player, init_db, save_inventory, load_inventory, update_player_stats, get_leaderboard
from server.item_system import ItemFactory, Item, Rarity, ItemType, EnhancementSystem
from server.game_manager import GameManager
from server.config import get_config


def _print_json(data):
    print(json.dumps(data, indent=2, ensure_ascii=False, default=str))


async def cmd_list_players(args):
    await init_db()
    gm = _get_game_manager()

    if gm.active_dungeons:
        online_players = []
        for dungeon_id, gs in gm.active_dungeons.items():
            for p in gs.players:
                online_players.append({
                    "name": p.name,
                    "class": p.class_type.value,
                    "level": p.level,
                    "hp": f"{p.hp}/{p.max_hp}",
                    "floor": gs.current_floor,
                    "position": p.position,
                    "alive": p.alive,
                    "dungeon_id": dungeon_id,
                })
        print(f"\n=== 在线玩家 ({len(online_players)}) ===")
        _print_json(online_players)
    else:
        print("当前无在线玩家")


async def cmd_give_item(args):
    await init_db()

    factory = ItemFactory()
    item = factory.create_item(args.item_id, floor_depth=args.floor or 1)

    if not item:
        print(f"错误：无法创建物品 '{args.item_id}'")
        return

    if args.enhance_level:
        item.enhance_level = min(args.enhance_level, 10)

    item_dict = item.to_dict()
    _print_json({
        "action": "give_item",
        "item_id": args.item_id,
        "item": item_dict,
        "message": f"已生成物品: {item.full_name()}" + (f" +{item.enhance_level}" if item.enhance_level > 0 else ""),
        "note": "请通过GM接口手动添加到玩家背包，或使用 --player 参数指定玩家",
    })


async def cmd_teleport(args):
    await init_db()
    gm = _get_game_manager()

    target_player = None
    target_gs = None
    for dungeon_id, gs in gm.active_dungeons.items():
        for p in gs.players:
            if p.name == args.player_name or p.client_id == args.player_name:
                target_player = p
                target_gs = gs
                break
        if target_player:
            break

    if not target_player:
        print(f"错误：未找到玩家 '{args.player_name}'")
        return

    if args.floor < 1 or args.floor > target_gs.current_floor + 1:
        print(f"错误：无效楼层 {args.floor}，当前最高 {target_gs.current_floor}")
        return

    old_floor = target_gs.current_floor
    target_player.position = target_gs.map.rooms[0].center()

    _print_json({
        "action": "teleport",
        "player": target_player.name,
        "from_floor": old_floor,
        "to_floor": args.floor,
        "new_position": target_player.position,
    })


async def cmd_trigger_event(args):
    await init_db()
    gm = _get_game_manager()

    event_file = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "events", "events.json")
    if not os.path.exists(event_file):
        print("错误：事件模板文件不存在")
        return

    with open(event_file, "r", encoding="utf-8") as f:
        events_data = json.load(f)

    available_events = events_data if isinstance(events_data, list) else events_data.get("events", [])
    if args.list:
        print("\n=== 可用地牢事件 ===")
        for evt in available_events:
            print(f"  [{evt.get('id', '?')}] {evt.get('name', 'Unknown')} - {evt.get('description', '')}")
        return

    if args.event_id:
        matched = [e for e in available_events if e.get("id") == args.event_id]
        if not matched:
            print(f"错误：未找到事件 '{args.event_id}'")
            return

        _print_json({
            "action": "trigger_event",
            "event_id": args.event_id,
            "event": matched[0],
            "message": f"已触发事件: {matched[0].get('name', args.event_id)}",
        })
    else:
        print("请指定 --event-id 或使用 --list 查看可用事件")


async def cmd_server_status(args):
    await init_db()
    gm = _get_game_manager()
    config = get_config()

    dungeon_count = len(gm.active_dungeons)
    total_monsters = 0
    total_items = 0
    dungeon_details = []

    for dungeon_id, gs in gm.active_dungeons.items():
        alive_monsters = sum(1 for m in gs.monsters if m.get("alive", True))
        total_monsters += alive_monsters
        total_items += len(gs.items)
        dungeon_details.append({
            "dungeon_id": dungeon_id,
            "floor": gs.current_floor,
            "players": len(gs.players),
            "alive_monsters": alive_monsters,
            "turn": gs.turn_count,
        })

    _print_json({
        "environment": config.environment.value,
        "map_size": f"{config.map_width}x{config.map_height}",
        "max_floors": config.max_floors,
        "monster_strength": config.monster_strength_mult,
        "active_dungeons": dungeon_count,
        "total_monsters_alive": total_monsters,
        "total_items_on_ground": total_items,
        "dungeons": dungeon_details,
    })


async def cmd_enhance_item(args):
    await init_db()

    factory = ItemFactory()
    item = factory.create_item(args.item_id, floor_depth=args.floor or 1)

    if not item:
        print(f"错误：无法创建物品 '{args.item_id}'")
        return

    if args.check:
        cost = EnhancementSystem.get_enhance_cost(item, args.floor or 1)
        _print_json({
            "item": item.full_name(),
            "current_level": item.enhance_level,
            "enhance_cost": cost,
        })
        return

    class FakePlayer:
        def __init__(self):
            self.gold = 999999
            self.inventory = [
                {"id": "enhance_stone", "stack_count": 999},
                {"id": "protection_rune", "stack_count": 999},
            ]

    player = FakePlayer()
    results = []

    target_level = args.target_level or (item.enhance_level + 1)
    target_level = min(target_level, 10)

    while item.enhance_level < target_level:
        result = EnhancementSystem.enhance(item, player, use_protection=args.protection, floor_depth=args.floor or 1)
        results.append({
            "attempt_level": item.enhance_level + 1,
            "success": result.get("success", False),
            "new_level": result.get("new_level", item.enhance_level),
            "message": result.get("message", ""),
        })
        if not result.get("success", False) and item.enhance_level == 0:
            break

    _print_json({
        "item_id": args.item_id,
        "final_name": item.full_name(),
        "final_level": item.enhance_level,
        "target_level": target_level,
        "attempts": results,
    })


def _get_game_manager() -> GameManager:
    return GameManager()


def main():
    parser = argparse.ArgumentParser(
        prog="dungeon-gm",
        description="地下城GM管理工具",
    )
    subparsers = parser.add_subparsers(dest="command", help="可用命令")

    p_players = subparsers.add_parser("players", help="查询在线玩家")
    p_players.add_argument("--format", choices=["json", "table"], default="json")

    p_give = subparsers.add_parser("give", help="生成特定物品")
    p_give.add_argument("item_id", help="物品模板ID")
    p_give.add_argument("--floor", type=int, default=1, help="按哪个楼层的掉率生成")
    p_give.add_argument("--enhance-level", type=int, default=0, help="直接设置强化等级")
    p_give.add_argument("--player", help="目标玩家名")

    p_teleport = subparsers.add_parser("teleport", help="传送玩家到指定楼层")
    p_teleport.add_argument("player_name", help="玩家名或client_id")
    p_teleport.add_argument("floor", type=int, help="目标楼层")

    p_event = subparsers.add_parser("event", help="强制触发地牢事件")
    p_event.add_argument("--event-id", help="事件ID")
    p_event.add_argument("--list", action="store_true", help="列出所有可用事件")
    p_event.add_argument("--player", help="目标玩家名")

    p_status = subparsers.add_parser("status", help="服务器状态")

    p_enhance = subparsers.add_parser("enhance", help="模拟装备强化")
    p_enhance.add_argument("item_id", help="物品模板ID")
    p_enhance.add_argument("--target-level", type=int, help="目标强化等级")
    p_enhance.add_argument("--floor", type=int, default=1)
    p_enhance.add_argument("--protection", action="store_true", help="使用保护符文")
    p_enhance.add_argument("--check", action="store_true", help="只查看强化费用")

    args = parser.parse_args()

    if not args.command:
        parser.print_help()
        return

    commands = {
        "players": cmd_list_players,
        "give": cmd_give_item,
        "teleport": cmd_teleport,
        "event": cmd_trigger_event,
        "status": cmd_server_status,
        "enhance": cmd_enhance_item,
    }

    handler = commands.get(args.command)
    if handler:
        asyncio.run(handler(args))
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
