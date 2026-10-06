package com.kosh.web;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Links back to the current list page with one parameter changed, for the pagination and sortable-header fragments
 * ({@code @links} in templates). Every other query parameter (search, filters) is kept, so paging never loses a filter.
 * Links are relative to the server root, never absolute.
 */
@Component("links")
public class Links {

	/** Marks a gap in {@link #pageWindow(Page)}. */
	public static final int GAP = -1;

	/** The current list at page {@code page} (zero-based, as Spring Data counts). */
	public String page(int page) {
		return current().replaceQueryParam("page", page).build().toUriString();
	}

	/** The current list sorted by {@code property} ({@code asc} or {@code desc}), back on the first page. */
	public String sort(String property, String direction) {
		return current().replaceQueryParam("sort", property + "," + direction).replaceQueryParam("page").build().toUriString();
	}

	/**
	 * Page numbers to show: the first, the last and the current page with one neighbour on each side, with
	 * {@link #GAP} where pages are left out. A gap would hide a single page, so that page is shown instead.
	 */
	public List<Integer> pageWindow(Page<?> page) {
		int last = page.getTotalPages() - 1;
		int current = page.getNumber();
		TreeSet<Integer> shown = new TreeSet<>();
		for (int n : new int[] { 0, current - 1, current, current + 1, last }) {
			if (n >= 0 && n <= last) {
				shown.add(n);
			}
		}
		List<Integer> window = new ArrayList<>();
		int previous = -1;
		for (int n : shown) {
			if (n - previous == 2) {
				window.add(previous + 1);
			}
			else if (n - previous > 2) {
				window.add(GAP);
			}
			window.add(n);
			previous = n;
		}
		return window;
	}

	private static UriComponentsBuilder current() {
		HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
			.getRequest();
		return UriComponentsBuilder.fromPath(request.getRequestURI()).query(request.getQueryString());
	}

}
