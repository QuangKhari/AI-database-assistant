package com.example.aidatabaseassistant.repository;

import com.example.aidatabaseassistant.entity.User;
import com.example.aidatabaseassistant.entity.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsernameIgnoreCase(String username);
    Optional<User> findByEmailIgnoreCase(String email);
    Optional<User> findByUsernameIgnoreCaseOrEmailIgnoreCase(String username, String email);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByRole(Role role);
    long countByRole(Role role);
    long countByRoleAndLockedTrue(Role role);
    long countByRoleAndLockedFalseAndEnabledTrue(Role role);

    @Query("""
            select user from User user
            where user.role = :role
              and (:search = '' or lower(user.username) like lower(concat('%', :search, '%'))
                   or lower(user.email) like lower(concat('%', :search, '%'))
                   or lower(coalesce(user.displayName, '')) like lower(concat('%', :search, '%')))
            """)
    Page<User> searchByRole(@Param("role") Role role, @Param("search") String search, Pageable pageable);

    default Optional<User> findByUsername(String username) {
        return findByUsernameIgnoreCase(username);
    }

    default Optional<User> findByEmail(String email) {
        return findByEmailIgnoreCase(email);
    }

    default boolean existsByUsername(String username) {
        return existsByUsernameIgnoreCase(username);
    }

    default boolean existsByEmail(String email) {
        return existsByEmailIgnoreCase(email);
    }
}
