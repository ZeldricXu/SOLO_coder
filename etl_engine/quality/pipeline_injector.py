from __future__ import annotations

import logging
from typing import Literal

from .online_checkpoint import CheckpointConfig

logger = logging.getLogger(__name__)

POSITION_ORDER = ["pre_transform", "mid_batch", "post_transform", "pre_load"]


class CheckpointInjector:
    def __init__(self, checkpoints: list[CheckpointConfig]) -> None:
        self._checkpoints = checkpoints

    def inject_into_transformations(
        self,
        transformations: list[dict],
    ) -> list[dict]:
        if not self._checkpoints or not transformations:
            return transformations

        pre_transform_cps = self.get_checkpoints_for_position("pre_transform")
        post_transform_cps = self.get_checkpoints_for_position("post_transform")
        mid_batch_cps = self.get_checkpoints_for_position("mid_batch")

        result: list[dict] = []

        for cp in pre_transform_cps:
            result.append(self._create_checkpoint_step(cp))

        for i, transform in enumerate(transformations):
            result.append(transform)

            if mid_batch_cps and i < len(transformations) - 1:
                for cp in mid_batch_cps:
                    result.append(self._create_checkpoint_step(cp))

        for cp in post_transform_cps:
            result.append(self._create_checkpoint_step(cp))

        logger.info(
            "Injected %d checkpoints into transformation pipeline (pre=%d, mid=%d, post=%d)",
            len(pre_transform_cps) + len(post_transform_cps) + len(mid_batch_cps),
            len(pre_transform_cps),
            len(mid_batch_cps),
            len(post_transform_cps),
        )

        return result

    def inject_into_dag(
        self,
        dag_definition: dict,
    ) -> dict:
        if not self._checkpoints:
            return dag_definition

        dag = dict(dag_definition)
        nodes = list(dag.get("nodes", []))
        edges = list(dag.get("edges", []))

        pre_transform_cps = self.get_checkpoints_for_position("pre_transform")
        post_transform_cps = self.get_checkpoints_for_position("post_transform")
        pre_load_cps = self.get_checkpoints_for_position("pre_load")

        transform_nodes = [n for n in nodes if n.get("type") in ("transform", "sql", "udf")]
        load_nodes = [n for n in nodes if n.get("type") in ("load", "sink")]

        first_transform_id = transform_nodes[0]["id"] if transform_nodes else None
        last_transform_id = transform_nodes[-1]["id"] if transform_nodes else None
        first_load_id = load_nodes[0]["id"] if load_nodes else None

        for cp in pre_transform_cps:
            cp_node = self._create_dag_node(cp)
            nodes.append(cp_node)

            if first_transform_id:
                incoming_edges = [e for e in edges if e["to"] == first_transform_id]
                for edge in incoming_edges:
                    edges.remove(edge)
                    edges.append({"from": edge["from"], "to": cp_node["id"]})
                edges.append({"from": cp_node["id"], "to": first_transform_id})

        for cp in post_transform_cps:
            cp_node = self._create_dag_node(cp)
            nodes.append(cp_node)

            if last_transform_id:
                outgoing_edges = [e for e in edges if e["from"] == last_transform_id]
                for edge in outgoing_edges:
                    edges.remove(edge)
                    edges.append({"from": cp_node["id"], "to": edge["to"]})
                edges.append({"from": last_transform_id, "to": cp_node["id"]})

        for cp in pre_load_cps:
            cp_node = self._create_dag_node(cp)
            nodes.append(cp_node)

            if first_load_id:
                incoming_edges = [e for e in edges if e["to"] == first_load_id]
                for edge in incoming_edges:
                    edges.remove(edge)
                    edges.append({"from": edge["from"], "to": cp_node["id"]})
                edges.append({"from": cp_node["id"], "to": first_load_id})

        dag["nodes"] = nodes
        dag["edges"] = edges

        total_injected = len(pre_transform_cps) + len(post_transform_cps) + len(pre_load_cps)
        logger.info(
            "Injected %d checkpoints into DAG (pre_transform=%d, post_transform=%d, pre_load=%d)",
            total_injected,
            len(pre_transform_cps),
            len(post_transform_cps),
            len(pre_load_cps),
        )

        return dag

    def get_checkpoints_for_position(
        self,
        position: Literal["pre_transform", "post_transform", "pre_load", "mid_batch"],
    ) -> list[CheckpointConfig]:
        return [cp for cp in self._checkpoints if cp.position == position]

    def _create_checkpoint_step(self, config: CheckpointConfig) -> dict:
        return {
            "type": "quality_checkpoint",
            "config": {
                "checkpoint_id": config.checkpoint_id,
            },
        }

    def _create_dag_node(self, config: CheckpointConfig) -> dict:
        return {
            "id": f"checkpoint_{config.checkpoint_id}",
            "type": "quality_checkpoint",
            "name": f"Quality Check: {config.checkpoint_id}",
            "config": {
                "checkpoint_id": config.checkpoint_id,
            },
        }
