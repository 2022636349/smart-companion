from __future__ import annotations

from dataclasses import dataclass
from urllib.parse import quote

import httpx2
from pydantic import ValidationError

from app.contracts.common import KnowledgePointCandidate
from app.contracts.graph import (
    GraphMatchResponse,
    GraphNodeListResponse,
    GraphRagContext,
    GraphRecommendationResponse,
    GraphSearchResponse,
)
from app.core.config import KnowledgeServiceSettings


class KnowledgeClientError(RuntimeError):
    """Sanitized knowledge-service failure safe for typed translation."""

    code = "KNOWLEDGE_CLIENT_ERROR"

    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


class KnowledgeNotFoundError(KnowledgeClientError):
    code = "KNOWLEDGE_CONTEXT_NOT_FOUND"


class KnowledgeAuthenticationError(KnowledgeClientError):
    code = "KNOWLEDGE_AUTHENTICATION_ERROR"


class KnowledgeTimeoutError(KnowledgeClientError):
    code = "KNOWLEDGE_TIMEOUT_ERROR"


class KnowledgeTransportError(KnowledgeClientError):
    code = "KNOWLEDGE_TRANSPORT_ERROR"


class KnowledgeServerError(KnowledgeClientError):
    code = "KNOWLEDGE_SERVER_ERROR"


class KnowledgeInputError(KnowledgeClientError):
    code = "KNOWLEDGE_INPUT_ERROR"


class KnowledgeRequestError(KnowledgeClientError):
    code = "KNOWLEDGE_REQUEST_ERROR"


class KnowledgeResponseError(KnowledgeClientError):
    code = "KNOWLEDGE_RESPONSE_ERROR"


@dataclass(frozen=True, slots=True)
class ApprovedKnowledgeMatch:
    candidates: tuple[KnowledgePointCandidate, ...]
    filtered_unapproved_count: int = 0
    requires_review_count: int = 0


class KnowledgeServiceClient:
    """Typed client for C's approved-only Graph-RAG context endpoint."""

    def __init__(
        self,
        settings: KnowledgeServiceSettings | None = None,
        *,
        client: httpx2.AsyncClient | None = None,
    ) -> None:
        self._settings = settings or KnowledgeServiceSettings.from_env()
        self._owns_client = client is None
        self._client = client or httpx2.AsyncClient(
            timeout=self._settings.timeout_seconds,
            trust_env=False,
        )

    async def __aenter__(self) -> KnowledgeServiceClient:
        return self

    async def __aexit__(self, *_: object) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def get_rag_context(
        self,
        knowledge_point_id: str,
        *,
        course_id: int,
        request_id: str,
    ) -> GraphRagContext:
        self._validate_course_id(course_id)
        if (
            not knowledge_point_id.strip()
            or knowledge_point_id in {".", ".."}
            or "/" in knowledge_point_id
            or "\\" in knowledge_point_id
            or any(ord(character) < 32 for character in knowledge_point_id)
        ):
            raise KnowledgeInputError(
                "The knowledge point identifier is not safe for a graph lookup."
            )
        encoded_node_id = quote(knowledge_point_id, safe="")
        url = (
            f"{self._settings.base_url}/internal/graph/rag-context/"
            f"{encoded_node_id}"
        )
        headers = {
            "X-Internal-Token": self._settings.internal_token.get_secret_value(),
            "X-Request-Id": request_id,
            "Accept": "application/json",
        }
        try:
            response = await self._client.get(
                url,
                headers=headers,
                params={
                    "max_related": self._settings.max_related,
                    "course_id": course_id,
                },
            )
        except httpx2.TimeoutException as exc:
            raise KnowledgeTimeoutError(
                "The knowledge-service request timed out."
            ) from exc
        except httpx2.TransportError as exc:
            raise KnowledgeTransportError(
                "The knowledge service could not be reached."
            ) from exc

        if response.status_code == 404:
            raise KnowledgeNotFoundError(
                "The requested approved graph context was not found.",
                status_code=response.status_code,
            )
        if response.status_code in {401, 403}:
            raise KnowledgeAuthenticationError(
                "The knowledge service rejected internal authentication.",
                status_code=response.status_code,
            )
        if 500 <= response.status_code <= 599:
            raise KnowledgeServerError(
                "The knowledge service returned a server error.",
                status_code=response.status_code,
            )
        if not 200 <= response.status_code < 300:
            raise KnowledgeRequestError(
                f"The knowledge service rejected the request with HTTP "
                f"{response.status_code}.",
                status_code=response.status_code,
            )

        try:
            body = response.json()
            if not isinstance(body, dict):
                raise ValueError("The response root must be an object.")
            context = GraphRagContext.model_validate(body)
            if context.course_id != course_id:
                raise ValueError("The graph context belongs to another course.")
            context.ensure_approved_and_traceable(knowledge_point_id)
        except (ValueError, TypeError, ValidationError) as exc:
            raise KnowledgeResponseError(
                "The knowledge service returned an invalid or unapproved graph context.",
                status_code=response.status_code,
            ) from exc
        return context

    async def match_knowledge_points(
        self,
        text: str,
        *,
        course_id: int,
        request_id: str,
    ) -> ApprovedKnowledgeMatch:
        self._validate_course_id(course_id)
        normalized = text.strip()
        if not normalized or len(normalized) > 30_000:
            raise KnowledgeInputError(
                "Question text must contain between 1 and 30000 characters."
            )
        url = f"{self._settings.base_url}/internal/graph/match"
        headers = {
            "X-Internal-Token": self._settings.internal_token.get_secret_value(),
            "X-Request-Id": request_id,
            "Accept": "application/json",
            "Content-Type": "application/json",
        }
        try:
            response = await self._client.post(
                url,
                headers=headers,
                params={"course_id": course_id},
                json={
                    "text": normalized,
                    "scene": "QUESTION",
                    "limit": self._settings.match_limit,
                },
            )
        except httpx2.TimeoutException as exc:
            raise KnowledgeTimeoutError(
                "The knowledge-service match request timed out."
            ) from exc
        except httpx2.TransportError as exc:
            raise KnowledgeTransportError(
                "The knowledge service could not be reached for matching."
            ) from exc

        if response.status_code in {401, 403}:
            raise KnowledgeAuthenticationError(
                "The knowledge service rejected internal authentication.",
                status_code=response.status_code,
            )
        if 500 <= response.status_code <= 599:
            raise KnowledgeServerError(
                "The knowledge service returned a server error while matching.",
                status_code=response.status_code,
            )
        if not 200 <= response.status_code < 300:
            raise KnowledgeRequestError(
                "The knowledge service rejected the match request.",
                status_code=response.status_code,
            )

        try:
            body = response.json()
            if not isinstance(body, dict):
                raise ValueError("The response root must be an object.")
            matched = GraphMatchResponse.model_validate(body)
        except (ValueError, TypeError, ValidationError) as exc:
            raise KnowledgeResponseError(
                "The knowledge service returned an invalid match response.",
                status_code=response.status_code,
            ) from exc

        candidates: list[KnowledgePointCandidate] = []
        seen_ids: set[str] = set()
        filtered_count = 0
        requires_review_count = 0
        for item in matched.items:
            node = item.node
            if node.review_status != "APPROVED" or node.node_type != "knowledge_point":
                filtered_count += 1
                continue
            if node.id in seen_ids:
                continue
            seen_ids.add(node.id)
            if item.requires_review:
                requires_review_count += 1
            candidates.append(
                KnowledgePointCandidate(
                    knowledge_point_id=node.id,
                    name=node.name,
                    score=item.score,
                )
            )
        return ApprovedKnowledgeMatch(
            candidates=tuple(candidates),
            filtered_unapproved_count=filtered_count,
            requires_review_count=requires_review_count,
        )

    async def search_graph(
        self,
        query: str,
        *,
        course_id: int,
        request_id: str,
        limit: int = 5,
    ) -> GraphSearchResponse:
        self._validate_course_id(course_id)
        normalized = query.strip()
        if not normalized or len(normalized) > 500:
            raise KnowledgeInputError("Graph search query must be 1..500 characters.")
        if type(limit) is not int or not 1 <= limit <= 20:
            raise KnowledgeInputError("Graph search limit must be between 1 and 20.")
        response = await self._request(
            "GET",
            f"{self._settings.base_url}/internal/graph/search",
            request_id=request_id,
            params={"q": normalized, "limit": limit, "course_id": course_id},
        )
        try:
            body = response.json()
            if not isinstance(body, dict):
                raise ValueError("The response root must be an object.")
            result = GraphSearchResponse.model_validate(body)
            if result.query.strip() != normalized:
                raise ValueError("The graph search response query does not match.")
            result.ensure_approved_knowledge_points()
        except (ValueError, TypeError, ValidationError) as exc:
            raise KnowledgeResponseError(
                "The knowledge service returned an invalid or unapproved search response.",
                status_code=response.status_code,
            ) from exc
        return result

    async def get_prerequisites(
        self,
        knowledge_point_id: str,
        *,
        course_id: int,
        request_id: str,
        max_depth: int = 5,
    ) -> GraphNodeListResponse:
        self._validate_course_id(course_id)
        encoded = self._safe_node_id(knowledge_point_id)
        if type(max_depth) is not int or not 1 <= max_depth <= 10:
            raise KnowledgeInputError("Prerequisite max_depth must be between 1 and 10.")
        response = await self._request(
            "GET",
            f"{self._settings.base_url}/internal/graph/prerequisites/{encoded}",
            request_id=request_id,
            params={"max_depth": max_depth, "course_id": course_id},
            not_found_message="The requested approved prerequisite chain was not found.",
        )
        try:
            body = response.json()
            if not isinstance(body, dict):
                raise ValueError("The response root must be an object.")
            result = GraphNodeListResponse.model_validate(body)
            result.ensure_approved_knowledge_points()
        except (ValueError, TypeError, ValidationError) as exc:
            raise KnowledgeResponseError(
                "The knowledge service returned invalid or unapproved prerequisites.",
                status_code=response.status_code,
            ) from exc
        return result

    async def recommend_next(
        self,
        *,
        course_id: int,
        student_id: int,
        mastery: list[dict[str, object]],
        request_id: str,
        limit: int = 5,
    ) -> GraphRecommendationResponse:
        self._validate_course_id(course_id)
        if type(student_id) is not int or student_id <= 0:
            raise KnowledgeInputError("student_id must be a positive integer.")
        if not mastery or len(mastery) > 100:
            raise KnowledgeInputError("mastery must contain 1..100 items.")
        if type(limit) is not int or not 1 <= limit <= 20:
            raise KnowledgeInputError("Recommendation limit must be between 1 and 20.")
        response = await self._request(
            "POST",
            f"{self._settings.base_url}/internal/graph/recommend-next",
            request_id=request_id,
            params={"course_id": course_id},
            json={"student_id": student_id, "mastery": mastery, "limit": limit},
        )
        try:
            body = response.json()
            if not isinstance(body, dict):
                raise ValueError("The response root must be an object.")
            result = GraphRecommendationResponse.model_validate(body)
            if result.course_id != course_id:
                raise ValueError("The recommendations belong to another course.")
        except (ValueError, TypeError, ValidationError) as exc:
            raise KnowledgeResponseError(
                "The knowledge service returned invalid or unapproved recommendations.",
                status_code=response.status_code,
            ) from exc
        return result

    @staticmethod
    def _validate_course_id(course_id: int) -> None:
        if (
            type(course_id) is not int
            or not 1 <= course_id <= 9_223_372_036_854_775_807
        ):
            raise KnowledgeInputError("course_id must be a positive int64.")

    @staticmethod
    def _safe_node_id(knowledge_point_id: str) -> str:
        if (
            not knowledge_point_id.strip()
            or knowledge_point_id in {".", ".."}
            or "/" in knowledge_point_id
            or "\\" in knowledge_point_id
            or any(ord(character) < 32 for character in knowledge_point_id)
        ):
            raise KnowledgeInputError(
                "The knowledge point identifier is not safe for a graph lookup."
            )
        return quote(knowledge_point_id, safe="")

    async def _request(
        self,
        method: str,
        url: str,
        *,
        request_id: str,
        params: dict[str, object] | None = None,
        json: dict[str, object] | None = None,
        not_found_message: str = "The requested approved graph data was not found.",
    ):
        headers = {
            "X-Internal-Token": self._settings.internal_token.get_secret_value(),
            "X-Request-Id": request_id,
            "Accept": "application/json",
        }
        if json is not None:
            headers["Content-Type"] = "application/json"
        try:
            response = await self._client.request(
                method,
                url,
                headers=headers,
                params=params,
                json=json,
            )
        except httpx2.TimeoutException as exc:
            raise KnowledgeTimeoutError(
                "The knowledge-service request timed out."
            ) from exc
        except httpx2.TransportError as exc:
            raise KnowledgeTransportError(
                "The knowledge service could not be reached."
            ) from exc
        if response.status_code == 404:
            raise KnowledgeNotFoundError(
                not_found_message,
                status_code=response.status_code,
            )
        if response.status_code in {401, 403}:
            raise KnowledgeAuthenticationError(
                "The knowledge service rejected internal authentication.",
                status_code=response.status_code,
            )
        if 500 <= response.status_code <= 599:
            raise KnowledgeServerError(
                "The knowledge service returned a server error.",
                status_code=response.status_code,
            )
        if not 200 <= response.status_code < 300:
            raise KnowledgeRequestError(
                "The knowledge service rejected the request.",
                status_code=response.status_code,
            )
        return response
