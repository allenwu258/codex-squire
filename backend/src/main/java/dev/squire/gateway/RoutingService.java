package dev.squire.gateway;

import dev.squire.provider.Provider;
import dev.squire.provider.ProviderRepository;
import dev.squire.scheme.Scheme;
import dev.squire.scheme.SchemeRepository;
import dev.squire.usage.AttemptRepository;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public final class RoutingService {
    private final SchemeRepository schemes;
    private final ProviderRepository providers;
    private final AttemptRepository attempts;
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    public RoutingService(SchemeRepository schemes, ProviderRepository providers, AttemptRepository attempts) {
        this.schemes = schemes;
        this.providers = providers;
        this.attempts = attempts;
    }

    public record Route(String schemeId, Scheme.Strategy strategy, List<Scheme.Member> members) {
        public Route {
            members = List.copyOf(members);
        }
    }

    public Route route() {
        Scheme scheme = schemes.active();
        Set<String> enabled = providers.list().stream().filter(Provider::enabled)
                .map(Provider::id).collect(Collectors.toSet());
        return new Route(scheme.id(), scheme.strategy(), scheme.members().stream()
                .filter(member -> enabled.contains(member.providerId())).toList());
    }

    public String nextProvider(Route route, Set<String> attempted) {
        List<Scheme.Member> remaining = route.members().stream()
                .filter(member -> !attempted.contains(member.providerId()))
                .toList();
        if (remaining.isEmpty()) {
            return null;
        }
        if (route.strategy() == Scheme.Strategy.ORDERED) {
            return remaining.get(0).providerId();
        }
        return weightedProvider(route.schemeId(), remaining);
    }

    public AttemptRepository.Admission admit(Route route, String providerId, String requestId, String model) {
        return attempts.admit(providerId, route.schemeId(), requestId, model);
    }

    private String weightedProvider(String schemeId, List<Scheme.Member> members) {
        int totalWeight = members.stream().mapToInt(Scheme.Member::weight).sum();
        long ticket = Math.floorMod(sequence(schemeId).getAndIncrement(), totalWeight);
        for (Scheme.Member member : members) {
            if (ticket < member.weight()) {
                return member.providerId();
            }
            ticket -= member.weight();
        }
        throw new IllegalStateException("Routing weight calculation produced no provider");
    }

    private AtomicLong sequence(String schemeId) {
        return sequences.computeIfAbsent(schemeId, ignored -> new AtomicLong());
    }
}
