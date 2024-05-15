package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.OrderBook;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.event.SecurityStateChangedEvent;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;


@Service
public class ChangeMatchStateHandler {
    SecurityRepository securityRepository;
    BrokerRepository brokerRepository;
    ShareholderRepository shareholderRepository;
    EventPublisher eventPublisher;
    Matcher matcher;

    public ChangeMatchStateHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository, ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
        this.matcher = matcher;
    }

    public void handleChangeMatchingState(ChangeMatchingStateRq changeMatchingStateRq){
        Security security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        if(security.isInAuctionMatchingState()){
            OrderBook orderBook = security.getOrderBook();
            HashMap<String, Long> openingPriceAndQuantity = orderBook.calcCurrentOpeningPriceAndMaxQuantity(security.getLatestPrice());
            matcher.auctionMatch(orderBook, openingPriceAndQuantity.get("price").intValue());
        }
        security.changeSecurityStatusTo(changeMatchingStateRq.getTargetState());
        eventPublisher.publish(new SecurityStateChangedEvent(LocalDateTime.now(),security.getIsin(),changeMatchingStateRq.getTargetState()));
    }
}
