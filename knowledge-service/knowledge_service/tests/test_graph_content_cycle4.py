from __future__ import annotations

import json
from pathlib import Path

from knowledge_service.app.repository.json_repo import JsonGraphRepository


ROOT = Path(__file__).resolve().parents[2]
GRAPH_FILE = ROOT / "knowledge_graph_v2.json"

CYCLE_FOUR_ATTRIBUTION_POINTS = {
    "3.1.2",
    "3.1.3",
    "3.1.4",
    "3.2.1",
    "3.2.2",
    "3.2.3",
    "3.4.1",
    "3.4.2",
    "4.2.1",
    "4.2.2",
    "4.5.1",
    "5.1.1",
    "5.1.2",
    "7.1.1",
    "7.1.2",
    "7.1.3",
}

EXPECTED_MARKERS = {
    "3.1.2": ("条件中误用赋值号", "相等比较应使用 =="),
    "3.1.3": ("独立if", "else 依据缩进绑定"),
    "3.1.4": ("边界遗漏", "位于边界的值"),
    "3.2.1": ("死循环", "continue 之前没有更新"),
    "3.2.2": ("off-by-one", "range(1, n + 1)"),
    "3.2.3": ("break缩进错误", "第一轮就被无条件终止"),
    "3.4.1": ("异常分类", 'int("abc")'),
    "3.4.2": ("try范围过大", "try 应尽量只包住"),
    "4.2.1": ("索引越界", "len(values) - 1"),
    "4.2.2": ("remove返回None", "删除且取得元素时可使用 pop()"),
    "4.5.1": ("缺失键", "会抛出 KeyError"),
    "5.1.1": ("定义未调用", "函数体会立即运行"),
    "5.1.2": ("忘记调用括号", "函数对象而不是执行结果"),
    "7.1.1": ("读取缺失文件", "会触发 FileNotFoundError"),
    "7.1.2": ("readlines返回列表", "后者返回由各行字符串组成的列表"),
    "7.1.3": ("异常也关闭", "上下文管理仍会关闭"),
}

APPROVED_REQUIRES = {
    ("2.1.2", "2.2.1"),
    ("2.1.4", "2.2.2"),
    ("2.1.4", "2.2.3"),
    ("2.1.1", "2.2.4"),
    ("2.1.3", "2.3.1"),
    ("2.2.2", "3.1.1"),
    ("2.2.3", "3.1.1"),
    ("3.1.1", "3.1.2"),
    ("3.1.2", "3.1.3"),
    ("3.1.3", "3.1.4"),
    ("3.1.2", "3.2.1"),
    ("3.2.1", "3.2.3"),
    ("3.2.2", "3.2.3"),
    ("3.4.1", "3.4.2"),
    ("4.2.1", "4.2.2"),
    ("5.1.1", "5.1.2"),
    ("7.1.1", "7.1.2"),
    ("7.1.2", "7.1.3"),
}


def load_graph() -> dict:
    return json.loads(GRAPH_FILE.read_text(encoding="utf-8"))


def semantic_version(value: str) -> tuple[int, int, int]:
    return tuple(int(part) for part in value.split("-", 1)[0].split("."))


def test_cycle_four_only_enriches_published_attribution_content():
    graph = load_graph()
    nodes = {node["id"]: node for node in graph["nodes"]}
    approved_nodes = {
        node["id"] for node in graph["nodes"] if node["review_status"] == "APPROVED"
    }
    approved_points = {
        node["id"]
        for node in graph["nodes"]
        if node["review_status"] == "APPROVED"
        and node["node_type"] == "knowledge_point"
    }
    source_nodes = {
        source["node_id"]
        for source in graph["source_sections"]
        if source["review_status"] == "APPROVED"
    }

    assert semantic_version(graph["metadata"]["version"]) >= (2, 3, 0)
    assert graph["metadata"]["approved_node_count"] == len(approved_nodes) >= 51
    assert len(approved_points) >= 31
    assert graph["metadata"]["approved_edge_count"] >= len(APPROVED_REQUIRES) == 18
    assert graph["metadata"]["source_count"] == len(graph["source_sections"]) >= 51
    assert approved_nodes == source_nodes

    for node_id in CYCLE_FOUR_ATTRIBUTION_POINTS:
        node = nodes[node_id]
        keyword_marker, misconception_marker = EXPECTED_MARKERS[node_id]
        assert node["review_status"] == "APPROVED"
        assert node["reviewed_by"] == "C4-GC-01"
        assert node["reviewed_at"] == "2026-08-13"
        assert node["aliases"]
        assert keyword_marker in node["keywords"]
        assert len(node["common_misconceptions"]) >= 3
        assert any(
            misconception_marker in misconception
            for misconception in node["common_misconceptions"]
        )
        assert node_id in source_nodes


def test_cycle_four_keeps_reviewed_prerequisites_and_sources_stable():
    graph = load_graph()
    approved_requires = {
        (edge["source"], edge["target"])
        for edge in graph["edges"]
        if edge["review_status"] == "APPROVED" and edge["relation"] == "REQUIRES"
    }
    assert APPROVED_REQUIRES <= approved_requires
    assert all(
        source.get("excerpt") in (None, "")
        and source["review_status"] == "APPROVED"
        for source in graph["source_sections"]
    )


def test_cycle_four_error_language_matches_stable_approved_nodes():
    repository = JsonGraphRepository(GRAPH_FILE, include_pending=False)
    cases = {
        "if条件中误用赋值号判断相等": "3.1.2",
        "两个独立if导致重复执行": "3.1.3",
        "成绩分段判断遗漏边界值": "3.1.4",
        "while循环忘记更新变量造成死循环": "3.2.1",
        "range左闭右开出现off-by-one边界错误": "3.2.2",
        "break缩进错误导致第一轮就提前终止": "3.2.3",
        "ValueError和TypeError异常分类": "3.4.1",
        "try范围过大且裸except吞掉异常": "3.4.2",
        "列表索引越界触发IndexError": "4.2.1",
        "列表remove返回None但pop按索引返回元素": "4.2.2",
        "字典缺失键访问触发KeyError": "4.5.1",
        "只写函数定义未调用所以函数体不执行": "5.1.1",
        "忘记调用括号得到函数对象而非返回值": "5.1.2",
        "读取缺失文件触发FileNotFoundError": "7.1.1",
        "read返回字符串而readlines返回列表": "7.1.2",
        "with异常退出也会自动关闭文件": "7.1.3",
    }

    for text, expected_node_id in cases.items():
        matches = repository.match(text, "QUESTION", limit=5)
        assert matches
        top = matches[0]
        assert top["node"]["id"] == expected_node_id
        assert top["node"]["review_status"] == "APPROVED"
        assert top["score"] >= 0.82
        assert top["requires_review"] is False

    pending_node = next(
        node
        for node in repository.nodes.values()
        if node["review_status"] == "PENDING" and node.get("aliases")
    )
    hidden = repository.match(pending_node["aliases"][0], "QUESTION", limit=20)
    assert pending_node["id"] not in {item["node"]["id"] for item in hidden}
