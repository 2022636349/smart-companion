package com.education.agent.classroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.education.agent.classroom.dto.ClassDetail;
import com.education.agent.classroom.dto.ClassSummary;
import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.classroom.mapper.ClassStudentMapper;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.common.api.PageData;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.List;

@Service
public class ClassroomQueryService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");

    private final ClassMapper classMapper;
    private final ClassStudentMapper classStudentMapper;
    private final UserMapper userMapper;
    private final com.education.agent.course.CourseAccessService courses;

    public ClassroomQueryService(
            ClassMapper classMapper,
            ClassStudentMapper classStudentMapper,
            UserMapper userMapper, com.education.agent.course.CourseAccessService courses
    ) {
        this.classMapper = classMapper;
        this.classStudentMapper = classStudentMapper;
        this.userMapper = userMapper;
        this.courses = courses;
    }

    public PageData<ClassSummary> listVisible(
            AuthenticatedUser principal,
            Long courseId,
            long page,
            long size,
            ClassStatus status
    ) {
        if (courseId != null) courses.requireView(principal, courseId);
        LambdaQueryWrapper<ClassEntity> query = new LambdaQueryWrapper<ClassEntity>()
                .eq(courseId != null, ClassEntity::getCourseId, courseId)
                .eq(status != null, ClassEntity::getStatus, status)
                .orderByDesc(ClassEntity::getCreatedAt);

        if (principal.role() == UserRole.STUDENT) {
            List<Long> classIds = classStudentMapper.findClassIdsByStudentId(principal.id());
            if (classIds.isEmpty()) {
                return new PageData<>(List.of(), page, size, 0, 0);
            }
            query.in(ClassEntity::getId, classIds);
            query.inSql(ClassEntity::getCourseId,"SELECT id FROM edu_course WHERE status<>'DRAFT'");
        } else if (principal.role() == UserRole.TEACHER) {
            query.inSql(ClassEntity::getCourseId,"SELECT course_id FROM edu_course_teacher WHERE teacher_id=" + principal.id() + " AND status='ACTIVE'");
        }

        Page<ClassEntity> result = classMapper.selectPage(Page.of(page, size), query);
        List<ClassSummary> items = result.getRecords().stream().map(this::toSummary).toList();
        return new PageData<>(items, result.getCurrent(), result.getSize(), result.getTotal(), result.getPages());
    }

    public ClassDetail getVisible(AuthenticatedUser principal, Long classId) {
        ClassEntity entity = classMapper.selectById(classId);
        if (entity == null || !canView(principal, entity)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "班级不存在");
        }
        courses.requireView(principal,entity.getCourseId());
        ClassSummary summary = toSummary(entity);
        return new ClassDetail(
                summary.id(),
                entity.getCourseId(),
                summary.name(),
                summary.courseName(),
                summary.teacherId(),
                summary.teacherName(),
                summary.studentCount(),
                summary.status(),
                entity.getDescription(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().atZone(DATABASE_ZONE).toInstant(),
                entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().atZone(DATABASE_ZONE).toInstant()
        );
    }

    private boolean canView(AuthenticatedUser principal, ClassEntity entity) {
        if (principal.role() == UserRole.ADMIN) {
            return true;
        }
        if (principal.role() == UserRole.TEACHER) {
            return courses.role(principal,entity.getCourseId()) != null;
        }
        return classStudentMapper.countMembership(entity.getId(), principal.id()) > 0;
    }

    private ClassSummary toSummary(ClassEntity entity) {
        UserEntity teacher = userMapper.selectById(entity.getTeacherId());
        long studentCount = classStudentMapper.countByClassId(entity.getId());
        return new ClassSummary(
                entity.getId(),
                entity.getCourseId(),
                entity.getName(),
                entity.getCourseName(),
                entity.getTeacherId(),
                teacher == null ? "未知教师" : teacher.getDisplayName(),
                studentCount,
                entity.getStatus()
        );
    }
}
