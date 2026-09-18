package org.teamsai.saibackend.domain.user.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.teamsai.saibackend.domain.user.dto.UserLoginDTO;

@Getter
@Builder
@AllArgsConstructor
public class AccessTokenResponse {

    private String accessToken;

    public static AccessTokenResponse from(UserLoginDTO userLoginDTO){
        return AccessTokenResponse.builder()
                .accessToken(userLoginDTO.getAccessToken())
                .build();
    }
}