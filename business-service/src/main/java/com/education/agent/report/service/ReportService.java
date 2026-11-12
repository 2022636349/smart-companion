package com.education.agent.report.service;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.course.CourseAccessService;
import com.education.agent.report.controller.ReportController.GradeFormat;
import com.education.agent.report.controller.ReportController.LearningFormat;
import com.education.agent.report.model.ReportFile;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.model.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
  public static final int ABSOLUTE_MAX_ROWS=100_000;
  public static final long ABSOLUTE_MAX_CELLS=1_000_000L;
  public static final int ABSOLUTE_MAX_CELL_CHARS=65_536;
  public static final long ABSOLUTE_MAX_TOTAL_CHARS=20_000_000L;
  public static final int ABSOLUTE_MAX_BYTES=50*1024*1024;
  private static final ZoneId DB_ZONE = ZoneId.of("Asia/Shanghai");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final CourseAccessService courses;
  private final int maxRows;
  private final long maxCells;
  private final int maxCellChars;
  private final long maxTotalChars;
  private final int maxBytes;

  @Autowired
  public ReportService(JdbcTemplate jdbc, ObjectMapper json, CourseAccessService courses,
      @Value("${education.report.max-rows:10000}") int maxRows,
      @Value("${education.report.max-cells:150000}") long maxCells,
      @Value("${education.report.max-cell-chars:4096}") int maxCellChars,
      @Value("${education.report.max-total-chars:2000000}") long maxTotalChars,
      @Value("${education.report.max-bytes:10485760}") int maxBytes) {
    requireRange("max-rows",maxRows,ABSOLUTE_MAX_ROWS);
    requireRange("max-cells",maxCells,ABSOLUTE_MAX_CELLS);
    requireRange("max-cell-chars",maxCellChars,ABSOLUTE_MAX_CELL_CHARS);
    requireRange("max-total-chars",maxTotalChars,ABSOLUTE_MAX_TOTAL_CHARS);
    requireRange("max-bytes",maxBytes,ABSOLUTE_MAX_BYTES);
    this.jdbc=jdbc;this.json=json;this.courses=courses;this.maxRows=maxRows;this.maxCells=maxCells;
    this.maxCellChars=maxCellChars;this.maxTotalChars=maxTotalChars;this.maxBytes=maxBytes;
  }

  private static void requireRange(String property,long value,long maximum){
    if(value<1||value>maximum)throw new IllegalArgumentException(
        "education.report."+property+" must be between 1 and "+maximum);
  }

  public ReportFile gradeReport(AuthenticatedUser principal, Long assessmentId, GradeFormat format) {
    AssessmentHeader header = jdbc.query("""
        SELECT id,creator_id,status,title,LEFT(settings_json,?) settings_json,
               CHAR_LENGTH(settings_json) settings_length FROM edu_assessment WHERE id=? AND deleted_at IS NULL
        """, rs -> {
          if(!rs.next())return null;
          long length=rs.getLong("settings_length");Long settingsLength=rs.wasNull()?null:length;
          return new AssessmentHeader(rs.getLong("id"),rs.getLong("creator_id"),rs.getString("status"),
              rs.getString("title"),rs.getString("settings_json"),settingsLength);
        }, maxCellChars + 1, assessmentId);
    if (header == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "报告资源不存在");
    Long courseId=jdbc.queryForObject("SELECT c.course_id FROM edu_assessment a JOIN edu_class c ON c.id=a.class_id WHERE a.id=?",Long.class,assessmentId);
    if (principal.role() != UserRole.ADMIN &&
        (principal.role() != UserRole.TEACHER || !principal.id().equals(header.creatorId()))) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "报告资源不存在");
    }
    courses.requireView(principal,courseId);
    if (!"RELEASED".equals(header.status()) && !"ARCHIVED".equals(header.status())) {
      throw new BusinessException(ErrorCode.RESULT_NOT_RELEASED, "成绩尚未发布");
    }
    if(header.settingsLength()!=null&&header.settingsLength()>maxCellChars)
      throw budgetExceeded("报告配置字符数超过上限");
    JsonNode settings = settings(header.settings());
    boolean showScore = settings.path("showScoreAfterRelease").asBoolean(true);
    boolean showFeedback = settings.path("showQuestionFeedbackAfterRelease").asBoolean(true);
    ReportBudget budget = new ReportBudget();
    List<List<Object>> rows = new ArrayList<>();
    rows.add(budget.add(List.of("submissionId", "studentId", "username", "displayName", "attemptNo",
        "status", "gradingStatus", "finalScore", "overallComment", "releasedAt"), false));
    rows.addAll(jdbc.query("""
        SELECT s.id,s.student_id,u.username,u.display_name,s.attempt_no,s.status,s.grading_status,
               s.final_score,LEFT(s.overall_comment,?) overall_comment,
               CHAR_LENGTH(s.overall_comment) overall_comment_length,s.result_released_at
        FROM edu_assessment_submission s JOIN edu_user u ON u.id=s.student_id
        WHERE s.assessment_id=? AND s.result_released_at IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM edu_assessment_submission newer
            WHERE newer.assessment_id=s.assessment_id AND newer.student_id=s.student_id
              AND newer.result_released_at IS NOT NULL AND newer.attempt_no>s.attempt_no)
        ORDER BY u.username,s.id LIMIT ?
        """, (rs, n) -> {
          if (showFeedback && rs.getLong("overall_comment_length") > maxCellChars && !rs.wasNull())
            throw budgetExceeded("报告单元格字符数超过上限");
          return budget.add(List.of(rs.getLong("id"), rs.getLong("student_id"), rs.getString("username"),
            rs.getString("display_name"), rs.getInt("attempt_no"), rs.getString("status"),
            rs.getString("grading_status"), showScore && rs.getBigDecimal("final_score") != null
                ? rs.getBigDecimal("final_score") : "",
            showFeedback && rs.getString("overall_comment") != null ? rs.getString("overall_comment") : "",
            timestamp(rs.getTimestamp("result_released_at"))), true);
        }, maxCellChars + 1, assessmentId, maxRows + 1));
    String base = "assessment-" + assessmentId + "-grades";
    return format == GradeFormat.CSV
        ? new ReportFile(csv(rows), "text/csv;charset=UTF-8", base + ".csv")
        : new ReportFile(xlsx(List.of(new Sheet("Grades", rows))),
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", base + ".xlsx");
  }

  public ReportFile learningReport(AuthenticatedUser principal, Long studentId, Long courseId, LearningFormat format) {
    StudentHeader student = jdbc.query("SELECT id,username,display_name FROM edu_user WHERE id=? AND role='STUDENT'",
        rs -> rs.next() ? new StudentHeader(rs.getLong(1), rs.getString(2), rs.getString(3)) : null, studentId);
    if (student == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "报告资源不存在");
    requireLearningAccess(principal, studentId, courseId);

    ReportBudget budget = new ReportBudget();
    List<List<Object>> summary = new ArrayList<>();
    summary.add(budget.add(List.of("studentId", "username", "displayName", "courseId", "courseName"), false));
    summary.add(budget.add(List.of(student.id(), student.username(), student.displayName(), courseId, courses.requireCourse(courseId).get("name")), true));
    List<List<Object>> mastery = new ArrayList<>();
    mastery.add(budget.add(List.of("knowledgePointId", "knowledgePointName", "score", "confidence", "status", "evidenceCount", "updatedAt"), false));
    mastery.addAll(jdbc.query("""
        SELECT knowledge_point_id,knowledge_point_name,score,confidence,mastery_status,evidence_count,updated_at
        FROM edu_knowledge_mastery WHERE student_id=? AND course_id=? ORDER BY score,knowledge_point_id LIMIT ?
        """, (rs,n) -> budget.add(List.of(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
            rs.getBigDecimal(4), rs.getString(5), rs.getInt(6), timestamp(rs.getTimestamp(7))), true),
        studentId, courseId, maxRows + 1));
    List<List<Object>> results = new ArrayList<>();
    results.add(budget.add(List.of("assessmentId", "title", "type", "finalScore", "overallComment", "releasedAt"), false));
    results.addAll(jdbc.query("""
        SELECT a.id,a.title,a.assessment_type,LEFT(a.settings_json,?) settings_json,
               CHAR_LENGTH(a.settings_json) settings_length,s.final_score,
               LEFT(s.overall_comment,?) overall_comment,CHAR_LENGTH(s.overall_comment) overall_comment_length,
               s.result_released_at
        FROM edu_assessment_submission s JOIN edu_assessment a ON a.id=s.assessment_id
        JOIN edu_class c ON c.id=a.class_id
        WHERE s.student_id=? AND c.course_id=? AND a.deleted_at IS NULL AND s.result_released_at IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM edu_assessment_submission newer
            WHERE newer.assessment_id=s.assessment_id AND newer.student_id=s.student_id
              AND newer.result_released_at IS NOT NULL AND newer.attempt_no>s.attempt_no)
        ORDER BY s.result_released_at DESC,a.id LIMIT ?
        """, (rs,n) -> {
          if(rs.getLong("settings_length")>maxCellChars&&!rs.wasNull())throw budgetExceeded("报告配置字符数超过上限");
          JsonNode visibility = settings(rs.getString("settings_json"));
          Object score = visibility.path("showScoreAfterRelease").asBoolean(true) && rs.getBigDecimal("final_score") != null
              ? rs.getBigDecimal("final_score") : "";
          boolean showComment=visibility.path("showQuestionFeedbackAfterRelease").asBoolean(true);
          if(showComment && rs.getLong("overall_comment_length")>maxCellChars && !rs.wasNull())
            throw budgetExceeded("报告单元格字符数超过上限");
          Object comment = showComment && rs.getString("overall_comment") != null ? rs.getString("overall_comment") : "";
          return budget.add(List.of(rs.getLong("id"), rs.getString("title"), rs.getString("assessment_type"),
              score, comment, timestamp(rs.getTimestamp("result_released_at"))), true);
        }, maxCellChars + 1, maxCellChars + 1, studentId, courseId, maxRows + 1));
    String base = "student-" + studentId + "-course-" + courseId + "-learning";
    List<Sheet> sheets = List.of(new Sheet("Summary", summary), new Sheet("Mastery", mastery),
        new Sheet("Released Results", results));
    return format == LearningFormat.XLSX
        ? new ReportFile(xlsx(sheets), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", base + ".xlsx")
        : new ReportFile(pdf(sheets), "application/pdf", base + ".pdf");
  }

  private void requireLearningAccess(AuthenticatedUser principal, Long studentId, Long courseId) {
    courses.requireView(principal,courseId);
    Integer enrolled=jdbc.queryForObject("SELECT COUNT(*) FROM edu_class_student cs JOIN edu_class c ON c.id=cs.class_id WHERE cs.student_id=? AND c.course_id=?",Integer.class,studentId,courseId);
    if(enrolled==null||enrolled==0)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"学生未加入该课程");
    if (principal.role() == UserRole.ADMIN || (principal.role() == UserRole.STUDENT && principal.id().equals(studentId))) return;
    if (principal.role() == UserRole.TEACHER) {
      Integer count = jdbc.queryForObject("""
          SELECT COUNT(*) FROM edu_class c JOIN edu_class_student cs ON cs.class_id=c.id
          WHERE c.course_id=? AND cs.student_id=?
          """, Integer.class, courseId, studentId);
      if (count != null && count > 0) return;
    }
    throw new BusinessException(ErrorCode.FORBIDDEN, "无权导出该学生学情");
  }

  private JsonNode settings(String source) {
    try { return source == null ? json.createObjectNode() : json.readTree(source); }
    catch (Exception ignored) { return json.createObjectNode(); }
  }

  private String timestamp(Timestamp value) {
    return value == null ? "" : TIME.format(value.toLocalDateTime().atZone(DB_ZONE));
  }

  private byte[] csv(List<List<Object>> rows) {
    try {
      BoundedBytes bytes = new BoundedBytes(maxBytes);
      try (OutputStreamWriter out = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
        out.write("\ufeff");
        for (List<Object> row : rows) {
          for (int i = 0; i < row.size(); i++) {
            if (i > 0) out.write(',');
            String value = String.valueOf(row.get(i));
            String trimmed = value.stripLeading();
            if (!trimmed.isEmpty() && "=+-@\t\r".indexOf(trimmed.charAt(0)) >= 0) value = "'" + value;
            out.write('"');out.write(value.replace("\"", "\"\""));out.write('"');
          }
          out.write("\r\n");
        }
      }
      return bytes.toByteArray();
    } catch (BusinessException exception) { throw exception; }
    catch (Exception exception) { throw new BusinessException(ErrorCode.INTERNAL_ERROR,"生成 CSV 报告失败"); }
  }

  private byte[] xlsx(List<Sheet> sheets) {
    try {
      BoundedBytes bytes = new BoundedBytes(maxBytes);
      try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
        entry(zip, "[Content_Types].xml", contentTypes(sheets.size()));
        entry(zip, "_rels/.rels", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>
            """);
        entry(zip, "docProps/core.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>EducationAgent Report</dc:title><dc:creator>EducationAgent</dc:creator></cp:coreProperties>
            """);
        entry(zip, "xl/workbook.xml", workbook(sheets));
        entry(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size()));
        entry(zip, "xl/styles.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="1"><xf xfId="0"/></cellXfs></styleSheet>
            """);
        for (int i = 0; i < sheets.size(); i++) entry(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", worksheet(sheets.get(i).rows()));
      }
      return bytes.toByteArray();
    } catch (BusinessException exception) { throw exception; }
    catch (Exception e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成 XLSX 报告失败"); }
  }

  private String contentTypes(int count) {
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/><Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>");
    for (int i=1;i<=count;i++) xml.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
    return xml.append("</Types>").toString();
  }
  private String workbook(List<Sheet> sheets) {
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
    for(int i=0;i<sheets.size();i++) xml.append("<sheet name=\"").append(escape(sheets.get(i).name())).append("\" sheetId=\"").append(i+1).append("\" r:id=\"rId").append(i+1).append("\"/>");
    return xml.append("</sheets></workbook>").toString();
  }
  private String workbookRels(int count) {
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
    for(int i=1;i<=count;i++) xml.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
    xml.append("<Relationship Id=\"rId").append(count+1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
    return xml.toString();
  }
  private String worksheet(List<List<Object>> rows) {
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
    for(int r=0;r<rows.size();r++) { xml.append("<row r=\"").append(r+1).append("\">");
      for(int c=0;c<rows.get(r).size();c++) { Object value=rows.get(r).get(c); String ref=column(c)+(r+1);
        if(value instanceof Number) xml.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>");
        else xml.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(escape(String.valueOf(value))).append("</t></is></c>");
      } xml.append("</row>"); }
    return xml.append("</sheetData></worksheet>").toString();
  }
  private String column(int index) { StringBuilder out=new StringBuilder(); for(int n=index+1;n>0;n=(n-1)/26) out.append((char)('A'+(n-1)%26)); return out.reverse().toString(); }
  private void entry(ZipOutputStream zip,String name,String value)throws java.io.IOException { ZipEntry entry=new ZipEntry(name); entry.setTime(0); zip.putNextEntry(entry); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
  private String escape(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;"); }

  private byte[] pdf(List<Sheet> sheets) {
    List<String> lines = new ArrayList<>();
    for (Sheet sheet : sheets) { lines.add("[" + ascii(sheet.name()) + "]"); for(List<Object> row:sheet.rows()) lines.add(ascii(row.stream().map(String::valueOf).reduce((a,b)->a+" | "+b).orElse(""))); lines.add(""); }
    List<List<String>> pages = new ArrayList<>(); for(int i=0;i<lines.size();i+=42) pages.add(lines.subList(i,Math.min(lines.size(),i+42))); if(pages.isEmpty())pages.add(List.of("EducationAgent Report"));
    try {
      List<byte[]> objects=new ArrayList<>(); objects.add(null); objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
      StringBuilder kids=new StringBuilder(); int fontId=3; int firstPage=4;
      for(int i=0;i<pages.size();i++)kids.append(firstPage+i*2).append(" 0 R ");
      objects.add(bytes("<< /Type /Pages /Kids ["+kids+"] /Count "+pages.size()+" >>")); objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"));
      for(int i=0;i<pages.size();i++){int pageId=firstPage+i*2,contentId=pageId+1; objects.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 "+fontId+" 0 R >> >> /Contents "+contentId+" 0 R >>")); StringBuilder content=new StringBuilder("BT /F1 9 Tf 40 800 Td 12 TL "); for(String line:pages.get(i))content.append('(').append(pdfEscape(line)).append(") Tj T* "); content.append("ET"); byte[] stream=bytes(content.toString()); objects.add(concat(bytes("<< /Length "+stream.length+" >>\nstream\n"),stream,bytes("\nendstream")));}
      BoundedBytes out=new BoundedBytes(maxBytes); out.write(bytes("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")); List<Integer> offsets=new ArrayList<>(); offsets.add(0); for(int i=1;i<objects.size();i++){offsets.add(out.size()); out.write(bytes(i+" 0 obj\n"));out.write(objects.get(i));out.write(bytes("\nendobj\n"));} int xref=out.size(); out.write(bytes("xref\n0 "+objects.size()+"\n0000000000 65535 f \n")); for(int i=1;i<objects.size();i++)out.write(bytes(String.format(Locale.ROOT,"%010d 00000 n \n",offsets.get(i)))); out.write(bytes("trailer\n<< /Size "+objects.size()+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n")); return out.toByteArray();
    } catch(BusinessException exception){throw exception;}
    catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"生成 PDF 报告失败");}
  }
  private byte[] bytes(String value){return value.getBytes(StandardCharsets.ISO_8859_1);} private byte[] concat(byte[]...parts){ByteArrayOutputStream out=new ByteArrayOutputStream();try{for(byte[]p:parts)out.write(p);}catch(Exception ignored){}return out.toByteArray();}
  private String ascii(String value){StringBuilder out=new StringBuilder();for(char c:value.toCharArray()){if(c>=32&&c<=126)out.append(c);else out.append(String.format(Locale.ROOT,"U+%04X",(int)c));}return out.toString();}
  private String pdfEscape(String value){return value.replace("\\","\\\\").replace("(","\\(").replace(")","\\)");}

  private BusinessException budgetExceeded(String message){return new BusinessException(ErrorCode.VALIDATION_ERROR,message);}

  private final class ReportBudget {
    private long dataRows,cells,characters;
    private List<Object> add(List<Object> row,boolean data){
      if(data && ++dataRows>maxRows)throw budgetExceeded("报告数据行数超过上限");
      if(cells>maxCells-row.size())throw budgetExceeded("报告单元格数量超过上限");
      cells+=row.size();
      for(Object cell:row){String value=String.valueOf(cell);if(value.length()>maxCellChars)throw budgetExceeded("报告单元格字符数超过上限");if(characters>maxTotalChars-value.length())throw budgetExceeded("报告累计字符数超过上限");characters+=value.length();}
      return row;
    }
  }

  private final class BoundedBytes extends ByteArrayOutputStream {
    private final int limit;
    private BoundedBytes(int limit){super(Math.min(limit,8192));this.limit=limit;}
    @Override public synchronized void write(int value){ensure(1);super.write(value);}
    @Override public synchronized void write(byte[] value,int offset,int length){ensure(length);super.write(value,offset,length);}
    private void ensure(int additional){if(additional<0||count>limit-additional)throw budgetExceeded("报告文件字节数超过上限");}
  }

  private record AssessmentHeader(Long id,Long creatorId,String status,String title,String settings,Long settingsLength) {}
  private record StudentHeader(Long id,String username,String displayName) {}
  private record Sheet(String name,List<List<Object>> rows) {}
}
