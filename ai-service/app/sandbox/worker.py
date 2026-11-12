from __future__ import annotations

import json
import math
import os
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

try:
    import resource
except ImportError:  # pragma: no cover - production worker is Linux-only
    resource = None  # type: ignore[assignment]


MAX_PROTOCOL_BYTES = 8 * 1024 * 1024
RETURN_OUTPUT_MAX_BYTES = 12 * 1024
RETURN_ERROR_MAX_BYTES = 4 * 1024
SAFE_ENVIRONMENT = {
    "LANG": "C.UTF-8",
    "LC_ALL": "C.UTF-8",
    "PYTHONHASHSEED": "0",
    "PYTHONIOENCODING": "utf-8",
    "PYTHONUNBUFFERED": "1",
}


def _read_request() -> dict[str, Any]:
    payload = sys.stdin.buffer.read(MAX_PROTOCOL_BYTES + 1)
    if not payload or len(payload) > MAX_PROTOCOL_BYTES:
        raise ValueError("SIZE")
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise ValueError("JSON") from exc
    if not isinstance(value, dict):
        raise ValueError("SHAPE")
    return value


def _write_response(response: dict[str, Any]) -> None:
    payload = json.dumps(response, ensure_ascii=False, separators=(",", ":")).encode(
        "utf-8"
    )
    if len(payload) > MAX_PROTOCOL_BYTES:
        payload = b'{"ok":false,"error":"WORKER_PROTOCOL_LIMIT"}'
    sys.stdout.buffer.write(payload)
    sys.stdout.buffer.flush()


def _limit_child(memory_bytes: int, output_bytes: int) -> None:
    if resource is None:
        raise RuntimeError("POSIX resource limits are unavailable")
    os.setsid()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_FSIZE, (output_bytes, output_bytes))
    resource.setrlimit(resource.RLIMIT_NOFILE, (16, 16))
    # RLIMIT_AS is the primary cross-allocation address-space boundary.  Alpine's
    # allocator may nevertheless make progress slowly enough that a heap-growth
    # program reaches the wall-clock deadline before surfacing MemoryError.
    # RLIMIT_DATA gives heap-backed Python objects the same explicit ceiling so
    # memory exhaustion is classified deterministically instead of as a timeout.
    if hasattr(resource, "RLIMIT_DATA"):
        resource.setrlimit(resource.RLIMIT_DATA, (memory_bytes, memory_bytes))
    resource.setrlimit(resource.RLIMIT_AS, (memory_bytes, memory_bytes))


def _terminate_group(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        process.wait()
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=0.2)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


def _compare(actual: str, expected: str, mode: str, tolerance: float | None) -> bool:
    if mode == "EXACT":
        return actual == expected
    if mode == "TRIM_TRAILING_WHITESPACE":
        normalize = lambda value: "\n".join(
            line.rstrip() for line in value.replace("\r\n", "\n").split("\n")
        ).rstrip()
        return normalize(actual) == normalize(expected)
    try:
        actual_values = [float(value) for value in actual.split()]
        expected_values = [float(value) for value in expected.split()]
    except ValueError:
        return False
    if len(actual_values) != len(expected_values) or tolerance is None:
        return False
    return all(
        math.isfinite(a) and math.isfinite(e) and abs(a - e) <= tolerance
        for a, e in zip(actual_values, expected_values, strict=True)
    )


def _run_case(
    *,
    interpreter: str,
    script_path: Path,
    test: dict[str, Any],
    time_ms: int,
    memory_mb: int,
    output_kb: int,
) -> dict[str, Any]:
    output_bytes = output_kb * 1024
    started = time.monotonic()
    with tempfile.TemporaryFile() as stdout_file, tempfile.TemporaryFile() as stderr_file:
        process = subprocess.Popen(
            [interpreter, "-I", "-S", "-B", str(script_path)],
            stdin=subprocess.PIPE,
            stdout=stdout_file,
            stderr=stderr_file,
            cwd=str(script_path.parent),
            env=SAFE_ENVIRONMENT,
            preexec_fn=lambda: _limit_child(memory_mb * 1024 * 1024, output_bytes),
        )
        try:
            process.communicate(
                test["input_data"].encode("utf-8"), timeout=time_ms / 1000
            )
        except subprocess.TimeoutExpired:
            return {
                "status": "TIME_LIMIT_EXCEEDED",
                "actual_output": None,
                "error_message": "Execution exceeded the time limit.",
                "time_ms": time_ms,
                "memory_kb": 0,
            }
        finally:
            _terminate_group(process)
        stdout_file.seek(0)
        stderr_file.seek(0)
        stdout = stdout_file.read(output_bytes + 1)
        stderr = stderr_file.read(output_bytes + 1)

    duration_ms = max(0, round((time.monotonic() - started) * 1000))
    if (
        len(stdout) > output_bytes
        or len(stderr) > output_bytes
        or len(stdout) + len(stderr) > output_bytes
        or process.returncode == -signal.SIGXFSZ
    ):
        status = "OUTPUT_LIMIT_EXCEEDED"
        error = "Execution exceeded the output limit."
    elif process.returncode != 0:
        decoded_error = stderr.decode("utf-8", errors="replace")[:RETURN_ERROR_MAX_BYTES]
        if "SyntaxError:" in decoded_error:
            status = "SYNTAX_ERROR"
        elif (
            process.returncode in {-signal.SIGKILL, -signal.SIGSEGV}
            or "MemoryError" in decoded_error
        ):
            status = "MEMORY_LIMIT_EXCEEDED"
        else:
            status = "RUNTIME_ERROR"
        error = decoded_error or "Execution failed."
    else:
        actual = stdout.decode("utf-8", errors="replace")
        accepted = _compare(
            actual,
            test["expected_output"],
            test["compare_mode"],
            test.get("float_tolerance"),
        )
        status = "ACCEPTED" if accepted else "WRONG_ANSWER"
        error = None
    return {
        "status": status,
        "actual_output": (
            stdout.decode("utf-8", errors="replace")[:RETURN_OUTPUT_MAX_BYTES]
            if test["visibility"] == "PUBLIC"
            else None
        ),
        "error_message": error if test["visibility"] == "PUBLIC" else None,
        "time_ms": duration_ms,
        "memory_kb": 0,
    }


def main() -> int:
    stage = "READ_REQUEST"
    try:
        request = _read_request()
        stage = "VERIFY_ISOLATION"
        if (
            os.name != "posix"
            or resource is None
            or request.get("isolation_verified") is not True
        ):
            raise RuntimeError("isolation is not verified")
        interpreter = request["interpreter"]
        tests = request["test_cases"]
        limits = request["limits"]
        process_memory_mb = request.get("process_memory_mb", limits["memory_mb"])
        if type(process_memory_mb) is not int or not 16 <= process_memory_mb <= 512:
            raise ValueError("invalid process memory limit")
        results = []
        stage = "RUN_TESTS"
        for test in tests:
            # Each test gets a fresh directory and interpreter process, so one
            # test cannot persist files or modify source for a later test.
            with tempfile.TemporaryDirectory(prefix="education-sandbox-") as temp_dir:
                script_path = Path(temp_dir) / "main.py"
                script_path.write_text(request["source_code"], encoding="utf-8")
                case_result = _run_case(
                    interpreter=interpreter,
                    script_path=script_path,
                    test=test,
                    time_ms=limits["time_ms"],
                    memory_mb=process_memory_mb,
                    output_kb=limits["output_kb"],
                )
            results.append(
                {
                    "test_case_id": test["test_case_id"],
                    "visibility": test["visibility"],
                    "max_score": test["score"],
                    **case_result,
                }
            )
        _write_response({"ok": True, "test_results": results})
    except Exception as exc:
        # Only a fixed stage label is emitted; never exception text, source,
        # paths, input, expected output, or traces.
        reason = (
            str(exc)
            if stage == "READ_REQUEST" and str(exc) in {"SIZE", "JSON", "SHAPE"}
            else ""
        )
        _write_response({"ok": False, "error": f"WORKER_FAILED_{stage}_{reason}"})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
