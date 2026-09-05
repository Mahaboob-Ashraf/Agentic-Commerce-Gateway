package dev.agenticcommerce.gateway.intent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BuyerVoicePreferenceRepository {
    private final JdbcClient jdbc;

    public BuyerVoicePreferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<BuyerVoicePreferenceService.VoicePreference> find(UUID buyerActorId) {
        return jdbc.sql("SELECT * FROM buyer_voice_preference WHERE buyer_actor_id=:buyer")
                .param("buyer", buyerActorId)
                .query(this::preference)
                .optional();
    }

    public BuyerVoicePreferenceService.VoicePreference save(UUID buyerActorId, String voiceName, Instant now) {
        return jdbc.sql("""
                INSERT INTO buyer_voice_preference(buyer_actor_id,voice_name,created_at,updated_at)
                VALUES(:buyer,:voice,:now,:now)
                ON CONFLICT (buyer_actor_id) DO UPDATE SET
                  voice_name=EXCLUDED.voice_name,
                  version=buyer_voice_preference.version+1,
                  updated_at=EXCLUDED.updated_at
                RETURNING *
                """)
                .param("buyer", buyerActorId)
                .param("voice", voiceName)
                .param("now", now.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(this::preference)
                .single();
    }

    private BuyerVoicePreferenceService.VoicePreference preference(ResultSet rs, int row) throws SQLException {
        return new BuyerVoicePreferenceService.VoicePreference(
                rs.getString("voice_name"),
                rs.getInt("version"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
