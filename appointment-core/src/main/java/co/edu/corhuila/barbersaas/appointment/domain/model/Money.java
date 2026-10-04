package co.edu.corhuila.barbersaas.appointment.domain.model;

import co.edu.corhuila.barbersaas.appointment.domain.model.DomainException.InvalidValue;

/** An amount in COP cents, never a floating point number (ADR-010); a price is never negative. */
public record Money(long cents) {

    public Money {
        if (cents < 0) {
            throw new InvalidValue("An amount cannot be negative");
        }
    }

    public static Money ofCents(long cents) {
        return new Money(cents);
    }
}
