package vn.ttcs.recruitment.interview;

import java.util.List;

public record InterviewPage(List<InterviewView> items, long totalElements, int page, int size) { }
