package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;

import ir.ramtung.tinyme.messaging.event.*;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
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
                new Order(6, security, Side.SELL, 500, 15800, broker, shareholder),
                new Order(7, security, Side.SELL, 285, 15810, broker, shareholder),
                new Order(8, security, Side.SELL, 800, 15810, broker, shareholder),
                new Order(9, security, Side.SELL, 340, 15820, broker, shareholder),
                new Order(10, security, Side.SELL, 65, 15820, broker, shareholder)
        );
        orders.forEach(order -> orderBook.enqueue(order));


    }


    @Test
    void check_stop_order_list() {

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(4, "ABC", 14, LocalDateTime.now(), Side.BUY,
                1, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 11, LocalDateTime.now(), Side.BUY,
                500, 15810, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 20000));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 12, LocalDateTime.now(), Side.BUY,
                500, 15805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 10000));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 13, LocalDateTime.now(), Side.BUY,
                500, 15835, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 40000));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 13, LocalDateTime.now(), Side.BUY,
                500, 13805, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 30000));

        LinkedList<Order> stopOrderList = security.getStopOrderList();

        ArrayList<Integer> stopOrderListPrices = new ArrayList<>();
        ArrayList<Integer> resultPrices = new ArrayList<>(
                Arrays.asList(10000, 20000, 30000, 40000));

        for (Order order : stopOrderList) {
            stopOrderListPrices.add(order.getPrice());
            System.out.println(order.getPrice());
        }
        assert (stopOrderListPrices.equals(resultPrices));
    }

    @Test
    void check_series_of_stop_orders_activate_and_run_correctly() {

        Order matchingSellOrder1 = orderBook.findByOrderId(Side.SELL,6);
        Order matchingSellOrder2 = orderBook.findByOrderId(Side.SELL,7);
        Order matchingSellOrder3 = orderBook.findByOrderId(Side.SELL,8);

//        security.getOrderBook().enqueue(matchingSellOrder1);
//        security.getOrderBook().enqueue(matchingSellOrder2);
//        security.getOrderBook().enqueue(matchingSellOrder3);

        Order stopOrder1 = new Order(20,security, BUY,285,15815,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,15801);
        Order stopOrder2 = new Order(21,security, BUY,100,1400,broker1,shareholder,LocalDateTime.now(),OrderStatus.NEW,0,false,15801);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 16, LocalDateTime.now(), Side.BUY,
                500, 15800, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

//        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 16));

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
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.SELL, 300, 15700, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        assertThat(broker2.getCredit()).isEqualTo(100_000_000 + 300 * 15700);
        assertThat(security.getLatestPrice()).isEqualTo(15700);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.SELL, 50, 15700, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15500));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 16, LocalDateTime.now(), Side.SELL, 50, 15500, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher).publish(new OrderActivatedEvent(2, 500));
        assertThat(security.getStopOrderList().size()).isEqualTo(0);
    }

    @Test
    void test_buy_limit_order_activated_but_rollback_for_not_enugh_money(){
        Broker broker2 = Broker.builder().brokerId(3).credit(20).build();
        brokerRepository.addBroker(broker2);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.SELL, 150, 15700, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

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
    void new_sell_stoplimit_matches_successfully(){

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.SELL, 300, 1500, 2, shareholder.getShareholderId(), 0, 0, 1400));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.SELL, 250, 1600, 2, shareholder.getShareholderId(), 0, 0, 0));
        // Order newNormalSell = new Order(500, security, Side.SELL, 250, 1600, broker2, shareholder);
        // Order stopLimit = new Order(500, security, Side.SELL, 250, 1600, broker2, shareholder);

        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(2, 500)));
        verify(eventPublisher).publish(new OrderActivatedEvent(1, 600));
        Trade trade1 = new Trade(security, 1800, 50, security.getOrderBook().findByOrderId(Side.BUY, 400), security.getOrderBook().findByOrderId(Side.BUY, 600));
        Trade trade2 = new Trade(security, 1800, 250, security.getOrderBook().findByOrderId(Side.BUY, 300), security.getOrderBook().findByOrderId(Side.BUY, 600));
        //verify(eventPublisher).publish(new OrderExecutedEvent(1, 600, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 600)).isEqualTo(null);
    }
}
