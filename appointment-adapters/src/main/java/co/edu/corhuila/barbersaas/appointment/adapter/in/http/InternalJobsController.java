package co.edu.corhuila.barbersaas.appointment.adapter.in.http;

import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.DailyJobUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.in.DailyJobUseCases.JobResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The daily jobs (tag Internal, DEC-APPT-07), for barber-saas-worker on the internal network. The
 * worker only calls at the right time; the rules are in the use case, which checks the service token.
 */
@RestController
@RequestMapping("/internal/v1/appointments")
public class InternalJobsController {

    private final DailyJobUseCases jobs;

    public InternalJobsController(DailyJobUseCases jobs) {
        this.jobs = jobs;
    }

    /** writeDueReminders: at most {@code limit} (1–100, default 20); {@code remaining} asks for another call. */
    @PostMapping("/reminders-due")
    public JobResult remindersDue(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                  @RequestParam(required = false) Integer limit) {
        return jobs.writeDueReminders(caller, Requests.page(1, limit).limit());
    }

    /** markNoShows: at most {@code limit} (1–100, default 20). */
    @PostMapping("/no-shows")
    public JobResult noShows(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                             @RequestParam(required = false) Integer limit) {
        return jobs.markNoShows(caller, Requests.page(1, limit).limit());
    }
}
