package co.edu.corhuila.barbersaas.appointment.application.port.in;

/** The daily jobs of DEC-APPT-07: only the service token of barber-saas-worker, which only calls on time. */
public interface DailyJobUseCases {

    /** JobResult: handled in this call, and whether more are left for the next one. */
    record JobResult(int processed, boolean remaining) { }

    /** AppointmentReminderDue for CONFIRMED appointments of tomorrow (barbershop local date), once each. */
    JobResult writeDueReminders(Caller caller, int limit);

    /** CONFIRMED appointments dated before today (barbershop local date) move to NO_SHOW. */
    JobResult markNoShows(Caller caller, int limit);
}
