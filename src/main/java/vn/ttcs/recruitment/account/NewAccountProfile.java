package vn.ttcs.recruitment.account;

/**
 * Optional profile fields saved together with a new account: the same fields PUT /accounts/{id} can change later.
 * Callers check the values first (length, phone pattern); {@link AccountProvisioningService} only checks that the
 * department is active. A null field stays empty.
 *
 * @param departmentCode the department code as administrators type it, compared exactly (upper/lower case included)
 * @param phone          an already normalized phone number, for example {@code 0912345678}
 */
public record NewAccountProfile(String departmentCode, String phone, String displayTitle) {

    /** No department, phone or title: what POST /accounts creates. */
    public static final NewAccountProfile NONE = new NewAccountProfile(null, null, null);
}
