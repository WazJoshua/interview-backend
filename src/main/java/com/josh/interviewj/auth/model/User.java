package com.josh.interviewj.auth.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = "roles")
@ToString(exclude = "roles")
@SQLRestriction("deleted_at IS NULL")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", unique = true, nullable = false)
    private UUID externalId;

    @Column(unique = true, nullable = false, length = 100)
    private String username;

    @Column(unique = true, nullable = false, length = 200)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String password;

    @Column(length = 100)
    private String nickname;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(length = 20)
    private String phone;

    @Builder.Default
    @Column(length = 20, nullable = false)
    private String status = "ACTIVE";

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Builder.Default
    @Column(name = "login_count", nullable = false)
    private Integer loginCount = 0;

    @Builder.Default
    @Column(name = "login_attempts", nullable = false)
    private Integer loginAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Builder.Default
    @Column(length = 10, nullable = false)
    private String locale = "zh-CN";

    @Builder.Default
    @Column(length = 50)
    private String timezone = "Asia/Shanghai";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private Set<UserRole> roles = new HashSet<>();

    /**
     * Initialize default values before insert.
     */
    @PrePersist
    public void prePersist() {
        if (this.externalId == null) {
            this.externalId = UUID.randomUUID();
        }
        if (this.status == null) {
            this.status = "ACTIVE";
        }
        if (this.locale == null) {
            this.locale = "zh-CN";
        }
        if (this.timezone == null) {
            this.timezone = "Asia/Shanghai";
        }
        if (this.loginCount == null) {
            this.loginCount = 0;
        }
        if (this.loginAttempts == null) {
            this.loginAttempts = 0;
        }
    }

    /**
     * Get role names from the user-role relationship.
     *
     * @return role name set
     */
    public Set<String> getRoleNames() {
        Set<String> roleNames = new HashSet<>();
        if (roles != null) {
            for (UserRole userRole : roles) {
                roleNames.add(userRole.getRole());
            }
        }
        return roleNames;
    }

    /**
     * Add a role to the user.
     *
     * @param role role name
     */
    public void addRole(String role) {
        if (this.roles == null) {
            this.roles = new HashSet<>();
        }
        UserRole userRole = new UserRole();
        userRole.setUserId(this.id);
        userRole.setRole(role);
        userRole.setUser(this);
        this.roles.add(userRole);
    }
}
