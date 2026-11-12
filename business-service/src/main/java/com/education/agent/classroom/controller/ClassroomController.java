package com.education.agent.classroom.controller;

import com.education.agent.classroom.dto.ClassDetail;
import com.education.agent.classroom.dto.ClassSummary;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.classroom.service.ClassroomQueryService;
import com.education.agent.common.api.ApiResponse;
import com.education.agent.common.api.PageData;
import com.education.agent.common.web.RequestIds;
import com.education.agent.security.AuthenticatedUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/classes")
public class ClassroomController {

    private final ClassroomQueryService classroomQueryService;

    public ClassroomController(ClassroomQueryService classroomQueryService) {
        this.classroomQueryService = classroomQueryService;
    }

    @GetMapping
    public ApiResponse<PageData<ClassSummary>> listClasses(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) Long courseId,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long size,
            @RequestParam(required = false) ClassStatus status
    ) {
        return ApiResponse.ok(
                RequestIds.current(),
                classroomQueryService.listVisible(principal, courseId, page, size, status)
        );
    }

    @GetMapping("/{classId}")
    public ApiResponse<ClassDetail> getClass(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long classId
    ) {
        return ApiResponse.ok(
                RequestIds.current(),
                classroomQueryService.getVisible(principal, classId)
        );
    }
}
