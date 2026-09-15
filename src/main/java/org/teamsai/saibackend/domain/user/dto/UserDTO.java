package org.teamsai.saibackend.domain.user.dto;

import lombok.*;
import org.teamsai.saibackend.domain.user.entity.User;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {
    private Long userId;
    private String userToken; //회원가입할 때 발급
    private String userKey; //계좌 연결할 때 발급
    private String email;
    private String password;
    private String name;
    private LocalDate birthDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static UserDTO from(User user){
        return UserDTO.builder()
                .userId(user.getUserId())
                .userToken(user.getUserToken())
                .userKey(user.getUserKey())
                .email(user.getEmail())
                .password(user.getPassword())
                .name(user.getName())
                .birthDate(user.getBirthDate())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

}
