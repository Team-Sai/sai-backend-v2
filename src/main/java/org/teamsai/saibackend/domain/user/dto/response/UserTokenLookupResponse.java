package org.teamsai.saibackend.domain.user.dto.response;

import lombok.Builder;
import lombok.Getter;
import org.teamsai.saibackend.domain.user.entity.User;

@Getter
@Builder
public class UserTokenLookupResponse {

    private String userToken;
    private String name;

    public static UserTokenLookupResponse from(User user){
        return UserTokenLookupResponse.builder()
                .userToken(user.getUserToken())
                .name(user.getName())
                .build();
    }
}
