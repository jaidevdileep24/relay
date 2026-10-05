package com.dileep.relay.api;

import com.dileep.relay.api.error.ValidationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Guards a client-supplied {@link Pageable} before it reaches a repository.
 *
 * <p>Spring Data will happily sort by any entity path the caller names -
 * {@code ?sort=endpoint.secret} adds a join and orders by the signing secret -
 * and an unknown property surfaces as a 500 from deep inside the query layer.
 * So each list route declares the properties it allows, and anything else is a
 * 400 at the boundary.
 *
 * <p>The page cap exists because {@code page * size} is computed as an int
 * offset: {@code page=2147483647} overflows it and also fails as a 500.
 */
public final class Pages {

	static final int MAX_PAGE = 10_000;

	private Pages() {
	}

	public static Pageable check(Pageable pageable, Set<String> sortable) {
		if (pageable.getPageNumber() > MAX_PAGE) {
			throw new ValidationException("page must be at most " + MAX_PAGE);
		}
		for (Sort.Order order : pageable.getSort()) {
			if (!sortable.contains(order.getProperty())) {
				throw new ValidationException("cannot sort by '%s' (allowed: %s)".formatted(
						order.getProperty(), sortable.stream().sorted().collect(Collectors.joining(", "))));
			}
		}
		return pageable;
	}
}
