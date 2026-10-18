package com.kosh.employee;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.kosh.common.Text;

/**
 * Whom to call if something happens to the employee at work: a name and a mobile number, the relation optional.
 * Tidied on construction. No contact is a null {@code EmergencyContact}.
 */
@Embeddable
public record EmergencyContact(
		@NotBlank(message = "Enter the contact’s name.")
		@Size(max = 120, message = "Use at most 120 characters.")
		@Column(name = "emergency_contact_name", length = 120)
		String name,

		@Size(max = 40, message = "Use at most 40 characters.")
		@Column(name = "emergency_contact_relation", length = 40)
		String relation,

		@NotBlank(message = "Enter the contact’s mobile number.")
		@Pattern(regexp = Employee.MOBILE_FORMAT, message = Employee.MOBILE_MESSAGE)
		@Column(name = "emergency_contact_phone", length = 10)
		String phone) {

	public EmergencyContact {
		name = Text.clean(name);
		relation = Text.clean(relation);
		phone = Text.code(phone);
	}

}
