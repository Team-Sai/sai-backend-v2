package org.teamsai.saibackend.domain.user.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.link.service.LinkOperationStore;
import org.teamsai.saibackend.domain.link.service.UserLinkLock;
import org.teamsai.saibackend.domain.user.dto.response.UserResponse;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.repository.UserRepository;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserLinkLock userLinkLock;
    private final LinkOperationStore linkOperationStore;
    private final EntityManager entityManager;

    public UserResponse getMyInfo(Long userId) {
        User user = getUser(userId);

        return UserResponse.from(user);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void withdraw(Long userId) {
        userLinkLock.execute(userId, () -> {
            if (linkOperationStore.hasUnresolved(userId)) {
                throw AccountErrorCode.LINK_RECONCILIATION_REQUIRED.toException();
            }

            User user = userRepository.findById(userId)
                    .orElseThrow(UserErrorCode.USER_NOT_FOUND::toException);

            userRepository.delete(user);
            return null;
        });
    }

    @Transactional(readOnly = true)
    public User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(
                        UserErrorCode.USER_NOT_FOUND::toException
                );
    }

    public Optional<User> findUserById(Long userId) {
        return userRepository.findById(userId);
    }

    public User getUserReference(Long userId) {
        return entityManager.getReference(User.class, userId);
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

    @Transactional
    public int updateUserKeyByUserId(Long userId, String userKey, String expectedPreviousKey) {
        return userRepository.updateUserKeyByUserId(userId, userKey, expectedPreviousKey);
    }
}
