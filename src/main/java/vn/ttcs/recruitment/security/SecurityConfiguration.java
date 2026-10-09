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
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
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
                    || "/api/v1/auth/login".equals(request.getServletPath())
                    || "/api/v1/auth/forgot-password".equals(request.getServletPath())
                    || "/api/v1/auth/reset-password".equals(request.getServletPath())
                    || "/api/v1/auth/activate-account".equals(request.getServletPath()))) {
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
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"));
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
                                                  PermissionService permissions,
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
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
                                "/api/v1/auth/activate-account").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/*/roles/*").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/*/lock").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/accounts/*/lock").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/accounts/*/roles/*").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/*").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts", "/api/v1/accounts/*")
                                .hasAuthority("PERM_USER_ADMIN_READ_ALL")
                        .requestMatchers(HttpMethod.GET, "/api/v1/departments", "/api/v1/departments/*")
                                .hasAuthority("PERM_ORGANIZATION_READ_ALL")
                        .requestMatchers(HttpMethod.POST, "/api/v1/departments")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/departments/*")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.GET, "/api/v1/profile").hasAuthority("PERM_SELF_PROFILE_READ")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/profile").hasAuthority("PERM_SELF_PROFILE_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me",
                                "/api/v1/auth/permissions").hasAuthority("PERM_SELF_PROFILE_READ")
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout",
                                "/api/v1/auth/change-password").hasAuthority("PERM_SELF_SECURITY_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/v1/positions", "/api/v1/positions/*")
                                .hasAuthority("PERM_ORGANIZATION_READ_ALL")
                        // Both salaries are required on a position write, so writers also need the salary permission.
                        .requestMatchers(HttpMethod.POST, "/api/v1/positions").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAuthority("PERM_ORGANIZATION_WRITE_ALL"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_SALARY_RANGES_WRITE_ALL")))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/positions/*").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAuthority("PERM_ORGANIZATION_WRITE_ALL"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_SALARY_RANGES_WRITE_ALL")))
                        // Competency frameworks are organization data: every internal role reads them (interviewers
                        // score with them), and only organization writers create or edit them.
                        .requestMatchers(HttpMethod.GET, "/api/v1/competency-frameworks",
                                "/api/v1/competency-frameworks/*").hasAuthority("PERM_ORGANIZATION_READ_ALL")
                        .requestMatchers(HttpMethod.POST, "/api/v1/competency-frameworks")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/competency-frameworks/*")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        // Choosing the shared competency framework of a position never touches its salary band,
                        // so organization writers do it without the salary permission.
                        .requestMatchers(HttpMethod.PUT, "/api/v1/positions/*/competency-framework")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/positions/*/competency-framework")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        // The evaluation criteria of a position carry no salary data, so every internal role reads
                        // them: interviewers score candidates with these criteria.
                        .requestMatchers(HttpMethod.GET, "/api/v1/positions/*/evaluation-criteria")
                                .hasAuthority("PERM_ORGANIZATION_READ_ALL")
                        // The interview question bank belongs to the competency frameworks: every internal role reads
                        // the questions (interviewers ask them), and only organization writers create or edit them.
                        .requestMatchers(HttpMethod.GET, "/api/v1/interview-questions",
                                "/api/v1/interview-questions/*").hasAuthority("PERM_ORGANIZATION_READ_ALL")
                        .requestMatchers(HttpMethod.POST, "/api/v1/interview-questions")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/interview-questions/*")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        // Requisitions: ALL and SCOPED callers both reach RequisitionService, which uses AccessScope.
                        .requestMatchers(HttpMethod.POST, "/api/v1/requisitions")
                                .hasAnyAuthority("PERM_REQUISITIONS_WRITE_ALL", "PERM_REQUISITIONS_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.GET, "/api/v1/requisitions", "/api/v1/requisitions/*")
                                .hasAnyAuthority("PERM_REQUISITIONS_READ_ALL", "PERM_REQUISITIONS_READ_SCOPED")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/requisitions/*")
                                .hasAnyAuthority("PERM_REQUISITIONS_WRITE_ALL", "PERM_REQUISITIONS_WRITE_SCOPED")
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
                            var authorities = new java.util.ArrayList<>(account.getRoles().stream()
                                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList());
                            permissions.forUser(account.getId()).stream()
                                    .map(code -> new SimpleGrantedAuthority("PERM_" + code))
                                    .forEach(authorities::add);
                            return new JwtAuthenticationToken(token, authorities, account.getId().toString());
                        })))
                .build();
    }
}
