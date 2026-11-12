import asyncio
import re
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.routers import assessment, graph_ingestion, health, memory, profile, sandbox, tutor, tutor_grading
from app.services.memory import close_memory_service
from app.providers.telemetry import close_shared_telemetry_delivery
from app.core.runtime_models import fetch_request_profiles, runtime_profiles
from app.routers import model_settings
from app.routers import smart_companion


@asynccontextmanager
async def lifespan(_: FastAPI):
    try:
        yield
    finally:
        await close_shared_telemetry_delivery()
        await asyncio.to_thread(close_memory_service)


app = FastAPI(
    title="Education Agent AI Service",
    version="0.1.0",
    description="Internal Agent, Ollama, RAG and grading service. Public clients use Spring Boot.",
    lifespan=lifespan,
)
app.include_router(tutor_grading.router)
app.include_router(model_settings.router)
app.include_router(smart_companion.router)

REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{16,64}$")


@app.middleware("http")
async def request_id_middleware(request: Request, call_next):
    supplied = request.headers.get("X-Request-Id", "")
    request_id = supplied if REQUEST_ID_PATTERN.fullmatch(supplied) else f"req-{uuid.uuid4()}"
    request.state.request_id = request_id
    try:
        profiles = await fetch_request_profiles(request)
    except Exception:
        return JSONResponse(status_code=503, headers={'X-Request-Id': request_id}, content={
            'detail': {'code': 'MODEL_SETTINGS_UNAVAILABLE', 'owner': 'D',
                       'message': 'Model settings are temporarily unavailable.', 'request_id': request_id}})
    token = runtime_profiles.set(profiles)
    try:
        response = await call_next(request)
    finally:
        runtime_profiles.reset(token)
    response.headers["X-Request-Id"] = request_id
    return response


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    request_id = getattr(request.state, "request_id", None)
    # Pydantic errors may echo rejected request values. Sandbox payloads can
    # contain hidden test input/expected output, so never serialize `input` or
    # exception context into an HTTP response or access log.
    safe_errors = [
        {
            "type": error.get("type", "value_error"),
            "loc": error.get("loc", ()),
            "msg": error.get("msg", "Invalid value."),
        }
        for error in exc.errors()
    ]
    return JSONResponse(
        status_code=422,
        content=jsonable_encoder(
            {
                "detail": {
                    "code": "VALIDATION_ERROR",
                    "owner": "D",
                    "message": "Request parameters are invalid.",
                    "request_id": request_id,
                    "errors": safe_errors,
                }
            }
        ),
        headers={"X-Request-Id": request_id} if request_id else None,
    )

app.include_router(health.router)
app.include_router(assessment.router)
app.include_router(graph_ingestion.router)
app.include_router(tutor.router)
app.include_router(memory.router)
app.include_router(profile.router)
app.include_router(sandbox.router)
