package co.edu.corhuila.barbersaas.appointment.application.port.out;

import co.edu.corhuila.barbersaas.appointment.domain.model.Appointment;
import co.edu.corhuila.barbersaas.appointment.domain.model.ReminderWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the daily jobs of DEC-APPT-07 read and write. Like the outbox relay, it works across
 * barbershops: the jobs are the platform's clock, not a user, and decide per barbershop.
 */
public interface DailyJobsStore {

    /**
     * CONFIRMED appointments of every barbershop dated from {@code from} (null: no lower bound) to
     * {@code to}, both inclusive, oldest first; only those without the reminder of
     * {@code withoutReminder} when it is set (null: all of them).
     */
    List<Appointment> confirmedBetween(LocalDate from, LocalDate to, ReminderWindow withoutReminder);

    /**
     * Sets the mark of the window (reminder_sent_at or hour_reminder_sent_at) and writes the event in ONE
     * transaction, only if the appointment is still CONFIRMED without that reminder; false when another
     * call already did it.
     */
    boolean markReminderSent(UUID appointmentId, ReminderWindow window, OutboxEvent event, Instant now);
}
