package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.apache.activemq.artemis.utils.collections.LinkedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class AuctionMatchingTest {


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

    }

    @Test
    void check_correct_iop() {
        orders = Arrays.asList(
                new Order(6, security, Side.SELL, 200, 15800, broker1, shareholder),
                new Order(7, security, Side.SELL, 200, 15810, broker1, shareholder),
                new Order(6, security, Side.BUY, 200, 15900, broker, shareholder),
                new Order(7, security, Side.BUY, 200, 15910, broker, shareholder));

        orders.forEach(order -> orderBook.enqueue(order));

        long IOP = orderBook.calculateIndicativeOpeningPrice(15850);
        assertThat(IOP).isEqualTo(15850);
        IOP = orderBook.calculateIndicativeOpeningPrice(16000);
        assertThat(IOP).isEqualTo(15900);
        IOP = orderBook.calculateIndicativeOpeningPrice(15000);
        assertThat(IOP).isEqualTo(15810);
    }

    @Test
    void check_auction_match_with_given_opening_price(){
        orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 15700, broker, shareholder),
                new Order(2, security, Side.BUY, 43, 15600, broker, shareholder),
                new Order(9, security, Side.SELL, 340, 15400, broker1, shareholder),
                new Order(10, security, Side.SELL, 65, 15500, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        var matchResults = matcher.auctionMatch(security.getOrderBook() , 15550);
        assertThat(broker.getCredit()).isEqualTo(100_000_000L + 304 * (15700 - 15550) + 43 * (15600 - 15550));
        assertThat(broker1.getCredit()).isEqualTo(100_000_000L + 347 * (15550));
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getSellQueue().getFirst().getQuantity()).isEqualTo(58);

    }


    @Test
    void check_auction_match_with_given_opening_price_with_one_buy_icebergOrder(){
        orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 15700, broker, shareholder),
                new IcebergOrder(3 , security , Side.BUY , 200 , 15650 , broker ,shareholder , 50),
                new Order(2, security, Side.BUY, 43, 15600, broker, shareholder),
                new Order(9, security, Side.SELL, 340, 15400, broker1, shareholder),
                new Order(10, security, Side.SELL, 65, 15500, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        var matchResults = matcher.auctionMatch(security.getOrderBook() , 15550);
        assertThat(broker.getCredit()).isEqualTo(100_000_000L + 304 * (15700 - 15550) + 43 * (15600 - 15550)
                + 58 * (15650 - 15550));
        assertThat(broker1.getCredit()).isEqualTo(100_000_000L + 405 * (15550));
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(1);
        assertThat(security.getOrderBook().getBuyQueue().getFirst().getOrderId()).isEqualTo(3);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(0);

    }

}
