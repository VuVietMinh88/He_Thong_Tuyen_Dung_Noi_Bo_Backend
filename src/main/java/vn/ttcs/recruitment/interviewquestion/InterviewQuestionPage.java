package vn.ttcs.recruitment.interviewquestion;

import java.util.List;

// Jira 223: one page of GET /api/v1/interview-questions, in the same shape as the other lists (departments,
// positions, competency frameworks).
public record InterviewQuestionPage(List<InterviewQuestionView> items, int page, int size,
                                    long totalElements, long totalPages) { }
