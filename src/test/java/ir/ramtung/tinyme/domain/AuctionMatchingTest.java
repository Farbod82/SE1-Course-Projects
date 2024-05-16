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
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
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
import java.util.*;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
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

    void set_check_opening_price_status(){
        orders = Arrays.asList(
                new Order(5, security, Side.SELL, 200, 16000, broker1, shareholder),
                new Order(6, security, Side.SELL, 200, 15800, broker1, shareholder),
                new Order(7, security, Side.SELL, 200, 15810, broker1, shareholder),
                new Order(8, security, BUY, 200, 15900, broker, shareholder),
                new Order(9, security, BUY, 200, 15910, broker, shareholder),
                new Order(10, security, BUY, 200, 15000, broker, shareholder));

        orders.forEach(order -> orderBook.enqueue(order));
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order1 = new Order(20,security, SELL,5,15799,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL,
                5, 15799, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
    }

    @Test
    void check_correct_indicative_opening_price() {
        orders = Arrays.asList(
                new Order(5, security, Side.SELL, 200, 16000, broker1, shareholder),
                new Order(6, security, Side.SELL, 200, 15800, broker1, shareholder),
                new Order(7, security, Side.SELL, 200, 15810, broker1, shareholder),
                new Order(8, security, BUY, 200, 15900, broker, shareholder),
                new Order(9, security, BUY, 200, 15910, broker, shareholder),
                new Order(10, security, BUY, 200, 15000, broker, shareholder));

        orders.forEach(order -> orderBook.enqueue(order));

        HashMap<String, Long> priceAndQuantity =  orderBook.calcCurrentOpeningPriceAndMaxQuantity(15850);
        assertThat(priceAndQuantity.get("price").intValue()).isEqualTo(15850);
        HashMap<String, Long> priceAndQuantity1 =  orderBook.calcCurrentOpeningPriceAndMaxQuantity(16000);
        assertThat(priceAndQuantity1.get("price").intValue()).isEqualTo(15900);
        HashMap<String, Long> priceAndQuantity2 =  orderBook.calcCurrentOpeningPriceAndMaxQuantity(15000);
        assertThat(priceAndQuantity2.get("price").intValue()).isEqualTo(15810);
    }

    @Test
    void check_opening_price_published_correctly() {
        orders = Arrays.asList(
                new Order(5, security, Side.SELL, 200, 16000, broker1, shareholder),
                new Order(6, security, Side.SELL, 200, 15800, broker1, shareholder),
                new Order(7, security, Side.SELL, 200, 15810, broker1, shareholder),
                new Order(8, security, BUY, 200, 15900, broker, shareholder),
                new Order(9, security, BUY, 200, 15910, broker, shareholder),
                new Order(10, security, BUY, 200, 15000, broker, shareholder));

        orders.forEach(order -> orderBook.enqueue(order));
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order1 = new Order(20,security, SELL,285,15815,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL,
                285, 15815, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(1, 20));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC", 15810, 400));
    }

    @Test
    void check_opening_price_published_correctly_after_change_last_price_by_opening() {
        set_check_opening_price_status();
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order2 = new Order(21,security, BUY,100,15835,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 21, order2.getEntryTime(), BUY,
                100, 15835, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(1, 20));
        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(2, 21));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC", 15815, 100));
    }

    @Test
    void check_opening_price_published_correctly_after_change_last_price_by_new_order_in_continues_state() {
        set_check_opening_price_status();
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.CONTINUOUS));

        Order order2 = new Order(21,security, BUY,100,15835,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 21, order2.getEntryTime(), BUY,
                100, 15835, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order3 = new Order(22,security, BUY,77,15845,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 22, order3.getEntryTime(), BUY,
                77, 15845, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(1, 20));
        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(2, 21));
        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(3, 22));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC", 15810, 400));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC", 15815, 77));
    }

    @Test
    void check_update_order_correctly_done_in_auction_matching(){
        set_check_opening_price_status();

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(20, "ABC", 7, LocalDateTime.now(), Side.SELL, 2, 15801, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0));
        Order order2 = new Order(21,security, BUY,100,15835,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 21, order2.getEntryTime(), SELL,
                103, 15803, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));


        verify(eventPublisher,times(1)).publish(new OrderUpdatedEvent(20, 7));
        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(1, 20));
        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(3, 21));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC", 15810, 400));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC", 15801, 207));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC", 15803, 207+103));
    }

    @Test
    void check_delete_order_correctly_done_in_auction_matching(){
        set_check_opening_price_status();

        orderHandler.handleDeleteOrder(new DeleteOrderRq(2, security.getIsin(), Side.SELL, 7));
        verify(eventPublisher).publish(new OrderDeletedEvent(2, 7));

        Order order2 = new Order(21,security, SELL,105,15803,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 21, order2.getEntryTime(), BUY,
                21, 15803, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(1, 20));
        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(3, 21));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC", 15810, 400));
        verify(eventPublisher,times(2)).publish(new OpeningPriceEvent("ABC", 15800, 205));
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
        verify(eventPublisher,times(1)).publish(new OrderRejectedEvent(1,11,List.of(Message.ORDER_WITH_MINIMUM_EXECUTION_QUANTITY_NOT_ALLOWED_IN_AUCTION_MODE)));
        assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
    }

    @Test
    void check_that_stop_orders_cant_enter_in_auction_state(){
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 11, LocalDateTime.now(), Side.BUY,
                500, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 200));
        verify(eventPublisher,times(1)).publish(new OrderRejectedEvent(1,11,List.of(Message.STOP_LIMIT_ORDER_NOT_ALLOWED_IN_AUCTION_MODE)));
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

    @Test
    void twice_changing_matching_state_and_publishing_opening_price_event_after_that(){
        orders = Arrays.asList(
                new Order(1, security, SELL, 200, 16000, broker1, shareholder),
                new Order(2, security, BUY, 300, 16000, broker, shareholder));
        orders.forEach(order -> orderBook.enqueue(order));
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order1 = new Order(3,security, SELL,100,16000,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 3, order1.getEntryTime(), SELL,
                100, 16000, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        Order order2 = new Order(4,security, BUY,285,15815,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        Order order3 = new Order(5,security, SELL,200,15800,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 4, order2.getEntryTime(), BUY,
                285, 15815, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 5, order3.getEntryTime(), SELL,
                285, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(1, 3));
        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(2, 4));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC", 16000, 300));
        verify(eventPublisher,times(2)).publish(new OpeningPriceEvent("ABC", 0, 0));
    }

    @Test
    void test_actionMatch_with_min_buy_price_less_min_sell_price(){
        orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 15700, broker, shareholder),
                new Order(2, security, Side.BUY, 43, 15600, broker, shareholder),
                new Order(3, security, Side.BUY, 100, 15450, broker, shareholder),
                new Order(8, security, Side.SELL, 100, 15750, broker1, shareholder),
                new Order(9, security, Side.SELL, 340, 15600, broker1, shareholder),
                new Order(10, security, Side.SELL, 4, 15500, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        int opening_price  = security.getOrderBook().calcCurrentOpeningPriceAndMaxQuantity((int) 16500).get("price").intValue();
        assertThat(opening_price).isEqualTo(15600);
        var matchResults = matcher.auctionMatch(security.getOrderBook() , opening_price);
        assertThat(broker.getCredit()).isEqualTo(100_000_000L + 304 * (15700 - 15600));
        assertThat(broker1.getCredit()).isEqualTo(100_000_000L + 344 * (15600));
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(2);
        assertThat(security.getOrderBook().getBuyQueue().getFirst().getOrderId()).isEqualTo(2);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(1);

    }


    @Test
    void test_actionMatch_with_equal_min_and_max_of_buy_price_and_sell_price(){
        orders = Arrays.asList(
                new Order(2, security, Side.BUY, 43, 15600, broker, shareholder),
                new Order(10, security, Side.SELL, 4, 15500, broker1, shareholder),
                new Order(3, security, Side.BUY, 100, 15500, broker, shareholder),
                new Order(8, security, Side.SELL, 100, 15700, broker1, shareholder),
                new Order(1, security, Side.BUY, 304, 15700, broker, shareholder),
                new Order(9, security, Side.SELL, 340, 15600, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        int opening_price  = security.getOrderBook().calcCurrentOpeningPriceAndMaxQuantity((int) 16500).get("price").intValue();
        assertThat(opening_price).isEqualTo(15600);
        var matchResults = matcher.auctionMatch(security.getOrderBook() , opening_price);
        assertThat(broker.getCredit()).isEqualTo(100_000_000L + 304 * (15700 - 15600));
        assertThat(broker1.getCredit()).isEqualTo(100_000_000L + 344 * (15600));
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(2);
        assertThat(security.getOrderBook().getBuyQueue().getFirst().getOrderId()).isEqualTo(2);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(1);

    }

}
