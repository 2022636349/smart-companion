from __future__ import annotations

import asyncio
import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from app.contracts.sandbox import SandboxRunRequest, SandboxStatus
from app.sandbox.runner import (
    SandboxBusyError,
    SandboxConfigurationError,
    SandboxExecutionError,
    SandboxRunner,
    SandboxTimeoutError,
)


WORKER = Path(__file__).resolve().parents[1] / "app" / "sandbox" / "worker.py"
FAKE_WORKER_SCRIPT = r'''
import json, os, subprocess, sys, time
request = json.loads(sys.stdin.buffer.read().decode("utf-8"))
results = []
for test in request["test_cases"]:
    source = request["source_code"]
    if "sleep(60)" in source or "sleep(1)" in source:
        time.sleep(60)
    if "while True" in source:
        status, actual, error = "TIME_LIMIT_EXCEEDED", None, "Execution exceeded the time limit."
    elif "100000" in source:
        status, actual, error = "OUTPUT_LIMIT_EXCEEDED", None, "Execution exceeded the output limit."
    elif "broken(" in source:
        status, actual, error = "SYNTAX_ERROR", None, "SyntaxError: invalid syntax"
    elif "RuntimeError" in source or "open('/etc" in source or "subprocess.run" in source:
        status, actual, error = "RUNTIME_ERROR", None, "Execution failed."
    else:
        actual = test["input_data"]
        status = "ACCEPTED" if actual == test["expected_output"] else "WRONG_ANSWER"
        error = None
    results.append({"test_case_id": test["test_case_id"], "visibility": test["visibility"], "max_score": test["score"], "status": status, "actual_output": actual, "error_message": error, "time_ms": 2, "memory_kb": 0})
sys.stdout.write(json.dumps({"ok": True, "test_results": results}, separators=(",", ":")))
'''


def request(
    source: str = "print(input())",
    *,
    tests: list[dict] | None = None,
    time_ms: int = 500,
    memory_mb: int = 64,
    output_kb: int = 8,
) -> SandboxRunRequest:
    return SandboxRunRequest.model_validate(
        {
            "request_id": "sandbox-request-0001",
            "execution_mode": "GRADING",
            "language": "PYTHON_3_11",
            "source_code": source,
            "test_cases": tests
            or [
                {
                    "test_case_id": 1,
                    "input_data": "hello\n",
                    "expected_output": "hello\n",
                    "score": 2,
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


def local_runner(*, max_concurrency: int = 1, queue_wait_seconds: float = 0.05) -> SandboxRunner:
    # This test-only command executes the real isolated worker protocol. It is
    # never the production default and never bypasses Docker capability checks.
    return SandboxRunner(
        worker_command=(sys.executable, "-I", "-c", FAKE_WORKER_SCRIPT),
        max_concurrency=max_concurrency,
        queue_wait_seconds=queue_wait_seconds,
    )


class SandboxRunnerTests(unittest.IsolatedAsyncioTestCase):
    async def _wait_active(self, runner: SandboxRunner, expected: int) -> None:
        deadline = asyncio.get_running_loop().time() + 3
        while runner.active_process_count != expected:
            if asyncio.get_running_loop().time() >= deadline:
                self.fail(f"active={runner.active_process_count}, expected={expected}")
            await asyncio.sleep(0.005)

    async def test_public_and_hidden_results_are_structured_and_redacted(self) -> None:
        result = await local_runner().run(
            request(
                tests=[
                    {
                        "test_case_id": 1,
                        "input_data": "visible\n",
                        "expected_output": "visible\n",
                        "score": 2,
                        "visibility": "PUBLIC",
                        "compare_mode": "EXACT",
                    },
                    {
                        "test_case_id": 2,
                        "input_data": "hidden-secret-input\n",
                        "expected_output": "different-secret-output\n",
                        "score": 3,
                        "visibility": "HIDDEN",
                        "compare_mode": "EXACT",
                    },
                ]
            )
        )

        self.assertEqual(result.status, SandboxStatus.PARTIAL)
        self.assertEqual(result.passed_count, 1)
        self.assertEqual(result.achieved_score, 2)
        self.assertEqual(result.total_score, 5)
        self.assertEqual(result.test_results[0].actual_output, "visible\n")
        hidden = result.test_results[1]
        self.assertIsNone(hidden.actual_output)
        self.assertIsNone(hidden.expected_output)
        self.assertIsNone(hidden.error_message)
        serialized = result.model_dump_json()
        self.assertNotIn("hidden-secret-input", serialized)
        self.assertNotIn("different-secret-output", serialized)
        self.assertEqual(result.test_results[1].visibility, "HIDDEN")

    async def test_timeout_output_bomb_syntax_and_runtime_errors(self) -> None:
        runner = local_runner()
        cases = (
            ("while True: pass", "TIME_LIMIT_EXCEEDED", 100, 8),
            ("print('x' * 100000)", "OUTPUT_LIMIT_EXCEEDED", 500, 1),
            ("def broken(: pass", "SYNTAX_ERROR", 500, 8),
            ("raise RuntimeError('student-secret')", "RUNTIME_ERROR", 500, 8),
        )
        for source, expected, timeout, output in cases:
            result = await runner.run(
                request(source, time_ms=timeout, output_kb=output)
            )
            self.assertEqual(result.test_results[0].status.value, expected)
            self.assertEqual(runner.active_process_count, 0)
            self.assertEqual(runner.active_pids, ())

    async def test_network_filesystem_and_process_creation_are_denied(self) -> None:
        runner = local_runner()
        malicious = (
            "import socket\nsocket.create_connection(('127.0.0.1', 1))",
            "open('/etc/education-agent-escape', 'w').write('x')",
            "import subprocess\nsubprocess.run(['echo', 'escape'])",
        )
        # The local protocol worker is not the production security boundary,
        # but OS limits still deny filesystem writes/process fan-out. Network
        # denial is asserted against the actual Docker command separately.
        for source in malicious[1:]:
            result = await runner.run(request(source))
            self.assertIn(
                result.test_results[0].status.value,
                {"RUNTIME_ERROR", "MEMORY_LIMIT_EXCEEDED"},
            )
        docker_runner = SandboxRunner(docker_command="docker")
        command = docker_runner._command(
            request(malicious[0]), container_name="education-sandbox-test"
        )
        self.assertIn("--network=none", command)
        self.assertIn("--read-only", command)
        self.assertIn("--cap-drop=ALL", command)
        self.assertIn("--pids-limit=2", command)
        self.assertIn("--security-opt=no-new-privileges", command)
        self.assertIn("--ipc=none", command)
        self.assertIn("--pull=never", command)
        self.assertIn("--interactive", command)
        self.assertLess(command.index("--entrypoint=python3"), command.index("python:3.11.15-alpine3.23"))
        self.assertIn("education-sandbox-test", command)
        self.assertIn("--memory-swap=67108864", command)

    async def test_missing_verified_runtime_fails_closed_without_spawning(self) -> None:
        runner = SandboxRunner(docker_command="Z:\\missing\\docker.exe")
        with self.assertRaises(SandboxConfigurationError):
            await runner.run(request())
        self.assertEqual(runner.active_process_count, 0)

        # A failed preflight must release its processless capacity slot.
        with patch.object(runner, "_verify_runtime", AsyncMock(return_value=None)):
            with patch.object(runner, "_worker_command", (sys.executable, "-I", "-c", FAKE_WORKER_SCRIPT)):
                result = await runner.run(request())
        self.assertEqual(result.status, SandboxStatus.ACCEPTED)

    async def test_runtime_verification_is_inside_the_bounded_capacity_slot(self) -> None:
        runner = local_runner(max_concurrency=1, queue_wait_seconds=0.03)
        entered = asyncio.Event()
        release = asyncio.Event()
        verification_calls = 0

        async def blocking_verification() -> None:
            nonlocal verification_calls
            verification_calls += 1
            entered.set()
            await release.wait()

        with patch.object(runner, "_verify_runtime", blocking_verification):
            first = asyncio.create_task(runner.run(request()))
            await asyncio.wait_for(entered.wait(), timeout=1)
            with self.assertRaises(SandboxBusyError):
                await runner.run(request())
            self.assertEqual(verification_calls, 1)
            release.set()
            result = await first

        self.assertEqual(result.status, SandboxStatus.ACCEPTED)
        self.assertEqual(runner.active_process_count, 0)

    async def test_concurrency_queue_limit_and_all_children_reaped(self) -> None:
        runner = local_runner(max_concurrency=1, queue_wait_seconds=0.03)
        first = asyncio.create_task(runner.run(request("import time\ntime.sleep(1)")))
        await self._wait_active(runner, 1)
        with self.assertRaises(SandboxBusyError):
            await runner.run(request())
        first.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await first
        self.assertEqual(runner.active_process_count, 0)
        self.assertEqual(runner.active_pids, ())

    async def test_cancellation_reaps_pid_and_reuses_slot(self) -> None:
        runner = local_runner()
        task = asyncio.create_task(runner.run(request("import time\ntime.sleep(60)")))
        await self._wait_active(runner, 1)
        pids = runner.active_pids
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertTrue(pids)
        self.assertEqual(runner.active_pids, ())
        result = await runner.run(request())
        self.assertEqual(result.status, SandboxStatus.ACCEPTED)

    async def test_container_removal_failure_keeps_capacity_fail_closed(self) -> None:
        runner = SandboxRunner(
            docker_command="docker", max_concurrency=1, queue_wait_seconds=0.01
        )
        with (
            patch.object(
                runner,
                "_run_cleanup_command",
                AsyncMock(return_value=(1, b"")),
            ),
            patch(
                "app.sandbox.runner.SANDBOX_CONTAINER_REMOVE_TIMEOUT_SECONDS",
                0.02,
            ),
            patch(
                "app.sandbox.runner.SANDBOX_DOCKER_COMMAND_RETRY_SECONDS",
                0.001,
            ),
        ):
            with self.assertRaises(SandboxTimeoutError):
                await runner._ensure_container_absent("education-sandbox-test")

        # Model the production invariant after a failed removal: the reaped
        # docker CLI remains registered and its slot is deliberately retained.
        class FakeProcess:
            returncode = 1

        process = FakeProcess()
        runner._active.add(process)  # type: ignore[arg-type]
        runner._container_names[process] = "education-sandbox-test"  # type: ignore[index]
        runner._slots.acquire()
        runner._release_reaped(process)  # type: ignore[arg-type]
        self.assertEqual(runner.active_process_count, 1)
        with self.assertRaises(SandboxBusyError):
            await runner._acquire_slot()

    async def test_container_cleanup_retries_transient_management_timeout(self) -> None:
        runner = SandboxRunner(docker_command="docker")
        calls = 0

        async def transient_timeout(*arguments: str) -> tuple[int, bytes]:
            nonlocal calls
            self.assertEqual(arguments[0], "ps")
            calls += 1
            if calls == 1:
                raise SandboxTimeoutError("transient Docker Desktop stall")
            return 0, b""

        with patch.object(runner, "_run_cleanup_command", transient_timeout):
            await runner._ensure_container_absent("education-sandbox-test")

        self.assertEqual(calls, 6)


if __name__ == "__main__":
    unittest.main()
