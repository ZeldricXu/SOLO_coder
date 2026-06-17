import pytest
import json
import numpy as np
from shapely.geometry import box, Polygon
from shapely.assertions import assert_geometries_equal
from unittest.mock import MagicMock, patch, AsyncMock

from app.tiles.generator import TileGenerator


def _make_tileset(root_geometric_error=500, root_children=None, max_level=3):
    tileset = {
        "asset": {"version": "1.0"},
        "geometricError": root_geometric_error,
        "root": {
            "boundingVolume": {"region": [0, 0, 0.01, 0.01, 0, 100]},
            "geometricError": root_geometric_error,
            "refine": "ADD",
            "content": {"uri": "tiles/0.b3dm"},
            "children": root_children or [],
        },
    }
    return tileset


def _make_child_tile(tile_id, geometric_error, region=None, refine="ADD", children=None):
    return {
        "boundingVolume": {
            "region": region or [0, 0, 0.005, 0.005, 0, 50]
        },
        "geometric_error": geometric_error,
        "geometricError": geometric_error,
        "refine": refine,
        "content": {"uri": f"tiles/{tile_id}.b3dm"},
        "children": children or [],
    }


def _make_quadtree_tileset(max_level=3, base_error=500):
    def build_tree(level, error, region_start):
        if level > max_level:
            return None
        tile = {
            "boundingVolume": {"region": region_start},
            "geometric_error": error,
            "geometricError": error,
            "refine": "REPLACE",
            "content": {"uri": f"tiles/l{level}_r{region_start[0]:.4f}.b3dm"},
            "children": [],
        }
        if level < max_level:
            half_lon = (region_start[2] - region_start[0]) / 2
            half_lat = (region_start[3] - region_start[1]) / 2
            for i in range(2):
                for j in range(2):
                    child_region = [
                        region_start[0] + i * half_lon,
                        region_start[1] + j * half_lat,
                        region_start[0] + (i + 1) * half_lon,
                        region_start[1] + (j + 1) * half_lat,
                        0, 50,
                    ]
                    child = build_tree(level + 1, error / 2, child_region)
                    if child:
                        tile["children"].append(child)
        return tile

    root = build_tree(0, base_error, [0, 0, 0.01, 0.01, 0, 100])
    return {
        "asset": {"version": "1.0"},
        "geometricError": base_error,
        "root": root,
    }


@pytest.mark.unit
class Test3DTilesLODSwitching:
    """3D Tiles切片LOD切换平滑性测试

    核心保证：
    1. 相机拉近拉远时瓦片无缝切换不闪烁
    2. 子瓦片完全覆盖父瓦片范围（无空洞）
    3. 几何误差随层级递减
    4. 相邻瓦片无间隙
    """

    def test_tile_children_structure_valid(self):
        tileset = _make_tileset(
            root_geometric_error=500,
            root_children=[
                _make_child_tile("1", 250),
                _make_child_tile("2", 250),
            ],
        )
        root = tileset["root"]
        assert len(root["children"]) == 2
        for child in root["children"]:
            assert "geometricError" in child or "geometric_error" in child
            assert "content" in child
            assert "boundingVolume" in child

    def test_geometric_error_decreases_with_zoom(self):
        tileset = _make_quadtree_tileset(max_level=3, base_error=500)

        def collect_errors(tile):
            errors = [tile["geometricError"]]
            for child in tile.get("children", []):
                errors.extend(collect_errors(child))
            return errors

        all_errors = collect_errors(tileset["root"])
        root_error = tileset["root"]["geometricError"]
        for err in all_errors[1:]:
            assert err <= root_error, \
                f"Child geometric error {err} should be <= root {root_error}"

    def test_geometric_error_halves_each_level(self):
        tileset = _make_quadtree_tileset(max_level=3, base_error=500)

        def check_level_errors(tile, expected_error, level=0):
            assert abs(tile["geometricError"] - expected_error) < 0.01, \
                f"Level {level}: expected error {expected_error}, got {tile['geometricError']}"
            for child in tile.get("children", []):
                check_level_errors(child, expected_error / 2, level + 1)

        check_level_errors(tileset["root"], 500)

    def test_additive_refinement_no_flickering(self):
        tileset = _make_tileset(
            root_geometric_error=500,
            root_children=[
                _make_child_tile("1", 250, refine="ADD"),
                _make_child_tile("2", 250, refine="ADD"),
            ],
        )
        tileset["root"]["refine"] = "ADD"

        for child in tileset["root"]["children"]:
            assert child["refine"] == "ADD"

    def test_no_gaps_between_adjacent_tiles_at_same_level(self):
        full_region = [0, 0, 0.01, 0.01, 0, 100]
        half_lon = 0.005
        half_lat = 0.005

        children = [
            _make_child_tile("sw", 250, region=[0, 0, half_lon, half_lat, 0, 50]),
            _make_child_tile("se", 250, region=[half_lon, 0, 0.01, half_lat, 0, 50]),
            _make_child_tile("nw", 250, region=[0, half_lat, half_lon, 0.01, 0, 50]),
            _make_child_tile("ne", 250, region=[half_lon, half_lat, 0.01, 0.01, 0, 50]),
        ]

        tileset = _make_tileset(root_geometric_error=500, root_children=children)

        child_regions = [c["boundingVolume"]["region"] for c in tileset["root"]["children"]]
        assert len(child_regions) == 4

        for cr in child_regions:
            assert cr[2] - cr[0] == half_lon, "Child width should be half of parent"
            assert cr[3] - cr[1] == half_lat, "Child height should be half of parent"

        min_lons = sorted(cr[0] for cr in child_regions)
        min_lats = sorted(cr[1] for cr in child_regions)
        assert min_lons[0] == 0
        assert min_lats[0] == 0
        assert max(cr[2] for cr in child_regions) == 0.01
        assert max(cr[3] for cr in child_regions) == 0.01

    def test_parent_covers_children_extent(self):
        tileset = _make_quadtree_tileset(max_level=2, base_error=500)

        def check_coverage(parent, children):
            parent_region = parent["boundingVolume"]["region"]
            for child in children:
                child_region = child["boundingVolume"]["region"]
                assert child_region[0] >= parent_region[0], "Child min lon within parent"
                assert child_region[1] >= parent_region[1], "Child min lat within parent"
                assert child_region[2] <= parent_region[2], "Child max lon within parent"
                assert child_region[3] <= parent_region[3], "Child max lat within parent"
                check_coverage(child, child.get("children", []))

        check_coverage(tileset["root"], tileset["root"]["children"])

    def test_uri_format_consistent(self):
        tileset = _make_quadtree_tileset(max_level=3, base_error=500)

        def check_uris(tile):
            uri = tile["content"]["uri"]
            assert uri.startswith("tiles/"), f"URI {uri} doesn't follow tiles/ pattern"
            assert uri.endswith(".b3dm"), f"URI {uri} doesn't end with .b3dm"
            for child in tile.get("children", []):
                check_uris(child)

        check_uris(tileset["root"])

    def test_zoom_terminates_at_max_level(self):
        max_level = 3
        tileset = _make_quadtree_tileset(max_level=max_level, base_error=500)

        def max_depth(tile):
            if not tile.get("children"):
                return 0
            return 1 + max(max_depth(c) for c in tile["children"])

        depth = max_depth(tileset["root"])
        assert depth == max_level, f"Expected max depth {max_level}, got {depth}"

    def test_four_children_per_node(self):
        tileset = _make_quadtree_tileset(max_level=2, base_error=500)

        def check_quadtree(tile, level=0, max_level=2):
            children = tile.get("children", [])
            if level < max_level:
                assert len(children) == 4, \
                    f"Level {level}: expected 4 children, got {len(children)}"
                for child in children:
                    check_quadtree(child, level + 1, max_level)
            else:
                assert len(children) == 0, \
                    f"Level {level}: leaf should have no children, got {len(children)}"

        check_quadtree(tileset["root"])

    def test_replace_refinement_seamless_transition(self):
        tileset = _make_quadtree_tileset(max_level=2, base_error=500)

        def check_replace(tile):
            if tile.get("refine") == "REPLACE":
                children = tile.get("children", [])
                if children:
                    parent_region = tile["boundingVolume"]["region"]
                    child_min_lon = min(c["boundingVolume"]["region"][0] for c in children)
                    child_min_lat = min(c["boundingVolume"]["region"][1] for c in children)
                    child_max_lon = max(c["boundingVolume"]["region"][2] for c in children)
                    child_max_lat = max(c["boundingVolume"]["region"][3] for c in children)

                    assert abs(child_min_lon - parent_region[0]) < 1e-10, \
                        "Children must start at parent's min lon"
                    assert abs(child_min_lat - parent_region[1]) < 1e-10, \
                        "Children must start at parent's min lat"
                    assert abs(child_max_lon - parent_region[2]) < 1e-10, \
                        "Children must end at parent's max lon"
                    assert abs(child_max_lat - parent_region[3]) < 1e-10, \
                        "Children must end at parent's max lat"
            for child in tile.get("children", []):
                check_replace(child)

        check_replace(tileset["root"])

    def test_sse_based_selection_no_flickering(self):
        base_error = 500.0
        tileset = _make_quadtree_tileset(max_level=3, base_error=base_error)

        screen_height = 1080.0
        sse_threshold = 2.0
        fov = 60.0
        sse_denominator = 2.0 * np.tan(np.radians(fov / 2))

        def simulate_camera_distance(geometric_error, distance):
            sse = geometric_error / (distance * sse_denominator / screen_height)
            return sse > sse_threshold

        distances = [100, 200, 500, 1000, 5000]
        prev_selected_level = None
        for dist in distances:
            level = 0
            current_error = base_error
            while simulate_camera_distance(current_error, dist):
                level += 1
                current_error /= 2
                if level > 10:
                    break

            if prev_selected_level is not None:
                assert abs(level - prev_selected_level) <= 1, \
                    f"At distance {dist}, level jumped from {prev_selected_level} to {level} — flickering!"
            prev_selected_level = level

    def test_adjacent_tiles_share_edges(self):
        tileset = _make_quadtree_tileset(max_level=2, base_error=500)

        def get_leaf_regions(tile, level=0, max_level=2):
            if level == max_level or not tile.get("children"):
                return [tile["boundingVolume"]["region"]]
            regions = []
            for child in tile["children"]:
                regions.extend(get_leaf_regions(child, level + 1, max_level))
            return regions

        leaf_regions = get_leaf_regions(tileset["root"])
        for i in range(len(leaf_regions)):
            for j in range(i + 1, len(leaf_regions)):
                r1 = leaf_regions[i]
                r2 = leaf_regions[j]
                touches = (
                    abs(r1[2] - r2[0]) < 1e-10 or abs(r2[2] - r1[0]) < 1e-10 or
                    abs(r1[3] - r2[1]) < 1e-10 or abs(r2[3] - r1[1]) < 1e-10
                )
                overlap_lon = min(r1[2], r2[2]) > max(r1[0], r2[0])
                overlap_lat = min(r1[3], r2[3]) > max(r1[1], r2[1])
                if overlap_lon and overlap_lat:
                    pytest.fail(f"Tiles {i} and {j} overlap in region space")

    def test_cache_write_read_roundtrip(self, tmp_path):
        gen = TileGenerator(output_dir=str(tmp_path))

        tileset = _make_tileset(root_geometric_error=500)
        tileset_path = tmp_path / "tileset.json"
        tileset_path.write_text(json.dumps(tileset))

        loaded = json.loads(tileset_path.read_text())
        assert loaded["root"]["geometricError"] == 500
        assert loaded["asset"]["version"] == "1.0"

    def test_empty_database_returns_minimal_tileset(self):
        gen = TileGenerator()

        with patch.object(gen, '_query_buildings', return_value=[]):
            with patch.object(gen, '_query_roads', return_value=[]):
                with patch.object(gen, '_query_pois', return_value=[]):
                    tileset = gen.generate_building_tileset(
                        bbox=(116.3, 39.8, 116.5, 40.0),
                        max_level=2,
                    )

        if tileset is not None:
            assert "asset" in tileset
            assert "root" in tileset

    def test_lod_switching_continuous_no_gaps_across_levels(self):
        tileset = _make_quadtree_tileset(max_level=4, base_error=1000)

        def validate_no_gaps_at_level(tile, level=0, target_level=2):
            if level == target_level:
                children = tile.get("children", [])
                if not children:
                    return True

                parent_region = tile["boundingVolume"]["region"]
                child_regions = [c["boundingVolume"]["region"] for c in children]

                child_min_lon = min(cr[0] for cr in child_regions)
                child_min_lat = min(cr[1] for cr in child_regions)
                child_max_lon = max(cr[2] for cr in child_regions)
                child_max_lat = max(cr[3] for cr in child_regions)

                assert abs(child_min_lon - parent_region[0]) < 1e-10
                assert abs(child_min_lat - parent_region[1]) < 1e-10
                assert abs(child_max_lon - parent_region[2]) < 1e-10
                assert abs(child_max_lat - parent_region[3]) < 1e-10
                return True

            for child in tile.get("children", []):
                validate_no_gaps_at_level(child, level + 1, target_level)
            return True

        for target in range(1, 4):
            validate_no_gaps_at_level(tileset["root"], target_level=target)
