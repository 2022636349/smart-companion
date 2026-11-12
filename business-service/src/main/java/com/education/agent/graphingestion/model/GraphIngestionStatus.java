package com.education.agent.graphingestion.model;

public enum GraphIngestionStatus {
    CREATED,
    EXTRACTING,
    REVIEWING,
    VALIDATING,
    WAITING_HUMAN_REVIEW,
    READY_TO_PUBLISH,
    PUBLISHING,
    PUBLISHED,
    FAILED,
    CANCELLED,
    PARTIALLY_COMPLETED
}
