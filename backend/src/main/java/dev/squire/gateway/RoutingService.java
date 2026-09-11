package dev.squire.gateway;

import dev.squire.scheme.Scheme;
import dev.squire.scheme.SchemeRepository;
import dev.squire.usage.AttemptRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public final class RoutingService {
    private final SchemeRepository schemes;
    private final AttemptRepository attempts;
    private final AtomicLong sequence = new AtomicLong();

    public RoutingService(SchemeRepository schemes, AttemptRepository attempts) {
        this.schemes = schemes;
        this.attempts = attempts;
    }

    public record Route(String schemeId, List<String> candidates) { }

    public Route route() {
        Scheme scheme = schemes.active();
        List<Scheme.Member> members = new ArrayList<>(scheme.members());
        if (scheme.strategy() == Scheme.Strategy.BALANCED && !members.isEmpty()) {
            int totalWeight = members.stream().mapToInt(Scheme.Member::weight).sum();
            long ticket = Math.floorMod(sequence.getAndIncrement(), totalWeight);
            int selected = 0;
            while (ticket >= members.get(selected).weight()) {
                ticket -= members.get(selected++).weight();
            }
            java.util.Collections.rotate(members, -selected);
        }
        return new Route(scheme.id(), members.stream().map(Scheme.Member::providerId).toList());
    }

    public AttemptRepository.Admission admit(Route route, int position, String requestId, String model) {
        return attempts.admit(route.candidates().get(position), route.schemeId(), requestId, model);
    }
}
