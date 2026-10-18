-- At most one avatar per internal account (the primary key is the account id). Both pictures are produced by the
-- backend from the upload: image is 256 x 256 pixels and thumbnail is 64 x 64 pixels, both PNG today.
-- size_bytes is the size of image, so lists can show it without reading the picture itself.
-- Deleting the account deletes its avatar.
CREATE TABLE user_avatars (
    user_id UUID PRIMARY KEY REFERENCES user_accounts(id) ON DELETE CASCADE,
    content_type VARCHAR(50) NOT NULL,
    image BYTEA NOT NULL,
    thumbnail BYTEA NOT NULL,
    size_bytes INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT valid_user_avatar_content_type CHECK (content_type IN ('image/png', 'image/jpeg')),
    CONSTRAINT valid_user_avatar_size CHECK (size_bytes > 0 AND size_bytes = octet_length(image)),
    CONSTRAINT non_empty_user_avatar_thumbnail CHECK (octet_length(thumbnail) > 0)
);
