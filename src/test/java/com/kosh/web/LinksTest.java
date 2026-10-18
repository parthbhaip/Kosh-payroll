package com.kosh.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Page and sort links keep the list's filters; the page window shows first, last and the current neighbourhood. */
class LinksTest {

	private static final int GAP = Links.GAP;

	private final Links links = new Links();

	@AfterEach
	void clearRequest() {
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void pageLinksKeepSearchFiltersAndSort() {
		onRequest("/hr/employees", "q=patil&department=PROD&sort=name,asc&page=2");

		assertThat(links.page(3)).isEqualTo("/hr/employees?q=patil&department=PROD&sort=name,asc&page=3");
		assertThat(links.page(0)).isEqualTo("/hr/employees?q=patil&department=PROD&sort=name,asc&page=0");
	}

	@Test
	void sortLinksReplaceTheSortAndGoBackToTheFirstPage() {
		onRequest("/hr/employees", "q=patil&sort=name,asc&page=2");

		assertThat(links.sort("name", "desc")).isEqualTo("/hr/employees?q=patil&sort=name,desc");
		assertThat(links.sort("joiningDate", "asc")).isEqualTo("/hr/employees?q=patil&sort=joiningDate,asc");
	}

	@Test
	void filterLinksSetOrDropOneFilterAndGoBackToTheFirstPage() {
		onRequest("/hr/employees", "q=Aarti%20K&status=EXITED&sort=code,asc&page=2");

		assertThat(links.filter("status", "ACTIVE")).isEqualTo("/hr/employees?q=Aarti%20K&sort=code,asc&status=ACTIVE");
		assertThat(links.filter("status", null)).isEqualTo("/hr/employees?q=Aarti%20K&sort=code,asc");
		onRequest("/hr/employees", null);
		assertThat(links.filter("status", null)).isEqualTo("/hr/employees");
	}

	@Test
	void linksWorkWithoutAQueryStringAndKeepEncodedValues() {
		onRequest("/hr/employees", null);
		assertThat(links.page(1)).isEqualTo("/hr/employees?page=1");

		onRequest("/hr/employees", "q=Aarti%20Kulkarni");
		assertThat(links.page(1)).isEqualTo("/hr/employees?q=Aarti%20Kulkarni&page=1");
	}

	@Test
	void pageWindowShowsFirstLastAndNeighboursWithGaps() {
		assertThat(window(0, 20)).containsExactly(0, 1, GAP, 19);
		assertThat(window(2, 20)).containsExactly(0, 1, 2, 3, GAP, 19);
		assertThat(window(10, 20)).containsExactly(0, GAP, 9, 10, 11, GAP, 19);
		assertThat(window(19, 20)).containsExactly(0, GAP, 18, 19);
	}

	@Test
	void pageWindowShowsASinglePageInsteadOfAGap() {
		// A gap between 0 and 2 (or 3 and 5) would hide one page, so that page is shown instead.
		assertThat(window(3, 6)).containsExactly(0, 1, 2, 3, 4, 5);
		assertThat(window(2, 6)).containsExactly(0, 1, 2, 3, 4, 5);
		assertThat(window(2, 7)).containsExactly(0, 1, 2, 3, GAP, 6);
	}

	@Test
	void pageWindowForOneOrNoPages() {
		assertThat(window(0, 1)).containsExactly(0);
		assertThat(links.pageWindow(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 25), 0))).isEmpty();
	}

	private List<Integer> window(int current, int totalPages) {
		int size = 25;
		List<String> rows = Collections.nCopies(size, "row");
		return links.pageWindow(new PageImpl<>(rows, PageRequest.of(current, size), (long) totalPages * size));
	}

	private static void onRequest(String uri, String query) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.setQueryString(query);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

}
