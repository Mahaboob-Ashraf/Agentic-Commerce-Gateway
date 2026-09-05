ALTER TABLE merchant
    ADD COLUMN logo_url VARCHAR(2048);

ALTER TABLE merchant
    ADD CONSTRAINT chk_merchant_logo_url_safe
    CHECK (
        logo_url IS NULL
        OR (
            logo_url = btrim(logo_url)
            AND char_length(logo_url) BETWEEN 1 AND 2048
            AND (
                (logo_url LIKE '/%' AND logo_url NOT LIKE '//%')
                OR logo_url LIKE 'https://%'
            )
        )
    );
