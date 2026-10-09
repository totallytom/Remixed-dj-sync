package net.remixed.djsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param supabaseUrl        project URL, e.g. https://xyz.supabase.co
 * @param supabaseJwtSecret  legacy HS256 JWT secret; leave empty to verify with the project's JWKS
 * @param supabaseServiceKey service-role key, used only to check group membership
 */
@ConfigurationProperties("dj-sync")
public record SyncProperties(String supabaseUrl, String supabaseJwtSecret, String supabaseServiceKey) {}
