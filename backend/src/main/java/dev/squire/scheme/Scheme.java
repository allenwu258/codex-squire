package dev.squire.scheme;

import java.util.List;

public record Scheme(String id, String name, Strategy strategy, boolean active, List<Member> members) {
    public enum Strategy { ORDERED, BALANCED }

    public record Member(String providerId, int weight) { }
}
