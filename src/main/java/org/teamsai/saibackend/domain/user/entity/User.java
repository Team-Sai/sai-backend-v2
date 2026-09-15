package org.teamsai.saibackend.domain.user.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;


@Entity
@Table(name="users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name="user_token", nullable = false, length = 36) //회원가입할 때 발급
    private String userToken;

    @Column(name="user_key", length = 100) //계좌 연결할 때 발급
    private String userKey;

    @Column(name="email", nullable = false, length = 100)
    private String email;

    @Column(name="password", nullable = false, length = 255)
    private String password;

    @Column(name="name", nullable = false, length = 50)
    private String name;

    @Column(name="birth_date", nullable = false)
    private LocalDate birthDate;

    @CreationTimestamp
    @Column(name="created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name="updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public User(Long userId, String userToken, String userKey, String email,
                String password, String name, LocalDate birthDate) {
        this.userId = userId;
        this.userToken = userToken;
        this.userKey = userKey;
        this.email = email;
        this.password = password;
        this.name = name;
        this.birthDate = birthDate;
    }

    public void updateUserKey(String userKey) {
        this.userKey = userKey;
    }
}
