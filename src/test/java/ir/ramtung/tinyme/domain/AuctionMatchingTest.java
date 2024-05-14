package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.ChangeMatchStateHandler;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.*;
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
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
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

        orderBook.updateCurrentOpeningPriceAndMaxQuantity(15850);
        assertThat(orderBook.getOpeningPrice()).isEqualTo(15850);
        orderBook.updateCurrentOpeningPriceAndMaxQuantity(16000);
        assertThat(orderBook.getOpeningPrice()).isEqualTo(15900);
        orderBook.updateCurrentOpeningPriceAndMaxQuantity(15000);
        assertThat(orderBook.getOpeningPrice()).isEqualTo(15810);
    }

    @Test
    void check_opening_price_published_correctly() {
        orders = Arrays.asList(
                new Order(6, security, Side.SELL, 200, 15800, broker1, shareholder),
                new Order(7, security, Side.SELL, 200, 15810, broker1, shareholder),
                new Order(6, security, BUY, 200, 15900, broker, shareholder),
                new Order(7, security, BUY, 200, 15910, broker, shareholder));

        orders.forEach(order -> orderBook.enqueue(order));
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order1 = new Order(20,security, SELL,285,15815,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL,
                285, 15815, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 20));
        verify(eventPublisher).publish(new OpeningPriceEvent("ABC", 15810, 400));
    }

    @Test
    void check_opening_price_published_correctly_after_change_last_price_by_opening() {
        orders = Arrays.asList(
                new Order(5, security, Side.SELL, 200, 16000, broker1, shareholder),
                new Order(6, security, Side.SELL, 200, 15800, broker1, shareholder),
                new Order(7, security, Side.SELL, 200, 15810, broker1, shareholder),
                new Order(8, security, BUY, 200, 15900, broker, shareholder),
                new Order(9, security, BUY, 200, 15910, broker, shareholder),
                new Order(10, security, BUY, 200, 15000, broker, shareholder));

        orders.forEach(order -> orderBook.enqueue(order));
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order1 = new Order(20,security, SELL,200,15815,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL,
                285, 15815, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order2 = new Order(21,security, BUY,100,15835,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 21, order2.getEntryTime(), BUY,
                100, 15835, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 20));
        verify(eventPublisher).publish(new OrderAcceptedEvent(2, 21));
        verify(eventPublisher).publish(new OpeningPriceEvent("ABC", 15815, 100));
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
    void check_that_order_with_positive_meq_cant_enter_in_auction_state(){
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 11, LocalDateTime.now(), Side.BUY,
                500, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 200));
        verify(eventPublisher).publish(new OrderRejectedEvent(1,11,List.of(Message.ORDER_WITH_MINIMUM_EXECUTION_QUANTITY_NOT_ALLOWED_IN_AUCTION_MODE)));
        assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
    }

    @Test
    void check_that_stop_orders_cant_enter_in_auction_state(){
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 11, LocalDateTime.now(), Side.BUY,
                500, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 200));
        verify(eventPublisher).publish(new OrderRejectedEvent(1,11,List.of(Message.STOP_LIMIT_ORDER_NOT_ALLOWED_IN_AUCTION_MODE)));
        assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
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
