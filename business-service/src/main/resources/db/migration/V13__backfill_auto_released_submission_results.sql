-- Auto release is submission-scoped: already-completed answers from assessments that opted in
-- must become visible without waiting for the whole assessment to close.
UPDATE edu_assessment_submission AS s
JOIN edu_assessment AS a ON a.id = s.assessment_id
SET s.result_released_at = COALESCE(
        s.submitted_at,
        s.updated_at,
        CURRENT_TIMESTAMP(3)
    ),
    s.updated_at = CURRENT_TIMESTAMP(3)
WHERE a.deleted_at IS NULL
  AND a.auto_release_enabled = TRUE
  AND s.grading_status = 'COMPLETED'
  AND s.result_released_at IS NULL;
