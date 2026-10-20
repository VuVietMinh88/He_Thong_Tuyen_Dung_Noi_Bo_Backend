-- Company introduction page on the recruitment portal (story S2-09).
-- Texts are plain text (Markdown-like), never HTML: the API rejects raw HTML and the portal
-- must render them as text, so a stored script can never run in a candidate's browser.

-- Uploaded logo and introduction images, stored inside PostgreSQL as BYTEA.
-- A row never changes after upload; replacing a picture means uploading a new row.
CREATE TABLE company_media (
    id UUID PRIMARY KEY,
    kind VARCHAR(16) NOT NULL,
    content_type VARCHAR(32) NOT NULL,
    data BYTEA NOT NULL,
    size_bytes INTEGER NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    CONSTRAINT valid_company_media_kind CHECK (kind IN ('LOGO', 'IMAGE')),
    CONSTRAINT valid_company_media_content_type CHECK (content_type IN ('image/jpeg', 'image/png')),
    -- The first bytes must match the declared type, so a renamed HTML or SVG file is never stored as an image.
    CONSTRAINT valid_company_media_signature CHECK (
        (content_type = 'image/png' AND substring(data FROM 1 FOR 8) = decode('89504e470d0a1a0a', 'hex'))
        OR (content_type = 'image/jpeg' AND substring(data FROM 1 FOR 3) = decode('ffd8ff', 'hex'))
    ),
    -- size_bytes is the real length of data: from 1 byte up to 5 MB (5 * 1024 * 1024 bytes).
    CONSTRAINT valid_company_media_size CHECK (size_bytes = octet_length(data) AND size_bytes BETWEEN 1 AND 5242880),
    -- Pixel size read from the decoded image, at most 6000 x 6000.
    CONSTRAINT valid_company_media_dimensions CHECK (width BETWEEN 1 AND 6000 AND height BETWEEN 1 AND 6000),
    -- Target of the (id, kind) foreign keys below, which let the database check the kind of a picture.
    CONSTRAINT company_media_id_kind_key UNIQUE (id, kind)
);

-- The portal shows one company, so this table holds at most one row and its id is always 1.
-- There is no row until HR saves the page for the first time.
CREATE TABLE company_profile (
    id INTEGER PRIMARY KEY DEFAULT 1,
    company_name VARCHAR(255) NOT NULL,
    tagline VARCHAR(255),
    introduction TEXT NOT NULL,
    logo_media_id UUID,
    -- Always 'LOGO'. It only exists so the foreign key below accepts LOGO pictures only.
    logo_media_kind VARCHAR(16) NOT NULL DEFAULT 'LOGO',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID NOT NULL REFERENCES user_accounts(id) ON DELETE RESTRICT,
    CONSTRAINT single_company_profile CHECK (id = 1),
    CONSTRAINT valid_company_name CHECK (company_name <> '' AND company_name !~ '^[[:space:]]|[[:space:]]$'),
    CONSTRAINT valid_company_tagline CHECK (
        tagline IS NULL OR (tagline <> '' AND tagline !~ '^[[:space:]]|[[:space:]]$')
    ),
    -- At most 20 000 characters with at least one visible character; line breaks are kept as typed.
    CONSTRAINT valid_company_introduction CHECK (
        char_length(introduction) <= 20000 AND introduction ~ '[^[:space:]]'
    ),
    CONSTRAINT valid_company_logo_kind CHECK (logo_media_kind = 'LOGO'),
    CONSTRAINT company_profile_logo_fk FOREIGN KEY (logo_media_id, logo_media_kind)
        REFERENCES company_media(id, kind) ON DELETE RESTRICT
);

-- Ordered introduction images: display_order 0 is shown first, at most 10 images.
CREATE TABLE company_profile_images (
    profile_id INTEGER NOT NULL REFERENCES company_profile(id) ON DELETE CASCADE,
    display_order INTEGER NOT NULL,
    media_id UUID NOT NULL,
    -- Always 'IMAGE'. It only exists so the foreign key below accepts IMAGE pictures only.
    media_kind VARCHAR(16) NOT NULL DEFAULT 'IMAGE',
    PRIMARY KEY (profile_id, display_order),
    CONSTRAINT valid_company_image_order CHECK (display_order BETWEEN 0 AND 9),
    CONSTRAINT valid_company_image_kind CHECK (media_kind = 'IMAGE'),
    CONSTRAINT company_profile_images_media_fk FOREIGN KEY (media_id, media_kind)
        REFERENCES company_media(id, kind) ON DELETE RESTRICT,
    -- Checked at commit: the backend may reorder images (for example swap two of them)
    -- inside one transaction, but the saved gallery never shows the same image twice.
    CONSTRAINT company_profile_images_media_key UNIQUE (profile_id, media_id) DEFERRABLE INITIALLY DEFERRED
);
