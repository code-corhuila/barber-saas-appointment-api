package co.edu.corhuila.barbersaas.appointment.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "co.edu.corhuila.barbersaas.appointment")
public class AppointmentApplication {
    public static void main(String[] args) {
        SpringApplication.run(AppointmentApplication.class, args);
    }
}
