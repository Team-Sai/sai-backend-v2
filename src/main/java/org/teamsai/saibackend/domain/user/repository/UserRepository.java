package org.teamsai.saibackend.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.user.entity.User;

public interface UserRepository extends JpaRepository<User,Long> {

    @Transactional
    @Modifying
    @Query("""
        update User u
        set u.userKey = :userKey
        where u.userId = :userId
          and u.userKey is null
        """)
    int updateUserKeyIfNull(
            @Param("userId") Long userId,
            @Param("userKey") String userKey
    );
}
