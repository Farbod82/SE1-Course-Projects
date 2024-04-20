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
                new Order(5, security, BUY, 1000, 15400, broker, shareholder)
//                new Order(6, security, Side.SELL, 500, 15800, broker, shareholder),
//                new Order(7, security, Side.SELL, 285, 15810, broker, shareholder),
//                new Order(8, security, Side.SELL, 800, 15810, broker, shareholder),
//                new Order(9, security, Side.SELL, 340, 15820, broker, shareholder),
//                new Order(10, security, Side.SELL, 65, 15820, broker, shareholder)
        );
        orders.forEach(order -> orderBook.enqueue(order));
    }
    

    @Test
    void check_series_of_stop_orders_activate_and_run_correctly() {

        Order matchingSellOrder1 = new Order(6, security, Side.SELL, 500, 15800, broker, shareholder);
        Order matchingSellOrder2 = new Order(7, security, Side.SELL, 285, 15810, broker, shareholder);
        Order matchingSellOrder3 = new Order(9, security, Side.SELL, 340, 15820, broker, shareholder);
        security.getOrderBook().enqueue(matchingSellOrder1);
        security.getOrderBook().enqueue(matchingSellOrder2);
        security.getOrderBook().enqueue(matchingSellOrder3);

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

        verify(eventPublisher).publish(new OrderActivatedEvent(4, 20));
        verify(eventPublisher).publish(new OrderActivatedEvent(6, 21));
        Trade trade1 = new Trade(security, 15810,200,stopOrder1,matchingSellOrder2);
        verify(eventPublisher).publish(new OrderExecutedEvent(4, 20,List.of(new TradeDTO(trade1))));

    }


    @Test
    void test_sell_limit_order_price_less_than_activated_price_and_change_lastprice(){
        Broker broker2 = Broker.builder().credit(100_000_000L).build();
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.SELL, 300, 15700, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));
        assertThat(security.getLatestPrice()).isEqualTo(1700);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.SELL, 50, 15700, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 15800));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 500, LocalDateTime.now(), Side.SELL, 50, 15800, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0, 0));

        assertThat(security.getStopOrderList().size()).isEqualTo(0);
    }

    void createTestOrders(Side side)
    {
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
        var testOrders = List.of(
                new Order(200, security,side, 300, 1600, broker, shareholder),
                new Order(300, security,side, 300, 1700, broker, shareholder),
                new Order(400, security,side, 300, 1800, broker, shareholder)
        );
        for(Order order : testOrders){
            orderBook.enqueue(order);
        }
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
