package com.intel.cosbench.shadow.web;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.service.ControllerService;
import com.intel.cosbench.model.WorkloadInfo;

@Controller
public class ControllerWebAdapter {

    private final ControllerService controllerService;

    public ControllerWebAdapter(ControllerService controllerService) {
        this.controllerService = controllerService;
    }

    @GetMapping("/")
    String root() {
        return "redirect:/index.html";
    }

    @GetMapping({"/logon.html", "/logon"})
    String logon(Model model) {
        model.addAttribute("cInfo", controllerService.getControllerInfo());
        model.addAttribute("username", "");
        model.addAttribute("password", "");
        return "logon";
    }

    @GetMapping({"/logonError.html", "/logonError"})
    String logonError(Model model) {
        model.addAttribute("cInfo", controllerService.getControllerInfo());
        return "logonError";
    }

    @GetMapping({"/index.html", "/index"})
    String index(@RequestParam(name = "loadArch", required = false) String loadArch, Model model) {
        if ("true".equals(loadArch)) {
            controllerService.setloadArch(true);
        } else if ("false".equals(loadArch)) {
            controllerService.setloadArch(false);
        }
        addPageModel(model);
        return "index";
    }

    @GetMapping({"/submit.html", "/submit"})
    String submitPage(Model model) {
        addSubmitModel(model);
        return "submit";
    }

    @PostMapping(value = "/submit-workload.do", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    String submitPageWorkload(@RequestParam(name = "config", required = false) MultipartFile config, Model model)
            throws IOException {
        if (config == null || config.isEmpty()) {
            addSubmitModel(model);
            model.addAttribute("error", "Bad Request");
            return "submit";
        }
        try (var input = config.getInputStream()) {
            String id = controllerService.submit(new XmlConfig(input));
            controllerService.fire(id);
            addSubmitModel(model);
            model.addAttribute("submitted", "your workload has been accepted");
            model.addAttribute("id", id);
        } catch (RuntimeException e) {
            addSubmitModel(model);
            model.addAttribute("error", e.getMessage());
        }
        return "submit";
    }

    @PostMapping(value = "/cli/submit.action", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    ResponseEntity<String> cliSubmit(@RequestParam(name = "config", required = false) MultipartFile config) {
        if (config == null || config.isEmpty()) {
            return ResponseEntity.ok("Bad Request");
        }
        try (var input = config.getInputStream()) {
            String id = controllerService.submit(new XmlConfig(input));
            controllerService.fire(id);
            return ResponseEntity.ok("Accepted with ID: " + id);
        } catch (RuntimeException e) {
            return ResponseEntity.ok(e.getMessage() == null ? "Internal Error" : e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.ok(e.getMessage());
        }
    }

    @GetMapping("/download-config.do")
    ResponseEntity<Resource> downloadConfig(@RequestParam("id") String id) {
        return download(id, true);
    }

    @GetMapping("/download-log.do")
    ResponseEntity<Resource> downloadLog(@RequestParam("id") String id) {
        return download(id, false);
    }

    private ResponseEntity<Resource> download(String id, boolean configFile) {
        WorkloadInfo workload = controllerService.getWorkloadInfo(id);
        if (workload == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        var file = configFile ? controllerService.getWorkloadConfig(workload) : controllerService.getWorkloadLog(workload);
        if (file == null || !file.isFile()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(configFile ? "application/xml" : "text/plain"));
        headers.setContentLength(file.length());
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
                + (configFile ? "workload-config.xml" : "workload-log.txt") + "\"");
        return ResponseEntity.ok().headers(headers).body(new FileSystemResource(file));
    }

    private void addPageModel(Model model) {
        model.addAllAttributes(Map.of(
                "cInfo", controllerService.getControllerInfo(),
                "aInfos", controllerService.getActiveWorkloads(),
                "hInfos", controllerService.getHistoryWorkloads(),
                "archInfos", controllerService.getArchivedWorkloads(),
                "loadArch", controllerService.getloadArch()));
    }

    private void addSubmitModel(Model model) {
        model.addAttribute("cInfo", controllerService.getControllerInfo());
        model.addAttribute("aInfos", controllerService.getActiveWorkloads());
    }
}
