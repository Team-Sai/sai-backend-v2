package org.teamsai.saibackend.domain.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.teamsai.saibackend.domain.user.entity.User;

@Getter
@Builder
@AllArgsConstructor
public class UserLoginDTO {

    private String accessToken;
    private String refreshToken;
    private String userToken;
    private String name;

    public static UserLoginDTO of(
            User user,
            String accessToken,
            String refreshToken
    ){
        return UserLoginDTO.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userToken(user.getUserToken())
                .name(user.getName())
                .build();
    }
}
