package org.gerbitpcb.supplier.murata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.gerbitpcb.supplier.murata.domain.Component;
import org.gerbitpcb.supplier.murata.repository.ComponentRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableScheduling
public class SupplierMurataApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupplierMurataApplication.class, args);
    }

    // This block runs exactly once, immediately after the app starts up!
//    @Bean
//    public CommandLineRunner loadTestData(ComponentRepository repository) {
//        return args -> {
//            if (repository.count() == 0) {
//                // Murata Passive Components
//                repository.save(createComponent("GRM188R71H104KA93D", "100nF MLCC Capacitor", "0.08", 4000));
//                repository.save(createComponent("GRM21BR71H105KA12L", "1uF MLCC Capacitor", "0.12", 2500));
//                repository.save(createComponent("LQG15HS4N7S02D", "4.7nH Multilayer Inductor", "0.15", 3000));
//                repository.save(createComponent("CSTCE16M0V53-R0", "16MHz Ceramic Resonator", "0.22", 1500));
//                repository.save(createComponent("NCP15XH103J03RC", "10k NTC Thermistor", "0.18", 2000));
//
//                System.out.println("Murata Component catalog loaded successfully!");
//            }
//        };
//    }
//
//    private Component createComponent(String sku, String name, String price, int stock) {
//        Component c = new Component();
//        c.setSku(sku);
//        c.setName(name);
//        c.setPrice(new java.math.BigDecimal(price));
//        c.setAvailableStock(stock);
//        c.setReservedStock(0);
//        return c;
//    }
}
