package co.edu.corhuila.barbersaas.appointment.adapter.in.http;

import co.edu.corhuila.barbersaas.appointment.adapter.in.http.ApiError.FieldError;
import co.edu.corhuila.barbersaas.appointment.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.barbersaas.appointment.application.port.in.Caller;
import co.edu.corhuila.barbersaas.appointment.application.port.in.DailyJobUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.in.DailyJobUseCases.JobResult;
import co.edu.corhuila.barbersaas.appointment.domain.model.ReminderWindow;
import java.util.List;
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

    /**
     * writeDueReminders: {@code window} DAY_BEFORE (default) or HOUR_BEFORE, at most {@code limit}
     * (1–100, default 20); {@code remaining} asks for another call.
     */
    @PostMapping("/reminders-due")
    public JobResult remindersDue(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                                  @RequestParam(required = false) String window,
                                  @RequestParam(required = false) Integer limit) {
        return jobs.writeDueReminders(caller, window(window), Requests.page(1, limit).limit());
    }

    private static ReminderWindow window(String value) {
        if (value == null) {
            return ReminderWindow.DAY_BEFORE;
        }
        try {
            return ReminderWindow.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("the request is not valid",
                    List.of(new FieldError("window", "one of " + List.of(ReminderWindow.values()))));
        }
    }

    /** markNoShows: at most {@code limit} (1–100, default 20). */
    @PostMapping("/no-shows")
    public JobResult noShows(@RequestAttribute(AuthFilter.CALLER_ATTRIBUTE) Caller caller,
                             @RequestParam(required = false) Integer limit) {
        return jobs.markNoShows(caller, Requests.page(1, limit).limit());
    }
}
