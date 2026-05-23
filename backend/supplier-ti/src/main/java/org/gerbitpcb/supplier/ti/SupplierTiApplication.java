package org.gerbitpcb.supplier.ti;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.gerbitpcb.supplier.ti.domain.Component;
import org.gerbitpcb.supplier.ti.repository.ComponentRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;

@SpringBootApplication
@EnableScheduling
public class SupplierTiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupplierTiApplication.class, args);
    }

    // This block runs exactly once, immediately after the app starts up!
//    @Bean
//    public CommandLineRunner loadTestData(ComponentRepository repository) {
//        return args -> {
//
//            repository.deleteAll();
//
//            if (repository.count() == 0) {
//                // Microcontrollers & Wireless
//                repository.save(createComponent("ATMEGA328P", "Microcontroller ATmega328P", "3.20", 240));
//                repository.save(createComponent("ESP32-WROOM-32", "Wireless Module ESP32", "4.80", 85));
//
//                // Classic Texas Instruments ICs
//                repository.save(createComponent("NE555P", "Precision Timer (555)", "0.45", 1500));
//                repository.save(createComponent("LM324N", "Quad Operational Amplifier", "0.35", 800));
//                repository.save(createComponent("LM317T", "Adjustable Voltage Regulator", "0.60", 650));
//                repository.save(createComponent("SN74HC08N", "Quad 2-Input AND Gate", "0.25", 2000));
//                repository.save(createComponent("LM393P", "Dual Voltage Comparator", "0.30", 1200));
//
//                // Sensors & Converters
//                repository.save(createComponent("ADS1115", "16-Bit ADC with PGA", "4.50", 120));
//                repository.save(createComponent("INA219", "Current/Power Monitor", "2.10", 300));
//                repository.save(createComponent("TMP36", "Analog Temperature Sensor", "1.50", 450));
//
//                System.out.println("Expanded TI Component catalog loaded successfully!");
//            } else {
//                System.out.println("Database already contains data. Skipping initialization.");
//            }
//        };
//    }
//
//    // Helper method to keep our code clean and DRY (Don't Repeat Yourself)
//    private Component createComponent(String sku, String name, String price, int stock) {
//        Component c = new Component();
//        c.setSku(sku);
//        c.setName(name);
//        c.setPrice(new BigDecimal(price));
//        c.setAvailableStock(stock);
//        c.setReservedStock(0);
//        return c;
//    }
}
