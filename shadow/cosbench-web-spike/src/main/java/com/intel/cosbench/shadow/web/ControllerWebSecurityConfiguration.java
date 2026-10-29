package com.intel.cosbench.shadow.web;

import org.springframework.beans.factory.annotation.Value;
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

@Configuration
public class ControllerWebSecurityConfiguration {

    @Bean
    SecurityFilterChain controllerWebSecurity(HttpSecurity http,
            @Value("${cosbench.web.username:anonymous}") String username,
            @Value("${cosbench.web.password:cosbench}") String password) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(publicRoutes())
                        .permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/logon.html")
                        .loginProcessingUrl("/j_security_check")
                        .usernameParameter("j_username")
                        .passwordParameter("j_password")
                        .defaultSuccessUrl("/index.html", true)
                        .failureUrl("/logonError.html")
                        .permitAll())
                .addFilterBefore(new CliLoginFilter(username, password), UsernamePasswordAuthenticationFilter.class);
        return http.build();
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

    private static String applicationPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        return path.startsWith(contextPath) ? path.substring(contextPath.length()) : path;
    }

    @Bean
    UserDetailsService controllerWebUsers(
            @Value("${cosbench.web.username:anonymous}") String username,
            @Value("${cosbench.web.password:cosbench}") String password) {
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password("{noop}" + password)
                .roles("COSBENCH")
                .build());
    }
}
