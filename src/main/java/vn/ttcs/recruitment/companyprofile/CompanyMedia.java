package vn.ttcs.recruitment.companyprofile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// An uploaded JPEG/PNG picture stored in PostgreSQL (BYTEA). It never changes after upload.
// Loading this entity always loads the image bytes, so lists of pictures should select only
// the metadata columns instead of whole entities.
@Entity
@Table(name = "company_media")
public class CompanyMedia {

    // Same limits as the CHECK constraints of V11.
    public static final int MAX_SIZE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_DIMENSION = 6000;

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CompanyMediaKind kind;

    // "image/jpeg" or "image/png"; V11 also checks that the first bytes of data match it.
    @Column(nullable = false, length = 32)
    private String contentType;

    @Column(nullable = false)
    private byte[] data;

    @Column(nullable = false)
    private int sizeBytes;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private UUID createdBy;

    protected CompanyMedia() {
    }

    public CompanyMedia(CompanyMediaKind kind, String contentType, byte[] data, int width, int height,
                        UUID createdBy, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.kind = kind;
        this.contentType = contentType;
        this.data = data;
        this.sizeBytes = data.length;
        this.width = width;
        this.height = height;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public CompanyMediaKind getKind() { return kind; }
    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
    public int getSizeBytes() { return sizeBytes; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }
}
