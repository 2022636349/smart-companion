package com.education.agent.smartcompanion;

import java.io.*;
import java.nio.file.Files;
import org.springframework.web.multipart.MultipartFile;

/** Production adapter for bounded archive entries, independent of Spring test fixtures. */
record CompanionMultipartFile(String originalFilename,byte[] bytes) implements MultipartFile {
  public String getName(){return "file";}
  public String getOriginalFilename(){return originalFilename;}
  public String getContentType(){return "application/octet-stream";}
  public boolean isEmpty(){return bytes.length==0;}
  public long getSize(){return bytes.length;}
  public byte[] getBytes(){return bytes;}
  public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
  public void transferTo(File destination) throws IOException {Files.write(destination.toPath(),bytes);}
}
