from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any

from knowledge_service.app.domain.graph_dto import node_type_for_level, normalize_source
from knowledge_service.app.repository.neo4j_repo import Neo4jGraphRepository
from knowledge_service.app.repository.course_scope import ensure_course_schema, scoped_statement, valid_course_id


def load_graph(path: str | Path) -> dict[str, Any]:
    with Path(path).open("r", encoding="utf-8") as file:
        graph = json.load(file)
    if not isinstance(graph.get("nodes"), list) or not isinstance(graph.get("edges"), list):
        raise ValueError("图谱文件必须包含 nodes 和 edges 数组")
    return graph


def import_graph(
    graph: dict[str, Any],
    uri: str,
    user: str,
    password: str,
    clear_first: bool = False,
    *,
    course_id: int = 1,
) -> tuple[int, int]:
    from neo4j import GraphDatabase

    course_id = valid_course_id(course_id)
    if (graph.get("metadata") or {}).get("course_id", 1) != course_id:
        raise ValueError("graph belongs to a different course")
    driver = GraphDatabase.driver(uri, auth=(user, password))
    try:
        with driver.session() as session:
            def run(statement: str, **params: Any):
                return session.run(scoped_statement(statement), course_id=course_id, **params)

            ensure_course_schema(session)
            run("RETURN 1").consume()
            if clear_first:
                run("MATCH (n:KnowledgeNode) DETACH DELETE n").consume()
                run("MATCH (n:GraphSource) DETACH DELETE n").consume()
                run("MATCH (n:GraphMetadata) DETACH DELETE n").consume()
            run(
                "CREATE INDEX knowledge_node_name IF NOT EXISTS "
                "FOR (n:KnowledgeNode) ON (n.name)"
            ).consume()
            run(
                "CREATE INDEX knowledge_node_parent_id IF NOT EXISTS "
                "FOR (n:KnowledgeNode) ON (n.parent_id)"
            ).consume()
            run(
                "CREATE INDEX graph_source_node_id IF NOT EXISTS "
                "FOR (s:GraphSource) ON (s.node_id)"
            ).consume()

            metadata = dict(graph.get("metadata") or {})
            metadata["course_id"] = course_id
            metadata["key"] = "current"
            run(
                """
                MERGE (m:GraphMetadata {key: $row.key})
                SET m += $row
                """,
                row=metadata,
            ).consume()

            node_rows = []
            for source in graph["nodes"]:
                node = dict(source)
                node["id"] = str(node["id"])
                node["level"] = int(node.get("level", 3))
                node["node_type"] = node_type_for_level(node["level"])
                node["review_status"] = node.get("review_status") or "PENDING"
                node["content_version"] = int(node.get("content_version") or 1)
                node["course_id"] = course_id
                node_rows.append(node)
            run(
                """
                UNWIND $rows AS row
                MERGE (n:KnowledgeNode {id: row.id})
                SET n += row
                """,
                rows=node_rows,
            ).consume()

            edge_rows: list[dict[str, Any]] = []
            edge_types = {"REQUIRES", "RELATED", "SUPPORTED_BY"}
            for edge in graph["edges"]:
                relation = str(edge.get("relation") or "REQUIRES").upper()
                if relation not in edge_types:
                    continue
                edge_rows.append(
                    {
                        "source": str(edge["source"]),
                        "target": str(edge["target"]),
                        "relation": relation,
                        "review_status": edge.get("review_status") or "PENDING",
                        "evidence": edge.get("evidence") or [],
                        "confidence": edge.get("confidence"),
                        "course_id": course_id,
                    }
                )
            for relation in sorted(edge_types):
                rows = [row for row in edge_rows if row["relation"] == relation]
                if not rows:
                    continue
                run(
                    f"""
                    UNWIND $rows AS row
                    MATCH (source:KnowledgeNode {{id: row.source}})
                    MATCH (target:KnowledgeNode {{id: row.target}})
                    MERGE (source)-[r:{relation}]->(target)
                    SET r.review_status = row.review_status,
                        r.evidence = row.evidence,
                        r.confidence = row.confidence,
                        r.course_id = $course_id
                    """,
                    rows=rows,
                ).consume()

            hierarchy_rows = [
                {"parent": str(node["parent_id"]), "child": str(node["id"])}
                for node in graph["nodes"]
                if node.get("parent_id") is not None
            ]
            run(
                """
                UNWIND $rows AS row
                MATCH (parent:KnowledgeNode {id: row.parent})
                MATCH (child:KnowledgeNode {id: row.child})
                MERGE (parent)-[r:CONTAINS]->(child)
                SET r.review_status = child.review_status,
                    r.course_id = $course_id
                """,
                rows=hierarchy_rows,
            ).consume()

            source_rows = [
                {**normalize_source(source), "course_id": course_id}
                for source in graph.get(
                    "source_sections", graph.get("sources", [])
                )
            ]
            if source_rows:
                run(
                    """
                    UNWIND $rows AS row
                    MERGE (source:GraphSource {id: row.id})
                    SET source += row
                    WITH source, row
                    MATCH (node:KnowledgeNode {id: row.node_id})
                    MERGE (node)-[rel:SUPPORTED_BY]->(source)
                    SET rel.review_status = row.review_status,
                        rel.course_id = $course_id
                    """,
                    rows=source_rows,
                ).consume()
        return len(node_rows), len(edge_rows)
    finally:
        driver.close()


def main() -> None:
    project_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description="将知识图谱 V2 数据幂等导入 Neo4j")
    parser.add_argument("--graph-file", default=os.getenv("GRAPH_FILE", str(project_root / "knowledge_graph_v2.json")))
    parser.add_argument("--uri", default=os.getenv("NEO4J_URI", "bolt://localhost:7687"))
    parser.add_argument("--user", default=os.getenv("NEO4J_USER", "neo4j"))
    parser.add_argument("--password", default=os.getenv("NEO4J_PASSWORD", ""))
    parser.add_argument("--course-id", type=int, required=True, help="目标课程ID，必须与发布物一致")
    parser.add_argument("--clear-first", action="store_true", help="导入前仅清空目标课程的数据")
    args = parser.parse_args()
    graph = load_graph(args.graph_file)
    node_count, edge_count = import_graph(
        graph, args.uri, args.user, args.password, clear_first=args.clear_first, course_id=args.course_id
    )
    print(f"导入完成：{node_count} 个节点，{edge_count} 条 REQUIRES 关系")


if __name__ == "__main__":
    main()
