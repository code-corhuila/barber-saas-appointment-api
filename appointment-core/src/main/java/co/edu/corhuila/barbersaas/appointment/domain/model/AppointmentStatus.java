package co.edu.corhuila.barbersaas.appointment.domain.model;

/** Same values as chk_appointment_status (06-data/models.md §5); edges in 08-diagrams/uml/state-appointment.md. */
public enum AppointmentStatus {
    PENDING, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW;

    /** CANCELLED and NO_SHOW free the slot, as ex_appointment_no_double_booking does (INV-APPT-001). */
    public boolean takesTime() {
        return this != CANCELLED && this != NO_SHOW;
    }
}
