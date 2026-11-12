import json
import unittest

from app.prompts.tutor import SYSTEM_PROMPT, TUTOR_PROMPT_VERSION, build_tutor_messages
from tests import test_tutor_agent as fixtures


class TutorPromptStyleTest(unittest.TestCase):
    def test_student_prose_is_separate_from_machine_citations(self):
        self.assertEqual(TUTOR_PROMPT_VERSION, "tutor-graph-rag-v4")
        self.assertIn("直接回应问题，像老师面对面讲解", SYSTEM_PROMPT)
        self.assertIn("不要把内部 source id、node_id、节点编号", SYSTEM_PROMPT)
        self.assertIn("cited_source_ids 仍必须准确、完整", SYSTEM_PROMPT)
        self.assertIn("cited_source_ids 必须非空", SYSTEM_PROMPT)
        self.assertIn("不要为了语气流畅补编内容", SYSTEM_PROMPT)
        self.assertIn("学生询问出处", SYSTEM_PROMPT)
        self.assertIn("不确定性说明只针对影响当前问题结论的缺失信息", SYSTEM_PROMPT)

    def test_style_change_keeps_graph_sources_and_student_question_intact(self):
        request = fixtures.TutorAgentTest.request()
        context = fixtures.approved_context()
        messages = build_tutor_messages(request, context)
        graph = json.loads(messages[0].content.split("\nGRAPH_RAG_CONTEXT:\n", 1)[1])
        self.assertEqual(graph["primary"]["sources"][0]["id"], context.sources[0].id)
        self.assertEqual(graph["primary"]["sources"][0]["title"], context.sources[0].title)
        self.assertEqual(graph["primary"]["knowledge_point"]["id"], request.knowledge_point_id)
        current_input = json.loads(messages[-1].content.split("当前输入：", 1)[1])
        self.assertEqual(current_input["question"], request.message)
        self.assertTrue(messages[-1].content.startswith("请自然、清楚地解答学生"))
