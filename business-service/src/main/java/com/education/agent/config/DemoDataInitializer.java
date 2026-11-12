package com.education.agent.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.education.agent.classroom.entity.ClassEntity;
import com.education.agent.classroom.entity.ClassStudentEntity;
import com.education.agent.classroom.mapper.ClassMapper;
import com.education.agent.classroom.mapper.ClassStudentMapper;
import com.education.agent.classroom.model.ClassStatus;
import com.education.agent.user.entity.UserEntity;
import com.education.agent.user.mapper.UserMapper;
import com.education.agent.user.model.UserRole;
import com.education.agent.user.model.UserStatus;
import com.education.agent.user.service.UserAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "education.demo-data.enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final UserMapper userMapper;
    private final UserAccountService userAccountService;
    private final ClassMapper classMapper;
    private final ClassStudentMapper classStudentMapper;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public DemoDataInitializer(
            UserMapper userMapper,
            UserAccountService userAccountService,
            ClassMapper classMapper,
            ClassStudentMapper classStudentMapper,
            PasswordEncoder passwordEncoder,
            org.springframework.jdbc.core.JdbcTemplate jdbc
    ) {
        this.userMapper = userMapper;
        this.userAccountService = userAccountService;
        this.classMapper = classMapper;
        this.classStudentMapper = classStudentMapper;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        UserEntity admin = ensureUser("admin_demo", "Admin@123", "演示管理员", UserRole.ADMIN);
        UserEntity teacher = ensureUser("teacher_demo", "Teacher@123", "演示教师", UserRole.TEACHER);
        UserEntity student = ensureUser("student_demo", "Student@123", "演示学生", UserRole.STUDENT);

        if (jdbc.queryForObject("SELECT COUNT(*) FROM edu_course_teacher WHERE course_id=1 AND teacher_id=?",Integer.class,teacher.getId())==0) {
            jdbc.update("INSERT INTO edu_course_teacher(course_id,teacher_id,member_role) VALUES(1,?,'OWNER')",teacher.getId());
        }

        ClassEntity demoClass = classMapper.selectOne(new LambdaQueryWrapper<ClassEntity>()
                .eq(ClassEntity::getName, "Python程序设计演示班")
                .eq(ClassEntity::getTeacherId, teacher.getId()));
        if (demoClass == null) {
            demoClass = new ClassEntity();
            demoClass.setCourseId(1L);
            demoClass.setName("Python程序设计演示班");
            demoClass.setCourseName("Python程序设计");
            demoClass.setDescription("B 组第 1 周真实登录与班级查询联调数据");
            demoClass.setTeacherId(teacher.getId());
            demoClass.setStatus(ClassStatus.ACTIVE);
            classMapper.insert(demoClass);
        }

        long membershipCount = classStudentMapper.countMembership(demoClass.getId(), student.getId());
        if (membershipCount == 0) {
            ClassStudentEntity membership = new ClassStudentEntity();
            membership.setClassId(demoClass.getId());
            membership.setStudentId(student.getId());
            classStudentMapper.insert(membership);
        }

        log.info(
                "Local demo accounts ready for usernames admin_demo, teacher_demo and student_demo; admin id={}. Credentials are not logged.",
                admin.getId()
        );
    }

    private UserEntity ensureUser(String username, String password, String displayName, UserRole role) {
        UserEntity existing = userAccountService.findByUsername(username);
        if (existing != null) {
            return existing;
        }
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setForcePasswordChange(false);
        userMapper.insert(user);
        return userMapper.selectById(user.getId());
    }
}
