package com.education.agent.submission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.education.agent.submission.entity.SubmissionAnswerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface SubmissionAnswerMapper extends BaseMapper<SubmissionAnswerEntity> {
  @Update(
      """
            UPDATE edu_submission_answer SET client_save_sequence=#{sequence}, answer_json=#{answerJson},
              code_text=#{codeText}, saved_at=CURRENT_TIMESTAMP(3), grading_status='NOT_STARTED'
            WHERE id=#{id} AND client_save_sequence < #{sequence}
            """)
  int updateIfNewer(
      @Param("id") Long id,
      @Param("sequence") long sequence,
      @Param("answerJson") String answerJson,
      @Param("codeText") String codeText);

  @Select("SELECT * FROM edu_submission_answer WHERE submission_id=#{submissionId} ORDER BY question_id")
  List<SubmissionAnswerEntity> findBySubmissionId(@Param("submissionId") Long submissionId);
}
