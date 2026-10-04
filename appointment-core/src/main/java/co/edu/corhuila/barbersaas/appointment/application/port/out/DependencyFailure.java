package co.edu.corhuila.barbersaas.appointment.application.port.out;

/**
 * Another domain's API did not answer in time or answered something unexpected. The request fails
 * (500 with a neutral message, the cause in the log): availability is never computed from a guess.
 */
public class DependencyFailure extends RuntimeException {

    public DependencyFailure(String dependency, String reason) {
        super(dependency + ": " + reason);
    }
}
