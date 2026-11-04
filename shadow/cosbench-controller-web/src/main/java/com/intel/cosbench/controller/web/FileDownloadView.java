/*
Copyright 2013 Intel Corporation, All Rights Reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
*/

package com.intel.cosbench.controller.web;

import java.io.File;
import java.io.FileInputStream;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.View;

final class FileDownloadView implements View {

    private final File file;
    private final String contentType;
    private final String downloadName;

    FileDownloadView(File file, String contentType, String downloadName) {
        this.file = file;
        this.contentType = contentType;
        this.downloadName = downloadName;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public void render(Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        response.setContentType(contentType);
        response.setHeader("Content-Length", Long.toString(file.length()));
        response.setHeader("Content-Disposition", "attachment; filename=\"" + downloadName + "\"");
        try (FileInputStream input = new FileInputStream(file)) {
            input.transferTo(response.getOutputStream());
        }
    }
}
