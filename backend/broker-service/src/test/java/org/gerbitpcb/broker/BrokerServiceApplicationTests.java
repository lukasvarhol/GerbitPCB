package org.gerbitpcb.broker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.gerbitpcb.broker.repository.TransactionRepository;
import org.gerbitpcb.broker.repository.ComponentRepository;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
class BrokerServiceApplicationTests {

    @MockBean
    TransactionRepository transactionRepository;

    @MockBean
    ComponentRepository componentRepository;

    @Test
    void contextLoads() {
    }

}
