package org.zalando.stups.logsink.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.zalando.stups.logsink.service.InstanceLogsService;

import java.io.IOException;
import java.io.Reader;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.util.FileCopyUtils.copyToString;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@RestController
public class InstanceLogsController {

    private final InstanceLogsService instanceLogsService;

    @Autowired
    public InstanceLogsController(InstanceLogsService instanceLogsService) {
        this.instanceLogsService = instanceLogsService;
    }

    @ResponseStatus(CREATED)
    @RequestMapping(method = POST, path = "/instance-logs", consumes = APPLICATION_JSON_VALUE)
    public void receiveInstanceLogs(final Reader reader) throws IOException {
        instanceLogsService.handleInstanceLogs(copyToString(reader));
    }
}
