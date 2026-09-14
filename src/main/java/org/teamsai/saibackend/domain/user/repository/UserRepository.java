package org.teamsai.saibackend.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsai.saibackend.domain.user.entity.User;

public interface UserRepository extends JpaRepository<User,Long> {
}
