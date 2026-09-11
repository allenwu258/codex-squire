package dev.squire.scheme;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SchemeInput(@NotBlank @Size(max = 80) String name, @NotNull Scheme.Strategy strategy,
                          @NotNull @Size(max = 100) List<@Valid MemberInput> members) {
    public record MemberInput(@NotBlank String providerId, @Min(1) @Max(100) int weight) { }
}
