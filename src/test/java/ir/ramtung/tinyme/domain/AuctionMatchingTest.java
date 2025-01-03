package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.ChangeMatchStateHandler;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
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

    void setupTest(){
        orders = Arrays.asList(
                new Order(5, security, Side.SELL, 200, 16000, broker1, shareholder),
                new Order(6, security, Side.SELL, 200, 15800, broker1, shareholder),
                new Order(7, security, Side.SELL, 200, 15810, broker1, shareholder),
                new Order(8, security, BUY, 200, 15900, broker, shareholder),
                new Order(9, security, BUY, 200, 15910, broker, shareholder),
                new Order(10, security, BUY, 200, 15000, broker, shareholder));

        orders.forEach(order -> orderBook.enqueue(order));
    }

    @Test
    void check_correct_indicative_opening_prices() {
        setupTest();
        HashMap<String, Long> priceAndQuantity =  orderBook.calcCurrentOpeningPriceAndMaxQuantity(15850);
        assertThat(priceAndQuantity.get("price").intValue()).isEqualTo(15850);
        HashMap<String, Long> priceAndQuantity1 =  orderBook.calcCurrentOpeningPriceAndMaxQuantity(16000);
        assertThat(priceAndQuantity1.get("price").intValue()).isEqualTo(15900);
        HashMap<String, Long> priceAndQuantity2 =  orderBook.calcCurrentOpeningPriceAndMaxQuantity(15000);
        assertThat(priceAndQuantity2.get("price").intValue()).isEqualTo(15810);
    }

    @Test
    void check_opening_price_published_correctly() {
        setupTest();
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order1 = new Order(20,security, SELL,285,15815,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL,
                285, 15815, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(1, 20));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC", 15810, 400));
    }

    @Test void check_opening_price_published_correctly_after_changed_last_price_by_opening() {
        setupTest();
        Order order1 = new Order(20,security, SELL,50,15805,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL, 50, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order2 = new Order(21,security, BUY,5,15835,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 21, order2.getEntryTime(), BUY, 5, 15835, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order3 = new Order(22,security, BUY,110,15905,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 22, order3.getEntryTime(), BUY, 110, 15905, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(1, 20));
        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(2, 21));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC", 15835, 355));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC", 15835, 45));
    }

    @Test
    void check_opening_price_published_correctly_after_change_last_price_by_new_order_in_continues_state() {
        setupTest();
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        Order order1 = new Order(20,security, SELL,285,15815,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL
                ,285,15815, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

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
        setupTest();
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        Order order1 = new Order(20,security, SELL,5,15799,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL,
                5, 15799, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));


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
        setupTest();
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        Order order1 = new Order(20,security, SELL,5,15799,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL,
                5, 15799, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

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
        verify(eventPublisher,times(1)).publish(new OrderRejectedEvent(1,11,List.of(Message.NEW_ORDER_WITH_MINIMUM_EXECUTION_QUANTITY_NOT_ALLOWED_IN_AUCTION_MODE)));
        assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
    }

    @Test
    void check_that_stop_orders_cant_enter_in_auction_state(){
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 11, LocalDateTime.now(), Side.BUY,
                500, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 200));
        verify(eventPublisher,times(1)).publish(new OrderRejectedEvent(1,11,List.of(Message.NEW_STOP_LIMIT_ORDER_NOT_ALLOWED_IN_AUCTION_MODE)));
        assertThat(security.getOrderBook().getBuyQueue()).isEmpty();
    }

    @Test
    void check_auction_match_with_given_opening_price_with_one_buy_icebergOrder_works_correctly(){
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
    void changing_matching_state_and_publishing_opening_price_event_after_that_twice(){
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
    void test_auction_match_with_min_buy_price_less_than_min_sell_price(){
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
    void test_auction_match_with_equal_min_and_max_of_buy_price_and_sell_price(){
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


    @Test
    void check_auction_match_with_equal_quantity(){
        orders = Arrays.asList(
                new Order(1, security, Side.BUY, 340, 15700, broker, shareholder),
                new Order(2, security, Side.BUY, 43, 15600, broker, shareholder),
                new Order(9, security, Side.SELL, 340, 15400, broker1, shareholder),
                new Order(10, security, Side.SELL, 43, 15500, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        var matchResults = matcher.auctionMatch(security.getOrderBook() , 15550);
        assertThat(broker.getCredit()).isEqualTo(100_000_000L + 340 * (15700 - 15550) + 43 * (15600 - 15550));
        assertThat(broker1.getCredit()).isEqualTo(100_000_000L + 383 * (15550));
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(0);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(0);

    }

    @Test
    void check_auction_match_with_equal_quantity_iceberg_order(){
        orders = Arrays.asList(
                new IcebergOrder(4, security, Side.BUY, 60, 15800, broker, shareholder , 50),
                new IcebergOrder(1, security, Side.BUY, 80, 15700, broker, shareholder , 50),
                new Order(2, security, Side.BUY, 20, 15600, broker, shareholder),
                new Order(3, security, Side.BUY, 70, 15580, broker, shareholder),
                new IcebergOrder(9, security, Side.SELL, 70, 15500, broker1, shareholder , 20),
                new IcebergOrder(10, security, Side.SELL, 100, 15400, broker1, shareholder , 50),
                new Order(11, security, Side.SELL, 50, 15300, broker1, shareholder , 50)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        var matchResults = matcher.auctionMatch(security.getOrderBook() , 15550);
        assertThat(broker.getCredit()).isEqualTo(100_000_000L + 60 * (15800 - 15550)
                + 70 * (15700 - 15550)+ (20) *(15600 - 15550) + (50 + 20)*(15580 - 15550));
        assertThat(broker1.getCredit()).isEqualTo(100_000_000L + 220 * (15550));
        assertThat(security.getOrderBook().getBuyQueue().size()).isEqualTo(1);
        Order remainderOrder = security.getOrderBook().getBuyQueue().getFirst();
        assertThat(remainderOrder.getOrderId()).isEqualTo(1);
        assertThat(remainderOrder.getQuantity()).isEqualTo(10);
        assertThat(security.getOrderBook().getSellQueue().size()).isEqualTo(0);

    }

    @Test
    void check_if_stop_order_limits_get_activated_after_auction_to_auction(){
        orders = Arrays.asList(
                new Order(3, security, Side.BUY, 300, 15500, broker, shareholder),
                new Order(2, security, Side.BUY, 43, 15600, broker, shareholder),
                new Order(1, security, Side.BUY, 2, 15700, broker, shareholder),
                new Order(10, security, Side.SELL, 340, 15500, broker1, shareholder),
                new Order(9, security, Side.SELL, 4, 15600, broker1, shareholder),
                new Order(8, security, Side.SELL, 100, 15700, broker1, shareholder)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        Order order1 = new Order(20,security, SELL,1,15700,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL,
                1, 15700, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        Order order2 = new Order(21,security, SELL,3,15900,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 21, order2.getEntryTime(), SELL,
                300, 15500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15600));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 22, order2.getEntryTime(), SELL,
                5, 15900, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(3,22));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC",15500,340));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        verify(eventPublisher,times(1)).publish(new TradeEvent("ABC",15500,1,1,10));
        verify(eventPublisher,times(1)).publish(new TradeEvent("ABC",15500,43,2,10));
        verify(eventPublisher,times(1)).publish(new TradeEvent("ABC",15500,296,3,10));

        verify(eventPublisher,times(1)).publish(new OrderActivatedEvent(2,21));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC",15500,4));
        assertThat(security.getStopOrderList()).isEmpty();

    }




    @Test
    void check_if_stop_order_limits_get_activated_after_auction_to_continuous(){
        orders = Arrays.asList(
                new Order(2, security, Side.BUY, 43, 15600, broker, shareholder),
                new Order(1, security, Side.BUY, 2, 15700, broker, shareholder),
                new Order(10, security, Side.SELL, 340, 15500, broker1, shareholder),
                new Order(9, security, Side.SELL, 4, 15600, broker1, shareholder),
                new Order(8, security, Side.SELL, 100, 15700, broker1, shareholder)
        );
        Order order4 = new Order(3, security, Side.BUY, 300, 15500, broker, shareholder);
        security.getOrderBook().enqueue(order4);
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        Order order1 = new Order(20,security, SELL,1,15700,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL,
                1, 15700, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        Order order2 = new Order(21,security, SELL,3,15900,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 21, order2.getEntryTime(), SELL,
                300, 15500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15600));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order3 = new Order(22,security, SELL,5,15900,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 22, order2.getEntryTime(), SELL,
                5, 15900, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher,times(1)).publish(new OrderAcceptedEvent(3,22));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC",15500,340));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.CONTINUOUS));
        verify(eventPublisher,times(1)).publish(new TradeEvent("ABC",15500,1,1,10));
        verify(eventPublisher,times(1)).publish(new TradeEvent("ABC",15500,43,2,10));
        verify(eventPublisher,times(1)).publish(new TradeEvent("ABC",15500,296,3,10));
        verify(eventPublisher,times(1)).publish(new OrderActivatedEvent(2,21));

        Trade trade1 = new Trade(security,15500,4,order2, order4);
        verify(eventPublisher,times(1)).publish(new OrderExecutedEvent(2,21,List.of(new TradeDTO(trade1))));
        assertThat(security.getStopOrderList()).isEmpty();
    }

    @Test
    void test_change_position_after_auction_match(){
        Shareholder shareholder2 = Shareholder.builder().shareholderId(2).build();
        shareholder2.incPosition(security, 100_000_000_0);
        shareholderRepository.addShareholder(shareholder2);
        Shareholder shareholder3 = Shareholder.builder().shareholderId(3).build();
        shareholder3.incPosition(security, 100_000_000_0);
        shareholderRepository.addShareholder(shareholder2);

        orders = Arrays.asList(
                new Order(2, security, Side.BUY, 43, 15600, broker, shareholder),
                new Order(1, security, Side.BUY, 2, 15700, broker, shareholder),
                new Order(10, security, Side.SELL, 41, 15500, broker1, shareholder3),
                new Order(9, security, Side.SELL, 4, 15600, broker1, shareholder2),
                new Order(8, security, Side.SELL, 100, 15700, broker1, shareholder2)
        );

        orders.forEach(order -> security.getOrderBook().enqueue(order));

        Order order1 = new Order(20,security, SELL,1,15700,broker1,shareholder2,LocalDateTime.now(),OrderStatus.NEW);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL,
                1, 15700, broker1.getBrokerId(), shareholder2.getShareholderId(), 0, 0, 0));

        assertThat(security.getLatestPrice()).isEqualTo(15700);
        assertThat(shareholder2.getPositions().get(security)).isEqualTo(1000_000_000 - 1);
        assertThat(shareholder.getPositions().get(security)).isEqualTo(1000_000_000 + 1);
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.CONTINUOUS));
        assertThat(shareholder2.getPositions().get(security)).isEqualTo(1000_000_000 - 1 - 3);
        assertThat(shareholder.getPositions().get(security)).isEqualTo(1000_000_000 + 45);
        assertThat(shareholder3.getPositions().get(security)).isEqualTo(1000_000_000 - 41);

    }

    @Test
    void test_deleting_unactivated_stop_limit_order_not_allowed(){
        orders = Arrays.asList(
                new Order(5, security, Side.SELL, 200, 16000, broker1, shareholder),
                new Order(6, security, Side.SELL, 200, 15800, broker1, shareholder),
                new Order(7, security, Side.SELL, 200, 15810, broker1, shareholder),
                new Order(8, security, BUY, 200, 15900, broker, shareholder),
                new Order(9, security, BUY, 200, 15910, broker, shareholder),
                new Order(10, security, BUY, 200, 15000, broker, shareholder));

        orders.forEach(order -> orderBook.enqueue(order));

        Order order1 = new Order(20,security, SELL,50,15805,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL, 50, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        Order order2 = new Order(21,security, SELL,50,15805,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 21, order2.getEntryTime(), SELL, 50, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15900));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        orderHandler.handleDeleteOrder(new DeleteOrderRq(3, security.getIsin(), SELL, 21));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        verify(eventPublisher, times(1)).publish(new OrderRejectedEvent(3,21,List.of(Message.DELETE_UNACTIVATED_STOP_LIMIT_ORDER_NOT_ALLOWED_IN_AUCTION_MODE)));
    }

    @Test
    void test_deleting_activated_stop_limit_order_works_correctly(){
        orders = Arrays.asList(
                new Order(5, security, Side.SELL, 200, 16000, broker1, shareholder),
                new Order(6, security, Side.SELL, 200, 15800, broker1, shareholder),
                new Order(7, security, Side.SELL, 200, 15810, broker1, shareholder),
                new Order(8, security, BUY, 200, 15900, broker, shareholder),
                new Order(9, security, BUY, 200, 15910, broker, shareholder),
                new Order(10, security, BUY, 200, 15000, broker, shareholder));

        orders.forEach(order -> orderBook.enqueue(order));

        Order order1 = new Order(20,security, SELL,50,15805,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL, 50, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        Order order2 = new Order(21,security, SELL,500,15805,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 21, order2.getEntryTime(), SELL, 500, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15920));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        Order order3 = new Order(22,security, BUY,600,15901,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 22, order3.getEntryTime(), BUY, 600, 15901, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleDeleteOrder(new DeleteOrderRq(3, security.getIsin(), SELL, 21));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC",15900,550));
        verify(eventPublisher,times(1)).publish(new OrderDeletedEvent(3, 21));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC",15900,400));
    }

    @Test
    void test_updating_unactivated_stop_limit_order_not_allowed(){
        orders = Arrays.asList(
                new Order(5, security, Side.SELL, 200, 16000, broker1, shareholder),
                new Order(6, security, Side.SELL, 100, 15800, broker1, shareholder),
                new Order(7, security, Side.SELL, 200, 15810, broker1, shareholder),
                new Order(8, security, BUY, 200, 15900, broker, shareholder),
                new Order(9, security, BUY, 200, 15910, broker, shareholder),
                new Order(10, security, BUY, 200, 15000, broker, shareholder));

        orders.forEach(order -> orderBook.enqueue(order));

        Order order1 = new Order(20,security, SELL,10,15805,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL, 10, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        Order order2 = new Order(21,security, SELL,50,15805,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 21, order2.getEntryTime(), SELL, 50, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15900));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(3,"ABC",21,LocalDateTime.now(), BUY,60,15800,1,shareholder.getShareholderId(),0,15910));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        verify(eventPublisher, times(1)).publish(new OrderRejectedEvent(3,21,List.of(Message.UPDATE_UNACTIVATED_STOP_LIMIT_ORDER_NOT_ALLOWED_IN_AUCTION_MODE)));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC",15900,50));
    }

    @Test
    void test_updating_activated_stop_limit_order_works_correctly(){
        orders = Arrays.asList(
                new Order(5, security, Side.SELL, 200, 16000, broker1, shareholder),
                new Order(6, security, Side.SELL, 100, 15800, broker1, shareholder),
                new Order(7, security, Side.SELL, 200, 15810, broker1, shareholder),
                new Order(8, security, BUY, 200, 15900, broker, shareholder),
                new Order(9, security, BUY, 200, 15910, broker, shareholder),
                new Order(10, security, BUY, 200, 15000, broker, shareholder));

        orders.forEach(order -> orderBook.enqueue(order));

        Order order1 = new Order(20,security, SELL,50,15805,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 20, order1.getEntryTime(), SELL, 50, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        Order order2 = new Order(21,security, SELL,430,15805,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 21, order2.getEntryTime(), SELL, 430, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15920));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(3,"ABC",21,LocalDateTime.now(), SELL,150,15800,1,shareholder.getShareholderId(),0,15920));

        Order order3 = new Order(22,security, BUY,600,15901,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,0);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 22, order3.getEntryTime(), BUY, 600, 15901, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        changeMatchStateHandler.handleChangeMatchingState(new ChangeMatchingStateRq("ABC", MatchingState.AUCTION));

        verify(eventPublisher,times(1)).publish(new OrderUpdatedEvent(3, 21));

        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC",15900,0));
        verify(eventPublisher,times(1)).publish(new OpeningPriceEvent("ABC",15900,450));

    }

}
