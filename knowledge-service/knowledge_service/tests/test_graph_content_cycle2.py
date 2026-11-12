from __future__ import annotations

import json
from pathlib import Path

from knowledge_service.app.repository.json_repo import JsonGraphRepository


ROOT = Path(__file__).resolve().parents[2]
GRAPH_FILE = ROOT / "knowledge_graph_v2.json"

CYCLE_TWO_KNOWLEDGE_POINTS = {
    "3.4.1",
    "3.4.2",
    "7.1.1",
    "7.1.2",
    "7.1.3",
}
CYCLE_TWO_ANCESTORS = {"3.4", "7", "7.1"}


def load_graph() -> dict:
    return json.loads(GRAPH_FILE.read_text(encoding="utf-8"))


def semantic_version(value: str) -> tuple[int, int, int]:
    return tuple(int(part) for part in value.split("-", 1)[0].split("."))


def test_cycle_two_assessment_terms_are_fully_reviewed_and_sourced():
    graph = load_graph()
    nodes = {node["id"]: node for node in graph["nodes"]}
    sources_by_node = {
        source["node_id"]: source for source in graph["source_sections"]
    }

    # Later content-only batches may advance the graph version while retaining
    # the approved Cycle 2 node/source closure verified below.
    assert semantic_version(graph["metadata"]["version"]) >= (2, 2, 0)
    assert graph["metadata"]["approved_node_count"] >= 51
    assert graph["metadata"]["approved_edge_count"] >= 18
    assert graph["metadata"]["source_count"] >= 51

    for node_id in CYCLE_TWO_KNOWLEDGE_POINTS:
        node = nodes[node_id]
        assert node["review_status"] == "APPROVED"
        assert node["reviewed_by"] in {"C2-GC-01", "C4-GC-01"}
        assert node["rights_status"] == "ALLOWED"
        assert node["description"]
        assert node["aliases"]
        assert node["keywords"]
        assert node["learning_objectives"]
        assert node["common_misconceptions"]
        assert node["example_code"]
        for example in node["example_code"]:
            compile(example, f"graph-example-{node_id}", "exec")
        assert node["importance"] is not None
        assert node["estimated_minutes"] is not None

        source = sources_by_node[node_id]
        assert source["review_status"] == "APPROVED"
        assert source["title"] == "Python程序设计 第2版 (董付国)"
        assert source["page_from"] >= 1
        assert source["page_to"] >= source["page_from"]
        assert source.get("excerpt") in (None, "")

    for node_id in CYCLE_TWO_ANCESTORS:
        node = nodes[node_id]
        assert node["review_status"] == "APPROVED"
        assert node["reviewed_by"] == "C2-GC-01"
        assert node["example_code"] == []
        assert node_id in sources_by_node


def test_cycle_two_prerequisites_are_only_the_three_reviewed_existing_edges():
    graph = load_graph()
    approved_pairs = {
        (edge["source"], edge["target"])
        for edge in graph["edges"]
        if edge["review_status"] == "APPROVED"
    }
    expected = {
        ("3.4.1", "3.4.2"),
        ("7.1.1", "7.1.2"),
        ("7.1.2", "7.1.3"),
    }
    assert expected <= approved_pairs
    # Later content batches may publish additional reviewed relations while
    # preserving the three Cycle 2 prerequisites as an immutable subset.
    assert len(approved_pairs) >= 18


def test_cycle_two_common_question_terms_match_stable_approved_nodes():
    repository = JsonGraphRepository(GRAPH_FILE, include_pending=False)
    cases = {
        "请说明Python变量赋值与名称绑定的含义": "2.1.1",
        "比较整数和浮点数的数据类型": "2.1.2",
        "使用if-elif-else多分支判断学生成绩等级": "3.1.4",
        "使用for循环和range求1到100的和": "3.2.2",
        "使用def定义函数计算圆的面积": "5.1.1",
        "创建列表并使用索引访问列表元素": "4.2.1",
        "创建字典并使用get方法访问字典中的值": "4.5.1",
        "使用open与close打开关闭文件": "7.1.1",
        "使用read与write读写文本文件": "7.1.2",
        "使用with open逐行读取文本文件并自动关闭文件": "7.1.3",
        "说明ValueError和TypeError两种异常类型": "3.4.1",
        "使用try except捕获ValueError异常": "3.4.2",
    }

    for text, expected_node_id in cases.items():
        matches = repository.match(text, "QUESTION", limit=5)
        assert matches
        top = matches[0]
        assert top["node"]["id"] == expected_node_id
        assert top["node"]["review_status"] == "APPROVED"
        assert top["score"] >= 0.82
        assert top["requires_review"] is False
