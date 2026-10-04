package co.edu.corhuila.barbersaas.appointment.application.port.out;

import java.util.UUID;

public interface IdGenerator {

    UUID next();
}
