package com.education.agent.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import com.education.agent.security.AuthenticatedUser;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserAccountService implements UserDetailsService {

    private final UserMapper userMapper;

    public UserAccountService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public UserEntity findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username));
    }

    public UserEntity requireById(Long id) {
        UserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        return user;
    }

    public AuthenticatedUser loadPrincipal(Long id) {
        return AuthenticatedUser.from(requireById(id));
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserEntity user = findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("用户不存在");
        }
        return AuthenticatedUser.from(user);
    }
}
