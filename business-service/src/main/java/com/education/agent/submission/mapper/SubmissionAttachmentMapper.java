package com.education.agent.submission.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SubmissionAttachmentMapper {
  @Select(
      "SELECT file_id FROM edu_submission_attachment WHERE answer_id=#{answerId} ORDER BY file_id")
  List<Long> findFileIds(@Param("answerId") Long answerId);

  @Delete("DELETE FROM edu_submission_attachment WHERE answer_id=#{answerId}")
  int deleteByAnswer(@Param("answerId") Long answerId);

  @Insert("INSERT INTO edu_submission_attachment(answer_id,file_id) VALUES(#{answerId},#{fileId})")
  int insert(@Param("answerId") Long answerId, @Param("fileId") Long fileId);
}
