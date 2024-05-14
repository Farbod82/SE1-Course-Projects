package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.ChangeMatchStateHandler;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.OrderRejectedEvent;
import ir.ramtung.tinyme.messaging.event.SecurityStateChangedEvent;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class AuctionMatchingTest {


    @Autowired
    OrderHandler orderHandler;

    @Autowired
    ChangeMatchStateHandler changeMatchStateHandler;

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
    void check_correct_indicative_opening_price() {
        orders = Arrays.asList(
                new Order(6, security, Side.SELL, 200, 15800, broker1, shareholder),
                new Order(7, security, Side.SELL, 200, 15810, broker1, shareholder),
                new Order(6, security, BUY, 200, 15900, broker, shareholder),
                new Order(7, security, BUY, 200, 15910, broker, shareholder));

        orders.forEach(order -> orderBook.enqueue(order));

        long IOP = orderBook.calculateIndicativeOpeningPrice(15850);
        assertThat(IOP).isEqualTo(15850);
        IOP = orderBook.calculateIndicativeOpeningPrice(16000);
        assertThat(IOP).isEqualTo(15900);
        IOP = orderBook.calculateIndicativeOpeningPrice(15000);
        assertThat(IOP).isEqualTo(15810);
    }

    @Test
    void security_correctly_change_state(){
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        assertThat(security.isInAuctionMatchingState()).isEqualTo(true);
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        assertThat(security.isInAuctionMatchingState()).isEqualTo(true);
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.CONTINUOUS));
        assertThat(security.isInAuctionMatchingState()).isEqualTo(false);
    }

    @Test
    void check_that_stop_orders_cant_enter_in_auction_state(){
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 11, LocalDateTime.now(), Side.BUY,
                500, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 200));
        verify(eventPublisher).publish(new OrderRejectedEvent(1,11,List.of(Message.MINIMUM_EXECUTION_QUANTITY_ORDER_NOT_ALLOWED_IN_AUCTION_MODE)));
        assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
    }

}
