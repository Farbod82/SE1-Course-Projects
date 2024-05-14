package ir.ramtung.tinyme.messaging.event;
import ir.ramtung.tinyme.messaging.request.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
public class SecurityStateChangedEvent extends Event{
    LocalDateTime time;
    String securityIsin;
    MatchingState state;
}
