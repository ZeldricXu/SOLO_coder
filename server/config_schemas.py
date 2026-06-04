"""
游戏配置数据Schema定义与校验

使用pydantic对所有JSON配置文件进行schema约束，
启动时校验配置格式，失败时给出清晰的错误提示。

包含的配置类型：
- 怪物模板 (MonsterTemplate)
- 物品模板 (ItemTemplate)
- 技能模板 (SkillTemplate)
- 事件模板 (EventTemplate)
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    field_validator,
)


# ==================== 公共字段与常量 ====================

RARITY_VALUES = {"white", "blue", "purple", "gold", "orange"}
ELEMENT_TYPES = {"fire", "ice", "poison", "lightning", "dark", "holy"}
EFFECT_TYPES = {"damage", "heal", "buff", "debuff", "stealth", "summon", "teleport",
                "defense_bonus", "dodge_bonus", "crit_bonus", "hp_regen", "gold",
                "resurrection", "enhance_material", "protect_enhance"}
EVENT_TYPES = {"trap", "benefit", "merchant", "altar", "curse", "treasure"}
EVENT_TRIGGERS = {"enter_room", "interact", "kill_monster", "use_item"}
ITEM_TYPES = {"weapon", "armor", "accessory", "consumable", "material"}
EQUIP_SLOTS = {"weapon", "offhand", "chest", "head", "ring", "accessory"}
CLASS_NAMES = {"warrior", "mage", "rogue", "priest"}


# ==================== 怪物模板 ====================

class LootEntry(BaseModel):
    """怪物掉落表条目

    Attributes:
        item_id: 掉落物品ID，对应物品模板
        drop_rate: 掉落概率，0~1之间
    """
    item_id: str = Field(..., description="掉落物品ID，必须存在于物品模板中")
    drop_rate: float = Field(..., ge=0.0, le=1.0, description="掉落概率，取值范围0.0~1.0")


class MonsterTemplate(BaseModel):
    """怪物模板配置

    Attributes:
        id: 怪物唯一标识
        name: 显示名称
        hp: 基础生命值，必须>0
        attack: 基础攻击力，必须>=0
        defense: 基础防御力，必须>=0
        speed: 速度值，决定行动顺序
        sight_range: 视野范围，格子数
        sound_range: 听觉范围，格子数
        exp_reward: 击杀经验奖励
        behavior_tree: 行为树XML文件名
        element_resist: 元素抗性映射，取值-1.0~1.0，负数表示易伤
        loot_table: 掉落表
        min_floor: 最低出现楼层
        tags: 标签列表，用于群体AI等逻辑
        max_summons: 召唤型怪物最大召唤数量
        summon_template: 召唤怪物的模板ID
    """
    model_config = ConfigDict(extra="forbid")

    id: str = Field(..., min_length=1, description="怪物唯一标识")
    name: str = Field(..., min_length=1, description="怪物显示名称")
    hp: int = Field(..., gt=0, description="基础生命值，必须大于0")
    attack: int = Field(..., ge=0, description="基础攻击力，必须大于等于0")
    defense: int = Field(..., ge=0, description="基础防御力，必须大于等于0")
    speed: int = Field(..., description="速度值，决定行动顺序")
    sight_range: int = Field(..., ge=1, le=20, description="视野范围（格子数），1~20")
    sound_range: int = Field(..., ge=0, le=15, description="听觉范围（格子数），0~15")
    exp_reward: int = Field(..., ge=0, description="击杀经验奖励")
    behavior_tree: str = Field(..., min_length=1, description="行为树XML文件名")
    element_resist: Dict[str, float] = Field(
        default_factory=dict,
        description="元素抗性映射，键为元素类型，值为-1.0~1.0"
    )
    loot_table: List[LootEntry] = Field(default_factory=list, description="掉落表")
    min_floor: int = Field(..., ge=1, description="最低出现楼层，至少1")
    tags: List[str] = Field(default_factory=list, description="标签列表")
    max_summons: Optional[int] = Field(None, ge=1, description="召唤型怪物最大召唤数量")
    summon_template: Optional[str] = Field(None, description="召唤怪物的模板ID")

    @field_validator("element_resist")
    @classmethod
    def validate_element_resist(cls, v: Dict[str, float]) -> Dict[str, float]:
        for elem, resist in v.items():
            if elem not in ELEMENT_TYPES:
                raise ValueError(f"未知元素类型 '{elem}'，有效值: {sorted(ELEMENT_TYPES)}")
            if not (-1.0 <= resist <= 1.0):
                raise ValueError(f"元素抗性 '{elem}'={resist} 超出范围 [-1.0, 1.0]")
        return v

    @field_validator("behavior_tree")
    @classmethod
    def validate_behavior_tree_extension(cls, v: str) -> str:
        if not v.endswith(".xml"):
            raise ValueError(f"行为树文件名必须以.xml结尾: {v}")
        return v


class MonsterTemplates(BaseModel):
    monsters: List[MonsterTemplate]

    @field_validator("monsters")
    @classmethod
    def validate_unique_ids(cls, v: List[MonsterTemplate]) -> List[MonsterTemplate]:
        ids = [m.id for m in v]
        if len(ids) != len(set(ids)):
            duplicates = [i for i in ids if ids.count(i) > 1]
            raise ValueError(f"怪物ID重复: {sorted(set(duplicates))}")
        return v


# ==================== 物品模板 ====================

class ItemTemplate(BaseModel):
    """物品模板配置

    Attributes:
        id: 物品唯一标识
        name: 显示名称
        type: 物品类型: weapon/armor/accessory/consumable/material
        subtype: 子类型，如sword/axe/chest/ring等
        attack: 攻击力加成
        defense: 防御力加成
        speed: 速度加成
        rarity: 稀有度: white/blue/purple/gold/orange
        min_floor: 最低掉落楼层
        slot: 装备槽位（装备类必填）
        two_hand: 是否双手武器
        special_effect: 特殊特效ID
        consumable_effect: 消耗品效果类型
        consumable_value: 消耗品数值
        consumable_element: 消耗品元素类型
        stack_max: 最大堆叠数量
    """
    model_config = ConfigDict(extra="forbid")

    id: str = Field(..., min_length=1, description="物品唯一标识")
    name: str = Field(..., min_length=1, description="物品显示名称")
    type: str = Field(..., description=f"物品类型: {sorted(ITEM_TYPES)}")
    subtype: Optional[str] = Field(None, description="子类型，如sword/axe/chest等")
    attack: int = Field(0, description="攻击力加成")
    defense: int = Field(0, description="防御力加成")
    speed: int = Field(0, description="速度加成")
    rarity: str = Field("white", description=f"稀有度: {sorted(RARITY_VALUES)}")
    min_floor: int = Field(1, ge=1, description="最低掉落楼层")
    slot: Optional[str] = Field(None, description=f"装备槽位: {sorted(EQUIP_SLOTS)}")
    two_hand: bool = Field(False, description="是否双手武器")
    special_effect: Optional[str] = Field(None, description="特殊特效ID")
    bonus_mana: Optional[int] = Field(None, ge=0, description="额外法力值加成")
    element_bonus: Optional[Dict[str, float]] = Field(None, description="元素加成映射")
    effect: Optional[str] = Field(None, description="效果类型（消耗品/材料用）")
    value: Optional[int] = Field(None, description="效果数值")
    element: Optional[str] = Field(None, description="元素类型")
    duration: Optional[int] = Field(None, description="持续时间（回合）")
    range: Optional[int] = Field(None, description="使用范围（格子数）")
    status_effect: Optional[str] = Field(None, description="附加状态效果")
    description: Optional[str] = Field(None, description="物品描述")
    consumable_effect: Optional[str] = Field(None, description="消耗品效果类型（兼容旧字段）")
    consumable_value: int = Field(0, description="消耗品数值（兼容旧字段）")
    consumable_element: Optional[str] = Field(None, description="消耗品元素类型（兼容旧字段）")
    consumable_duration: Optional[int] = Field(None, description="消耗品持续时间（兼容旧字段）")
    stack_max: int = Field(1, ge=1, description="最大堆叠数量")

    @field_validator("type")
    @classmethod
    def validate_item_type(cls, v: str) -> str:
        if v not in ITEM_TYPES:
            raise ValueError(f"未知物品类型 '{v}'，有效值: {sorted(ITEM_TYPES)}")
        return v

    @field_validator("rarity")
    @classmethod
    def validate_rarity(cls, v: str) -> str:
        if v not in RARITY_VALUES:
            raise ValueError(f"未知稀有度 '{v}'，有效值: {sorted(RARITY_VALUES)}")
        return v

    @field_validator("slot")
    @classmethod
    def validate_slot(cls, v: Optional[str]) -> Optional[str]:
        if v is not None and v not in EQUIP_SLOTS:
            raise ValueError(f"未知装备槽位 '{v}'，有效值: {sorted(EQUIP_SLOTS)}")
        return v

    @field_validator("consumable_element")
    @classmethod
    def validate_consumable_element(cls, v: Optional[str]) -> Optional[str]:
        if v is not None and v not in ELEMENT_TYPES:
            raise ValueError(f"未知元素类型 '{v}'，有效值: {sorted(ELEMENT_TYPES)}")
        return v


class ItemTemplates(BaseModel):
    weapons: List[ItemTemplate] = Field(default_factory=list)
    armors: List[ItemTemplate] = Field(default_factory=list)
    accessories: List[ItemTemplate] = Field(default_factory=list)
    consumables: List[ItemTemplate] = Field(default_factory=list)
    materials: List[ItemTemplate] = Field(default_factory=list)

    def all_items(self) -> List[ItemTemplate]:
        return self.weapons + self.armors + self.accessories + self.consumables + self.materials

    @field_validator("weapons", "armors", "accessories", "consumables", "materials")
    @classmethod
    def validate_unique_ids_per_category(cls, v: List[ItemTemplate]) -> List[ItemTemplate]:
        ids = [item.id for item in v]
        if len(ids) != len(set(ids)):
            duplicates = [i for i in ids if ids.count(i) > 1]
            raise ValueError(f"物品ID重复: {sorted(set(duplicates))}")
        return v


# ==================== 技能模板 ====================

class SkillTemplate(BaseModel):
    """技能模板配置

    Attributes:
        id: 技能唯一标识
        name: 显示名称
        type: 技能类型: active/passive
        mana_cost: 法力消耗
        cooldown: 冷却回合数
        range: 施法距离（格子数）
        effect: 效果类型: damage/heal/buff/debuff/stealth/summon
        damage_multiplier: 伤害倍率（攻击*倍率）
        element: 伤害元素类型
        aoe: 范围伤害半径
        heal_value: 治疗量
        status_effect: 附加状态效果类型
        status_duration: 状态效果持续回合
        class_restriction: 职业限制列表
        description: 技能描述文本
    """
    model_config = ConfigDict(extra="forbid")

    id: str = Field(..., min_length=1, description="技能唯一标识")
    name: str = Field(..., min_length=1, description="技能显示名称")
    type: str = Field("active", description="技能类型: active/passive")
    mana_cost: int = Field(0, ge=0, description="法力消耗，必须>=0")
    cooldown: int = Field(0, ge=0, description="冷却回合数，必须>=0")
    range: int = Field(1, ge=0, description="施法距离（格子数），>=0")
    effect: str = Field(..., description=f"效果类型: {sorted(EFFECT_TYPES)}")
    damage_multiplier: Optional[float] = Field(None, gt=0, description="伤害倍率（攻击*倍率）")
    element: Optional[str] = Field(None, description=f"伤害元素类型: {sorted(ELEMENT_TYPES)}")
    aoe: Optional[int] = Field(None, ge=0, description="范围伤害半径，>=0")
    heal_value: Optional[int] = Field(None, gt=0, description="治疗量，必须>0")
    status_effect: Optional[str] = Field(None, description="附加状态效果类型")
    status_duration: Optional[int] = Field(None, ge=1, description="状态效果持续回合，>=1")
    class_restriction: Optional[List[str]] = Field(None, description=f"职业限制: {sorted(CLASS_NAMES)}")
    condition: Optional[str] = Field(None, description="施放前置条件")
    description: str = Field("", description="技能描述文本")
    duration: Optional[int] = Field(None, description="技能效果持续时间")
    buff_stat: Optional[str] = Field(None, description="buff属性名（attack/defense等）")
    buff_value: Optional[int] = Field(None, description="buff数值")
    aoe_friendly: Optional[bool] = Field(None, description="是否为友方范围技能")
    teleport_range: Optional[int] = Field(None, description="传送范围（格子数）")
    value: Optional[float] = Field(None, description="被动技能数值")

    @field_validator("type")
    @classmethod
    def validate_skill_type(cls, v: str) -> str:
        if v not in {"active", "passive"}:
            raise ValueError(f"未知技能类型 '{v}'，有效值: active, passive")
        return v

    @field_validator("effect")
    @classmethod
    def validate_effect(cls, v: str) -> str:
        if v not in EFFECT_TYPES:
            raise ValueError(f"未知效果类型 '{v}'，有效值: {sorted(EFFECT_TYPES)}")
        return v

    @field_validator("element")
    @classmethod
    def validate_element(cls, v: Optional[str]) -> Optional[str]:
        if v is not None and v not in ELEMENT_TYPES:
            raise ValueError(f"未知元素类型 '{v}'，有效值: {sorted(ELEMENT_TYPES)}")
        return v

    @field_validator("class_restriction")
    @classmethod
    def validate_class_restriction(cls, v: Optional[List[str]]) -> Optional[List[str]]:
        if v is not None:
            for c in v:
                if c not in CLASS_NAMES:
                    raise ValueError(f"未知职业 '{c}'，有效值: {sorted(CLASS_NAMES)}")
        return v


class SkillBookTemplate(BaseModel):
    """技能书模板配置

    Attributes:
        id: 技能书唯一标识
        name: 显示名称
        skill_id: 可学习的技能ID，必须存在于技能模板中
        rarity: 稀有度
    """
    model_config = ConfigDict(extra="forbid")

    id: str = Field(..., min_length=1, description="技能书唯一标识")
    name: str = Field(..., min_length=1, description="显示名称")
    skill_id: str = Field(..., min_length=1, description="可学习的技能ID")
    rarity: str = Field("white", description=f"稀有度: {sorted(RARITY_VALUES)}")

    @field_validator("rarity")
    @classmethod
    def validate_rarity(cls, v: str) -> str:
        if v not in RARITY_VALUES:
            raise ValueError(f"未知稀有度 '{v}'，有效值: {sorted(RARITY_VALUES)}")
        return v


class SkillTemplates(BaseModel):
    skills: List[SkillTemplate]
    skill_books: List[SkillBookTemplate] = Field(default_factory=list)

    @field_validator("skills")
    @classmethod
    def validate_unique_ids(cls, v: List[SkillTemplate]) -> List[SkillTemplate]:
        ids = [s.id for s in v]
        if len(ids) != len(set(ids)):
            duplicates = [i for i in ids if ids.count(i) > 1]
            raise ValueError(f"技能ID重复: {sorted(set(duplicates))}")
        return v

    @field_validator("skill_books")
    @classmethod
    def validate_unique_book_ids(cls, v: List[SkillBookTemplate]) -> List[SkillBookTemplate]:
        ids = [b.id for b in v]
        if len(ids) != len(set(ids)):
            duplicates = [i for i in ids if ids.count(i) > 1]
            raise ValueError(f"技能书ID重复: {sorted(set(duplicates))}")
        return v


# ==================== 事件模板 ====================

class EventChoice(BaseModel):
    """事件选项

    Attributes:
        name: 选项显示名称
        effect: 选择后触发的效果
        cost_gold: 金币消耗
        cost_hp_percent: 生命百分比消耗
        cost_item: 是否消耗一件装备
    """
    model_config = ConfigDict(extra="allow")

    name: str = Field(..., description="选项显示名称")
    effect: str = Field(..., description="选择后触发的效果ID")
    cost_gold: Optional[int] = Field(None, ge=0, description="金币消耗，>=0")
    cost_hp_percent: Optional[float] = Field(None, ge=0, le=1.0, description="生命百分比消耗，0~1.0")
    cost_item: Optional[bool] = Field(None, description="是否消耗一件装备")
    duration: Optional[int] = Field(None, description="效果持续回合")
    value: Optional[int] = Field(None, description="效果数值")
    curse_duration: Optional[int] = Field(None, description="诅咒持续回合")


class EventTemplate(BaseModel):
    """地牢事件模板配置

    Attributes:
        id: 事件唯一标识
        name: 事件显示名称
        type: 事件类型: trap/benefit/merchant/altar/curse/treasure
        trigger: 触发方式: enter_room/interact/kill_monster/use_item
        probability: 触发概率，0~1
        description: 事件描述文本
        effect: 主效果ID
        choices: 玩家可选择的选项列表
        min_floor: 最低出现楼层
        max_floor: 最高出现楼层，None表示无上限
    """
    model_config = ConfigDict(extra="forbid")

    id: str = Field(..., min_length=1, description="事件唯一标识")
    name: str = Field(..., min_length=1, description="事件显示名称")
    type: str = Field(..., description=f"事件类型: {sorted(EVENT_TYPES)}")
    trigger: str = Field(..., description=f"触发方式: {sorted(EVENT_TRIGGERS)}")
    probability: float = Field(..., gt=0, le=1.0, description="触发概率，0~1")
    description: str = Field(..., min_length=1, description="事件描述文本")
    effect: Optional[str] = Field(None, description="主效果ID")
    choices: Optional[List[EventChoice]] = Field(None, description="玩家可选择的选项列表")
    min_floor: int = Field(1, ge=1, description="最低出现楼层")
    max_floor: Optional[int] = Field(None, ge=1, description="最高出现楼层，None表示无上限")
    shop_items: Optional[int] = Field(None, description="商人出售的物品数量")
    price_multiplier: Optional[float] = Field(None, gt=0, description="价格倍率")
    curse_type: Optional[str] = Field(None, description="诅咒类型")
    curse_duration: Optional[int] = Field(None, description="诅咒持续回合")
    loot_rarity_bonus: Optional[int] = Field(None, description="掉落稀有度加成")
    trap_types: Optional[List[str]] = Field(None, description="可能出现的陷阱类型列表")
    damage_range: Optional[List[int]] = Field(None, description="伤害范围 [min, max]")
    turns_limit: Optional[int] = Field(None, description="限时回合数")
    item_count: Optional[List[int]] = Field(None, description="物品数量范围 [min, max]")

    @field_validator("type")
    @classmethod
    def validate_event_type(cls, v: str) -> str:
        if v not in EVENT_TYPES:
            raise ValueError(f"未知事件类型 '{v}'，有效值: {sorted(EVENT_TYPES)}")
        return v

    @field_validator("trigger")
    @classmethod
    def validate_trigger(cls, v: str) -> str:
        if v not in EVENT_TRIGGERS:
            raise ValueError(f"未知触发方式 '{v}'，有效值: {sorted(EVENT_TRIGGERS)}")
        return v

    @field_validator("damage_range", "item_count")
    @classmethod
    def validate_range(cls, v: Optional[List[int]]) -> Optional[List[int]]:
        if v is not None:
            if len(v) != 2:
                raise ValueError(f"范围必须为 [min, max] 两个值，当前: {v}")
            if v[0] > v[1]:
                raise ValueError(f"范围最小值不能大于最大值: {v}")
        return v


class EventTemplates(BaseModel):
    events: List[EventTemplate]

    @field_validator("events")
    @classmethod
    def validate_unique_ids(cls, v: List[EventTemplate]) -> List[EventTemplate]:
        ids = [e.id for e in v]
        if len(ids) != len(set(ids)):
            duplicates = [i for i in ids if ids.count(i) > 1]
            raise ValueError(f"事件ID重复: {sorted(set(duplicates))}")
        return v


# ==================== 配置加载器 ====================

class ConfigLoader:
    """配置加载与校验器

    在服务启动时调用 `validate_all_configs()` 校验所有配置文件，
    失败时打印清晰的错误信息并退出进程。
    """

    def __init__(self, data_dir: str | Path = "data"):
        self.data_dir = Path(data_dir)
        self.monsters: Optional[MonsterTemplates] = None
        self.items: Optional[ItemTemplates] = None
        self.skills: Optional[SkillTemplates] = None
        self.events: Optional[EventTemplates] = None

    def _load_json(self, path: Path) -> Dict[str, Any]:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)

    def _format_validation_error(self, file_name: str, e: ValidationError) -> str:
        lines = [f"\n{'='*60}"]
        lines.append(f"❌ 配置文件校验失败: {file_name}")
        lines.append(f"{'='*60}")

        for i, err in enumerate(e.errors(), 1):
            loc = " → ".join(str(x) for x in err["loc"])
            lines.append(f"\n  错误 #{i}:")
            lines.append(f"    位置: {loc}")
            lines.append(f"    问题: {err['msg']}")
            lines.append(f"    错误类型: {err['type']}")
            if "input" in err:
                lines.append(f"    实际值: {repr(err['input'])}")

        lines.append(f"\n{'='*60}\n")
        return "\n".join(lines)

    def validate_monsters(self) -> Tuple[bool, Optional[MonsterTemplates]]:
        """校验怪物模板"""
        path = self.data_dir / "monsters" / "templates.json"
        try:
            data = self._load_json(path)
            templates = MonsterTemplates(**data)
            self.monsters = templates
            return True, templates
        except ValidationError as e:
            print(self._format_validation_error("monsters/templates.json", e), file=sys.stderr)
            return False, None
        except FileNotFoundError:
            print(f"❌ 配置文件不存在: {path}", file=sys.stderr)
            return False, None

    def validate_items(self) -> Tuple[bool, Optional[ItemTemplates]]:
        """校验物品模板"""
        path = self.data_dir / "items" / "templates.json"
        try:
            data = self._load_json(path)
            templates = ItemTemplates(**data)
            self.items = templates
            return True, templates
        except ValidationError as e:
            print(self._format_validation_error("items/templates.json", e), file=sys.stderr)
            return False, None
        except FileNotFoundError:
            print(f"❌ 配置文件不存在: {path}", file=sys.stderr)
            return False, None

    def validate_skills(self) -> Tuple[bool, Optional[SkillTemplates]]:
        """校验技能模板"""
        path = self.data_dir / "skills" / "skills.json"
        try:
            data = self._load_json(path)
            templates = SkillTemplates(**data)
            self.skills = templates
            return True, templates
        except ValidationError as e:
            print(self._format_validation_error("skills/skills.json", e), file=sys.stderr)
            return False, None
        except FileNotFoundError:
            print(f"❌ 配置文件不存在: {path}", file=sys.stderr)
            return False, None

    def validate_events(self) -> Tuple[bool, Optional[EventTemplates]]:
        """校验事件模板"""
        path = self.data_dir / "events" / "events.json"
        try:
            data = self._load_json(path)
            templates = EventTemplates(**data)
            self.events = templates
            return True, templates
        except ValidationError as e:
            print(self._format_validation_error("events/events.json", e), file=sys.stderr)
            return False, None
        except FileNotFoundError:
            print(f"❌ 配置文件不存在: {path}", file=sys.stderr)
            return False, None

    def validate_all_configs(self, exit_on_failure: bool = True) -> bool:
        """校验所有配置文件

        Args:
            exit_on_failure: 校验失败时是否直接退出进程

        Returns:
            bool: 所有配置校验通过返回True
        """
        print("🔍 开始校验配置文件...\n")

        results = [
            ("怪物模板", self.validate_monsters()),
            ("物品模板", self.validate_items()),
            ("技能模板", self.validate_skills()),
            ("事件模板", self.validate_events()),
        ]

        all_passed = True
        for name, (ok, data) in results:
            if ok:
                count = len(data.monsters) if hasattr(data, "monsters") else (
                    len(data.skills) if hasattr(data, "skills") else (
                        len(data.events) if hasattr(data, "events") else (
                            len(data.all_items()) if hasattr(data, "all_items") else 0
                        )
                    )
                )
                print(f"  ✅ {name}: {count} 条记录校验通过")
            else:
                print(f"  ❌ {name}: 校验失败")
                all_passed = False

        if all_passed:
            print("\n🎉 所有配置文件校验通过！\n")
        else:
            print("\n💥 部分配置校验失败，请修复后重试。\n", file=sys.stderr)
            if exit_on_failure:
                sys.exit(1)

        return all_passed


_global_loader: Optional[ConfigLoader] = None


def get_config_loader(data_dir: str | Path = "data") -> ConfigLoader:
    """获取全局配置加载器"""
    global _global_loader
    if _global_loader is None:
        _global_loader = ConfigLoader(data_dir)
    return _global_loader


def validate_configs_on_startup(data_dir: str | Path = "data") -> None:
    """启动时校验所有配置，失败则退出"""
    loader = get_config_loader(data_dir)
    loader.validate_all_configs(exit_on_failure=True)
