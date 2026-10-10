package pl.kacosmetology.scheduler.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.servlet.HandlerExceptionResolver
import pl.kacosmetology.scheduler.security.JwtAuthenticationFilter

/**
 * Spring Security configuration.
 * Stateless JWT-based authentication; public routes for health, auth, availability and public service endpoints.
 * Unauthenticated requests to protected routes are delegated to [RestExceptionHandler] and answered with a 401 ProblemDetail.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthenticationFilter,
    private val userDetailsService: UserDetailsService,
    @param:Qualifier("handlerExceptionResolver") private val exceptionResolver: HandlerExceptionResolver
) {

    /** Configures DAO-based authentication using [userDetailsService] and BCrypt password encoding. */
    @Bean
    fun authenticationProvider(): AuthenticationProvider {
        val authProvider = DaoAuthenticationProvider(userDetailsService)
        authProvider.setPasswordEncoder(passwordEncoder())
        return authProvider
    }

    /** BCrypt password encoder used for staff password hashing and verification. */
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /** Exposes Spring's [AuthenticationManager] as a bean for use in [pl.kacosmetology.scheduler.auth.AuthService]. */
    @Bean
    fun authenticationManager(config: AuthenticationConfiguration): AuthenticationManager {
        return config.authenticationManager
    }

    /** Defines the security filter chain: disables CSRF, configures public/protected routes, and registers the JWT filter. */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { }
            .csrf { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/prometheus",
                        "/actuator/metrics",
                        "/actuator/metrics/**",
                        "/actuator/info"
                    ).permitAll()
                    .requestMatchers(
                        "/api/auth/request-code",
                        "/api/auth/verify-code",
                        "/api/auth/login-staff"
                    ).permitAll()
                    .requestMatchers("/api/availability").permitAll()
                    .requestMatchers("/api/offerings/public/**").permitAll()
                    .requestMatchers("/api/company/public/**").permitAll()
                    .requestMatchers("/api/employees/public/**").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { handling ->
                handling.authenticationEntryPoint { request, response, ex ->
                    exceptionResolver.resolveException(request, response, null, ex)
                }
            }
            .sessionManagement { session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}