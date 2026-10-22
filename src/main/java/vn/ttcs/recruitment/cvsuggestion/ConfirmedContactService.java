package vn.ttcs.recruitment.cvsuggestion;

import java.text.Normalizer;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.cvextraction.CvExtractionResult;
import static vn.ttcs.recruitment.cvsuggestion.ConfirmedContactView.*;

@Service
public class ConfirmedContactService {
    private final ConfirmedContactRepository contacts;
    private final CvSuggestionAccess access;
    private final JdbcTemplate jdbc;
    private final Validator validator;
    private final TransactionTemplate write,read;
    private final boolean enabled;
    public ConfirmedContactService(ConfirmedContactRepository contacts,CvSuggestionAccess access,JdbcTemplate jdbc,
            Validator validator,PlatformTransactionManager manager,
            @Value("${app.cv-contact-confirmation.enabled:false}") boolean enabled) {
        this.contacts=contacts;this.access=access;this.jdbc=jdbc;this.validator=validator;this.enabled=enabled;
        write=new TransactionTemplate(manager);write.setTimeout(5);
        read=new TransactionTemplate(manager);read.setTimeout(5);read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public ConfirmedContactView get(Jwt jwt,UUID id) {
        return database(()->read.execute(ignored->{
            var caller=access.reader(jwt);var app=contacts.application(id,false);access.scope(caller,app.requisition());
            return contacts.view(app);
        }));
    }
    public ConfirmedContactView confirm(Jwt jwt,UUID id,ContactConfirmationRequest request) {
        if (request==null || !validator.validate(request).isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Thông tin xác nhận không hợp lệ.");
        return database(()->write.execute(ignored->{
            jdbc.execute("SET LOCAL lock_timeout='2s'");jdbc.execute("SET LOCAL statement_timeout='3s'");
            var actor=access.lock(jwt);access.writer(jwt,actor);
            var app=contacts.application(id,true);access.scope(access.writer(jwt,actor),app.requisition());
            if (!enabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CONTACT_CONFIRMATION_UNAVAILABLE","Chức năng xác nhận tạm chưa sẵn sàng.");
            var old=contacts.current(id).orElse(null);
            if (request.expectedRevision()!=(old==null ? 0 : old.revision())) throw conflict("CONTACT_REVISION_CONFLICT");
            if (!app.hash().equals(request.expectedCvHash())) throw conflict("CV_CHANGED");
            validateSources(app,request);
            if (old!=null && old.contact().equals(request.values()) && old.cvHash().equals(app.hash())
                    && old.nameSource()==request.nameSource() && old.emailSource()==request.emailSource()
                    && old.phoneSource()==request.phoneSource() && java.util.Objects.equals(old.extractionId(),request.extractionId()))
                return contacts.view(app);
            access.scope(access.writer(jwt,actor),app.requisition());
            String actorName=jdbc.queryForObject("SELECT full_name FROM user_accounts WHERE id=?",String.class,actor.id());
            contacts.save(app,request,old,actor.id(),actorName);return contacts.view(app);
        }));
    }
    public HistoryPage history(Jwt jwt,UUID id,int page,int size) {
        if (page<0 || size<1 || size>100 || (long)page*size>1_000_000)
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Phân trang không hợp lệ.");
        return database(()->read.execute(ignored->{
            var caller=access.reader(jwt);var app=contacts.application(id,false);access.scope(caller,app.requisition());
            return contacts.history(id,page,size);
        }));
    }
    private void validateSources(ConfirmedContactRepository.Application app,ContactConfirmationRequest request) {
        if (request.usesExtraction()!=(request.extractionId()!=null)) throw invalidSource();
        CvExtractionResult extracted=null;
        if (request.usesExtraction()) {
            var results=jdbc.query("""
                    SELECT result::text FROM cv_extraction_jobs WHERE id=? AND application_id=? AND cv_sha256=?
                    AND extractor_version=? AND status='SUCCEEDED'
                    """,(rs,n)->rs.getString(1),request.extractionId(),app.id(),java.util.HexFormat.of().parseHex(app.hash()),CvExtractionResult.VERSION);
            if (results.isEmpty()) throw conflict("CV_EXTRACTION_STALE");
            extracted=CvSuggestionJson.read(results.getFirst());
        }
        verify(request.nameSource(),request.fullName(),app.submitted().fullName(),extracted==null ? List.of() : extracted.suggestions().fullName(),"name");
        verify(request.emailSource(),request.email(),app.submitted().email(),extracted==null ? List.of() : extracted.suggestions().email(),"email");
        verify(request.phoneSource(),request.phone(),app.submitted().phone(),extracted==null ? List.of() : extracted.suggestions().phone(),"phone");
    }
    private void verify(ContactSource source,String value,String submitted,List<CvExtractionResult.Candidate> candidates,String field) {
        if (source==ContactSource.MANUAL) return;
        if (source==ContactSource.SUBMITTED) { if (!same(value,submitted,field)) throw invalidSource();return; }
        if (candidates.stream().noneMatch(candidate->{
            if (same(value,field.equals("name") ? candidate.value() : candidate.normalizedValue(),field)) return true;
            // A phone candidate exposes both the original number and its international normalization.
            String original=field.equals("phone") ? new vn.ttcs.recruitment.application.ApplicationSubmission(null,null,candidate.value(),null,null).phone()
                    : candidate.value();
            return original!=null && same(value,original,field);
        }))
            throw invalidSource();
    }
    private static boolean same(String left,String right,String field) {
        if (field.equals("email")) return left.toLowerCase(java.util.Locale.ROOT).equals(right.toLowerCase(java.util.Locale.ROOT));
        return Normalizer.normalize(left,Normalizer.Form.NFC).equals(Normalizer.normalize(right,Normalizer.Form.NFC));
    }
    private static ApiException invalidSource() { return new ApiException(HttpStatus.BAD_REQUEST,"CONTACT_SOURCE_INVALID","Nguồn thông tin không khớp giá trị đã chọn."); }
    private static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Dữ liệu đã thay đổi. Vui lòng tải lại và xác nhận."); }
    private <T> T database(java.util.function.Supplier<T> callback) {
        try { return callback.get(); } catch (DataAccessException | TransactionException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CONTACT_CONFIRMATION_UNAVAILABLE","Chức năng liên hệ tạm chưa sẵn sàng.");
        }
    }
}
