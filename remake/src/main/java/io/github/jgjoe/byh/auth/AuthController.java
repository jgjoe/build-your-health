package io.github.jgjoe.byh.auth;

import io.github.jgjoe.byh.auth.dto.LoginRequest;
import io.github.jgjoe.byh.auth.dto.LoginResponse;
import io.github.jgjoe.byh.common.InvalidParameterException;
import io.github.jgjoe.byh.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Login and logout (RQ-F-004). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final LoginGate loginGate;
    private final String dummyPasswordHash;

    public AuthController(MemberMapper memberMapper, PasswordEncoder passwordEncoder, LoginGate loginGate) {
        this.memberMapper = memberMapper;
        this.passwordEncoder = passwordEncoder;
        this.loginGate = loginGate;
        // One valid cost-10 hash per controller lifetime, not per nonexistent-user request.
        this.dummyPasswordHash = passwordEncoder.encode("byh-dummy-password-not-an-account");
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        if (request == null || isBlank(request.id()) || isBlank(request.password())) {
            throw new InvalidParameterException("아이디와 비밀번호를 모두 입력해야 합니다.");
        }
        return loginGate.run(() -> authenticate(request, httpRequest));
    }

    private LoginResponse authenticate(LoginRequest request, HttpServletRequest httpRequest) {
        Member member = memberMapper.findById(request.id());
        String hash = member == null ? dummyPasswordHash : member.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (member == null || !passwordMatches) {
            // Same expensive verification and response for unknown ids and wrong passwords.
            throw new UnauthorizedException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        // Issue a new session id on login so an id fixed before authentication cannot be reused.
        HttpSession session = httpRequest.getSession(true);
        httpRequest.changeSessionId();
        LoginMember.login(session, member.getId());
        return new LoginResponse(member.getId(), member.getName());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpSession session) {
        session.invalidate();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
