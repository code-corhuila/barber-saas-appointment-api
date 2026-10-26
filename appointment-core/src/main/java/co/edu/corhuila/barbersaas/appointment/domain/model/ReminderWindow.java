package co.edu.corhuila.barbersaas.appointment.domain.model;

/**
 * The two reminders of a CONFIRMED appointment (DEC-APPT-12), each written once: the day before
 * (reminder_sent_at) and about one hour before it starts (hour_reminder_sent_at).
 */
public enum ReminderWindow {
    DAY_BEFORE,
    HOUR_BEFORE
}
