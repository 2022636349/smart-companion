package com.education.agent.assessment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.education.agent.assessment.entity.AssessmentEntity;
import com.education.agent.assessment.entity.AssessmentImportTaskEntity;
import com.education.agent.assessment.mapper.AssessmentImportTaskMapper;
import com.education.agent.assessment.mapper.AssessmentMapper;
import com.education.agent.assessment.model.AssessmentStatus;
import com.education.agent.assessment.model.TaskStatus;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class AssessmentImportRecovery implements ApplicationRunner {

    private final AssessmentImportTaskMapper taskMapper;
    private final AssessmentMapper assessmentMapper;

    public AssessmentImportRecovery(
            AssessmentImportTaskMapper taskMapper,
            AssessmentMapper assessmentMapper
    ) {
        this.taskMapper = taskMapper;
        this.assessmentMapper = assessmentMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<AssessmentImportTaskEntity> interrupted = taskMapper.selectList(
                new LambdaQueryWrapper<AssessmentImportTaskEntity>()
                        .in(AssessmentImportTaskEntity::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING)
        );
        Set<Long> assessmentIds = new HashSet<>();
        for (AssessmentImportTaskEntity task : interrupted) {
            task.setStatus(TaskStatus.FAILED);
            task.setErrorCode("IMPORT_INTERRUPTED");
            task.setErrorMessage("服务重启中断了题目解析，请重新上传同一文件重试");
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            assessmentIds.add(task.getAssessmentId());
        }
        for (Long assessmentId : assessmentIds) {
            AssessmentEntity assessment = assessmentMapper.selectForUpdate(assessmentId);
            if (assessment != null && assessment.getStatus() == AssessmentStatus.PARSING) {
                assessment.setStatus(AssessmentStatus.DRAFT);
                assessmentMapper.updateById(assessment);
            }
        }
    }
}
