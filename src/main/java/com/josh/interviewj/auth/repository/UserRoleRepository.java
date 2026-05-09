package com.josh.interviewj.auth.repository;

import com.josh.interviewj.auth.model.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    boolean existsByUserIdAndRole(Long userId, String role);

    @Modifying
    void deleteByUserIdAndRole(Long userId, String role);
}
