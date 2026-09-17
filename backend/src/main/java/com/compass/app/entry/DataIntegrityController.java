package com.compass.app.entry;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/data-integrity")
public class DataIntegrityController {
    private final DataIntegrityService service;

    public DataIntegrityController(DataIntegrityService service) {
        this.service = service;
    }

    @PostMapping
    public DataIntegrityService.Report inspect(@RequestParam(defaultValue = "false") boolean apply) {
        return service.inspect(apply);
    }
}
