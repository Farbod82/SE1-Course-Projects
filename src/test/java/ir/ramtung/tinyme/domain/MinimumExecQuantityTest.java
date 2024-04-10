package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class MinimumExecQuantityTest {

    @Autowired
    OrderHandler orderHandler;

    private Security security;
    private Broker broker;
    private Shareholder shareholder;
    private OrderBook orderBook;
    private List<Order> orders;
    private Broker broker1;
    @Autowired
    private Matcher matcher;

    @Autowired
    EventPublisher eventPublisher;

    @Autowired
    SecurityRepository securityRepository;

    @Autowired
    BrokerRepository brokerRepository;

    @Autowired
    ShareholderRepository shareholderRepository;

    @BeforeEach
    void setupOrderBook() {
        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();

        security = Security.builder().isin("ABC").build();
        securityRepository.addSecurity(security);

        broker = Broker.builder().credit(100_000_000L).brokerId(0).build();
        broker1 = Broker.builder().credit(100_000_000L).brokerId(1).build();
        brokerRepository.addBroker(broker1);

        shareholder = Shareholder.builder().shareholderId(1).build();
        shareholder.incPosition(security, 100_000_000_0);
        shareholderRepository.addShareholder(shareholder);

        orderBook = security.getOrderBook();
        orders = Arrays.asList(
                new Order(1, security, BUY, 304, 15700, broker, shareholder),
                new Order(2, security, BUY, 43, 15500, broker, shareholder),
                new Order(3, security, BUY, 445, 15450, broker, shareholder),
                new Order(4, security, BUY, 526, 15450, broker, shareholder),
                new Order(5, security, BUY, 1000, 15400, broker, shareholder),
                new Order(6, security, Side.SELL, 350, 15800, broker, shareholder),
                new Order(7, security, Side.SELL, 285, 15810, broker, shareholder),
                new Order(8, security, Side.SELL, 800, 15810, broker, shareholder),
                new Order(9, security, Side.SELL, 340, 15820, broker, shareholder),
                new Order(10, security, Side.SELL, 65, 15820, broker, shareholder)
        );
        orders.forEach(order -> orderBook.enqueue(order));


    }


    @Test
    void match_sell_order_matched_quantity_is_less_than_minExecQuantity_and_rollback(){

        Order order = new Order(11, security, Side.SELL, 500, 15500,
                broker1, shareholder ,400 );
        matcher.execute(order);
        assertThat(broker1.getCredit())
                .isEqualTo(100_000_000L);
        assertThat(broker.getCredit())
                .isEqualTo(100_000_000L);

        assertThat(orderBook.findByOrderId(Side.BUY ,1).getQuantity())
                .isEqualTo(304);
        assertThat(orderBook.findByOrderId(Side.BUY ,2).getQuantity())
                .isEqualTo(43);

    }


    @Test
    void match_buy_order_matched_quantity_is_less_than_minExecQuantity_and_rollback(){

        Order order = new Order(11, security, Side.BUY, 1600, 15810,
                broker1, shareholder ,1500 );
        matcher.execute(order);
        assertThat(broker1.getCredit())
                .isEqualTo(100_000_000L);
        assertThat(broker.getCredit())
                .isEqualTo(100_000_000L);
        assertThat(orderBook.findByOrderId(Side.SELL ,6).getQuantity())
                .isEqualTo(350);
        assertThat(orderBook.findByOrderId(Side.SELL ,7).getQuantity())
                .isEqualTo(285);
        assertThat(orderBook.findByOrderId(Side.SELL ,8).getQuantity())
                .isEqualTo(800);
    }


    @Test
    void match_buy_order_matched_quantity_is_greater_than_minExecQuantity(){

        Order order1 = new Order(11, security, Side.BUY, 700, 16000,
                broker1, shareholder ,500 );
        matcher.execute(order1);

        assertThat(broker1.getCredit())
                .isEqualTo(88_936_500);
        assertThat(broker.getCredit())
                .isEqualTo(111_063_500L);
        assertThat(orderBook.findByOrderId(Side.SELL ,6)).isNull();

        assertThat(orderBook.findByOrderId(Side.SELL ,7)).isNull();

        assertThat(orderBook.findByOrderId(Side.SELL ,8).getQuantity())
                .isEqualTo(735);

        Broker broker2 = Broker.builder().credit(100_000_000L).build();
        Order order2 = new Order(11, security, Side.BUY, 800, 15810,
                broker2, shareholder ,500 );
        matcher.execute(order2);

        assertThat(orderBook.findByOrderId(Side.SELL ,8)).isNull();
        assertThat(broker2.getCredit())
                .isEqualTo(87_352_000);
        assertThat(broker.getCredit())
                .isEqualTo(122_683_850L);
        assertThat(order2.getQuantity())
                .isEqualTo(65);
    }


    @Test
    void match_sell_order_matched_quantity_is_grather_than_minExecQuantity(){

        Order order1 = new Order(11, security, Side.SELL, 340, 15500,
                broker1, shareholder ,320 );
        matcher.execute(order1);

        assertThat(broker1.getCredit())
                .isEqualTo(105_330_800);
        assertThat(broker.getCredit())
                .isEqualTo(100_000_000);
        assertThat(orderBook.findByOrderId(Side.BUY ,1)).isNull();

        assertThat(orderBook.findByOrderId(Side.BUY ,2).getQuantity())
                .isEqualTo(7);

        Broker broker2 = Broker.builder().credit(10_000_000L).build();
        Order order2 = new Order(11, security, Side.SELL, 10, 15_500,
                broker2, shareholder ,5 );

        matcher.execute(order2);

        assertThat(broker2.getCredit())
                .isEqualTo(10_108_500);
        assertThat(broker.getCredit())
                .isEqualTo(100_000_000);
        assertThat(orderBook.findByOrderId(Side.BUY ,2)).isNull();
        assertThat(order2.getQuantity())
                .isEqualTo(3);
    }

    @Test
    void min_exec_quantity_has_not_impact_on_update_order() {

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 11, LocalDateTime.now(), Side.BUY,
                500, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 200));

        assertThat(broker1.getCredit())
                .isEqualTo(92_099_250L);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 12, LocalDateTime.now(), Side.SELL,
                3000, 15700, broker.getBrokerId(), shareholder.getShareholderId(), 2000));

        assertThat(broker1.getCredit())
                .isEqualTo(92_099_250L);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 11, LocalDateTime.now(), Side.BUY, 1000, 15805, 1, 1, 0));

        assertThat(broker1.getCredit())
                .isEqualTo(78_665_000L);

    }


    }
