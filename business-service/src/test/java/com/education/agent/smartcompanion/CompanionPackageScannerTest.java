package com.education.agent.smartcompanion;
import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

class CompanionPackageScannerTest {
  static byte[] zip(Map<String,byte[]> contents) throws Exception {
    var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out,StandardCharsets.UTF_8)){
      for(var e:contents.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}
    }return out.toByteArray();
  }
  static byte[] text(String value){return value.getBytes(StandardCharsets.UTF_8);}
  @Test void rolesDoNotTreatTextbookRulesAsCompetitionInstructions() throws Exception {
    var scan=new CompanionPackageScanner().scan(zip(Map.of("新学科教材.md",text("这里说明运算规则"),"参赛说明.txt",text("比赛规则"),"课程标准.pdf",text("pdf"),"unknown.csv",text("id,value\n1,2"))));
    assertThat(scan.analysis()).isNull();assertThat(scan.dataPresent()).isTrue();
    assertThat(scan.entries()).anySatisfy(e->{assertThat(e.path()).isEqualTo("新学科教材.md");assertThat(e.role()).isEqualTo("TEXTBOOK");assertThat(e.recommended()).isTrue();});
    assertThat(scan.entries()).anySatisfy(e->{assertThat(e.path()).isEqualTo("参赛说明.txt");assertThat(e.role()).isEqualTo("INSTRUCTIONS");assertThat(e.recommended()).isFalse();});
  }
  @Test void rejectsTraversalAbsolutePathsAndCaseDuplicatePaths() throws Exception {
    for(String path:List.of("../x.txt","/x.txt","C:/x.txt","a/../../x.txt","a\\..\\x.txt","a:stream.txt"))
      assertThatThrownBy(()->new CompanionPackageScanner().scan(zip(Map.of(path,text("x"))))).isInstanceOf(IOException.class);
    assertThatThrownBy(()->new CompanionPackageScanner().scan(zip(Map.of("A.txt",text("a"),"a.txt",text("b"))))).isInstanceOf(IOException.class);
  }
  @Test void rejectsEntryCountSingleEntryTotalAndCompressionBomb() throws Exception {
    var pair=zip(Map.of("a.txt",text("123456"),"b.txt",text("123456")));
    assertThatThrownBy(()->new CompanionPackageScanner(1,100,100,200).scan(pair)).isInstanceOf(IOException.class);
    assertThatThrownBy(()->new CompanionPackageScanner(10,5,100,200).scan(pair)).isInstanceOf(IOException.class);
    assertThatThrownBy(()->new CompanionPackageScanner(10,10,10,200).scan(pair)).isInstanceOf(IOException.class);
    var bomb=zip(Map.of("bomb.txt",new byte[2*1024*1024]));
    assertThatThrownBy(()->new CompanionPackageScanner().scan(bomb)).hasMessageContaining("压缩比");
  }
  @Test void nestedArchivesAreListedWithoutExpansion() throws Exception {
    var scan=new CompanionPackageScanner().scan(zip(Map.of("nested.zip",zip(Map.of("../evil.txt",text("x"))),"教材.txt",text("new content"))));
    assertThat(scan.entries()).hasSize(2);assertThat(scan.dataPresent()).isFalse();
    assertThat(scan.entries()).anySatisfy(e->{assertThat(e.path()).isEqualTo("nested.zip");assertThat(e.document()).isFalse();assertThat(e.reason()).contains("不展开");});
  }
  @Test void sixTablesAreRecognizedByHeadersAfterRenamingFiles() throws Exception {
    var raw=new SmartCompanionEngineTest().raw();var entries=new LinkedHashMap<String,byte[]>();int i=0;
    for(String table:SmartCompanionReader.TABLES) {
      var rows=raw.get(table);var headers=new ArrayList<>(rows.get(0).keySet());var csv=new StringBuilder();
      CompanionPackageScanner.appendCsv(csv,headers);for(var row:rows)CompanionPackageScanner.appendCsv(csv,headers.stream().map(row::get).toList());
      entries.put("random-"+(i++)+".csv",text(csv.toString()));
    }
    var scan=new CompanionPackageScanner().scan(zip(entries));assertThat(scan.analysis()).isNotNull();
    var parsed=new SmartCompanionEngine(new com.fasterxml.jackson.databind.ObjectMapper()).parse(new SmartCompanionReader().read(scan.analysis(),"recognized.zip"));
    assertThat(parsed.path("preview").path("quality").path("effectiveRecordCount").asInt()).isEqualTo(794);
  }
  @Test void officialWholePackageRecognizesDataWithoutFilenameSpecialCases() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SmartCompanionEngineTest.BASE.resolve("source.zip")),"Official package absent; explicitly skipped");
    var scan=new CompanionPackageScanner().scan(Files.readAllBytes(SmartCompanionEngineTest.BASE.resolve("source.zip")));
    assertThat(scan.analysis()).isNotNull();assertThat(scan.entries().stream().filter(CompanionPackageScanner.Entry::document).count()).isGreaterThan(0);
    assertThat(scan.entries()).anySatisfy(e->{assertThat(e.role()).isEqualTo("STANDARD");assertThat(e.recommended()).isTrue();});
  }
  @Test void warningBudgetAggregatesRemainingCountAndFileNamesRespectContract() throws Exception {
    var entries=new LinkedHashMap<String,byte[]>();for(int i=0;i<120;i++)entries.put("unknown-"+i+".csv",text("id,value\n1,2"));
    var scan=new CompanionPackageScanner().scan(zip(entries));
    assertThat(scan.warnings()).hasSize(100);assertThat(scan.warnings().get(99)).contains("23条警告");
    assertThatThrownBy(()->new CompanionPackageScanner().scan(zip(Map.of("a".repeat(256)+".bin",text("a"))))).hasMessageContaining("255");
  }
}
