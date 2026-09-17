package org.teamsai.saibackend.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.user.dto.response.UserResponse;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserResponse getMyInfo(Long userId) {
        User user = getUser(userId);

        return UserResponse.from(user);
    }

    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(
                  UserErrorCode.USER_NOT_FOUND::toException
                );
        userRepository.delete(user);

    }

    @Transactional(readOnly = true)
    public User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(
                        UserErrorCode.USER_NOT_FOUND::toException
                );
    }

    public User findRequestTarget(Long requestUserId, String userToken) {
        User targetUser = userRepository.findByUserToken(userToken)
                .orElseThrow(UserErrorCode.USER_NOT_FOUND::toException);

        if (requestUserId.equals(targetUser.getUserId())) {
            throw UserErrorCode.CANNOT_SELECT_SELF.toException();
        }
        return targetUser;
    }

    @Transactional(readOnly = true)
    public String getUserKeyByUserId(Long userId){
        return userRepository.findUserKeyByUserId(userId);
    }
}