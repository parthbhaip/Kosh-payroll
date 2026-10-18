package com.kosh.employee;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import com.kosh.statutory.PfPolicy;

/**
 * The rules between an employee's fields. Each broken rule is reported on the field to correct, so a form can show it
 * there: date of birth against the joining date, confirmation and contract end dates, the PT exemption reason, and a PF
 * exclusion for someone who already has a UAN.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = EmployeeConsistency.Check.class)
public @interface EmployeeConsistency {

	String message() default "Check the employee’s details.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Check implements ConstraintValidator<EmployeeConsistency, Employee> {

		@Override
		public boolean isValid(Employee employee, ConstraintValidatorContext context) {
			if (employee == null) {
				return true;
			}
			context.disableDefaultConstraintViolation();
			boolean valid = true;
			LocalDate born = employee.getDateOfBirth();
			LocalDate joined = employee.getJoiningDate();
			if (born != null && joined != null) {
				if (born.plusYears(Employee.MIN_AGE_AT_JOINING).isAfter(joined)) {
					valid = fail(context, "dateOfBirth", "The employee must be at least %d on the joining date."
						.formatted(Employee.MIN_AGE_AT_JOINING));
				}
				else if (!born.plusYears(Employee.MAX_AGE_AT_JOINING).isAfter(joined)) {
					valid = fail(context, "dateOfBirth", "Check the year: this makes the employee %d or older on the joining date."
						.formatted(Employee.MAX_AGE_AT_JOINING));
				}
			}
			LocalDate confirmed = employee.getConfirmationDate();
			if (confirmed != null && joined != null && confirmed.isBefore(joined)) {
				valid = fail(context, "confirmationDate", "The confirmation date can’t be before the joining date.");
			}
			EmploymentType type = employee.getEmploymentType();
			LocalDate ends = employee.getContractEndDate();
			if (ends == null) {
				if (type != null && type.needsEndDate()) {
					valid = fail(context, "contractEndDate", "Enter the contract end date for a fixed-term employee.");
				}
			}
			else if (type != null && !type.allowsEndDate()) {
				valid = fail(context, "contractEndDate", "Only fixed-term employees and interns have a contract end date.");
			}
			else if (joined != null && !ends.isAfter(joined)) {
				valid = fail(context, "contractEndDate", "The contract must end after the joining date.");
			}
			if (employee.isPtExempt() && employee.getPtExemptReason() == null) {
				valid = fail(context, "ptExemptReason", "Give the reason for the professional tax exemption.");
			}
			if (employee.getPfPolicy() == PfPolicy.EXCLUDED && employee.getUan() != null) {
				valid = fail(context, "pfPolicy", "Someone with a UAN is already a PF member, so PF can’t be excluded.");
			}
			return valid;
		}

		private static boolean fail(ConstraintValidatorContext context, String field, String message) {
			context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
			return false;
		}

	}

}
