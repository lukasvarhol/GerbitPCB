package org.gerbitpcb.supplier.murata;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.gerbitpcb.supplier.murata.repository.ComponentRepository;
import org.gerbitpcb.supplier.murata.repository.ReservationRepository;
import org.gerbitpcb.supplier.murata.services.ComponentService;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"
})
class SupplierMurataApplicationTests {

    @MockBean
    JwtDecoder jwtDecoder;

    @MockBean
    ComponentRepository componentRepository;

    @MockBean
    ReservationRepository reservationRepository;

    @MockBean
    ComponentService componentService;

    @Test
    void contextLoads() {
    }

}
