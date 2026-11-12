package com.education.agent.graphingestion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.tutor.entity.IdempotencyRecordEntity;
import com.education.agent.tutor.mapper.IdempotencyRecordMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Component
public class GraphMutationIdempotency {
    private final IdempotencyRecordMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public GraphMutationIdempotency(
            IdempotencyRecordMapper mapper, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public <T> Claim<T> claim(
            Long userId, String operationId, String key, String requestHash, Class<T> responseType) {
        try {
            return Objects.requireNonNull(tx.execute(status -> {
                IdempotencyRecordEntity existing = find(userId, operationId, key, true);
                if (existing != null && existing.getExpiresAt().isBefore(LocalDateTime.now())) {
                    mapper.deleteById(existing.getId());
                    existing = null;
                }
                if (existing != null) return replay(existing, requestHash, responseType);
                IdempotencyRecordEntity record = new IdempotencyRecordEntity();
                record.setUserId(userId);
                record.setOperationId(operationId);
                record.setIdempotencyKey(key);
                record.setRequestHash(requestHash);
                record.setUpstreamRequestId(newUpstreamRequestId());
                record.setExpiresAt(LocalDateTime.now().plusHours(24));
                mapper.insert(record);
                return new Claim<>(record.getId(), record.getUpstreamRequestId(), null, false);
            }));
        } catch (DuplicateKeyException exception) {
            return Objects.requireNonNull(tx.execute(status -> {
                IdempotencyRecordEntity existing = find(userId, operationId, key, true);
                if (existing == null) {
                    throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "重复操作正在处理");
                }
                return replay(existing, requestHash, responseType);
            }));
        }
    }

    public void complete(Long recordId, Object response) {
        tx.executeWithoutResult(status -> {
            IdempotencyRecordEntity record = mapper.selectById(recordId);
            if (record == null) throw new BusinessException(ErrorCode.INTERNAL_ERROR, "幂等操作记录不存在");
            String serialized = write(response);
            if (record.getResponseJson() != null && !record.getResponseJson().equals(serialized)) {
                throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION, "幂等操作产生了不一致结果");
            }
            record.setResponseStatus(200);
            record.setResponseJson(serialized);
            mapper.updateById(record);
        });
    }

    private <T> Claim<T> replay(IdempotencyRecordEntity existing, String hash, Class<T> type) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "同一个幂等键不能用于不同请求");
        }
        if (existing.getUpstreamRequestId() == null || existing.getUpstreamRequestId().isBlank()) {
            existing.setUpstreamRequestId(newUpstreamRequestId());
            mapper.updateById(existing);
        }
        if (existing.getResponseJson() == null) {
            return new Claim<>(existing.getId(), existing.getUpstreamRequestId(), null, true);
        }
        try {
            return new Claim<>(existing.getId(), existing.getUpstreamRequestId(),
                    json.readValue(existing.getResponseJson(), type), false);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "幂等操作结果损坏");
        }
    }

    private IdempotencyRecordEntity find(Long userId, String operation, String key, boolean lock) {
        LambdaQueryWrapper<IdempotencyRecordEntity> query = new LambdaQueryWrapper<IdempotencyRecordEntity>()
                .eq(IdempotencyRecordEntity::getUserId, userId)
                .eq(IdempotencyRecordEntity::getOperationId, operation)
                .eq(IdempotencyRecordEntity::getIdempotencyKey, key);
        if (lock) query.last("FOR UPDATE");
        return mapper.selectOne(query);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法保存幂等操作结果");
        }
    }

    private String newUpstreamRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public record Claim<T>(Long recordId, String upstreamRequestId, T replay, boolean incomplete) {}
}
