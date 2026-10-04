package co.edu.corhuila.barbersaas.appointment.domain.model;

/** Errors the domain raises. The HTTP adapter turns each into one status code. */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    /** A value the contract already forbids (a past date, a note too long): 400 VALIDATION_ERROR. */
    public static class InvalidValue extends DomainException {
        public InvalidValue(String message) {
            super(message);
        }
    }

    /** An input that breaks an invariant: 422 BUSINESS_RULE_VIOLATION. */
    public static class BusinessRuleViolation extends DomainException {
        public BusinessRuleViolation(String message) {
            super(message);
        }
    }

    /** A transition the state machine does not draw (INV-APPT-004): 422 INVALID_STATUS_TRANSITION. */
    public static class InvalidStatusTransition extends DomainException {
        public InvalidStatusTransition(AppointmentStatus from, String transition) {
            super("An appointment in " + from + " cannot " + transition);
        }
    }
}
