package pl.kacosmetology.scheduler.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer
import org.springframework.web.servlet.HandlerExceptionResolver
import pl.kacosmetology.scheduler.security.AccountSessionRegistry
import pl.kacosmetology.scheduler.security.CustomUserDetailsService
import pl.kacosmetology.scheduler.security.SessionAuthorizationFilter

/** Cookie/session security with explicit REST login persistence, SPA CSRF and immediate scope revalidation. */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val userDetailsService: CustomUserDetailsService,
    private val accountSessions: AccountSessionRegistry,
    @Value($$"${server.servlet.session.cookie.secure:false}") private val secureCookie: Boolean,
    @param:Qualifier("handlerExceptionResolver") private val exceptionResolver: HandlerExceptionResolver,
) {

    /** BCrypt password encoder used for staff password hashing and verification. */
    @Bean fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /** Repository shared by the filter chain and the REST login/logout bridge. */
    @Bean fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    /** Explicit standard Spring Session cookie settings, independent of servlet container defaults. */
    @Bean
    fun cookieSerializer(): CookieSerializer =
        DefaultCookieSerializer().also {
            it.setCookieName("SESSION")
            it.setCookiePath("/")
            it.setUseHttpOnlyCookie(true)
            it.setSameSite("Lax")
            if (secureCookie) it.setUseSecureCookie(true)
        }

    /** Standard SPA CSRF cookie; only this non-credential token is readable by JavaScript. */
    @Bean
    fun csrfTokenRepository(): CookieCsrfTokenRepository =
        CookieCsrfTokenRepository.withHttpOnlyFalse().also {
            it.setCookiePath("/")
            it.setCookieCustomizer { cookie -> cookie.sameSite("Lax").secure(secureCookie) }
        }

    /** REST endpoints invoke these same standard strategies rather than relying on form login. */
    @Bean
    fun sessionAuthenticationStrategy(): SessionAuthenticationStrategy =
        CompositeSessionAuthenticationStrategy(
            listOf(ChangeSessionIdAuthenticationStrategy(), CsrfAuthenticationStrategy(csrfTokenRepository())),
        )

    /** Invalidates HttpSession and clears both the holder and persisted SecurityContext. */
    @Bean
    fun logoutHandler(): SecurityContextLogoutHandler =
        SecurityContextLogoutHandler().also {
            it.setSecurityContextRepository(securityContextRepository())
        }

    /** Defines public routes and session-based API authorization without HTML redirects or saved requests. */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors {}
            .csrf { it.spa().csrfTokenRepository(csrfTokenRepository()) }
            .securityContext { it.securityContextRepository(securityContextRepository()).requireExplicitSave(true) }
            .requestCache { it.requestCache(NullRequestCache()) }
            .logout { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/prometheus",
                        "/actuator/metrics",
                        "/actuator/metrics/**",
                        "/actuator/info",
                    )
                    .permitAll()
                    .requestMatchers(
                        "/api/auth/csrf",
                        "/api/auth/logout",
                        "/api/auth/request-code",
                        "/api/auth/verify-code",
                        "/api/auth/login-staff",
                    )
                    .permitAll()
                    .requestMatchers("/api/availability")
                    .permitAll()
                    .requestMatchers("/api/offerings/public/**")
                    .permitAll()
                    .requestMatchers("/api/company/public/**")
                    .permitAll()
                    .requestMatchers("/api/employees/public/**")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }
            .exceptionHandling { handling ->
                handling.authenticationEntryPoint { request, response, ex ->
                    exceptionResolver.resolveException(request, response, null, ex)
                }
                handling.accessDeniedHandler { request, response, ex ->
                    exceptionResolver.resolveException(request, response, null, ex)
                }
            }
            .sessionManagement { session ->
                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            }
            .addFilterBefore(
                SessionAuthorizationFilter(userDetailsService, logoutHandler(), accountSessions),
                CsrfFilter::class.java,
            )

        return http.build()
    }
}
