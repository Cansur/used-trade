package com.portfolio.used_trade.user.service;

import com.portfolio.used_trade.common.exception.BusinessException;
import com.portfolio.used_trade.common.exception.ErrorCode;
import com.portfolio.used_trade.user.domain.Role;
import com.portfolio.used_trade.user.domain.User;
import com.portfolio.used_trade.user.dto.SignUpRequest;
import com.portfolio.used_trade.user.dto.UserResponse;
import com.portfolio.used_trade.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link UserService} 단위 테스트.
 *
 * <p>의도적으로 진짜 DB / 진짜 BCrypt 를 쓰지 않는다 — 통합 테스트는 별도 영역.
 * 여기서는 비즈니스 규칙만 검증.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 단위 테스트")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("새 이메일로 가입 시 — User 가 저장되고 UserResponse 반환")
    void signUp_withNewEmail_savesAndReturnsUser() {
        // ── Arrange ──
        var request = new SignUpRequest("a@b.com", "pass1234", "tom");

        given(userRepository.existsByEmail("a@b.com")).willReturn(false);
        given(passwordEncoder.encode("pass1234")).willReturn("HASHED_PASSWORD");

        // 저장 시점에 id 가 채워졌다고 흉내내기 위해 ReflectionTestUtils 로 id 주입
        given(userRepository.save(any(User.class))).willAnswer(invocation -> {
            User u = invocation.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 1L);
            return u;
        });

        // ── Act ──
        UserResponse response = userService.signUp(request);

        // ── Assert ──
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("a@b.com");
        assertThat(response.nickname()).isEqualTo("tom");
        assertThat(response.role()).isEqualTo(Role.USER);

        // 저장된 User 의 password 가 평문이 아니라 해시인지 검증
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPassword())
                .isEqualTo("HASHED_PASSWORD")
                .isNotEqualTo("pass1234");      // 평문 저장 금지 회귀 방지
    }

    @Test
    @DisplayName("이미 존재하는 이메일로 가입 시 — EMAIL_ALREADY_EXISTS 예외")
    void signUp_withDuplicateEmail_throwsException() {
        // ── Arrange ──
        var request = new SignUpRequest("dup@b.com", "pass1234", "tom");
        given(userRepository.existsByEmail("dup@b.com")).willReturn(true);

        // ── Act & Assert ──
        assertThatThrownBy(() -> userService.signUp(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);

        // 중복일 때는 비번 해싱도, save 도 일어나지 않아야 함 (불필요 비용 방지)
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("비밀번호는 평문이 아니라 BCrypt 해시로 저장된다")
    void signUp_passwordIsHashedNotPlain() {
        // 회귀 방지 전용 테스트 — 누군가 실수로 user.password = request.password() 같은 코드를
        // 넣어도 이 테스트가 빨갛게 켜진다.

        var request = new SignUpRequest("c@b.com", "myPlainPass1", "tom");
        given(userRepository.existsByEmail(anyString())).willReturn(false);
        given(passwordEncoder.encode("myPlainPass1")).willReturn("$2a$10$HASHED");
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        userService.signUp(request);

        verify(passwordEncoder).encode("myPlainPass1");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo("$2a$10$HASHED");
    }
}
