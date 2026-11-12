package com.education.agent.modelsettings;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ModelSettingsCipher {
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();
    public ModelSettingsCipher(@Value("${education.model-settings.key-file:D:/CodexEnvs/EducationAgent/secrets/model-settings.key}") String file) {
        keyFile = Path.of(file).toAbsolutePath().normalize();
    }
    private synchronized byte[] key(boolean create) throws Exception {
        if (!Files.exists(keyFile) && create) {
            Files.createDirectories(keyFile.getParent());
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            try { Files.write(keyFile, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
            catch (FileAlreadyExistsException ignored) { /* Another JVM created the shared deployment key. */ }
        }
        byte[] key = Files.readAllBytes(keyFile);
        if (key.length != 32) throw new IllegalStateException("Invalid encryption key size");
        return key;
    }
    public String encrypt(String slot, String plain) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(slot.getBytes(StandardCharsets.UTF_8));
            byte[] encoded = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(encoded);
        } catch (Exception ignored) { throw failure(); }
    }
    public String decrypt(String slot, String value) {
        try {
            String[] parts = value.split("\\.", 2);
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(slot.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception ignored) { throw failure(); }
    }
    private BusinessException failure() {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, "无法读写模型配置，请检查服务端配置加密密钥。");
    }
}
