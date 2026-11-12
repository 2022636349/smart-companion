package com.education.agent.report.controller;

import com.education.agent.report.model.ReportFile;
import com.education.agent.report.service.ReportService;
import com.education.agent.security.AuthenticatedUser;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/reports")
public class ReportController {
  private final ReportService reports;

  public ReportController(ReportService reports) { this.reports = reports; }

  @GetMapping("/assessments/{assessmentId}/grades")
  public ResponseEntity<byte[]> grades(@AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long assessmentId, @RequestParam(defaultValue = "XLSX") GradeFormat format) {
    return response(reports.gradeReport(principal, assessmentId, format));
  }

  @GetMapping("/students/{studentId}/learning")
  public ResponseEntity<byte[]> learning(@AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable Long studentId, @RequestParam @Min(1) Long courseId,
      @RequestParam(defaultValue = "PDF") LearningFormat format) {
    return response(reports.learningReport(principal, studentId, courseId, format));
  }

  private ResponseEntity<byte[]> response(ReportFile report) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, report.contentType())
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(report.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(report.content());
  }

  public enum GradeFormat { CSV, XLSX }
  public enum LearningFormat { PDF, XLSX }
}
