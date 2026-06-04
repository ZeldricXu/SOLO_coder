import sys
import os
import random
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from server.item_system import Item, ItemFactory, AffixGenerator, Backpack, Rarity, ItemType, RARITY_NAMES


class TestAffixGenerator:
    def test_affix_values_within_range(self):
        random.seed(42)
        gen = AffixGenerator()
        has_nonempty = False
        for _ in range(100):
            prefix_name, prefix_stats, suffix_name, suffix_stats = gen.generate_affix(Rarity.BLUE, ItemType.WEAPON)
            if prefix_name or suffix_name:
                has_nonempty = True
            if "attack" in prefix_stats:
                assert 2 <= prefix_stats["attack"] <= 5
            if "attack" in suffix_stats:
                assert 1 <= suffix_stats["attack"] <= 3
        assert has_nonempty

    def test_blue_rarity_has_affixes(self):
        random.seed(42)
        gen = AffixGenerator()
        found_affix = False
        for _ in range(50):
            prefix_name, prefix_stats, suffix_name, suffix_stats = gen.generate_affix(Rarity.BLUE, ItemType.WEAPON)
            if prefix_name != "" or suffix_name != "":
                found_affix = True
                break
        assert found_affix

    def test_white_rarity_no_affixes(self):
        gen = AffixGenerator()
        prefix_name, prefix_stats, suffix_name, suffix_stats = gen.generate_affix(Rarity.WHITE, ItemType.WEAPON)
        assert prefix_name == ""
        assert suffix_name == ""
        assert prefix_stats == {}
        assert suffix_stats == {}

    def test_consumable_no_affixes(self):
        gen = AffixGenerator()
        prefix_name, prefix_stats, suffix_name, suffix_stats = gen.generate_affix(Rarity.GOLD, ItemType.CONSUMABLE)
        assert prefix_name == ""
        assert suffix_name == ""
        assert prefix_stats == {}
        assert suffix_stats == {}


class TestItemFactoryRarity:
    def test_rarity_distribution_floor_1(self):
        random.seed(42)
        factory = ItemFactory()
        counts = {r: 0 for r in Rarity}
        n = 10000
        for _ in range(n):
            r = factory._roll_rarity(1)
            counts[r] += 1
        assert counts[Rarity.WHITE] / n > 0.50
        assert 0.10 < counts[Rarity.BLUE] / n < 0.30
        assert counts[Rarity.ORANGE] / n < 0.05

    def test_rarity_distribution_floor_7(self):
        random.seed(42)
        factory = ItemFactory()
        counts = {r: 0 for r in Rarity}
        n = 10000
        for _ in range(n):
            r = factory._roll_rarity(7)
            counts[r] += 1
        assert counts[Rarity.WHITE] / n < 0.40
        assert counts[Rarity.PURPLE] / n > 0.15
        assert counts[Rarity.ORANGE] / n > 0.03


class TestBackpack:
    def test_backpack_add_item(self):
        bp = Backpack()
        item = Item(id="bone_sword", name="骨剑", item_type=ItemType.WEAPON)
        result = bp.add_item(item)
        assert result is True
        assert bp.get_item(0) is item

    def test_backpack_full_rejects(self):
        bp = Backpack()
        for i in range(20):
            item = Item(id=f"item_{i}", name=f"Item {i}", item_type=ItemType.WEAPON)
            result = bp.add_item(item)
            if i < 19:
                assert result is True
        assert bp.add_item(Item(id="extra", name="Extra", item_type=ItemType.WEAPON)) is False

    def test_backpack_consumable_stacking(self):
        bp = Backpack()
        item1 = Item(id="health_potion", name="生命药水", item_type=ItemType.CONSUMABLE, stack_count=1, stack_max=10)
        item2 = Item(id="health_potion", name="生命药水", item_type=ItemType.CONSUMABLE, stack_count=1, stack_max=10)
        bp.add_item(item1)
        result = bp.add_item(item2)
        assert result is True
        assert bp.get_item(0).stack_count == 2


class TestItem:
    def test_item_full_name(self):
        item = Item(name="骨剑", prefix_name="锋利的", suffix_name="之力")
        assert item.full_name() == "锋利的骨剑之力"

    def test_item_total_attack(self):
        item = Item(attack=5, prefix_stats={"attack": 3}, suffix_stats={"attack": 1})
        assert item.total_attack() == 9


class TestItemFactory:
    def test_create_item_from_factory(self):
        random.seed(42)
        factory = ItemFactory()
        item = factory.create_item("bone_sword", floor_depth=1)
        assert item is not None
        assert item.name == "骨剑"
        assert item.item_type == "weapon"

    def test_create_random_drop(self):
        random.seed(42)
        factory = ItemFactory()
        item = factory.create_random_drop(floor_depth=1)
        assert item is not None
        assert isinstance(item, Item)
