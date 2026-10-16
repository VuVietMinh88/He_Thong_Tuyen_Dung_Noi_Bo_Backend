package vn.ttcs.recruitment.companyprofile;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Content of the company introduction page on the recruitment portal.
// Texts are plain text (Markdown-like), never HTML; the portal renders them as text.
@Entity
@Table(name = "company_profile")
public class CompanyProfile {

    // The portal shows one company, so V11 only accepts the row with this id.
    public static final int SINGLETON_ID = 1;
    // Same limits as the CHECK constraints of V11.
    public static final int MAX_INTRODUCTION_LENGTH = 20_000;
    public static final int MAX_IMAGES = 10;

    @Id
    private int id;

    @Column(nullable = false)
    private String companyName;

    private String tagline;

    @Column(nullable = false, columnDefinition = "text")
    private String introduction;

    // Optional. V11 also stores logo_media_kind = 'LOGO' (left to its default here), so the
    // (logo_media_id, logo_media_kind) foreign key only accepts LOGO media.
    private UUID logoMediaId;

    // Introduction images in display order: the list index is display_order (0 is shown first).
    // Each item must be IMAGE media and may appear only once (V11 foreign key and UNIQUE).
    // The UNIQUE check waits for the commit, after a @Transactional service method has returned,
    // so callers must reject repeated ids and more than MAX_IMAGES ids before saving.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "company_profile_images", joinColumns = @JoinColumn(name = "profile_id"))
    @OrderColumn(name = "display_order")
    @Column(name = "media_id", nullable = false)
    private List<UUID> imageIds = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private UUID updatedBy;

    protected CompanyProfile() {
    }

    public CompanyProfile(String companyName, String tagline, String introduction, UUID logoMediaId,
                          List<UUID> imageIds, UUID updatedBy, Instant createdAt) {
        this.id = SINGLETON_ID;
        this.createdAt = createdAt;
        applyChanges(companyName, tagline, introduction, logoMediaId, imageIds, updatedBy, createdAt);
    }

    public void update(String companyName, String tagline, String introduction, UUID logoMediaId,
                       List<UUID> imageIds, UUID updatedBy, Instant updatedAt) {
        applyChanges(companyName, tagline, introduction, logoMediaId, imageIds, updatedBy, updatedAt);
    }

    private void applyChanges(String companyName, String tagline, String introduction, UUID logoMediaId,
                              List<UUID> imageIds, UUID updatedBy, Instant updatedAt) {
        this.companyName = companyName;
        this.tagline = tagline;
        this.introduction = introduction;
        this.logoMediaId = logoMediaId;
        // Hibernate rewrites the changed positions of the list. Swapping two images briefly
        // repeats one of them, which V11 allows because its UNIQUE check waits for the commit.
        this.imageIds.clear();
        this.imageIds.addAll(imageIds);
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public int getId() { return id; }
    public String getCompanyName() { return companyName; }
    public String getTagline() { return tagline; }
    public String getIntroduction() { return introduction; }
    public UUID getLogoMediaId() { return logoMediaId; }
    public List<UUID> getImageIds() { return List.copyOf(imageIds); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
}
