package co.edu.corhuila.barbersaas.appointment.app;

import co.edu.corhuila.barbersaas.appointment.adapter.in.http.AuthFilter;
import co.edu.corhuila.barbersaas.appointment.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.barbersaas.appointment.adapter.in.http.Rs256Verifier;
import co.edu.corhuila.barbersaas.appointment.adapter.out.http.BarbershopApiClient;
import co.edu.corhuila.barbersaas.appointment.adapter.out.http.BarbershopZonesClient;
import co.edu.corhuila.barbersaas.appointment.adapter.out.http.ScheduleApiClient;
import co.edu.corhuila.barbersaas.appointment.adapter.out.persistence.InMemoryAppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.adapter.out.persistence.JdbcAppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.adapter.out.persistence.SystemClock;
import co.edu.corhuila.barbersaas.appointment.adapter.out.persistence.UuidGenerator;
import co.edu.corhuila.barbersaas.appointment.application.port.in.AppointmentUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.in.BusySlotUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.in.DailyJobUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.in.OutboxRelayUseCases;
import co.edu.corhuila.barbersaas.appointment.application.port.out.AppointmentRepository;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarberAvailability;
import co.edu.corhuila.barbersaas.appointment.application.port.out.BarbershopCatalog;
import co.edu.corhuila.barbersaas.appointment.application.port.out.DailyJobsStore;
import co.edu.corhuila.barbersaas.appointment.application.port.out.OutboxStore;
import co.edu.corhuila.barbersaas.appointment.application.usecase.ManageAppointments;
import co.edu.corhuila.barbersaas.appointment.application.usecase.QueryBusySlots;
import co.edu.corhuila.barbersaas.appointment.application.usecase.RelayOutbox;
import co.edu.corhuila.barbersaas.appointment.application.usecase.RunDailyJobs;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Composition root: the only place that knows every concrete type. The pool and its limits are
 * built here explicitly (norm 5.3.10); the limits of the calls to other APIs live in their client.
 */
@Configuration
public class AppointmentConfiguration {

    /** JDBC over the appointment schema as appointment_app, or in memory when DATABASE_URL is empty. */
    @Bean
    AppointmentRepository appointmentRepository(@Value("${appointment.database.url:}") String url,
                                                @Value("${appointment.database.user:}") String user,
                                                @Value("${appointment.database.password:}") String password,
                                                @Value("${appointment.database.pool-max:10}") int poolMax,
                                                @Value("${appointment.database.statement-timeout-ms:5000}")
                                                int statementTimeoutMs,
                                                ObjectMapper json) {
        if (url.isBlank()) {
            return new InMemoryAppointmentRepository();
        }
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(url);
        pool.setUsername(user);                                       // appointment_app, never the administrator
        pool.setPassword(password);
        pool.setMaximumPoolSize(poolMax);
        pool.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
        pool.setMaxLifetime(Duration.ofMinutes(30).toMillis());
        pool.setConnectionInitSql("SET statement_timeout = " + statementTimeoutMs);
        HikariDataSource dataSource = new HikariDataSource(pool);
        return new JdbcAppointmentRepository(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), json);
    }

    @Bean
    BarbershopCatalog barbershopCatalog(@Value("${appointment.barbershop-api-url}") String url) {
        return new BarbershopApiClient(url);
    }

    @Bean
    BarberAvailability barberAvailability(@Value("${appointment.schedule-api-url}") String url) {
        return new ScheduleApiClient(url);
    }

    @Bean
    AppointmentUseCases appointmentUseCases(AppointmentRepository appointments, BarbershopCatalog catalog,
                                            BarberAvailability availability) {
        return new ManageAppointments(appointments, catalog, availability, new SystemClock(), new UuidGenerator());
    }

    @Bean
    BusySlotUseCases busySlotUseCases(AppointmentRepository appointments) {
        return new QueryBusySlots(appointments);
    }

    /** The same store that writes the events reads them for the worker (both repositories implement it). */
    @Bean
    OutboxRelayUseCases outboxRelayUseCases(AppointmentRepository appointments) {
        return new RelayOutbox((OutboxStore) appointments, new SystemClock());
    }

    /** The barbershop's zone comes from its public detail: the worker's token is not one barbershop-api takes. */
    @Bean
    DailyJobUseCases dailyJobUseCases(AppointmentRepository appointments,
                                      @Value("${appointment.barbershop-api-url}") String barbershopUrl) {
        return new RunDailyJobs((DailyJobsStore) appointments, appointments, new BarbershopZonesClient(barbershopUrl),
                new SystemClock(), new UuidGenerator());
    }

    /** JWT_PUBLIC_KEY: the PEM itself; a one-line value with literal \n escapes, as an env file holds it, is accepted. */
    @Bean
    Rs256Verifier tokenVerifier(@Value("${JWT_PUBLIC_KEY:}") String pem) {
        return new Rs256Verifier(pem.replace("\\n", "\n"));
    }

    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> bean = new FilterRegistrationBean<>(new CorrelationFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(Rs256Verifier verifier, ObjectMapper json) {
        FilterRegistrationBean<AuthFilter> bean = new FilterRegistrationBean<>(new AuthFilter(verifier, json));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
