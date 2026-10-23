package vn.ttcs.recruitment.interview;

/** Queue notifications in the caller's scheduling transaction; delivery happens after commit. */
public interface InterviewNotifications {
    void requireReady();
    void scheduled(InterviewView current);
    void changed(InterviewView previous, InterviewView current);
    void cancelled(InterviewView previous, InterviewView current, String reason);
}
