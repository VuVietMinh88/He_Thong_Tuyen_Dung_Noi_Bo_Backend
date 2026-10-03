package vn.ttcs.recruitment.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.auth.AuthService;

import java.util.List;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    public BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        return request -> {
            // These endpoints authenticate their body; a stale bearer must not block recovery.
            if ("POST".equals(request.getMethod())
                    && ("/api/v1/auth/refresh".equals(request.getServletPath())
                    || "/api/v1/auth/login".equals(request.getServletPath()))) {
                return null;
            }
            return resolver.resolve(request);
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Accept", "Content-Type", "Authorization"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration legacyHealth = new CorsConfiguration();
        legacyHealth.setAllowedOrigins(allowedOrigins);
        legacyHealth.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
        legacyHealth.setAllowedHeaders(List.of("Accept", "Content-Type"));
        legacyHealth.setAllowCredentials(false);
        legacyHealth.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/health", legacyHealth);
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService authService,
                                                  JsonSecurityErrors errors,
                                                  BearerTokenResolver bearerTokenResolver) throws Exception {
        return http
                .cors(withDefaults())
                // These APIs accept bearer headers/body tokens only, never browser authentication cookies.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> errors.unauthorized(response))
                        .accessDeniedHandler((request, response, exception) -> errors.forbidden(response)))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver)
                        .authenticationEntryPoint((request, response, exception) -> errors.unauthorized(response))
                        .accessDeniedHandler((request, response, exception) -> errors.forbidden(response))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                            Account account = authService.requireActiveAccount(token);
                            var authorities = account.getRoles().stream()
                                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
                            return new JwtAuthenticationToken(token, authorities, account.getId().toString());
                        })))
                .build();
    }
}
