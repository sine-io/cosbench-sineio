package com.intel.cosbench.controller.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

import jakarta.servlet.http.HttpServletRequest;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
public class ControllerWebSecurityConfiguration {

    @Bean
    SecurityFilterChain controllerWebSecurity(HttpSecurity http,
            @Qualifier("controllerWebCredentials") Map<String, String> credentials) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(publicRoutes())
                        .permitAll()
                        .requestMatchers(pageRoutes())
                        .authenticated()
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .loginPage("/logon.html")
                        .loginProcessingUrl("/j_security_check")
                        .usernameParameter("j_username")
                        .passwordParameter("j_password")
                        .defaultSuccessUrl("/index.html", true)
                        .failureUrl("/logonError.html")
                        .permitAll())
                .addFilterBefore(new CliLoginFilter(credentials), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    Map<String, String> controllerWebCredentials(
            @Value("${cosbench.web.username:anonymous}") String username,
            @Value("${cosbench.web.password:cosbench}") String password,
            @Value("${cosbench.web.cosbenchUsers:}") String usersFile) {
        return LegacyWebUserLoader.load(username, password, usersFile);
    }

    @Bean
    UserDetailsService controllerWebUsers(@Qualifier("controllerWebCredentials") Map<String, String> credentials) {
        var users = credentials.entrySet().stream()
                .map(entry -> User.withUsername(entry.getKey())
                        .password("{noop}" + entry.getValue())
                        .roles("COSBENCH")
                        .build())
                .toArray(org.springframework.security.core.userdetails.UserDetails[]::new);
        return new InMemoryUserDetailsManager(users);
    }

    private static RequestMatcher publicRoutes() {
        return request -> {
            String path = applicationPath(request);
            return path.startsWith("/resources/") || path.equals("/favicon.ico")
                    || path.equals("/logon") || path.equals("/logon.html")
                    || path.equals("/logonError") || path.equals("/logonError.html")
                    || path.equals("/j_security_check") || path.startsWith("/cli/")
                    || path.equals("/error");
        };
    }

    private static RequestMatcher pageRoutes() {
        return request -> {
            String path = applicationPath(request);
            return path.endsWith(".html") || path.endsWith(".do") || path.endsWith(".csv");
        };
    }

    private static String applicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        return path.startsWith(contextPath) ? path.substring(contextPath.length()) : path;
    }
}
