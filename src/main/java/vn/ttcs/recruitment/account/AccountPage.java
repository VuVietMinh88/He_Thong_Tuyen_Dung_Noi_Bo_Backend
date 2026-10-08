package vn.ttcs.recruitment.account;

import java.util.List;

public record AccountPage(List<AccountView> items, int page, int size, long totalElements, long totalPages) { }
