"""Deterministic identity reuse for one course root, never concept merging."""
from typing import Any


def reusable_course_root(base_graph: dict[str, Any], nodes: list[dict[str, Any]]):
    base_roots = [n for n in base_graph.get('nodes', []) if n.get('node_type') == 'course']
    batch_roots = [n for n in nodes if n.get('node_type') == 'course']
    if len(base_roots) != 1 or len(batch_roots) != 1:
        return None
    base, incoming = base_roots[0], batch_roots[0]
    if base.get('review_status') != 'APPROVED':
        return None
    if any(root.get('level') != 0 or root.get('parent_id') not in (None, '') for root in (base, incoming)):
        return None
    # A candidate cannot reuse the identity of a different existing concept.
    if any(n.get('id') == incoming.get('id') and n.get('id') != base.get('id')
           for n in base_graph.get('nodes', [])):
        return None
    return incoming, base
