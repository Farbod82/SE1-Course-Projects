package ir.ramtung.tinyme.messaging.event;
import ir.ramtung.tinyme.messaging.request.*;

public class SecurityStateChangedEvent extends Event{
    String securityIsin;
    MatchingState state;
}
