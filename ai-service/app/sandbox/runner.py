from __future__ import annotations

import asyncio
import json
import os
import shutil
import subprocess
import sys
import threading
import uuid
from decimal import Decimal
from pathlib import Path
from typing import Sequence

from app.contracts.sandbox import (
    SandboxRunRequest,
    SandboxRunResponse,
    SandboxStatus,
    SandboxTestResult,
    SandboxTestStatus,
)


SANDBOX_MAX_CONCURRENCY = 2
SANDBOX_QUEUE_WAIT_SECONDS = 0.25
SANDBOX_TERMINATE_GRACE_SECONDS = 0.5
SANDBOX_DOCKER_COMMAND_TIMEOUT_SECONDS = 3.0
SANDBOX_CONTAINER_REMOVE_TIMEOUT_SECONDS = 15.0
SANDBOX_DOCKER_COMMAND_RETRY_SECONDS = 0.1
SANDBOX_CONTAINER_ABSENCE_POLLS = 5
SANDBOX_CONTAINER_ABSENCE_POLL_SECONDS = 0.1
SANDBOX_PROTOCOL_MAX_BYTES = 8 * 1024 * 1024
SANDBOX_IMAGE_DEFAULT = "python:3.11.15-alpine3.23"


class SandboxConfigurationError(RuntimeError):
    """No verified strong-isolation runtime is available; execution is denied."""


class SandboxBusyError(RuntimeError):
    """All bounded sandbox capacity is occupied."""


class SandboxExecutionError(RuntimeError):
    """The isolated runner failed without a valid result."""


class SandboxTimeoutError(RuntimeError):
    """The whole sandbox request exceeded its deadline."""


def _minimal_environment() -> dict[str, str]:
    result = {
        "PATH": os.environ.get("PATH", ""),
        "SYSTEMROOT": os.environ.get("SYSTEMROOT", ""),
        "WINDIR": os.environ.get("WINDIR", ""),
    }
    return {key: value for key, value in result.items() if value}


async def _terminate_and_reap(process: asyncio.subprocess.Process) -> None:
    if process.returncode is None:
        try:
            process.terminate()
        except ProcessLookupError:
            pass
        try:
            await asyncio.wait_for(
                process.wait(), timeout=SANDBOX_TERMINATE_GRACE_SECONDS
            )
        except TimeoutError:
            if process.returncode is None:
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
            await process.wait()
    else:
        await process.wait()


async def _cleanup_uninterruptibly(
    process: asyncio.subprocess.Process,
) -> asyncio.CancelledError | None:
    cleanup_task = asyncio.create_task(_terminate_and_reap(process))
    pending_cancellation: asyncio.CancelledError | None = None
    while True:
        try:
            await asyncio.shield(cleanup_task)
            break
        except asyncio.CancelledError as exc:
            pending_cancellation = exc
    cleanup_task.result()
    return pending_cancellation


async def _finish_cancelled_spawn(
    spawn_task: asyncio.Task[asyncio.subprocess.Process],
) -> asyncio.subprocess.Process | None:
    while True:
        try:
            return await asyncio.shield(spawn_task)
        except asyncio.CancelledError:
            continue
        except OSError:
            return None


class SandboxRunner:
    """Runs code only through a verified, no-network Linux container runtime."""

    def __init__(
        self,
        *,
        docker_command: str | None = None,
        image: str | None = None,
        max_concurrency: int = SANDBOX_MAX_CONCURRENCY,
        queue_wait_seconds: float = SANDBOX_QUEUE_WAIT_SECONDS,
        worker_command: Sequence[str] | None = None,
    ) -> None:
        if not 1 <= max_concurrency <= SANDBOX_MAX_CONCURRENCY:
            raise ValueError("max_concurrency exceeds the global sandbox limit.")
        self._docker = docker_command or os.getenv("SANDBOX_DOCKER_COMMAND", "docker")
        self._image = image or os.getenv("SANDBOX_PYTHON_IMAGE", SANDBOX_IMAGE_DEFAULT)
        self._queue_wait_seconds = queue_wait_seconds
        self._slots = threading.BoundedSemaphore(max_concurrency)
        self._worker_path = Path(__file__).with_name("worker.py")
        self._worker_command = tuple(worker_command) if worker_command else None
        self._active: set[asyncio.subprocess.Process] = set()
        self._active_lock = threading.Lock()
        self._container_names: dict[asyncio.subprocess.Process, str] = {}
        self._cleanup_confirmed: set[asyncio.subprocess.Process] = set()

    @property
    def active_process_count(self) -> int:
        with self._active_lock:
            return len(self._active)

    @property
    def active_pids(self) -> tuple[int, ...]:
        with self._active_lock:
            return tuple(p.pid for p in self._active if p.pid is not None)

    async def _acquire_slot(self) -> None:
        deadline = asyncio.get_running_loop().time() + self._queue_wait_seconds
        while not self._slots.acquire(blocking=False):
            if asyncio.get_running_loop().time() >= deadline:
                raise SandboxBusyError("Sandbox capacity is full.")
            await asyncio.sleep(0.01)

    def _register(
        self, process: asyncio.subprocess.Process, container_name: str | None = None
    ) -> None:
        with self._active_lock:
            self._active.add(process)
            if container_name is not None:
                self._container_names[process] = container_name

    def _release_reaped(self, process: asyncio.subprocess.Process | None) -> None:
        if process is not None:
            if process.returncode is None:
                return
            if (
                process in self._container_names
                and process not in self._cleanup_confirmed
            ):
                return
            with self._active_lock:
                self._active.discard(process)
                self._container_names.pop(process, None)
                self._cleanup_confirmed.discard(process)
        self._slots.release()

    async def _verify_runtime(self) -> None:
        if self._worker_command is not None:
            return
        command_path = shutil.which(self._docker)
        if command_path is None and not Path(self._docker).is_file():
            raise SandboxConfigurationError("The Docker sandbox runtime is unavailable.")
        try:
            result = await asyncio.to_thread(
                subprocess.run,
                [self._docker, "image", "inspect", self._image],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                env=_minimal_environment(),
                timeout=3,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise SandboxConfigurationError(
                "The Docker sandbox runtime is unavailable."
            ) from exc
        if result.returncode != 0:
            raise SandboxConfigurationError(
                "The configured sandbox image is not available locally; automatic pulls are disabled."
            )

    def _command(
        self, request: SandboxRunRequest, *, container_name: str | None = None
    ) -> tuple[str, ...]:
        if self._worker_command is not None:
            return self._worker_command
        memory_bytes = request.limits.memory_mb * 1024 * 1024
        worker_dir = self._worker_path.parent.resolve()
        return (
            self._docker,
            "run",
            "--rm",
            "--pull=never",
            "--interactive",
            "--name",
            container_name or f"education-sandbox-{uuid.uuid4().hex}",
            "--network=none",
            "--read-only",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--ipc=none",
            "--pids-limit=2",
            f"--memory={memory_bytes}",
            f"--memory-swap={memory_bytes}",
            "--cpus=1",
            "--user=65534:65534",
            "--workdir=/runner",
            "--tmpfs=/tmp:rw,nosuid,nodev,size=16m,mode=1777",
            f"--mount=type=bind,source={worker_dir},target=/runner,readonly",
            "--entrypoint=python3",
            self._image,
            "-I",
            "-S",
            "/runner/worker.py",
        )

    async def run(self, request: SandboxRunRequest) -> SandboxRunResponse:
        await self._acquire_slot()
        process: asyncio.subprocess.Process | None = None
        release_processless_slot = True
        container_name = (
            None
            if self._worker_command is not None
            else f"education-sandbox-{uuid.uuid4().hex}"
        )
        try:
            # Runtime/image inspection is part of the bounded sandbox work. If
            # it ran before the semaphore, concurrent requests could create an
            # unbounded number of Docker CLI/daemon probes without ever
            # consuming execution capacity.
            await self._verify_runtime()
            command = self._command(request, container_name=container_name)
            creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
            spawn_task = asyncio.create_task(
                asyncio.create_subprocess_exec(
                    *command,
                    stdin=asyncio.subprocess.PIPE,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.DEVNULL,
                    cwd=str(Path(__file__).resolve().parents[2]),
                    env=_minimal_environment(),
                    creationflags=creation_flags,
                )
            )
            try:
                process = await asyncio.shield(spawn_task)
            except asyncio.CancelledError:
                release_processless_slot = False
                process = await _finish_cancelled_spawn(spawn_task)
                if process is not None:
                    self._register(process, container_name)
                    await _cleanup_uninterruptibly(process)
                    await self._confirm_container_cleanup(process, container_name)
                else:
                    await self._ensure_container_absent_uninterruptibly(container_name)
                    release_processless_slot = True
                raise
            except OSError as exc:
                raise SandboxConfigurationError(
                    "The sandbox process could not be started."
                ) from exc
            self._register(process, container_name)
            try:
                timeout_seconds = (
                    len(request.test_cases) * request.limits.time_ms / 1000 + 5
                )
                try:
                    payload = await asyncio.wait_for(
                        self._exchange(process, request), timeout=timeout_seconds
                    )
                except TimeoutError as exc:
                    raise SandboxTimeoutError("Sandbox request timed out.") from exc
                response = self._decode(request, payload)
            except asyncio.CancelledError as exc:
                await _cleanup_uninterruptibly(process)
                await self._confirm_container_cleanup(process, container_name)
                raise exc
            except (SandboxExecutionError, SandboxTimeoutError):
                pending = await _cleanup_uninterruptibly(process)
                await self._confirm_container_cleanup(process, container_name)
                if pending is not None:
                    raise pending
                raise
            else:
                # `docker run --rm` exited cleanly, so Docker confirmed the
                # container's automatic removal. This is the normal success
                # path and makes slot release safe.
                self._mark_cleanup_confirmed(process)
                return response
        finally:
            if process is None:
                if release_processless_slot:
                    self._release_reaped(None)
            elif process.returncode is not None:
                self._release_reaped(process)

    def _mark_cleanup_confirmed(self, process: asyncio.subprocess.Process) -> None:
        if process in self._container_names:
            with self._active_lock:
                self._cleanup_confirmed.add(process)

    async def _confirm_container_cleanup(
        self,
        process: asyncio.subprocess.Process,
        container_name: str | None,
    ) -> None:
        if container_name is None or process.returncode == 0:
            # A zero exit from `docker run --rm` is Docker's confirmation that
            # automatic cleanup completed. A test worker has no container.
            self._mark_cleanup_confirmed(process)
            return
        await self._ensure_container_absent_uninterruptibly(container_name)
        self._mark_cleanup_confirmed(process)

    async def _ensure_container_absent_uninterruptibly(
        self, container_name: str | None
    ) -> None:
        if container_name is None:
            return
        cleanup = asyncio.create_task(self._ensure_container_absent(container_name))
        while True:
            try:
                await asyncio.shield(cleanup)
                break
            except asyncio.CancelledError:
                continue
        cleanup.result()

    async def _run_cleanup_command(self, *arguments: str) -> tuple[int, bytes]:
        try:
            process = await asyncio.create_subprocess_exec(
                self._docker,
                *arguments,
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
                env=_minimal_environment(),
                creationflags=(
                    getattr(subprocess, "CREATE_NO_WINDOW", 0)
                    if os.name == "nt"
                    else 0
                ),
            )
            try:
                stdout, _ = await asyncio.wait_for(
                    process.communicate(),
                    timeout=SANDBOX_DOCKER_COMMAND_TIMEOUT_SECONDS,
                )
            except TimeoutError as exc:
                await _terminate_and_reap(process)
                raise SandboxTimeoutError(
                    "A Docker cleanup command timed out."
                ) from exc
            return process.returncode or 0, stdout
        except OSError as exc:
            # Fail closed: keep the capacity slot occupied because the service
            # cannot prove the student's container has been destroyed.
            raise SandboxExecutionError(
                "The sandbox container could not be confirmed destroyed."
            ) from exc

    async def _ensure_container_absent(self, container_name: str) -> None:
        consecutive_absent = 0
        last_management_failure: RuntimeError | None = None
        deadline = (
            asyncio.get_running_loop().time()
            + SANDBOX_CONTAINER_REMOVE_TIMEOUT_SECONDS
        )
        exact_filter = f"name=^/{container_name}$"
        while asyncio.get_running_loop().time() < deadline:
            try:
                return_code, stdout = await self._run_cleanup_command(
                    "ps",
                    "-a",
                    "--filter",
                    exact_filter,
                    "--format",
                    "{{.Names}}",
                )
            except (SandboxExecutionError, SandboxTimeoutError) as failure:
                # Docker Desktop can briefly stall its management API while a
                # cancelled `docker run` is tearing down. A single management
                # timeout is not proof that the student's container remains.
                # Retry within one bounded cleanup deadline and keep the slot
                # fail-closed until absence is positively observed.
                last_management_failure = failure
                consecutive_absent = 0
                await asyncio.sleep(SANDBOX_DOCKER_COMMAND_RETRY_SECONDS)
                continue
            if return_code != 0:
                last_management_failure = SandboxExecutionError(
                    "The sandbox container could not be confirmed destroyed."
                )
                consecutive_absent = 0
                await asyncio.sleep(SANDBOX_DOCKER_COMMAND_RETRY_SECONDS)
                continue
            present = container_name.encode("utf-8") in stdout.splitlines()
            if present:
                consecutive_absent = 0
                # A non-zero rm can be a create/rm race. Re-query instead of
                # accepting or immediately releasing capacity.
                try:
                    remove_code, _ = await self._run_cleanup_command(
                        "rm", "--force", container_name
                    )
                    if remove_code != 0:
                        last_management_failure = SandboxExecutionError(
                            "The sandbox container removal command failed."
                        )
                except (SandboxExecutionError, SandboxTimeoutError) as failure:
                    last_management_failure = failure
            else:
                consecutive_absent += 1
                if consecutive_absent >= SANDBOX_CONTAINER_ABSENCE_POLLS:
                    return
            await asyncio.sleep(SANDBOX_CONTAINER_ABSENCE_POLL_SECONDS)
        if last_management_failure is not None:
            raise SandboxTimeoutError(
                "Sandbox container cleanup could not be confirmed before the deadline."
            ) from last_management_failure
        raise SandboxTimeoutError(
            "Sandbox container cleanup could not be confirmed before the deadline."
        )

    async def _exchange(
        self, process: asyncio.subprocess.Process, request: SandboxRunRequest
    ) -> bytes:
        if process.stdin is None or process.stdout is None:
            raise SandboxExecutionError("Sandbox pipes are unavailable.")
        payload = request.model_dump(mode="json")
        payload["interpreter"] = (
            sys.executable if self._worker_command else "/usr/local/bin/python3"
        )
        # The outer Docker cgroup is the authoritative memory boundary. The
        # inner RLIMIT_AS must leave enough address space for a Python 3.11
        # interpreter and native runtime even when the business memory budget
        # is the contract minimum (16 MiB).
        payload["process_memory_mb"] = request.limits.memory_mb
        payload["isolation_verified"] = True
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(encoded) > SANDBOX_PROTOCOL_MAX_BYTES:
            raise SandboxExecutionError("Sandbox request exceeds the protocol limit.")
        process.stdin.write(encoded)
        await process.stdin.drain()
        process.stdin.close()
        try:
            await process.stdin.wait_closed()
        except (BrokenPipeError, ConnectionResetError):
            pass
        response = await process.stdout.read(SANDBOX_PROTOCOL_MAX_BYTES + 1)
        return_code = await process.wait()
        if return_code != 0 or not response or len(response) > SANDBOX_PROTOCOL_MAX_BYTES:
            raise SandboxExecutionError("Sandbox returned an invalid result.")
        return response

    def _decode(self, request: SandboxRunRequest, payload: bytes) -> SandboxRunResponse:
        try:
            value = json.loads(payload.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as exc:
            raise SandboxExecutionError("Sandbox returned invalid JSON.") from exc
        if not isinstance(value, dict) or value.get("ok") is not True:
            raise SandboxExecutionError("Sandbox worker failed closed.")
        raw_results = value.get("test_results")
        if not isinstance(raw_results, list) or len(raw_results) != len(request.test_cases):
            raise SandboxExecutionError("Sandbox returned an invalid result set.")

        results: list[SandboxTestResult] = []
        achieved = Decimal("0")
        total = sum((Decimal(str(test.score)) for test in request.test_cases), Decimal("0"))
        passed = 0
        for test, raw in zip(request.test_cases, raw_results, strict=True):
            if not isinstance(raw, dict) or raw.get("test_case_id") != test.test_case_id:
                raise SandboxExecutionError("Sandbox result identity is invalid.")
            try:
                status = SandboxTestStatus(raw["status"])
            except (KeyError, ValueError, TypeError) as exc:
                raise SandboxExecutionError("Sandbox status is invalid.") from exc
            score = test.score if status == SandboxTestStatus.ACCEPTED else 0.0
            if status == SandboxTestStatus.ACCEPTED:
                passed += 1
                achieved += Decimal(str(test.score))
            result = SandboxTestResult(
                test_case_id=test.test_case_id,
                status=status,
                score=score,
                max_score=test.score,
                time_ms=max(0, int(raw.get("time_ms", 0))),
                memory_kb=max(0, int(raw.get("memory_kb", 0))),
                visibility=test.visibility,
                actual_output=raw.get("actual_output") if test.visibility == "PUBLIC" else None,
                expected_output=test.expected_output if test.visibility == "PUBLIC" else None,
                error_message=raw.get("error_message") if test.visibility == "PUBLIC" else None,
            )
            results.append(result)

        statuses = [result.status for result in results]
        if all(status == SandboxTestStatus.ACCEPTED for status in statuses):
            aggregate = SandboxStatus.ACCEPTED
        elif any(status == SandboxTestStatus.ACCEPTED for status in statuses):
            aggregate = SandboxStatus.PARTIAL
        else:
            precedence = (
                SandboxTestStatus.SYNTAX_ERROR,
                SandboxTestStatus.TIME_LIMIT_EXCEEDED,
                SandboxTestStatus.MEMORY_LIMIT_EXCEEDED,
                SandboxTestStatus.OUTPUT_LIMIT_EXCEEDED,
                SandboxTestStatus.RUNTIME_ERROR,
                SandboxTestStatus.SYSTEM_ERROR,
                SandboxTestStatus.WRONG_ANSWER,
            )
            selected = next((item for item in precedence if item in statuses), SandboxTestStatus.WRONG_ANSWER)
            aggregate = SandboxStatus(selected.value)
        compile_message = next(
            (
                result.error_message
                for result in results
                if result.status == SandboxTestStatus.SYNTAX_ERROR and result.visibility == "PUBLIC"
            ),
            None,
        )
        return SandboxRunResponse(
            request_id=request.request_id,
            status=aggregate,
            achieved_score=float(achieved),
            total_score=float(total),
            passed_count=passed,
            total_count=len(results),
            compile_message=compile_message,
            test_results=results,
        )


_PUBLIC_RUNNER = SandboxRunner(max_concurrency=1)
_GRADING_RUNNER = SandboxRunner(max_concurrency=1)


def create_sandbox_runner(execution_mode: str = "GRADING") -> SandboxRunner:
    if execution_mode == "PUBLIC_RUN":
        return _PUBLIC_RUNNER
    if execution_mode == "GRADING":
        return _GRADING_RUNNER
    raise SandboxConfigurationError("Unknown sandbox execution mode.")
