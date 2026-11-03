package com.intel.cosbench.controller.web;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class CliLoginFilter extends OncePerRequestFilter {

    private final String username;
    private final String password;

    CliLoginFilter(String username, String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().endsWith(".action");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestUsername = request.getParameter("username");
        String requestPassword = request.getParameter("password");
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/plain;charset=UTF-8");
        if (requestUsername == null || requestUsername.isEmpty() || requestPassword == null || requestPassword.isEmpty()) {
            response.getWriter().write("Please input complete user info [username:password] to get authentication!");
            return;
        }
        if (!username.equals(requestUsername) || !password.equals(requestPassword)) {
            response.getWriter().write("User info [username:password] incorrect !");
            return;
        }
        chain.doFilter(request, response);
    }
}
