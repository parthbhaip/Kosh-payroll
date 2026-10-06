package com.kosh.web;

import java.util.Locale;

/**
 * A message shown once at the top of the next page (Post/Redirect/Get) or as an htmx toast:
 * {@code redirect.addFlashAttribute(Flash.ATTRIBUTE, Flash.success("September payroll is ready for approval"))}.
 * The app layout renders it; danger messages are announced as alerts, the rest as status.
 */
public record Flash(Kind kind, String title, String text) {

	/** The model and flash attribute the app layout reads. */
	public static final String ATTRIBUTE = "flash";

	public enum Kind {

		SUCCESS("check"), INFO("info"), WARNING("alert"), DANGER("alert");

		private final String icon;

		Kind(String icon) {
			this.icon = icon;
		}

		public String icon() {
			return icon;
		}

		/** CSS modifier: {@code flash-success}, {@code toast-success}. */
		public String css() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

	public static Flash success(String title) {
		return new Flash(Kind.SUCCESS, title, null);
	}

	public static Flash info(String title) {
		return new Flash(Kind.INFO, title, null);
	}

	public static Flash warning(String title) {
		return new Flash(Kind.WARNING, title, null);
	}

	public static Flash danger(String title) {
		return new Flash(Kind.DANGER, title, null);
	}

	/** The same message with a second, explanatory line. */
	public Flash withText(String text) {
		return new Flash(kind, title, text);
	}

}
