from __future__ import annotations

import re
import uuid
import logging
from time import perf_counter
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from knowledge_service.app.api.graph_routes import (
    graph_ingestion_service,
    graph_service,
    close_course_services,
    router as graph_router,
)


REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{16,64}$")
HTTP_LOGGER = logging.getLogger("uvicorn.access")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    try:
        yield
    finally:
        close_course_services()
        graph_ingestion_service.close()
        graph_service.close()


app = FastAPI(
    title="智学引擎知识图谱服务",
    version="2.8.0",
    description="C 组独立 FastAPI 服务；浏览器只能通过 Spring Boot 访问。",
    lifespan=lifespan,
)


@app.middleware("http")
async def request_id_middleware(request: Request, call_next):
    started = perf_counter()
    supplied = request.headers.get("X-Request-Id", "")
    request_id = supplied if REQUEST_ID_PATTERN.fullmatch(supplied) else f"req-{uuid.uuid4()}"
    request.state.request_id = request_id
    response = await call_next(request)
    response.headers["X-Request-Id"] = request_id
    HTTP_LOGGER.info(
        "request_id=%s method=%s path=%s status=%s duration_ms=%.2f",
        request_id,
        request.method,
        request.url.path,
        response.status_code,
        (perf_counter() - started) * 1000,
    )
    return response


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    request_id = getattr(request.state, "request_id", None)
    errors = exc.errors()
    graph_budget_error = request.url.path.startswith(
        "/internal/graph/ingestion-batches"
    ) and any(
        error.get("type") == "too_long"
        and len(error.get("loc", ())) >= 2
        and error["loc"][0] == "body"
        and error["loc"][1] in {"nodes", "edges", "sources"}
        for error in errors
    )
    # Pydantic's full error objects may contain the rejected input and
    # contextual values.  Those values can include student content, source
    # evidence, or credentials, so the public contract exposes only stable
    # diagnostic fields.
    safe_errors = [
        {
            "loc": error.get("loc", ()),
            "type": error.get("type", "value_error"),
            "msg": error.get("msg", "请求参数格式错误"),
        }
        for error in errors
    ]
    return JSONResponse(
        status_code=422,
        content=jsonable_encoder(
            {
                "detail": {
                    "code": (
                        "GRAPH_DRAFT_BUDGET_EXCEEDED"
                        if graph_budget_error
                        else "VALIDATION_ERROR"
                    ),
                    "owner": "C",
                    "message": "请求参数格式错误",
                    "request_id": request_id,
                    "errors": safe_errors,
                }
            }
        ),
        headers={"X-Request-Id": request_id} if request_id else None,
    )


app.include_router(graph_router)
