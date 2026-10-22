package vn.ttcs.recruitment.auth;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.*;
import vn.ttcs.recruitment.application.*;
import vn.ttcs.recruitment.cvsuggestion.*;
import vn.ttcs.recruitment.cvextraction.*;
import static org.assertj.core.api.Assertions.*;

abstract class CvApplicationTestSupport {
    protected static final Instant NOW=Instant.parse("2026-10-15T03:00:00Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired AuthIntegrationTest.MutableClock clock;
    @Autowired CvValidator validator;
    @Autowired ApplicationStorageService storage;
    @Autowired CvSuggestionRepository jobs;
    @Autowired CvSuggestionService service;
    @Autowired javax.sql.DataSource dataSource;
    protected ApprovalTestSupport api;
    protected TransactionTemplate tx;
    protected UUID admin,recruiter,other,requisition,posting,app;
    protected String adminToken,recruiterToken,otherToken;
    protected byte[] pdf;

    @BeforeEach void fixture() throws Exception {
        clock.set(NOW);
        // TRUNCATE ... CASCADE also empties email_templates (updated_by references user_accounts); keep the rows V66
        // seeded, which every application receipt reads, across the reset.
        String templates=jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t)-'updated_by'),'[]') FROM email_templates t",String.class);
        jdbc.execute("TRUNCATE user_accounts,departments,positions,recruitment_catalog_items,recruitment_requisitions,job_postings CASCADE");
        jdbc.update("INSERT INTO email_templates SELECT * FROM jsonb_populate_recordset(NULL::email_templates,CAST(? AS jsonb))",templates);
        api=new ApprovalTestSupport(jdbc,json,manager,Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        tx=new TransactionTemplate(manager);tx.setTimeout(5);
        String password=encoder.encode(ApprovalTestSupport.PASSWORD);
        admin=account("admin",Role.ADMIN,password);recruiter=account("recruiter",Role.RECRUITER,password);
        other=account("other",Role.RECRUITER,password);
        adminToken=api.token("admin@example.test");recruiterToken=api.token("recruiter@example.test");otherToken=api.token("other@example.test");
        UUID department=api.department("IT",admin,null,NOW),position=api.position(NOW);
        UUID location=catalog("WORK_LOCATION"),employment=catalog("EMPLOYMENT_TYPE");
        requisition=UUID.randomUUID();posting=UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at) VALUES (?,?,?,1,'NEW_HEADCOUNT','APPROVED',?,?,?)",
                requisition,position,department,admin,Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,
                work_location_id,employment_type_id,application_deadline,show_salary,created_by,created_at,updated_at,published_by,published_at)
                VALUES (?,?,'PUBLISHED',1,'Hiring developer','Description','Requirements',?,?,?,false,?,?,?,?,?)
                """,posting,requisition,location,employment,LocalDate.of(2026,12,31),admin,
                Timestamp.from(NOW),Timestamp.from(NOW),admin,Timestamp.from(NOW));
        jdbc.update("INSERT INTO requisition_recruiters(requisition_id,recruiter_id,assignment_role,assigned_by,assigned_at) VALUES (?,?,'PRIMARY',?,?)",
                requisition,recruiter,admin,Timestamp.from(NOW));
        pdf=pdf("Emily Carter");
        app=application("one@example.test");
    }
    protected UUID account(String name,Role role,String password) { return accounts.saveAndFlush(new Account(name+"@example.test",name,password,Set.of(role),NOW)).getId(); }
    protected UUID catalog(String type) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_catalog_items(id,catalog_type,code,name,sort_order,active,created_at,updated_at) VALUES (?,?,?,?,0,true,?,?)",
                id,type,type,type,Timestamp.from(NOW),Timestamp.from(NOW));return id;
    }
    protected UUID application(String email) {
        byte[] hash=new byte[32];new java.security.SecureRandom().nextBytes(hash);
        return storage.store(posting,new ApplicationSubmission("Original Name",email,"0912345678",null,null),
                validator.validate("cv.pdf",new ByteArrayInputStream(pdf)),hash).id();
    }
    protected byte[] pdf(String name) throws Exception {
        try (var document=new org.apache.pdfbox.pdmodel.PDDocument();var output=new java.io.ByteArrayOutputStream()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage();document.addPage(page);
            try (var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(document,page)) {
                content.beginText();content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),14);
                content.newLineAtOffset(50,740);content.setLeading(24);
                for (String line : List.of("Full name: "+name,"Email: emily@example.test","Phone: 0912345678",
                        "Experienced developer working on reliable business software and backend services.")) {
                    content.showText(line);content.newLine();
                }
                content.endText();
            }
            document.save(output);return output.toByteArray();
        }
    }
}
