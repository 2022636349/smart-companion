from __future__ import annotations

from typing import Annotated, NoReturn

from fastapi import APIRouter, Depends, HTTPException, status

from app.contracts.sandbox import (
    CodeExplainRequest,
    CodeExplainResponse,
    SandboxRunRequest,
    SandboxRunResponse,
)
from app.core.security import require_internal_headers
from app.sandbox import (
    SandboxBusyError,
    SandboxConfigurationError,
    SandboxExecutionError,
    SandboxTimeoutError,
    create_sandbox_runner,
)


router = APIRouter(tags=["code-sandbox"])


@router.post(
    "/internal/code/run",
    operation_id="internalRunCode",
    response_model=SandboxRunResponse,
    response_model_exclude_none=True,
)
async def run_code(
    request: SandboxRunRequest,
    trace_request_id: Annotated[str, Depends(require_internal_headers)],
) -> SandboxRunResponse:
    if request.request_id != trace_request_id:
        _raise_typed_error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "SANDBOX_REQUEST_ID_MISMATCH",
            "request_id must match X-Request-Id.",
            trace_request_id,
        )
    try:
        return await create_sandbox_runner(request.execution_mode).run(request)
    except SandboxBusyError:
        # CONTRACT_CHANGE_REQUEST: internal 2.1.1 currently documents only
        # 200/422 for this route. Runtime must still fail explicitly rather
        # than queue without a bound or synthesize a SYSTEM_ERROR success.
        _raise_typed_error(
            status.HTTP_429_TOO_MANY_REQUESTS,
            "SANDBOX_CAPACITY_EXHAUSTED",
            "Sandbox capacity is temporarily exhausted.",
            trace_request_id,
        )
    except SandboxTimeoutError:
        _raise_typed_error(
            status.HTTP_504_GATEWAY_TIMEOUT,
            "SANDBOX_TOTAL_TIMEOUT",
            "Sandbox execution exceeded its total deadline.",
            trace_request_id,
        )
    except (SandboxConfigurationError, SandboxExecutionError):
        _raise_typed_error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "SANDBOX_UNAVAILABLE",
            "A verified isolated code runtime is unavailable.",
            trace_request_id,
        )


@router.post(
    "/internal/ai/code/explain-result",
    operation_id="explainCodeResult",
    response_model=CodeExplainResponse,
)
def explain_code_result(
    request: CodeExplainRequest,
    trace_request_id: Annotated[str, Depends(require_internal_headers)],
) -> CodeExplainResponse:
    if request.request_id != trace_request_id:
        _raise_typed_error(
            status.HTTP_422_UNPROCESSABLE_CONTENT,
            "CODE_EXPLAIN_REQUEST_ID_MISMATCH",
            "request_id must match X-Request-Id.",
            trace_request_id,
        )
    # Deliberately deterministic: use only status/position metadata and never
    # echo source, raw exception text, test input, expected output or actual
    # output. Thus a caller accidentally sending hidden material cannot leak it
    # through this route, and no cloud model receives student/test data.
    feedback = _deterministic_feedback(request)
    return CodeExplainResponse(
        request_id=request.request_id,
        feedback=feedback,
        suspected_knowledge_points=[],
        model_version="deterministic-no-model-v1",
        prompt_version="code-explain-redacted-v1",
    )


def _deterministic_feedback(request: CodeExplainRequest) -> str:
    if not request.failures:
        return "本次没有可分析的失败测试，请先运行代码并确认提交内容。"
    counts: dict[str, int] = {}
    known_statuses = {
        "WRONG_ANSWER",
        "SYNTAX_ERROR",
        "RUNTIME_ERROR",
        "TIME_LIMIT_EXCEEDED",
        "MEMORY_LIMIT_EXCEEDED",
        "OUTPUT_LIMIT_EXCEEDED",
        "SYSTEM_ERROR",
    }
    for failure in request.failures:
        candidate = failure.status.strip().upper()
        normalized = candidate if candidate in known_statuses else "UNKNOWN"
        counts[normalized] = counts.get(normalized, 0) + 1
    advice = {
        "SYNTAX_ERROR": "先检查语法、缩进、括号和冒号。",
        "RUNTIME_ERROR": "检查异常分支、数据类型、下标和空输入处理。",
        "TIME_LIMIT_EXCEEDED": "检查死循环，并优化重复计算或不必要的嵌套循环。",
        "MEMORY_LIMIT_EXCEEDED": "减少一次性数据规模，避免无限增长的容器。",
        "OUTPUT_LIMIT_EXCEEDED": "删除调试输出，确保只打印题目要求的结果。",
        "WRONG_ANSWER": "对照公开样例检查边界条件、输出格式和算法逻辑。",
    }
    ordered = sorted(counts.items())
    summary = "、".join(f"{status} {count} 个" for status, count in ordered)
    suggestions = [advice[status] for status, _ in ordered if status in advice]
    if not suggestions:
        suggestions = ["根据公开测试状态逐步缩小问题范围。"]
    return f"检测到 {summary}。" + "".join(dict.fromkeys(suggestions))


def _raise_typed_error(
    status_code: int, code: str, message: str, request_id: str
) -> NoReturn:
    raise HTTPException(
        status_code=status_code,
        detail={
            "code": code,
            "owner": "D",
            "message": message,
            "request_id": request_id,
        },
    )
