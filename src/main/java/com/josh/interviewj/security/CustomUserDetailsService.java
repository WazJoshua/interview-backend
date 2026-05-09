package com.josh.interviewj.security;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Load a user by username or email and map roles to Spring Security authorities.
     *
     * @param username username or email
     * @return user details
     * @throws UsernameNotFoundException if user not found
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseGet(() -> userRepository.findByEmail(username)
                        .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username)));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .authorities(mapRolesToAuthorities(user.getRoleNames()))
                .accountExpired(false)
                .accountLocked(user.getLockedUntil() != null && user.getLockedUntil().isAfter(java.time.LocalDateTime.now()))
                .credentialsExpired(false)
                .disabled(!"ACTIVE".equals(user.getStatus()))
                .build();
    }

    /**
     * Convert role strings into {@link GrantedAuthority} values.
     *
     * @param roles role names
     * @return authority collection
     */
    private Collection<? extends GrantedAuthority> mapRolesToAuthorities(java.util.Set<String> roles) {
        if (roles == null) {
            return java.util.Collections.emptyList();
        }
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
    }
}
