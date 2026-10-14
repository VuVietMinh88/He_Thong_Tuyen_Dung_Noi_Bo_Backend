package vn.ttcs.recruitment.application;

import java.util.UUID;

// Integration point for receipt generation and the durable acknowledgement outbox. A production implementation
// must atomically save application, CV and outbox under READ_COMMITTED, then return only after commit.
// There is deliberately no fallback writer that saves an application without its acknowledgement.
public interface ApplicationReceiptWriter {
    ApplicationReceipt accept(UUID postingId, ApplicationSubmission submission, ValidatedCv cv);
}
