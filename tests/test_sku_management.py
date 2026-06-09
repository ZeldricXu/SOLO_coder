from __future__ import annotations

import time

import pytest
from sqlalchemy.orm import Session

from app.models import SkuLifecycleStatus
from app.services.sku_service import sku_service
from app.schemas.product import SkuGenerateRequest, SkuGenerateAttributeItem, SkuUpdate
from tests.factories import get_factory

pytestmark = [pytest.mark.unit, pytest.mark.sku]


class TestSkuGenerationAlgorithm:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)
        self.product, _ = self.factory.create_product_with_sku(num_skus=0)

    def test_two_colors_times_three_sizes_generates_six_skus(self):
        colors = ["红色", "蓝色"]
        sizes = ["S", "M", "L"]

        request = self.factory.create_sku_generation_request(
            product_id=self.product.id,
            colors=colors,
            sizes=sizes,
        )

        result = sku_service.generate_skus(self.db, request=request)

        assert result["total_count"] == 6
        assert result["success_count"] == 6
        assert len(result["skus"]) == 6

        expected_combinations = [(c, s) for c in colors for s in sizes]
        actual_combinations = [
            (sku.attributes["color"]["value"], sku.attributes["size"]["value"])
            for sku in result["skus"]
        ]

        for combo in expected_combinations:
            assert combo in actual_combinations

        sku_codes = [sku.sku_code for sku in result["skus"]]
        assert len(set(sku_codes)) == 6

    def test_generate_skus_creates_valid_sku_codes(self):
        request = self.factory.create_sku_generation_request(
            product_id=self.product.id,
            colors=["黑色"],
            sizes=["L"],
        )

        result = sku_service.generate_skus(self.db, request=request)
        sku = result["skus"][0]

        assert sku.sku_code.startswith(f"PRD{self.product.id:06d}-")
        assert len(sku.sku_code) == len(f"PRD{self.product.id:06d}-") + 8
        assert sku.sku_code.split("-")[1].isupper()

    def test_generate_skus_with_attributes_sets_correct_attributes(self):
        colors = ["红色"]
        sizes = ["M"]

        request = self.factory.create_sku_generation_request(
            product_id=self.product.id,
            colors=colors,
            sizes=sizes,
        )

        result = sku_service.generate_skus(self.db, request=request)
        sku = result["skus"][0]

        assert "color" in sku.attributes
        assert sku.attributes["color"]["value"] == "红色"
        assert sku.attributes["color"]["name"] == "颜色"
        assert "size" in sku.attributes
        assert sku.attributes["size"]["value"] == "M"
        assert sku.attributes["size"]["name"] == "尺寸"

    def test_generate_skus_with_single_attribute(self):
        request = SkuGenerateRequest(
            product_id=self.product.id,
            attributes=[
                SkuGenerateAttributeItem(
                    attribute_code="color",
                    attribute_name="颜色",
                    values=["红", "绿", "蓝"],
                ),
            ],
        )

        result = sku_service.generate_skus(self.db, request=request)

        assert result["total_count"] == 3
        assert result["success_count"] == 3

    def test_generate_skus_with_three_attributes(self):
        request = SkuGenerateRequest(
            product_id=self.product.id,
            attributes=[
                SkuGenerateAttributeItem(
                    attribute_code="color",
                    attribute_name="颜色",
                    values=["红", "蓝"],
                ),
                SkuGenerateAttributeItem(
                    attribute_code="size",
                    attribute_name="尺寸",
                    values=["S", "M"],
                ),
                SkuGenerateAttributeItem(
                    attribute_code="material",
                    attribute_name="材质",
                    values=["棉", "涤纶"],
                ),
            ],
        )

        result = sku_service.generate_skus(self.db, request=request)

        assert result["total_count"] == 8
        assert result["success_count"] == 8


class TestSkuLifecycleStateMachine:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)
        self.product, skus = self.factory.create_product_with_sku(num_skus=1)
        self.sku = skus[0]

    def test_concept_to_sample_transition_allowed(self):
        self.sku.lifecycle_status = SkuLifecycleStatus.CONCEPT
        self.db.commit()

        updated_sku = sku_service.update_lifecycle_status(
            self.db,
            sku_id=self.sku.id,
            target_status=SkuLifecycleStatus.SAMPLE,
        )

        assert updated_sku.lifecycle_status == SkuLifecycleStatus.SAMPLE

    def test_sample_to_production_transition_allowed(self):
        self.sku.lifecycle_status = SkuLifecycleStatus.SAMPLE
        self.db.commit()

        updated_sku = sku_service.update_lifecycle_status(
            self.db,
            sku_id=self.sku.id,
            target_status=SkuLifecycleStatus.PRODUCTION,
        )

        assert updated_sku.lifecycle_status == SkuLifecycleStatus.PRODUCTION

    def test_concept_to_production_transition_forbidden(self):
        self.sku.lifecycle_status = SkuLifecycleStatus.CONCEPT
        self.db.commit()

        with pytest.raises(Exception) as exc_info:
            sku_service.update_lifecycle_status(
                self.db,
                sku_id=self.sku.id,
                target_status=SkuLifecycleStatus.PRODUCTION,
            )

        assert "Invalid lifecycle transition" in str(exc_info.value)

    def test_end_of_life_is_terminal_state(self):
        self.sku.lifecycle_status = SkuLifecycleStatus.PRODUCTION
        self.db.commit()

        updated_sku = sku_service.update_lifecycle_status(
            self.db,
            sku_id=self.sku.id,
            target_status=SkuLifecycleStatus.END_OF_LIFE,
        )

        assert updated_sku.lifecycle_status == SkuLifecycleStatus.END_OF_LIFE

        with pytest.raises(Exception):
            sku_service.update_lifecycle_status(
                self.db,
                sku_id=self.sku.id,
                target_status=SkuLifecycleStatus.PRODUCTION,
            )

    def test_same_state_transition_allowed(self):
        self.sku.lifecycle_status = SkuLifecycleStatus.PRODUCTION
        self.db.commit()

        updated_sku = sku_service.update_lifecycle_status(
            self.db,
            sku_id=self.sku.id,
            target_status=SkuLifecycleStatus.PRODUCTION,
        )

        assert updated_sku.lifecycle_status == SkuLifecycleStatus.PRODUCTION


class TestSkuBoundaryScenarios:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)
        self.product, _ = self.factory.create_product_with_sku(num_skus=0)

    @pytest.mark.slow
    def test_sku_attribute_combinations_at_1000_limit_performance(self):
        num_values_per_attr = 32
        num_attributes = 3

        values = [f"val_{i}" for i in range(num_values_per_attr)]
        attributes = []
        for i in range(num_attributes):
            attributes.append(
                SkuGenerateAttributeItem(
                    attribute_code=f"attr_{i}",
                    attribute_name=f"属性{i}",
                    values=values[:10],
                )
            )

        request = SkuGenerateRequest(
            product_id=self.product.id,
            attributes=attributes,
        )

        start_time = time.time()
        result = sku_service.generate_skus(self.db, request=request)
        elapsed_time = time.time() - start_time

        assert result["total_count"] == 1000
        assert result["success_count"] == 1000
        assert elapsed_time < 5.0

    @pytest.mark.slow
    def test_many_skus_generation_performance(self):
        colors = [f"颜色_{i}" for i in range(10)]
        sizes = [f"尺寸_{i}" for i in range(10)]
        styles = [f"款式_{i}" for i in range(10)]

        request = SkuGenerateRequest(
            product_id=self.product.id,
            attributes=[
                SkuGenerateAttributeItem(
                    attribute_code="color",
                    attribute_name="颜色",
                    values=colors,
                ),
                SkuGenerateAttributeItem(
                    attribute_code="size",
                    attribute_name="尺寸",
                    values=sizes,
                ),
                SkuGenerateAttributeItem(
                    attribute_code="style",
                    attribute_name="款式",
                    values=styles,
                ),
            ],
        )

        start_time = time.time()
        result = sku_service.generate_skus(self.db, request=request)
        elapsed_time = time.time() - start_time

        assert result["total_count"] == 1000
        assert elapsed_time < 10.0

    def test_generate_skus_with_empty_attributes_raises_error(self):
        request = SkuGenerateRequest(
            product_id=self.product.id,
            attributes=[],
        )

        with pytest.raises(Exception) as exc_info:
            sku_service.generate_skus(self.db, request=request)

        assert "No attribute combinations" in str(exc_info.value)

    def test_generate_skus_with_attribute_having_empty_values(self):
        request = SkuGenerateRequest(
            product_id=self.product.id,
            attributes=[
                SkuGenerateAttributeItem(
                    attribute_code="color",
                    attribute_name="颜色",
                    values=[],
                ),
            ],
        )

        with pytest.raises(Exception):
            sku_service.generate_skus(self.db, request=request)

    def test_generate_skus_with_nonexistent_product(self):
        request = SkuGenerateRequest(
            product_id=999999,
            attributes=[
                SkuGenerateAttributeItem(
                    attribute_code="color",
                    attribute_name="颜色",
                    values=["红色"],
                ),
            ],
        )

        with pytest.raises(Exception) as exc_info:
            sku_service.generate_skus(self.db, request=request)

        assert "not found" in str(exc_info.value)

    def test_generate_duplicate_skus_returns_errors(self):
        colors = ["红色"]
        sizes = ["M"]

        request = self.factory.create_sku_generation_request(
            product_id=self.product.id,
            colors=colors,
            sizes=sizes,
        )

        result1 = sku_service.generate_skus(self.db, request=request)
        assert result1["success_count"] == 1

        result2 = sku_service.generate_skus(self.db, request=request)
        assert result2["success_count"] == 0
        assert len(result2["errors"]) == 1
        assert "already exists" in str(result2["errors"][0]["error"]).lower()


class TestSkuCrudOperations:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)
        self.product, self.skus = self.factory.create_product_with_sku(num_skus=3)

    def test_get_sku_by_id(self):
        sku = sku_service.get(self.db, id=self.skus[0].id)
        assert sku is not None
        assert sku.id == self.skus[0].id

    def test_get_sku_list_pagination(self):
        result = sku_service.get_multi(self.db, page=1, page_size=2)
        assert result["page"] == 1
        assert result["page_size"] == 2
        assert result["total"] >= 3
        assert len(result["items"]) == 2

    def test_update_sku_price(self):
        sku = self.skus[0]
        new_price = 999.99

        update_data = SkuUpdate(price=new_price)
        updated_sku = sku_service.update(self.db, db_obj=sku, obj_in=update_data)

        assert updated_sku.price == new_price

    def test_delete_sku(self):
        sku_id = self.skus[0].id
        sku_service.delete(self.db, id=sku_id)

        deleted_sku = sku_service.get(self.db, id=sku_id)
        assert deleted_sku is None

    def test_get_sku_by_code(self):
        sku = self.skus[0]
        result = sku_service.get_by_code(self.db, sku_code=sku.sku_code)
        assert result is not None
        assert result.id == sku.id


class TestSkuCartesianCombinations:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db

    def test_cartesian_with_single_attribute(self):
        attributes = [
            {"attribute_code": "color", "attribute_name": "颜色", "values": ["红", "绿", "蓝"]}
        ]
        combinations = sku_service._get_cartesian_combinations(attributes)
        assert len(combinations) == 3

    def test_cartesian_with_two_attributes(self):
        attributes = [
            {"attribute_code": "color", "attribute_name": "颜色", "values": ["红", "蓝"]},
            {"attribute_code": "size", "attribute_name": "尺寸", "values": ["S", "M", "L"]},
        ]
        combinations = sku_service._get_cartesian_combinations(attributes)
        assert len(combinations) == 6

        color_values = [c["color"]["value"] for c in combinations]
        size_values = [c["size"]["value"] for c in combinations]

        assert set(color_values) == {"红", "蓝"}
        assert set(size_values) == {"S", "M", "L"}
        assert ("红", "S") in [(c["color"]["value"], c["size"]["value"]) for c in combinations]

    def test_cartesian_with_four_attributes(self):
        attributes = [
            {"attribute_code": "a", "attribute_name": "A", "values": ["1", "2"]},
            {"attribute_code": "b", "attribute_name": "B", "values": ["1", "2"]},
            {"attribute_code": "c", "attribute_name": "C", "values": ["1", "2"]},
            {"attribute_code": "d", "attribute_name": "D", "values": ["1", "2"]},
        ]
        combinations = sku_service._get_cartesian_combinations(attributes)
        assert len(combinations) == 16

    def test_cartesian_with_empty_input(self):
        combinations = sku_service._get_cartesian_combinations([])
        assert combinations == []

    def test_cartesian_combination_structure(self):
        attributes = [
            {"attribute_code": "color", "attribute_name": "颜色", "values": ["红色"]},
            {"attribute_code": "size", "attribute_name": "尺寸", "values": ["M"]},
        ]
        combinations = sku_service._get_cartesian_combinations(attributes)

        assert len(combinations) == 1
        combo = combinations[0]

        assert "color" in combo
        assert combo["color"]["value"] == "红色"
        assert combo["color"]["name"] == "颜色"
        assert "size" in combo
        assert combo["size"]["value"] == "M"
        assert combo["size"]["name"] == "尺寸"
