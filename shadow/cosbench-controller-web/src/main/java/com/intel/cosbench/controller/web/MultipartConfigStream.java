package com.intel.cosbench.controller.web;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import com.intel.cosbench.web.BadRequestException;

import jakarta.servlet.http.HttpServletRequest;

public final class MultipartConfigStream {

    private MultipartConfigStream() {
    }

    public static InputStream retrieve(HttpServletRequest request) throws IOException {
        if (!(request instanceof MultipartHttpServletRequest multipart)) {
            throw new BadRequestException();
        }
        MultipartFile config = multipart.getFile("config");
        if (config == null || config.isEmpty()) {
            throw new BadRequestException();
        }
        return config.getInputStream();
    }
}
