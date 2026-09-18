package org.teamsai.saibackend.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.user.entity.User;

import java.util.Optional;

public interface UserRepository
        extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    Optional<User> findByUserToken(
            String userToken
    );

    boolean existsByUserToken(
            String userToken
    );

    @Query("""
            select u.userKey
            from User u
            where u.userId = :userId
            """)
    String findUserKeyByUserId(
            @Param("userId") Long userId
    );

    @Modifying(clearAutomatically = true)
    @Query("""
            update User u
            set u.userKey = :userKey
            where u.userId = :userId
              and (
                    u.userKey = :expectedPreviousKey
                    or (
                        u.userKey is null
                        and :expectedPreviousKey is null
                    )
                  )
            """)
    int updateUserKeyByUserId(
            @Param("userId") Long userId,
            @Param("userKey") String userKey,
            @Param("expectedPreviousKey")
            String expectedPreviousKey
    );
}