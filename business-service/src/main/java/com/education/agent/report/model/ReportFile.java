package com.education.agent.report.model;

public record ReportFile(byte[] content, String contentType, String filename) {}
