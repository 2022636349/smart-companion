-- One-time cleanup for repeated records left by the browser E2E suite before it
-- gained teardown. Scope is deliberately limited to the demo teacher and the
-- two fixed E2E titles; ordinary assessments with the same title are untouched.
UPDATE edu_assessment assessment
JOIN (
    SELECT ranked.id
    FROM (
        SELECT candidate.id,
               ROW_NUMBER() OVER (
                   PARTITION BY candidate.creator_id, candidate.class_id,
                                candidate.assessment_type, candidate.title
                   ORDER BY (
                       SELECT COUNT(*)
                       FROM edu_assessment_submission submission
                       WHERE submission.assessment_id = candidate.id
                         AND submission.status <> 'IN_PROGRESS'
                   ) DESC,
                   (
                       SELECT COUNT(*)
                       FROM edu_assessment_submission submission
                       WHERE submission.assessment_id = candidate.id
                   ) DESC,
                   candidate.id DESC
               ) AS duplicate_rank
        FROM edu_assessment candidate
        JOIN edu_user creator ON creator.id = candidate.creator_id
        WHERE candidate.deleted_at IS NULL
          AND creator.username = 'teacher_demo'
          AND candidate.title IN ('C2 E2E 手工作业', 'C2 E2E AI 导入降级')
    ) ranked
    WHERE ranked.duplicate_rank > 1
) duplicate ON duplicate.id = assessment.id
SET assessment.deleted_at = CURRENT_TIMESTAMP(3)
WHERE assessment.deleted_at IS NULL;
