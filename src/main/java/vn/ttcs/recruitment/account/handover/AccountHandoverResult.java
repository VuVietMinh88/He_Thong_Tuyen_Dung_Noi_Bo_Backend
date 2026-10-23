package vn.ttcs.recruitment.account.handover;

/**
 * POST /api/v1/accounts/{id}/handover: how many requisitions were handed over (every item of the request, since the
 * handover is all or nothing) and what the account still holds afterwards, in the shape of GET handover-items, so the
 * lock dialog can show the rest without another request.
 */
public record AccountHandoverResult(int transferred, AccountHandoverView remaining) { }
