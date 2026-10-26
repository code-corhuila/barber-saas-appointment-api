package co.edu.corhuila.barbersaas.appointment.application.port.out;

/**
 * Another domain's API could not be reached, timed out or answered 502/503/504: the request is not
 * stored and answers 503, so the client can try again.
 */
public class DependencyUnavailable extends DependencyFailure {

    public DependencyUnavailable(String dependency, String reason) {
        super(dependency, reason);
    }
}
