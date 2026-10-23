-- Task 531 (story S8-01): one editable email template per candidate email type. The subject and the body are plain
-- text with {{variable}} placeholders; supported_variables is the fixed list a template of that type may use (the API
-- refuses any other placeholder). auto_send says whether the business event of the type sends the email by itself.
-- The text the system always adds (tracking and withdrawal links, interview details, offer terms and reply links) is
-- not part of the template, so an edit can never remove it.
CREATE TABLE email_templates (
    template_type VARCHAR(32) PRIMARY KEY,
    subject VARCHAR(200) NOT NULL,
    body VARCHAR(5000) NOT NULL,
    supported_variables VARCHAR(32)[] NOT NULL,
    auto_send BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    -- SET NULL: removing an account keeps the template; the last editor is informative only.
    updated_by UUID REFERENCES user_accounts(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT email_templates_type CHECK (template_type IN
        ('APPLICATION_RECEIPT', 'INTERVIEW_INVITATION', 'INTERVIEW_THANKS', 'REJECTION', 'OFFER')),
    -- A subject is one header line: no line break or other control character can reach the SMTP header.
    CONSTRAINT email_templates_subject CHECK (btrim(subject) <> '' AND subject !~ '[[:cntrl:]]'),
    CONSTRAINT email_templates_body CHECK (btrim(body) <> '' AND body !~ '[\x01-\x08\x0B-\x1F\x7F]'),
    CONSTRAINT email_templates_variables CHECK (cardinality(supported_variables) BETWEEN 1 AND 10),
    CONSTRAINT email_templates_version CHECK (version >= 0)
);

-- Defaults reproduce the emails sent before templates existed. The two new emails (thanks after an interview, rejection)
-- start with automatic sending off, so deploying this version sends nothing new until HR turns them on.
INSERT INTO email_templates (template_type, subject, body, supported_variables, auto_send, updated_at) VALUES
    ('APPLICATION_RECEIPT', 'Xác nhận nhận hồ sơ ứng tuyển — Hệ thống tuyển dụng nội bộ',
     E'Chào {{candidateName}},\n\nHệ thống đã nhận hồ sơ ứng tuyển của bạn.',
     ARRAY['candidateName', 'positionTitle', 'companyName'], TRUE, clock_timestamp()),
    ('INTERVIEW_INVITATION', 'Thư mời phỏng vấn',
     E'Xin chào {{candidateName}},\n\nBạn được mời tham gia buổi phỏng vấn với thông tin dưới đây.',
     ARRAY['candidateName', 'positionTitle', 'interviewTime', 'companyName'], TRUE, clock_timestamp()),
    ('INTERVIEW_THANKS', 'Cảm ơn bạn đã tham gia phỏng vấn',
     E'Xin chào {{candidateName}},\n\nCảm ơn bạn đã dành thời gian tham gia buổi phỏng vấn vị trí {{positionTitle}} lúc {{interviewTime}}. Bộ phận tuyển dụng sẽ thông báo kết quả cho bạn trong thời gian sớm nhất.',
     ARRAY['candidateName', 'positionTitle', 'interviewTime', 'companyName'], FALSE, clock_timestamp()),
    ('REJECTION', 'Thông báo kết quả ứng tuyển',
     E'Xin chào {{candidateName}},\n\nCảm ơn bạn đã quan tâm và ứng tuyển vị trí {{positionTitle}}. Sau khi cân nhắc kỹ, chúng tôi rất tiếc phải thông báo rằng hồ sơ của bạn chưa phù hợp với vị trí này ở thời điểm hiện tại.\n\nChúc bạn nhiều thành công trong thời gian tới.',
     ARRAY['candidateName', 'positionTitle', 'companyName'], FALSE, clock_timestamp()),
    ('OFFER', 'Thư mời nhận việc',
     E'Xin chào {{candidateName}},\n\nBạn nhận được lời mời nhận việc với thông tin dưới đây.',
     ARRAY['candidateName', 'positionTitle', 'companyName'], TRUE, clock_timestamp());
