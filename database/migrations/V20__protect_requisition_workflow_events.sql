-- Task 268 (story S3-03): "Lịch sử không sửa và không xoá được". The approval history is only ever added to: no saved
-- decision or comment can be changed or removed, not even with SQL written by hand. TRUNCATE (only the table owner
-- may run it; tests use it to clean up) does not fire row triggers and stays possible, as for V18's flow versions.
CREATE FUNCTION reject_requisition_workflow_event_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'requisition_workflow_events is append-only: % is not allowed', TG_OP
        USING ERRCODE = '23514';
END $$;

CREATE TRIGGER requisition_workflow_events_append_only
    BEFORE UPDATE OR DELETE ON requisition_workflow_events
    FOR EACH ROW EXECUTE FUNCTION reject_requisition_workflow_event_change();
