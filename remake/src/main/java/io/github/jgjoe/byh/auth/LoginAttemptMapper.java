package io.github.jgjoe.byh.auth;

import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LoginAttemptMapper {
    OffsetDateTime findLockUntil(@Param("key") byte[] key);
    LoginAttempt findForUpdate(@Param("key") byte[] key);
    void insert(@Param("key") byte[] key, @Param("state") LoginAttempt state);
    void update(@Param("key") byte[] key, @Param("state") LoginAttempt state);
    void delete(@Param("key") byte[] key);
    int deleteExpired(@Param("now") OffsetDateTime now);
}
