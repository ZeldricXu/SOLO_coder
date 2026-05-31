ALTER TABLE `address_entry`
    ADD COLUMN `tags` VARCHAR(500) DEFAULT NULL COMMENT 'Comma-separated tags' AFTER `label`,
    ADD COLUMN `note` TEXT DEFAULT NULL COMMENT 'Additional notes' AFTER `tags`,
    ADD COLUMN `public_key` VARCHAR(512) DEFAULT NULL COMMENT 'Public key' AFTER `hd_index`,
    ADD INDEX `idx_address_entry_tag` (`tags`),
    ADD INDEX `idx_address_entry_label` (`label`);
