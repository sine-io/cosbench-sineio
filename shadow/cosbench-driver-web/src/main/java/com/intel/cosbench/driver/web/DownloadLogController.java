package com.intel.cosbench.driver.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

import com.intel.cosbench.model.MissionInfo;

public class DownloadLogController extends MissionPageController {

    private static final View LOG = new LogView();

    @Override
    protected ModelAndView process(MissionInfo info) {
        String log;
        try {
            log = info.getLogManager().getLogAsString();
        } catch (IOException failure) {
            log = "";
        }
        return new ModelAndView(LOG, "log", log);
    }

    private static final class LogView implements View {
        @Override
        public String getContentType() {
            return "text/plain";
        }

        @Override
        public void render(Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
                throws Exception {
            byte[] content = ((String) model.get("log")).getBytes(StandardCharsets.UTF_8);
            response.setContentType(getContentType());
            response.setHeader("Content-Length", String.valueOf(content.length));
            response.setHeader("Content-Disposition", "attachment; filename=\"mission-log.txt\"");
            response.getOutputStream().write(content);
        }
    }
}
