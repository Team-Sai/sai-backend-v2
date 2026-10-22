package org.teamsai.saibackend.global.config;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.teamsai.saibackend.global.jwt.JwtAuthenticationEntryPoint;
import org.teamsai.saibackend.global.jwt.JwtAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter
            jwtAuthenticationFilter;

    private final JwtAuthenticationEntryPoint
            jwtAuthenticationEntryPoint;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .exceptionHandling(exception ->
                        exception.authenticationEntryPoint(
                                jwtAuthenticationEntryPoint
                        )
                )

                .authorizeHttpRequests(authorize ->
                        authorize


                                // SSE(스트리밍) 응답은 처음 요청에서 이미 인증·인가를 마친 뒤
                                // 같은 요청을 ASYNC로 다시 디스패치한다.
                                // JWT 필터는 ASYNC 디스패치에서 다시 돌지 않으므로 여기서 허용한다.
                                .dispatcherTypeMatchers(DispatcherType.ASYNC)
                                .permitAll()

                                .requestMatchers(
                                        "/api/auth/signup",
                                        "/api/auth/login",
                                        "/api/auth/reissue",
                                        "/api/auth/logout",
                                        "/login",
                                        "/signup",
                                        "/v3/api-docs/**",
                                        "/swagger-ui/**",
                                        "/swagger-ui.html",
                                        "/actuator/health",
                                        "/actuator/health/**",
                                        "/actuator/prometheus"
                                )
                                .permitAll()

                                .requestMatchers(
                                        "/",
                                        "/intro",
                                        "/login",
                                        "/signup",
                                        "/mypage",
                                        "/mypage/",

                                        "/identity-test",



                                        "/accounts/**",

                                        "/contract",
                                        "/settlements",
                                        "/settlements/**",
                                        "/contracts/**",
                                        "/notifications",
                                        "/archive",
                                        "/archive/**",
                                        "/calendar",

                                        "/integration/dashboard",
                                        "/dev/**"


                                )
                                .permitAll()

                                .requestMatchers(
                                        "/css/**",
                                        "/js/**",
                                        "/images/**",
                                        "/font/**",
                                        "/favicon.ico",
                                        "/error"
                                )
                                .permitAll()
                                .requestMatchers("/api/**")
                                .authenticated()
                                .anyRequest()
                                .authenticated()
                )

                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
