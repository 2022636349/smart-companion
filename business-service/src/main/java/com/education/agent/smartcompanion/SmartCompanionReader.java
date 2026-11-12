package com.education.agent.smartcompanion;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Value-only, bounded readers. Nothing from an upload is extracted or executed. */
public final class SmartCompanionReader {
  static final List<String> TABLES = List.of("课程要求", "题目与材料", "模拟学生", "测验节点", "作答记录", "字段说明");
  static final int MAX_ENTRY = 16 * 1024 * 1024, MAX_TOTAL = 48 * 1024 * 1024;

  public Map<String, List<Map<String, String>>> read(byte[] bytes, String name) throws Exception {
    if (name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) return workbook(bytes);
    if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) throw new IOException("只支持xlsx或zip");
    Map<String, byte[]> selected = new LinkedHashMap<>();
    int count = 0, total = 0;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        if (++count > 500) throw new IOException("压缩包文件过多");
        String p = e.getName().replace('\\', '/');
        if (p.startsWith("/") || p.contains("../") || p.matches("^[A-Za-z]:.*"))
          throw new IOException("压缩包路径非法");
        String base = p.substring(p.lastIndexOf('/') + 1);
        boolean csv = TABLES.stream().anyMatch(t -> base.equals(t + ".csv"));
        boolean xlsx = base.toLowerCase(Locale.ROOT).endsWith(".xlsx");
        if (!csv && !xlsx) {
          byte[] skip = new byte[8192];
          int read;
          while ((read = zip.read(skip)) != -1) {
            total += read;
            if (total > 256 * 1024 * 1024) throw new IOException("压缩包实际解压超过256MiB预算");
          }
          continue;
        }
        byte[] content = bounded(zip, MAX_ENTRY);
        total += content.length;
        if (total > 256 * 1024 * 1024) throw new IOException("压缩包实际解压超过256MiB预算");
        if (selected.putIfAbsent(base, content) != null) throw new IOException("同名数据文件歧义: " + base);
      }
    }
    var result = new LinkedHashMap<String, List<Map<String, String>>>();
    for (String t : TABLES)
      if (selected.containsKey(t + ".csv"))
        result.put(t, table(csv(new String(selected.get(t + ".csv"), StandardCharsets.UTF_8))));
    var books =
        selected.entrySet().stream()
            .filter(e -> e.getKey().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            .toList();
    if (books.size() > 1) throw new IOException("多份xlsx存在歧义");
    if (!books.isEmpty()) {
      var book = workbook(books.get(0).getValue());
      if (result.isEmpty()) result.putAll(book);
      else {
        for (String t : TABLES)
          if (result.containsKey(t) && !Objects.equals(result.get(t), book.get(t)))
            throw new IOException("CSV与xlsx不一致: " + t);
        if (book.containsKey("数据说明")) result.put("数据说明", book.get("数据说明"));
      }
    }
    return result;
  }

  static byte[] bounded(InputStream in, int max) throws IOException {
    var out = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > max) throw new IOException("数据单元超过预算");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }

  static List<List<String>> csv(String text) throws IOException {
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    var rows = new ArrayList<List<String>>();
    var row = new ArrayList<String>();
    var value = new StringBuilder();
    boolean quoted = false, closed = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            value.append('"');
            i++;
          } else {
            quoted = false;
            closed = true;
          }
        } else value.append(c);
      } else if (c == ',' || c == '\r' || c == '\n') {
        row.add(value.toString());
        value.setLength(0);
        closed = false;
        if (c != ',') {
          rows.add(row);
          row = new ArrayList<>();
          if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        }
      } else if (c == '"' && value.length() == 0 && !closed) quoted = true;
      else {
        if (closed || c == '"') throw new IOException("CSV引号结构无效");
        value.append(c);
      }
    }
    if (quoted) throw new IOException("CSV引号未闭合");
    if (value.length() > 0 || !row.isEmpty() || closed) {
      row.add(value.toString());
      rows.add(row);
    }
    return rows;
  }

  static List<Map<String, String>> table(List<List<String>> rows) throws IOException {
    if (rows.isEmpty()) throw new IOException("空表");
    var headers = rows.get(0);
    if (new HashSet<>(headers).size() != headers.size() || headers.contains(""))
      throw new IOException("列名为空或重复");
    var data = new ArrayList<Map<String, String>>();
    for (int i = 1; i < rows.size(); i++) {
      var cells = rows.get(i);
      if (cells.stream().allMatch(String::isEmpty)) continue;
      if (cells.size() > headers.size()) throw new IOException("列数超出表头");
      var r = new LinkedHashMap<String, String>();
      for (int j = 0; j < headers.size(); j++)
        r.put(headers.get(j), j < cells.size() ? cells.get(j) : "");
      data.add(r);
    }
    if (data.size() > 50000) throw new IOException("数据行超过预算");
    return data;
  }

  static Document xml(byte[] bytes) throws Exception {
    var f = DocumentBuilderFactory.newInstance();
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setFeature("http://xml.org/sax/features/external-general-entities", false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    f.setXIncludeAware(false);
    f.setExpandEntityReferences(false);
    return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
  }

  static String value(Element e, String tag) {
    var nodes = e.getElementsByTagName(tag);
    return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
  }

  static Map<String, List<Map<String, String>>> workbook(byte[] bytes) throws Exception {
    return workbook(bytes, false);
  }

  static Map<String, List<Map<String, String>>> workbook(byte[] bytes, boolean allSheets) throws Exception {
    var entries = new HashMap<String, byte[]>();
    int total = 0, count = 0;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        if (++count > 500) throw new IOException("xlsx条目过多");
        String n = e.getName();
        if (n.contains("..") || n.startsWith("/")) throw new IOException("xlsx路径非法");
        if (n.endsWith("vbaProject.bin")) throw new IOException("不支持宏");
        if (n.endsWith(".xml") || n.endsWith(".rels")) {
          byte[] b = bounded(zip, MAX_ENTRY);
          total += b.length;
          if (total > MAX_TOTAL) throw new IOException("xlsx解压超过预算");
          if (entries.put(n, b) != null) throw new IOException("xlsx重复条目");
        }
      }
    }
    if (!entries.containsKey("xl/workbook.xml")
        || !entries.containsKey("xl/_rels/workbook.xml.rels")) throw new IOException("不是有效xlsx");
    var strings = new ArrayList<String>();
    if (entries.containsKey("xl/sharedStrings.xml")) {
      var sis = xml(entries.get("xl/sharedStrings.xml")).getElementsByTagName("si");
      for (int i = 0; i < sis.getLength(); i++) strings.add(sis.item(i).getTextContent());
    }
    var targets = new HashMap<String, String>();
    var rels = xml(entries.get("xl/_rels/workbook.xml.rels")).getElementsByTagName("Relationship");
    for (int i = 0; i < rels.getLength(); i++) {
      var e = (Element) rels.item(i);
      if ("External".equals(e.getAttribute("TargetMode"))) throw new IOException("不支持外部关系");
      String t = e.getAttribute("Target");
      targets.put(e.getAttribute("Id"), t.startsWith("/") ? t.substring(1) : "xl/" + t);
    }
    var result = new LinkedHashMap<String, List<Map<String, String>>>();
    var sheets = xml(entries.get("xl/workbook.xml")).getElementsByTagName("sheet");
    for (int i = 0; i < sheets.getLength(); i++) {
      var sheet = (Element) sheets.item(i);
      String name = sheet.getAttribute("name");
      if (!allSheets && !TABLES.contains(name) && !name.equals("数据说明")) continue;
      String target = targets.get(sheet.getAttribute("r:id"));
      if (target == null || !entries.containsKey(target)) throw new IOException("工作表缺失");
      var rowNodes = xml(entries.get(target)).getElementsByTagName("row");
      var rows = new ArrayList<List<String>>();
      for (int r = 0; r < rowNodes.getLength(); r++) {
        var cells = ((Element) rowNodes.item(r)).getElementsByTagName("c");
        var line = new ArrayList<String>();
        for (int c = 0; c < cells.getLength(); c++) {
          var cell = (Element) cells.item(c);
          if (cell.getElementsByTagName("f").getLength() > 0) throw new IOException("不执行公式，请上传静态值");
          String ref = cell.getAttribute("r");
          int col = 0;
          for (char ch : ref.toCharArray()) {
            if (ch < 'A' || ch > 'Z') break;
            col = col * 26 + ch - 'A' + 1;
          }
          if (col < 1 || col > 100) throw new IOException("单元格列越界");
          while (line.size() < col) line.add("");
          String v = value(cell, "v"), type = cell.getAttribute("t");
          if (type.equals("s")) v = strings.get(Integer.parseInt(v));
          if (type.equals("inlineStr")) v = value(cell, "is");
          line.set(col - 1, v);
        }
        rows.add(line);
      }
      if (result.put(name, table(rows)) != null) throw new IOException("工作表重名");
    }
    return result;
  }
}
