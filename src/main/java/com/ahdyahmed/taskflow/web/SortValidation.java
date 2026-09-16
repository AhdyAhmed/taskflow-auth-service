package com.ahdyahmed.taskflow.web;

import org.springframework.data.domain.Sort;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Day 9. Spring Data binds {@code ?sort=} straight from the query
 * string and only validates it once that property name is resolved
 * against the entity — at query-execution time, deep in the repository
 * layer. Before this, a typo'd or unexpected sort property (e.g.
 * {@code ?sort=nonsense} or {@code ?sort=owner.email}) surfaced as a raw
 * {@code PropertyReferenceException} that fell through to the generic
 * 500 handler instead of a clean 400 — a client input problem reported
 * as a server error. This validates the request's sort against an
 * explicit per-endpoint allowlist before it ever reaches the
 * repository. It also rejects relationship-traversal properties like
 * {@code owner.email} on principle even though nothing sensitive would
 * leak through an ORDER BY — letting the client dictate an implicit
 * JOIN on every listing request via an undocumented query param isn't
 * a shape of control the API means to offer.
 */
public final class SortValidation {

    private SortValidation() {
    }

    /** @throws IllegalArgumentException (→ 400, see GlobalExceptionHandler) if any requested property isn't allowed. */
    public static void requireAllowed(Sort sort, Set<String> allowedProperties) {
        Set<String> rejected = sort.stream()
                .map(Sort.Order::getProperty)
                .filter(property -> !allowedProperties.contains(property))
                .collect(Collectors.toSet());

        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot sort by %s — allowed properties: %s".formatted(rejected, allowedProperties));
        }
    }
}
