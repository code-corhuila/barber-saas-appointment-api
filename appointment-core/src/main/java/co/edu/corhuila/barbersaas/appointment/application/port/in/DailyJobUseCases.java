package co.edu.corhuila.barbersaas.appointment.application.port.in;

import co.edu.corhuila.barbersaas.appointment.domain.model.ReminderWindow;

/** The daily jobs of DEC-APPT-07: only the service token of barber-saas-worker, which only calls on time. */
public interface DailyJobUseCases {

    /** JobResult: handled in this call, and whether more are left for the next one. */
    record JobResult(int processed, boolean remaining) { }

    /**
     * AppointmentReminderDue, once per appointment and window, for the CONFIRMED appointments of
     * tomorrow (DAY_BEFORE) or that start in 55 to 65 minutes (HOUR_BEFORE), in barbershop local time.
     */
    JobResult writeDueReminders(Caller caller, ReminderWindow window, int limit);

    /** CONFIRMED appointments dated before today (barbershop local date) move to NO_SHOW. */
    JobResult markNoShows(Caller caller, int limit);
}
