package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;

import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import ir.ramtung.tinyme.messaging.event.OrderExecutedEvent;
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
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class StopOrderTest {

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
                new Order(6, security, SELL, 500, 15800, broker, shareholder),
                new Order(7, security, SELL, 285, 15810, broker, shareholder),
                new Order(8, security, SELL, 800, 15810, broker, shareholder),
                new Order(9, security, SELL, 340, 15820, broker, shareholder),
                new Order(10, security, SELL, 65, 15820, broker, shareholder)
        );
        orders.forEach(order -> orderBook.enqueue(order));


    }

    @Test
    void check_sorting_of_activated_sell_order_list(){
        Broker broker2 = Broker.builder().brokerId(3).credit(20).build();
        OrderBook orderBook = security.getOrderBook();
        brokerRepository.addBroker(broker2);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 11,LocalDateTime.now(), Side.BUY,
                500, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 12, LocalDateTime.now(), SELL,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15799));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 13, LocalDateTime.now(), SELL,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15797));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 14, LocalDateTime.now(), SELL,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15798));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 15, LocalDateTime.now(), SELL,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 158799));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 16,LocalDateTime.now(), SELL,
                500, 15798, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(7, "ABC", 17,LocalDateTime.now(), Side.BUY,
                500, 15798, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));


        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(8, "ABC", 17,LocalDateTime.now(), Side.BUY,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        assertThat(orderBook.findByOrderId(SELL ,13)).isNull();
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(9, "ABC", 18,LocalDateTime.now(), Side.BUY,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        assertThat(orderBook.findByOrderId(SELL ,14)).isNull();
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(10, "ABC", 19,LocalDateTime.now(), Side.BUY,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        assertThat(orderBook.findByOrderId(SELL ,12)).isNull();
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(11, "ABC", 20,LocalDateTime.now(), Side.BUY,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        assertThat(orderBook.findByOrderId(SELL ,15)).isNull();

    }

    @Test
    void check_sorting_of_activated_buy_order_list(){
        Broker broker2 = Broker.builder().brokerId(3).credit(20).build();
        OrderBook orderBook = security.getOrderBook();
        brokerRepository.addBroker(broker2);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 11,LocalDateTime.now(), Side.BUY,
                500, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 12, LocalDateTime.now(), Side.BUY,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15808));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 13, LocalDateTime.now(), Side.BUY,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15807));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 14, LocalDateTime.now(), Side.BUY,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15809));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 15, LocalDateTime.now(), Side.BUY,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15807));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 16,LocalDateTime.now(), SELL,
                500, 15809, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(7, "ABC", 17,LocalDateTime.now(), Side.BUY,
                500, 15809, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));


        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(8, "ABC", 17,LocalDateTime.now(), SELL,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        assertThat(orderBook.findByOrderId(Side.BUY ,14)).isNull();
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(9, "ABC", 18,LocalDateTime.now(), SELL,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        assertThat(orderBook.findByOrderId(Side.BUY ,12)).isNull();
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(10, "ABC", 19,LocalDateTime.now(), SELL,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        assertThat(orderBook.findByOrderId(Side.BUY ,13)).isNull();
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(11, "ABC", 20,LocalDateTime.now(), SELL,
                50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        assertThat(orderBook.findByOrderId(Side.BUY ,15)).isNull();

    }

    @Test
    void check_series_of_stop_orders_activate_and_run_correctly() {

        Order matchingSellOrder2 = orderBook.findByOrderId(SELL,7);

        Order stopOrder1 = new Order(20,security, BUY,285,15815,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,15801);
        Order stopOrder2 = new Order(21,security, BUY,100,1400,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,15801);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 16, LocalDateTime.now(), Side.BUY,
                500, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 20, stopOrder1.getEntryTime(), Side.BUY,
                200, 15815, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15801));

        verify(eventPublisher).publish(new OrderAcceptedEvent(4,20));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 21, stopOrder2.getEntryTime(), Side.BUY,
                100, 14000, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15801));

        verify(eventPublisher).publish(new OrderAcceptedEvent(6,21));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 17, LocalDateTime.now(), Side.BUY,
                85, 15810, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 16));
        verify(eventPublisher).publish(new OrderActivatedEvent(4, 20));
        verify(eventPublisher).publish(new OrderActivatedEvent(6, 21));
        Trade trade1 = new Trade(security, 15810,200,stopOrder1,matchingSellOrder2);
        verify(eventPublisher).publish(new OrderExecutedEvent(4, 20,List.of(new TradeDTO(trade1))));

    }


    @Test
    void test_sell_limit_order_price_less_than_activated_price_and_change_lastprice(){
        Broker broker2 = Broker.builder().brokerId(3).credit(100_000_000L).build();
        brokerRepository.addBroker(broker2);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), SELL, 300, 15700, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        assertThat(broker2.getCredit()).isEqualTo(100_000_000 + 300 * 15700);
        assertThat(security.getLatestPrice()).isEqualTo(15700);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), SELL, 50, 15700, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15500));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 16, LocalDateTime.now(), SELL, 50, 15500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher).publish(new OrderActivatedEvent(2, 500));
        assertThat(security.getStopOrderList().size()).isEqualTo(0);
    }
    @Test
    void test_sell_limit_order_price_match_directly(){
        Broker broker2 = Broker.builder().brokerId(3).credit(100_000_000L).build();
        brokerRepository.addBroker(broker2);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), SELL, 300, 15700, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        assertThat(broker2.getCredit()).isEqualTo(100_000_000 + 300 * 15700);
        assertThat(security.getLatestPrice()).isEqualTo(15700);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), SELL, 50, 15500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15800));

        assertThat(security.getStopOrderList().size()).isEqualTo(0);
        verify(eventPublisher).publish(new OrderActivatedEvent(2, 500));
        verify(eventPublisher).publish(new OrderAcceptedEvent(2, 500));


    }

    @Test
    void test_buy_limit_order_activated_but_rollback_for_not_enugh_money(){
        Broker broker2 = Broker.builder().brokerId(3).credit(20).build();
        brokerRepository.addBroker(broker2);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), SELL, 150, 15700, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        assertThat(security.getLatestPrice()).isEqualTo(15700);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.BUY, 50, 15810, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15800));
        assertThat(security.getStopOrderList().size()).isEqualTo(1);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 16, LocalDateTime.now(), Side.BUY, 50, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        assertThat(broker.getCredit()).isEqualTo(1000_00_000L + 50 * 15800);
        verify(eventPublisher).publish(new OrderActivatedEvent(2, 500));
        assertThat(broker.getCredit()).isEqualTo(1000_00_000L + 50 * 15800);
        assertThat(broker1.getCredit()).isEqualTo( 1000_00_000+150 * 15700 - 50 * 15800);
        assertThat(security.getStopOrderList().size()).isEqualTo(0);
    }



    @Test
    void test_if_activated_sell_stop_orders_activate_other_sell_stop_orders_correctly(){
        Order stopOrder1 = new Order(20,security, SELL,285,15815,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,15801);
        Order stopOrder2 = new Order(21,security, SELL,100,1400,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,15801);
        Order stopOrder3 = new Order(22,security, SELL,100,1400,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,1000);
        Order stopOrder4 = new Order(23,security, SELL,100,1400,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,1000);

        Order matchingBuyOrder1 = orderBook.findByOrderId(BUY,2);
        Order matchingBuyOrder2 = orderBook.findByOrderId(BUY,3);
        Order matchingBuyOrder3 = orderBook.findByOrderId(BUY,4);
        Order matchingBuyOrder4 = orderBook.findByOrderId(BUY,5);


        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 16, LocalDateTime.now(), SELL,
                304, 15000, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 20, stopOrder1.getEntryTime(), SELL,
                40, 15500, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15600));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 21, stopOrder2.getEntryTime(), SELL,
                445, 15000, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15600));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 22, stopOrder3.getEntryTime(), SELL,
                526, 15000, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15455));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 23, stopOrder4.getEntryTime(), SELL,
                2, 15000, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15455));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 17, LocalDateTime.now(), SELL,
                3, 15000, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher).publish(new OrderActivatedEvent(2, 20));
        verify(eventPublisher).publish(new OrderActivatedEvent(3, 21));
        verify(eventPublisher).publish(new OrderAcceptedEvent(4, 22));
        verify(eventPublisher).publish(new OrderActivatedEvent(5, 23));

        Trade trade1 = new Trade(security, 15500,40,stopOrder1,matchingBuyOrder1);
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 20,List.of(new TradeDTO(trade1))));

        Trade trade2 = new Trade(security, 15450,445,stopOrder2,matchingBuyOrder2);
        verify(eventPublisher).publish(new OrderExecutedEvent(3, 21,List.of(new TradeDTO(trade2))));

        Trade trade3 = new Trade(security, 15450,526,stopOrder3,matchingBuyOrder3);
        verify(eventPublisher).publish(new OrderExecutedEvent(4, 22,List.of(new TradeDTO(trade3))));

        Trade trade4 = new Trade(security, 15400,2,stopOrder4,matchingBuyOrder4);
        verify(eventPublisher).publish(new OrderExecutedEvent(5, 23,List.of(new TradeDTO(trade4))));
    }
}
