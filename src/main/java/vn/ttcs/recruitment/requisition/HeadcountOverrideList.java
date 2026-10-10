package vn.ttcs.recruitment.requisition;

import vn.ttcs.recruitment.headcount.HeadcountOverrideView;

import java.util.List;

// Task 275: GET /requisitions/{id}/headcount-overrides returns {"items": [...]}, newest first.
public record HeadcountOverrideList(List<HeadcountOverrideView> items) {
}
