package com.kosh.audit;

/**
 * What an audit event records. Stored by name, so never rename or remove a constant once released; add new ones as
 * later features need them. {@code tone} colours the action's badge on {@code /admin/audit} (null: neutral).
 */
public enum AuditAction {

	LOGIN_SUCCEEDED("Signed in", null),
	LOGIN_FAILED("Sign-in failed", "warning"),
	ACCOUNT_LOCKED("Account locked", "danger"),
	PASSWORD_CHANGED("Password changed", null),
	PASSWORD_RESET("Password reset", "info"),
	INVITE_ACCEPTED("Invite accepted", null),
	USER_INVITED("User invited", "info"),
	INVITE_RESENT("Invite resent", null),
	USER_UPDATED("User details changed", null),
	ROLES_CHANGED("Roles changed", "info"),
	USER_DISABLED("User disabled", "warning"),
	USER_ENABLED("User enabled", "success"),
	ADMIN_BOOTSTRAPPED("Admin set up at startup", "warning"),
	AUDIT_EXPORTED("Audit log exported", null),
	COMPANY_DETAILS_CHANGED("Company details changed", "info"),
	COMPANY_LOGO_CHANGED("Company logo changed", null),
	PAY_SETTINGS_CHANGED("Pay settings changed", "info"),
	ORG_ITEM_ADDED("Organisation item added", "info"),
	ORG_ITEM_CHANGED("Organisation item changed", null),
	ORG_ITEM_DEACTIVATED("Organisation item deactivated", "warning"),
	ORG_ITEM_ACTIVATED("Organisation item activated", "success"),
	ORG_ITEM_DELETED("Organisation item deleted", "danger"),
	HOLIDAY_ADDED("Holiday added", "info"),
	HOLIDAY_CHANGED("Holiday changed", null),
	HOLIDAY_DELETED("Holiday deleted", "danger"),
	HOLIDAYS_COPIED("Holidays copied", "info"),
	WORK_WEEK_CHANGED("Work week changed", "info"),
	STATUTORY_VERSION_ADDED("Statutory rule version added", "warning"),
	STATUTORY_RULES_CHECKED("Statutory rules checked", null),
	STATUTORY_VERSION_WITHDRAWN("Statutory rule version withdrawn", "danger");

	private final String label;

	private final String tone;

	AuditAction(String label, String tone) {
		this.label = label;
		this.tone = tone;
	}

	public String label() {
		return label;
	}

	public String tone() {
		return tone;
	}

}
