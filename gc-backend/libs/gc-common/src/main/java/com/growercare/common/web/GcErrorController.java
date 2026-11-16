package com.growercare.common.web;

import com.growercare.common.web.GcProblemHandler.ProblemDetails;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPEC-PLAT-05 D2: replaces Spring Boot's {@code BasicErrorController}. Whatever still reaches {@code /error} (errors
 * raised in servlet filters, container errors) is rendered as RFC 9457 problem+json with its original status, with
 * no exception details.
 */
@RestController
public class GcErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
        int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code ? code : 500;
        var problem = ProblemDetails.of(ProblemDetails.codeFor(status), "The request could not be processed", request);
        problem.setStatus(status);
        return ProblemDetails.response(problem);
    }
}
