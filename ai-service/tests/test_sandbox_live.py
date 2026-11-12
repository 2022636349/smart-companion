from __future__ import annotations

import asyncio
import os
import subprocess
import unittest

from app.contracts.sandbox import SandboxRunRequest, SandboxStatus
from app.sandbox.runner import SandboxBusyError, SandboxRunner


RUN_LIVE = os.getenv("RUN_SANDBOX_LIVE_TESTS") == "1"
DOCKER = os.getenv("SANDBOX_DOCKER_COMMAND", "docker")
IMAGE = os.getenv("SANDBOX_PYTHON_IMAGE", "python:3.11.15-alpine3.23")


def make_request(
    source: str,
    *,
    tests: list[dict] | None = None,
    time_ms: int = 500,
    memory_mb: int = 64,
    output_kb: int = 8,
) -> SandboxRunRequest:
    return SandboxRunRequest.model_validate(
        {
            "request_id": "sandbox-live-request-0001",
            "execution_mode": "GRADING",
            "language": "PYTHON_3_11",
            "source_code": source,
            "test_cases": tests
            or [
                {
                    "test_case_id": 1,
                    "input_data": "hello\n",
                    "expected_output": "hello\n",
                    "score": 1,
                    "visibility": "PUBLIC",
                    "compare_mode": "EXACT",
                }
            ],
            "limits": {
                "time_ms": time_ms,
                "memory_mb": memory_mb,
                "output_kb": output_kb,
            },
        }
    )


@unittest.skipUnless(RUN_LIVE, "set RUN_SANDBOX_LIVE_TESTS=1")
class DockerSandboxLiveTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.runner = SandboxRunner(
            docker_command=DOCKER,
            image=IMAGE,
            max_concurrency=2,
            queue_wait_seconds=0.1,
        )

    def tearDown(self) -> None:
        residual = subprocess.run(
            [
                DOCKER,
                "ps",
                "-a",
                "--filter",
                "name=education-sandbox",
                "--format",
                "{{.ID}} {{.Names}} {{.Status}}",
            ],
            capture_output=True,
            text=True,
            timeout=5,
            check=True,
        ).stdout.strip()
        self.assertEqual(residual, "", f"residual containers: {residual}")

    async def test_public_hidden_success_and_wrong_answer_redaction(self) -> None:
        result = await self.runner.run(
            make_request(
                "print(input())",
                tests=[
                    {
                        "test_case_id": 1,
                        "input_data": "public\n",
                        "expected_output": "public\n",
                        "score": 2,
                        "visibility": "PUBLIC",
                        "compare_mode": "EXACT",
                    },
                    {
                        "test_case_id": 2,
                        "input_data": "hidden-input-secret\n",
                        "expected_output": "hidden-expected-secret\n",
                        "score": 3,
                        "visibility": "HIDDEN",
                        "compare_mode": "EXACT",
                    },
                ],
            )
        )
        self.assertEqual(result.status, SandboxStatus.PARTIAL)
        self.assertEqual(result.passed_count, 1)
        self.assertEqual(result.achieved_score, 2)
        self.assertEqual(result.test_results[0].actual_output, "public\n")
        hidden = result.test_results[1]
        self.assertIsNone(hidden.actual_output)
        self.assertIsNone(hidden.expected_output)
        self.assertIsNone(hidden.error_message)
        serialized = result.model_dump_json()
        self.assertNotIn("hidden-input-secret", serialized)
        self.assertNotIn("hidden-expected-secret", serialized)

    async def test_syntax_runtime_network_readonly_and_process_denial(self) -> None:
        cases = (
            ("def broken(: pass", "SYNTAX_ERROR"),
            ("raise RuntimeError('boom')", "RUNTIME_ERROR"),
            (
                "import socket\nsocket.create_connection(('1.1.1.1', 80), timeout=0.2)",
                "RUNTIME_ERROR",
            ),
            ("open('/escape', 'w').write('x')", "RUNTIME_ERROR"),
            (
                "import subprocess\nsubprocess.run(['python3','-c','print(1)'], check=True)",
                "RUNTIME_ERROR",
            ),
        )
        for source, expected in cases:
            with self.subTest(expected=expected, source=source[:20]):
                result = await self.runner.run(make_request(source))
                self.assertEqual(result.test_results[0].status.value, expected)

    async def test_output_timeout_and_memory_limits(self) -> None:
        minimum = await self.runner.run(
            make_request("print(input())", memory_mb=16)
        )
        self.assertEqual(minimum.status, SandboxStatus.ACCEPTED)
        cases = (
            ("print('x' * 100000)", "OUTPUT_LIMIT_EXCEEDED", 500, 64, 1),
            ("while True: pass", "TIME_LIMIT_EXCEEDED", 100, 64, 8),
            (
                "items=[]\nwhile True: items.append(bytearray(1024 * 1024))",
                "MEMORY_LIMIT_EXCEEDED",
                1000,
                32,
                8,
            ),
        )
        for source, expected, timeout, memory, output in cases:
            with self.subTest(expected=expected):
                result = await self.runner.run(
                    make_request(
                        source,
                        time_ms=timeout,
                        memory_mb=memory,
                        output_kb=output,
                    )
                )
                self.assertEqual(result.test_results[0].status.value, expected)

    async def test_cancellation_reaps_container_and_capacity_reuses(self) -> None:
        task = asyncio.create_task(
            self.runner.run(make_request("import time\ntime.sleep(60)", time_ms=10_000))
        )
        deadline = asyncio.get_running_loop().time() + 5
        while self.runner.active_process_count != 1:
            if asyncio.get_running_loop().time() >= deadline:
                self.fail("sandbox process did not start")
            await asyncio.sleep(0.02)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(self.runner.active_process_count, 0)
        self.assertEqual(self.runner.active_pids, ())
        reused = await self.runner.run(make_request("print(input())"))
        self.assertEqual(reused.status, SandboxStatus.ACCEPTED)

    async def test_immediate_cancellation_during_docker_create_leaves_no_container(self) -> None:
        # Repeatedly cancel immediately after scheduling. This exercises the
        # Windows Docker CLI window where a daemon-side container can appear
        # after the client process has already received cancellation.
        for _ in range(6):
            task = asyncio.create_task(
                self.runner.run(
                    make_request("import time\ntime.sleep(60)", time_ms=10_000)
                )
            )
            await asyncio.sleep(0)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
        reused = await self.runner.run(make_request("print(input())"))
        self.assertEqual(reused.status, SandboxStatus.ACCEPTED)

    async def test_concurrency_is_bounded_and_third_request_is_rejected(self) -> None:
        first = asyncio.create_task(
            self.runner.run(make_request("import time\ntime.sleep(60)", time_ms=10_000))
        )
        second = asyncio.create_task(
            self.runner.run(make_request("import time\ntime.sleep(60)", time_ms=10_000))
        )
        deadline = asyncio.get_running_loop().time() + 5
        while self.runner.active_process_count != 2:
            if asyncio.get_running_loop().time() >= deadline:
                self.fail("two sandbox processes did not start")
            await asyncio.sleep(0.02)
        with self.assertRaises(SandboxBusyError):
            await self.runner.run(make_request("print(input())"))
        first.cancel()
        second.cancel()
        for task in (first, second):
            with self.assertRaises(asyncio.CancelledError):
                await task


if __name__ == "__main__":
    unittest.main()
