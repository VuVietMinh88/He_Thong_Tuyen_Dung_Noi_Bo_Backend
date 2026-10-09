package vn.ttcs.recruitment.position;

import java.util.List;

public record PositionPage(List<PositionView> items, int page, int size,
                           long totalElements, long totalPages) { }
